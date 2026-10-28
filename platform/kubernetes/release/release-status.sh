#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Release & Operational Health Status
# Displays active Helm release revisions, deployed image digests, database schema
# versions, monitoring alerts, and cluster health in a single unified view.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"
APP_NS="swiftdrop-apps"
DATA_NS="swiftdrop-data"
IDENTITY_NS="swiftdrop-identity"
MESSAGING_NS="swiftdrop-messaging"
MONITORING_NS="swiftdrop-monitoring"

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>    Kubernetes context (default: k3d-swiftdrop-local)
  -h, --help             Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

echo "================================================================================"
echo "          SWIFTDROP ACTIVE RELEASE & OPERATIONAL HEALTH STATUS                 "
echo "================================================================================"
echo "Target Context: ${CONTEXT_NAME}"

# 1. Helm Releases
echo ""
echo "--- 1. ACTIVE HELM RELEASES ---"
helm list --kube-context "${CONTEXT_NAME}" -A -o table

# 2. Deployed Images and Digests
echo ""
echo "--- 2. APPLICATION WORKLOADS & DEPLOYED IMAGE DIGESTS ---"
for dep in frontend api-gateway accounts-service pricing-service delivery-service communications-service graphql-query-service; do
  STATUS="$(kubectl --context "${CONTEXT_NAME}" get deployment "${dep}" -n "${APP_NS}" -o jsonpath='{.status.readyReplicas}/{\.spec.replicas}' 2>/dev/null || echo "N/A")"
  IMG="$(kubectl --context "${CONTEXT_NAME}" get deployment "${dep}" -n "${APP_NS}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "N/A")"
  printf "  %-24s Ready: %-8s Image: %s\n" "${dep}" "${STATUS}" "${IMG}"
done

KC_STATUS="$(kubectl --context "${CONTEXT_NAME}" get deployment keycloak -n "${IDENTITY_NS}" -o jsonpath='{.status.readyReplicas}/{\.spec.replicas}' 2>/dev/null || echo "N/A")"
KC_IMG="$(kubectl --context "${CONTEXT_NAME}" get deployment keycloak -n "${IDENTITY_NS}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "N/A")"
printf "  %-24s Ready: %-8s Image: %s\n" "keycloak" "${KC_STATUS}" "${KC_IMG}"

# 3. Database State
echo ""
echo "--- 3. DATABASE STATE & FLYWAY SCHEMAS ---"
for spec in "accounts-postgres-0:accounts_app:accounts_db" "business-postgres-0:pricing_app:pricing_db" "business-postgres-0:delivery_app:delivery_db" "business-postgres-0:communications_app:communications_db"; do
  IFS=':' read -r pod user db <<< "${spec}"
  VER="$(kubectl --context "${CONTEXT_NAME}" exec -n "${DATA_NS}" "${pod}" -- \
    psql -U "${user}" -d "${db}" -tAc "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;" 2>/dev/null || echo "unknown")"
  printf "  %-20s (DB: %-18s) Schema Version: %s\n" "${pod}" "${db}" "${VER}"
done

# 4. Storage Claims
echo ""
echo "--- 4. PERSISTENT STORAGE STATUS ---"
kubectl --context "${CONTEXT_NAME}" get pvc -A -o custom-columns="NAMESPACE:.metadata.namespace,NAME:.metadata.name,STATUS:.status.phase,CAPACITY:.status.capacity.storage,STORAGECLASS:.spec.storageClassName"

echo ""
echo "================================================================================"
echo "Status check complete."

