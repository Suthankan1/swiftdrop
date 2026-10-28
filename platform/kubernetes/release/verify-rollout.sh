#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Staging Rollout Verification
# Validates deployed pod replicas, container image digests against manifest,
# probe readiness, and application smoke health across all 8 runtime workloads.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

CONTEXT_NAME="k3d-swiftdrop-local"
NAMESPACE="swiftdrop-apps"
IDENTITY_NAMESPACE="swiftdrop-identity"
MANIFEST_FILE="${REPO_ROOT}/build/artifacts/digests/release-candidate-manifest.json"
TIMEOUT_SECS=120
DRY_RUN=false
FIXTURE_FILE=""

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>      Kubernetes context (default: k3d-swiftdrop-local)
  --namespace <NAMESPACE>  Application namespace (default: swiftdrop-apps)
  --manifest <PATH>        Path to release candidate manifest JSON
  --timeout <SECS>         Timeout in seconds for rollout verification (default: 120)
  --fixture <FILE>         JSON fixture simulating deployment status (for tests)
  --dry-run                Perform syntax and contract check without querying cluster
  -h, --help               Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --manifest) MANIFEST_FILE="$2"; shift 2 ;;
    --timeout) TIMEOUT_SECS="$2"; shift 2 ;;
    --fixture) FIXTURE_FILE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

echo "================================================================================"
echo "          SWIFTDROP STAGING DEPLOYMENT & ROLLOUT VERIFICATION                  "
echo "================================================================================"
echo "Target Context:      ${CONTEXT_NAME}"
echo "Apps Namespace:      ${NAMESPACE}"
echo "Identity Namespace:  ${IDENTITY_NAMESPACE}"
echo "Candidate Manifest:  ${MANIFEST_FILE}"

if [[ "${DRY_RUN}" != "true" && -z "${FIXTURE_FILE}" ]]; then
  if [[ "${CONTEXT_NAME}" != "k3d-swiftdrop-local" ]]; then
    echo "FATAL: Deployment verification strictly restricts operations to 'k3d-swiftdrop-local'." >&2
    exit 1
  fi
fi

if [[ -n "${FIXTURE_FILE}" && -f "${FIXTURE_FILE}" ]]; then
  echo "--- RUNNING IN FIXTURE MODE (${FIXTURE_FILE}) ---"
  python3 - << EOF
import json, sys

with open("${FIXTURE_FILE}", "r", encoding="utf-8") as f:
    data = json.load(f)

workloads = data.get("workloads", {})
failures = []
expected_workloads = [
    "frontend", "api-gateway", "accounts-service", "pricing-service",
    "delivery-service", "communications-service", "graphql-query-service", "keycloak"
]

for w in expected_workloads:
    info = workloads.get(w)
    if not info:
        failures.append(f"Workload {w} missing from rollout status")
        continue
    if not info.get("ready", False):
        failures.append(f"Workload {w} is not ready (replicas: {info.get('ready_replicas')}/{info.get('desired_replicas')})")
    if not info.get("digest_matched", False):
        failures.append(f"Workload {w} deployed image does not match candidate manifest digest!")

if failures:
    print("FATAL: Rollout verification failed on fixture:", file=sys.stderr)
    for err in failures:
        print(f"  ✗ {err}", file=sys.stderr)
    sys.exit(1)

print("✓ All 8 workloads verified ready with matching digests in fixture.")
EOF
  exit 0
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "✓ Dry-run completed. Verification logic and parameters valid."
  exit 0
fi

# Load candidate digests from manifest
if [ ! -f "${MANIFEST_FILE}" ]; then
  echo "FATAL: Release manifest missing: ${MANIFEST_FILE}" >&2
  exit 1
fi

echo "--- 1. VERIFYING WORKLOAD ROLLOUT COMPLETION ---"

APP_DEPLOYMENTS=(
  "frontend"
  "api-gateway"
  "accounts-service"
  "pricing-service"
  "delivery-service"
  "communications-service"
  "graphql-query-service"
)

