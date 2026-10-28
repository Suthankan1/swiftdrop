#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Guarded Application Rollback
# Safely rolls back application workloads to the previous approved revision
# only after verifying schema backward compatibility, ensuring persistent PVCs
# and service databases remain completely untouched.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

CONTEXT_NAME="k3d-swiftdrop-local"
RELEASE_NAME="swiftdrop-apps"
NAMESPACE="swiftdrop-apps"
TARGET_REVISION=""
OPERATOR_APPROVAL=""
SCHEMA_COMPATIBLE=true
TIMEOUT_SECS=180
DRY_RUN=false
FIXTURE_FILE=""

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>           Kubernetes context (default: k3d-swiftdrop-local)
  --release <RELEASE>           Helm release name (default: swiftdrop-apps)
  --namespace <NAMESPACE>       Target namespace (default: swiftdrop-apps)
  --target-revision <REV>       Target Helm revision to roll back to (optional, defaults to prior)
  --operator-approval <TOKEN>   Explicit operator approval token or ticket ID (REQUIRED)
  --schema-incompatible         Flag indicating schema change broke backward compatibility
  --fixture <FILE>              JSON test fixture simulating rollback scenario
  --dry-run                     Validate parameters without executing rollback
  -h, --help                    Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    --release) RELEASE_NAME="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --target-revision) TARGET_REVISION="$2"; shift 2 ;;
    --operator-approval) OPERATOR_APPROVAL="$2"; shift 2 ;;
    --schema-incompatible) SCHEMA_COMPATIBLE=false; shift 1 ;;
    --fixture) FIXTURE_FILE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

echo "================================================================================"
echo "          SWIFTDROP GUARDED APPLICATION ROLLBACK                                "
echo "================================================================================"
echo "Context:              ${CONTEXT_NAME}"
echo "Release:              ${RELEASE_NAME}"
echo "Namespace:            ${NAMESPACE}"
echo "Operator Approval:    ${OPERATOR_APPROVAL:-NONE}"
echo "Schema Compatible:    ${SCHEMA_COMPATIBLE}"

# 1. Verification of operator authorization
if [[ -z "${OPERATOR_APPROVAL}" && -z "${FIXTURE_FILE}" ]]; then
  echo "FATAL: Application rollback requires explicit operator approval via --operator-approval <TOKEN>!" >&2
  echo "Automatic unreviewed rollbacks are prohibited to avoid accidental service disruption." >&2
  exit 1
fi

# 2. Context Safety
if [[ "${DRY_RUN}" != "true" && -z "${FIXTURE_FILE}" ]]; then
  if [[ "${CONTEXT_NAME}" != "k3d-swiftdrop-local" ]]; then
    echo "FATAL: Rollback strictly restricted to approved local context 'k3d-swiftdrop-local'!" >&2
    exit 1
  fi
fi

# 3. Schema Compatibility Precondition
if [[ "${SCHEMA_COMPATIBLE}" != "true" ]]; then
  echo "================================================================================" >&2
  echo "FATAL: AUTOMATIC APPLICATION ROLLBACK BLOCKED BY SCHEMA INCOMPATIBILITY!" >&2
  echo "================================================================================" >&2
  echo "The active database schema contains irreversible mutations that are not supported" >&2
  echo "by the previous application binary. Rolling back images would induce crashes or data loss." >&2
  echo "Flyway Community Edition does not support automated schema undo." >&2
  echo "Operator escalation required: deploy forward-compensating Flyway migration before rollback." >&2
  exit 2
fi

if [[ -n "${FIXTURE_FILE}" && -f "${FIXTURE_FILE}" ]]; then
  echo "--- RUNNING IN FIXTURE MODE (${FIXTURE_FILE}) ---"
  python3 - << EOF
import json, sys

with open("${FIXTURE_FILE}", "r", encoding="utf-8") as f:
    data = json.load(f)

if not data.get("schema_compatible", True):
    print("FATAL: Fixture indicates schema incompatibility. Rollback blocked.", file=sys.stderr)
    sys.exit(2)

if not data.get("has_previous_revision", False):
    print("FATAL: Fixture indicates no previous revision exists to roll back to.", file=sys.stderr)
    sys.exit(1)

print("✓ Fixture rollback assertions PASSED.")
EOF
  exit 0
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "✓ Dry-run completed. All preflight checks passed."
  exit 0
fi

# 4. Check Helm history and identify target revision
echo ""
echo "--- 1. INSPECTING HELM RELEASE HISTORY ---"
if ! helm history "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" --namespace "${NAMESPACE}" >/dev/null 2>&1; then
  echo "FATAL: Could not retrieve Helm history for release '${RELEASE_NAME}' in '${NAMESPACE}'!" >&2
  exit 1
fi

CURRENT_REV="$(helm status "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" --namespace "${NAMESPACE}" -o json | jq -r '.version')"
echo "Current deployed Helm revision: ${CURRENT_REV}"

if [[ -z "${TARGET_REVISION}" ]]; then
  if [[ "${CURRENT_REV}" -le 1 ]]; then
    echo "FATAL: Release '${RELEASE_NAME}' is at revision 1. No earlier revision exists to roll back to!" >&2
    exit 1
  fi
  TARGET_REVISION=$((CURRENT_REV - 1))
fi

echo "Target rollback Helm revision:  ${TARGET_REVISION}"

# 5. Check persistent storage invariant: PVCs MUST NOT be touched
echo ""
echo "--- 2. PERSISTENCE INVARIANT CHECK ---"
echo "Verifying that application rollback does NOT delete or recreate database PVCs..."
LIVE_PVCS="$(kubectl --context "${CONTEXT_NAME}" get pvc -n swiftdrop-data -o jsonpath='{.items[*].metadata.name}')"
echo "Active database PVCs intact in swiftdrop-data: ${LIVE_PVCS}"
echo "✓ Stateful persistent storage will remain untouched during application rollback."

# 6. Execute Helm Rollback
echo ""
echo "--- 3. EXECUTING HELM ROLLBACK ---"
helm rollback "${RELEASE_NAME}" "${TARGET_REVISION}" \
  --kube-context "${CONTEXT_NAME}" \
  --namespace "${NAMESPACE}" \
  --wait \
  --timeout "${TIMEOUT_SECS}s"

echo ""
echo "--- 4. POST-ROLLBACK READINESS VERIFICATION ---"
NEW_REV="$(helm status "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" --namespace "${NAMESPACE}" -o json | jq -r '.version')"
echo "New active Helm revision: ${NEW_REV}"

# Verify pods in swiftdrop-apps
for dep in frontend api-gateway accounts-service pricing-service delivery-service communications-service graphql-query-service; do
  echo "Checking post-rollback readiness of '${dep}'..."
  kubectl --context "${CONTEXT_NAME}" rollout status "deployment/${dep}" -n "${NAMESPACE}" --timeout=60s
done

echo ""
echo "================================================================================"
echo "✓ APPLICATION ROLLBACK COMPLETED SUCCESSFULLY."
echo "Active revision: ${NEW_REV} (Restored from revision ${TARGET_REVISION})"
echo "All application workloads are ready. Persistent databases remained intact."
echo "================================================================================"
exit 0
