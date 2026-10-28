#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Verify Release Candidate Manifest
# Validates release candidate manifest integrity, schema compliance,
# SHA provenance, 8 immutable image digests, and fail-closed security invariants.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SCHEMA_FILE="${SCRIPT_DIR}/manifest-schema.json"

MANIFEST_FILE="${REPO_ROOT}/build/artifacts/digests/release-candidate-manifest.json"
EXPECTED_SHA=""
STRICT_GIT_CHECK=true

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --manifest <PATH>      Path to release-candidate-manifest.json
  --expected-sha <SHA>   Verify manifest matches this expected Git commit SHA
  --skip-git-check       Skip local Git tree ancestry check (for offline/isolated tests)
  -h, --help             Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) MANIFEST_FILE="$2"; shift 2 ;;
    --expected-sha) EXPECTED_SHA="$2"; shift 2 ;;
    --skip-git-check) STRICT_GIT_CHECK=false; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [ ! -f "${MANIFEST_FILE}" ]; then
  echo "FATAL: Manifest file not found: '${MANIFEST_FILE}'" >&2
  exit 1
fi

echo "================================================================================"
echo "          SWIFTDROP RELEASE CANDIDATE MANIFEST INTEGRITY VERIFICATION           "
echo "================================================================================"
echo "Manifest Path:  ${MANIFEST_FILE}"
echo "Schema Path:    ${SCHEMA_FILE}"

python3 - << EOF
import json, sys, re, os, subprocess

schema_path = "${SCHEMA_FILE}"
manifest_path = "${MANIFEST_FILE}"
expected_sha = "${EXPECTED_SHA}".strip()
strict_git = "${STRICT_GIT_CHECK}".lower() == "true"
repo_root = "${REPO_ROOT}"

# 1. Parse JSON
try:
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
except Exception as e:
    print(f"FATAL: Manifest is not valid JSON: {e}", file=sys.stderr)
    sys.exit(1)

# 2. Validate against schema
try:
    import jsonschema
    with open(schema_path, "r", encoding="utf-8") as sf:
        schema = json.load(sf)
    jsonschema.validate(instance=manifest, schema=schema)
    print("✓ Manifest structurally complies with JSON schema (draft-07).")
except Exception as e:
    print(f"FATAL: Manifest schema validation failed: {e}", file=sys.stderr)
    sys.exit(1)

# 3. Check commit SHA
sha = manifest.get("source_commit_sha", "")
if not re.match(r"^[0-9a-f]{40}$", sha):
    print(f"FATAL: Source commit SHA '{sha}' is not a valid 40-character hex string.", file=sys.stderr)
    sys.exit(1)

if expected_sha and expected_sha != sha:
    print(f"FATAL: Candidate SHA mismatch! Manifest has '{sha}', expected '{expected_sha}'.", file=sys.stderr)
    sys.exit(1)

print(f"✓ Candidate Commit SHA verified: {sha}")

# 4. Check git ancestry if enabled
if strict_git:
    try:
        proc = subprocess.run(
            ["git", "-C", repo_root, "cat-file", "-e", f"{sha}^{{commit}}"],
            capture_output=True, text=True
        )
        if proc.returncode != 0:
            print(f"FATAL: Candidate SHA {sha} does not exist in local Git repository history!", file=sys.stderr)
            sys.exit(1)
        print(f"✓ Candidate SHA confirmed in repository commit graph.")
    except Exception as e:
        print(f"WARNING: Git ancestry check could not be run: {e}", file=sys.stderr)

# 5. Validate exact 8 images
expected_names = [
    "frontend",
    "api-gateway",
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
    "keycloak"
]

images = manifest.get("images", [])
if len(images) != 8:
    print(f"FATAL: Expected exactly 8 images, found {len(images)}.", file=sys.stderr)
    sys.exit(1)

found_names = []
for idx, img in enumerate(images):
    name = img.get("image_name")
    repo = img.get("image_repository")
    digest = img.get("image_digest")
    tag = img.get("tag_reference")

    if not name or name not in expected_names:
        print(f"FATAL: Unexpected or missing image_name: '{name}' at index {idx}.", file=sys.stderr)
        sys.exit(1)
    if name in found_names:
        print(f"FATAL: Duplicate image entry for '{name}'.", file=sys.stderr)
        sys.exit(1)
    found_names.append(name)

    if not digest or not re.match(r"^sha256:[0-9a-f]{64}$", digest):
        print(f"FATAL: Image '{name}' has invalid or mutable digest: '{digest}'. Must be sha256:<64-hex>.", file=sys.stderr)
        sys.exit(1)

    if "digest-pending" in str(digest) or "latest" in str(digest):
        print(f"FATAL: Image '{name}' contains prohibited mutable placeholder: '{digest}'.", file=sys.stderr)
        sys.exit(1)

    print(f"  [{name:<24}] -> {digest[:19]}... ({repo})")

missing = set(expected_names) - set(found_names)
if missing:
    print(f"FATAL: Missing required release components: {missing}", file=sys.stderr)
    sys.exit(1)

print("-" * 80)
print("✓ ALL 8 RUNTIME IMAGES VERIFIED IMMUTABLE WITH CRYPTOGRAPHIC DIGESTS.")
print(f"Approval Reference: {manifest.get('approval_reference')}")
print(f"Generation Time:    {manifest.get('artifact_generation_time')}")
print("================================================================================")
EOF

echo "✓ Candidate manifest verification PASSED cleanly."
