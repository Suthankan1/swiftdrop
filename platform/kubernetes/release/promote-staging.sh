#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Guarded Staging Promotion
# Promotes reviewed release candidate to staging environment using immutable
# container digests, migration safety preflight, and deterministic rollout validation.
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CHART_DIR="${REPO_ROOT}/platform/kubernetes/helm/swiftdrop"
VALUES_FILE="${CHART_DIR}/values-staging.yaml"

CONTEXT_NAME="k3d-swiftdrop-local"
RELEASE_NAME="swiftdrop-apps"
NAMESPACE="swiftdrop-apps"
MANIFEST_FILE="${REPO_ROOT}/build/artifacts/digests/release-candidate-manifest.json"
APPROVAL_REF=""
TIMEOUT_SECS=180
DRY_RUN=false
SKIP_ROLLBACK_ON_FAILURE=false

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>           Kubernetes context (default: k3d-swiftdrop-local)
  --release <RELEASE>           Helm release name (default: swiftdrop-apps)
  --namespace <NAMESPACE>       Target namespace (default: swiftdrop-apps)
  --manifest <PATH>             Path to release candidate manifest JSON
  --approval-ref <REF>          Required operator or reviewer approval reference
  --values <PATH>               Custom values file (default: values-staging.yaml)
  --timeout <SECS>              Rollout timeout in seconds (default: 180)
  --dry-run                     Perform preflight validation without applying changes
  --no-rollback                 Do not automatically trigger application rollback on failure
  -h, --help                    Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    --release) RELEASE_NAME="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --manifest) MANIFEST_FILE="$2"; shift 2 ;;
    --approval-ref) APPROVAL_REF="$2"; shift 2 ;;
    --values) VALUES_FILE="$2"; shift 2 ;;
    --timeout) TIMEOUT_SECS="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift 1 ;;
    --no-rollback) SKIP_ROLLBACK_ON_FAILURE=true; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

echo "================================================================================"
echo "          SWIFTDROP GATED STAGING PROMOTION & ROLLOUT                           "
echo "================================================================================"
echo "Release Name:     ${RELEASE_NAME}"
echo "Namespace:        ${NAMESPACE}"
echo "Target Context:   ${CONTEXT_NAME}"
echo "Manifest:         ${MANIFEST_FILE}"
echo "Approval Ref:     ${APPROVAL_REF:-NONE}"
echo "Values File:      ${VALUES_FILE}"

# 1. Approval Gate Check
if [[ -z "${APPROVAL_REF}" ]]; then
  echo "FATAL: Staging promotion requires an explicit approval reference (--approval-ref <REF>)!" >&2
  echo "Unreviewed or automatic promotions are strictly prohibited." >&2
  exit 1
fi

# 2. Strict Context Safety Check
if [[ "${CONTEXT_NAME}" != "k3d-swiftdrop-local" ]]; then
  echo "FATAL: Deployment strictly restricted to approved local context 'k3d-swiftdrop-local'!" >&2
  echo "Refusing to target external, production, or unapproved context '${CONTEXT_NAME}'." >&2
  exit 1
fi

# 3. Values File Preflight: Reject Placeholder Services
echo ""
echo "--- 1. VERIFYING RELEASE VALUES FILE ---"
if ! grep -q "createPlaceholderServices: false" "${VALUES_FILE}"; then
  echo "FATAL: Staging promotion requires real stateful service dependencies!" >&2
  echo "Found placeholder interfaces enabled in ${VALUES_FILE}. Deployment blocked." >&2
  exit 1
fi
echo "✓ Stateful interfaces verified: placeholder services are disabled."

# 4. Manifest Integrity Preflight
echo ""
echo "--- 2. VERIFYING CANDIDATE MANIFEST INTEGRITY ---"
"${SCRIPT_DIR}/verify-candidate-manifest.sh" --manifest "${MANIFEST_FILE}"