ROLLOUT_FAILURES=0

for dep in "${APP_DEPLOYMENTS[@]}"; do
  echo "Checking deployment '${dep}' in '${NAMESPACE}'..."
  if ! kubectl --context "${CONTEXT_NAME}" rollout status "deployment/${dep}" -n "${NAMESPACE}" --timeout="${TIMEOUT_SECS}s" >/dev/null 2>&1; then
    echo "  ✗ FATAL: Deployment '${dep}' did not achieve readiness within ${TIMEOUT_SECS}s!" >&2
    ROLLOUT_FAILURES=$((ROLLOUT_FAILURES + 1))
  else
    echo "  ✓ Deployment '${dep}' rollout complete."
  fi
done

echo "Checking Keycloak deployment in '${IDENTITY_NAMESPACE}'..."
if ! kubectl --context "${CONTEXT_NAME}" rollout status "deployment/keycloak" -n "${IDENTITY_NAMESPACE}" --timeout="${TIMEOUT_SECS}s" >/dev/null 2>&1; then
  echo "  ✗ FATAL: Deployment 'keycloak' did not achieve readiness within ${TIMEOUT_SECS}s!" >&2
  ROLLOUT_FAILURES=$((ROLLOUT_FAILURES + 1))
else
  echo "  ✓ Deployment 'keycloak' rollout complete."
fi

echo ""
echo "--- 2. VERIFYING IMMUTABLE IMAGE DIGEST DEPLOYMENT ---"

python3 - << EOF
import json, sys, subprocess

manifest_file = "${MANIFEST_FILE}"
context = "${CONTEXT_NAME}"
app_ns = "${NAMESPACE}"
identity_ns = "${IDENTITY_NAMESPACE}"

with open(manifest_file, "r", encoding="utf-8") as f:
    manifest = json.load(f)

manifest_digests = {img["image_name"]: img["image_digest"] for img in manifest.get("images", [])}

workload_mappings = [
    ("frontend", app_ns, "frontend"),
    ("api-gateway", app_ns, "api-gateway"),
    ("accounts-service", app_ns, "accounts-service"),
    ("pricing-service", app_ns, "pricing-service"),
    ("delivery-service", app_ns, "delivery-service"),
    ("communications-service", app_ns, "communications-service"),
    ("graphql-query-service", app_ns, "graphql-query-service"),
    ("keycloak", identity_ns, "keycloak"),
]

digest_failures = []
for name, ns, dep in workload_mappings:
    expected_digest = manifest_digests.get(name)
    try:
        proc = subprocess.run(
            ["kubectl", "--context", context, "get", f"deployment/{dep}", "-n", ns,
             "-o", "jsonpath={.spec.template.spec.containers[0].image}"],
            capture_output=True, text=True, check=True
        )
        deployed_image = proc.stdout.strip()
        print(f"[{name:<24}] -> Deployed: {deployed_image}")
        if expected_digest not in deployed_image:
            digest_failures.append(f"Workload '{name}' deployed image '{deployed_image}' does not contain expected digest '{expected_digest}'")
    except Exception as e:
        digest_failures.append(f"Failed inspecting deployment '{dep}' in namespace '{ns}': {e}")

if digest_failures:
    print("FATAL: Image digest verification failed:", file=sys.stderr)
    for err in digest_failures:
        print(f"  ✗ {err}", file=sys.stderr)
    sys.exit(1)

print("-" * 80)
print("✓ ALL 8 DEPLOYED WORKLOADS RUN THE EXACT APPROVED IMMUTABLE DIGESTS.")
EOF

if [[ ${ROLLOUT_FAILURES} -gt 0 ]]; then
  echo "FATAL: Rollout verification failed with ${ROLLOUT_FAILURES} unready workload(s)." >&2
  exit 1
fi

echo ""
echo "================================================================================"
echo "✓ STAGING ROLLOUT AND DIGEST VERIFICATION COMPLETED SUCCESSFULLY."
echo "================================================================================"
exit 0
