#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Generate Release Candidate Manifest
# Produces a deterministic, schema-compliant JSON manifest containing the
# exact commit SHA, 8 immutable image digests, provenance, and approval reference.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SCHEMA_FILE="${SCRIPT_DIR}/manifest-schema.json"

SHA_VAL=""
BRANCH_VAL="test"
RUN_ID="${GITHUB_RUN_ID:-local-$(date +%s)}"
RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-1}"
ARCH_VAL="linux/amd64"
APPROVAL_REF="pending-review"
DIGESTS_FILE=""
OUTPUT_FILE="${REPO_ROOT}/build/artifacts/digests/release-candidate-manifest.json"

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --sha <SHA>             40-char git commit SHA (default: HEAD)
  --branch <BRANCH>       Target integration branch (default: test)
  --run-id <ID>           Workflow run ID or execution ID
  --run-attempt <NUM>     Workflow run attempt (default: 1)
  --arch <ARCH>           Target architecture (linux/amd64, linux/arm64, multi-arch)
  --approval-ref <REF>    Approval reference (PR number, ruleset, or operator handle)
  --digests-file <PATH>   Path to text file containing name: tag (digest) lines
  --output <PATH>         Output JSON manifest path
  -h, --help              Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sha) SHA_VAL="$2"; shift 2 ;;
    --branch) BRANCH_VAL="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --run-attempt) RUN_ATTEMPT="$2"; shift 2 ;;
    --arch) ARCH_VAL="$2"; shift 2 ;;
    --approval-ref) APPROVAL_REF="$2"; shift 2 ;;
    --digests-file) DIGESTS_FILE="$2"; shift 2 ;;
    --output) OUTPUT_FILE="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [ -z "${SHA_VAL}" ]; then
  SHA_VAL="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "")"
fi

if [[ ! "${SHA_VAL}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: Invalid or missing commit SHA: '${SHA_VAL}' (expected 40-character hex SHA)" >&2
  exit 1
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"

python3 - << EOF
import json, sys, re, os
from datetime import datetime, timezone

schema_path = "${SCHEMA_FILE}"
output_path = "${OUTPUT_FILE}"
sha = "${SHA_VAL}"
branch = "${BRANCH_VAL}"
run_id = str("${RUN_ID}")
run_attempt = int("${RUN_ATTEMPT}")
arch = "${ARCH_VAL}"
approval_ref = "${APPROVAL_REF}"
digests_file = "${DIGESTS_FILE}"
repo_root = "${REPO_ROOT}"

expected_services = [
    ("frontend", "ghcr.io/suthankan1/swiftdrop-frontend"),
    ("api-gateway", "ghcr.io/suthankan1/swiftdrop-api-gateway"),
    ("accounts-service", "ghcr.io/suthankan1/swiftdrop-accounts"),
    ("pricing-service", "ghcr.io/suthankan1/swiftdrop-pricing"),
    ("delivery-service", "ghcr.io/suthankan1/swiftdrop-delivery"),
    ("communications-service", "ghcr.io/suthankan1/swiftdrop-communications"),
    ("graphql-query-service", "ghcr.io/suthankan1/swiftdrop-graphql"),
    ("keycloak", "ghcr.io/suthankan1/swiftdrop-keycloak"),
]

# Read digests from file if provided, or parse Helm values.yaml fallback
digests_map = {}
if digests_file and os.path.isfile(digests_file):
    with open(digests_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            # Format: name: tag (digest) or name: repo@digest
            m = re.search(r"([a-zA-Z0-9_-]+):\s*(?:.*?\()?(sha256:[a-f0-9]{64})\)?", line)
            if m:
                digests_map[m.group(1)] = m.group(2)
            else:
                m2 = re.search(r"([a-zA-Z0-9_-]+):.*?(sha256:[a-f0-9]{64})", line)
                if m2:
                    digests_map[m2.group(1)] = m2.group(2)

# If not in digests file, check Helm values.yaml
helm_values_path = os.path.join(repo_root, "platform/kubernetes/helm/swiftdrop/values.yaml")
if len(digests_map) < len(expected_services) and os.path.isfile(helm_values_path):
    try:
        import yaml
        with open(helm_values_path, "r", encoding="utf-8") as f:
            vdata = yaml.safe_load(f)
            mapping = {
                "frontend": vdata.get("frontend", {}).get("image", {}),
                "api-gateway": vdata.get("apiGateway", {}).get("image", {}),
                "accounts-service": vdata.get("accounts", {}).get("image", {}),
                "pricing-service": vdata.get("pricing", {}).get("image", {}),
                "delivery-service": vdata.get("delivery", {}).get("image", {}),
                "communications-service": vdata.get("communications", {}).get("image", {}),
                "graphql-query-service": vdata.get("graphql", {}).get("image", {}),
                "keycloak": vdata.get("keycloak", {}).get("image", {}),
            }
            for svc_name, img_dict in mapping.items():
                if svc_name not in digests_map and img_dict.get("digest"):
                    digests_map[svc_name] = img_dict.get("digest")
    except Exception as e:
        print(f"Warning reading values.yaml: {e}", file=sys.stderr)

images_list = []
missing = []
for svc_name, repo in expected_services:
    digest = digests_map.get(svc_name)
    if not digest or not re.match(r"^sha256:[a-f0-9]{64}$", digest):
        missing.append(svc_name)
    else:
        images_list.append({
            "image_name": svc_name,
            "image_repository": repo,
            "image_digest": digest,
            "tag_reference": sha[:7]
        })

if missing:
    print(f"ERROR: Missing or invalid immutable digests for {len(missing)} image(s): {missing}", file=sys.stderr)
    sys.exit(1)

manifest = {
    "manifest_version": "1.0.0",
    "source_commit_sha": sha,
    "approved_base_branch": branch,
    "workflow_run_id": run_id,
    "workflow_run_attempt": run_attempt,
    "artifact_generation_time": datetime.now(timezone.utc).isoformat(),
    "target_architecture": arch,
    "contract_artifact_digest": None,
    "approval_reference": approval_ref,
    "images": images_list
}

# Validate with jsonschema
try:
    import jsonschema
    with open(schema_path, "r", encoding="utf-8") as sf:
        schema = json.load(sf)
    jsonschema.validate(instance=manifest, schema=schema)
    print("✓ Release candidate manifest validated successfully against schema.")
except Exception as e:
    print(f"ERROR: Schema validation failed: {e}", file=sys.stderr)
    sys.exit(1)

with open(output_path, "w", encoding="utf-8") as out:
    json.dump(manifest, out, indent=2)

print(f"✓ Generated release candidate manifest: {output_path}")
EOF

chmod 644 "${OUTPUT_FILE}"
echo "Manifest generation complete for candidate ${SHA_VAL}."