# 5. Database Migration Preflight
echo ""
echo "--- 3. DATABASE MIGRATION SAFETY PREFLIGHT ---"
if [[ "${DRY_RUN}" == "true" ]]; then
  "${SCRIPT_DIR}/migration-preflight.sh" --context "${CONTEXT_NAME}" --dry-run
else
  "${SCRIPT_DIR}/migration-preflight.sh" --context "${CONTEXT_NAME}"
fi

# 6. Helm Chart Linting
echo ""
echo "--- 4. LINTING HELM CHART ---"
helm lint "${CHART_DIR}" --values "${VALUES_FILE}"

if [[ "${DRY_RUN}" == "true" ]]; then
  echo ""
  echo "✓ Dry-run completed. All release promotion gates passed cleanly."
  exit 0
fi

# 7. Extract Image Digests from Manifest to pass to Helm
SET_ARGS=()
while IFS= read -r line; do
  SET_ARGS+=("$line")
done < <(python3 - << EOF
import json
with open("${MANIFEST_FILE}", "r", encoding="utf-8") as f:
    m = json.load(f)
key_map = {
    "frontend": "frontend.image.digest",
    "api-gateway": "apiGateway.image.digest",
    "accounts-service": "accounts.image.digest",
    "pricing-service": "pricing.image.digest",
    "delivery-service": "delivery.image.digest",
    "communications-service": "communications.image.digest",
    "graphql-query-service": "graphql.image.digest",
    "keycloak": "keycloak.image.digest"
}
for img in m.get("images", []):
    k = key_map.get(img["image_name"])
    if k:
        print(f"--set {k}={img['image_digest']}")
EOF
)

# 8. Record Previous Revision for Safe Rollback if needed
PREV_REVISION="$(helm status "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" --namespace "${NAMESPACE}" -o json 2>/dev/null | jq -r '.version // empty' || echo "")"
echo "Previous Helm revision: ${PREV_REVISION:-none}"

# 9. Perform Helm Upgrade
echo ""
echo "--- 5. EXECUTING HELM UPGRADE FOR RELEASE '${RELEASE_NAME}' ---"

DEPLOY_SUCCESS=true
if ! helm upgrade --install "${RELEASE_NAME}" "${CHART_DIR}" \
    --kube-context "${CONTEXT_NAME}" \
    --namespace "${NAMESPACE}" \
    --values "${VALUES_FILE}" \
    "${SET_ARGS[@]}" \
    --wait \
    --timeout "${TIMEOUT_SECS}s"; then
  DEPLOY_SUCCESS=false
fi

if [[ "${DEPLOY_SUCCESS}" != "true" ]]; then
  echo ""
  echo "================================================================================" >&2
  echo "FATAL: HELM UPGRADE / ROLLOUT FAILED FOR RELEASE '${RELEASE_NAME}'!" >&2
  echo "================================================================================" >&2

  if [[ "${SKIP_ROLLBACK_ON_FAILURE}" != "true" && -n "${PREV_REVISION}" ]]; then
    echo "Initiating automatic guarded rollback to previous revision ${PREV_REVISION}..."
    "${SCRIPT_DIR}/rollback-app.sh" \
      --context "${CONTEXT_NAME}" \
      --release "${RELEASE_NAME}" \
      --namespace "${NAMESPACE}" \
      --target-revision "${PREV_REVISION}" \
      --operator-approval "auto-rollback-on-failed-rollout-${APPROVAL_REF}"
  fi
  exit 1
fi

# 10. Post-Rollout Verification
echo ""
echo "--- 6. VERIFYING POST-ROLLOUT PODS & DIGESTS ---"
"${SCRIPT_DIR}/verify-rollout.sh" \
  --context "${CONTEXT_NAME}" \
  --namespace "${NAMESPACE}" \
  --manifest "${MANIFEST_FILE}" \
  --timeout "${TIMEOUT_SECS}"

echo ""
echo "================================================================================"
echo "✓ STAGING PROMOTION COMPLETED SUCCESSFULLY."
echo "Release '${RELEASE_NAME}' is ACTIVE and RUNNING approved candidate digests."
echo "================================================================================"
exit 0

