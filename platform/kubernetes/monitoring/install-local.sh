#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Monitoring Stack Installer (Issue #160)
# Strictly $0 Local Deployment (Prometheus, Grafana, Alertmanager, Loki)
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
NAMESPACE="swiftdrop-monitoring"
CHART_DIR="platform/kubernetes/helm/swiftdrop-monitoring"
VALUES_FILE="${CHART_DIR}/values-local.yaml"

echo "================================================================================"
echo "          SWIFTDROP PROTECTED MONITORING PLATFORM INSTALLER                     "
echo "================================================================================"

# 1. Preflight binary checks
echo "--- 1. CHECKING REQUIRED CLI BINARIES ---"
for bin in kubectl helm docker; do
  if ! command -v "${bin}" >/dev/null 2>&1; then
    echo "ERROR: Required binary '${bin}' is missing from PATH." >&2
    exit 1
  fi
  echo "✓ ${bin} is present ($(which "${bin}"))."
done

# 2. Context verification
echo ""
echo "--- 2. VERIFYING KUBERNETES CONTEXT ---"
CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || echo "")"
if [ "${CURRENT_CONTEXT}" != "${CONTEXT_NAME}" ]; then
  echo "WARNING: Current context '${CURRENT_CONTEXT}' is not '${CONTEXT_NAME}'."
  echo "Setting context to '${CONTEXT_NAME}'..."
  kubectl config use-context "${CONTEXT_NAME}"
fi
echo "✓ Active context: ${CONTEXT_NAME}"

# 3. Node readiness & capacity verification
echo ""
echo "--- 3. VERIFYING CLUSTER READINESS & CAPACITY ---"
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready node/swiftdrop-control-plane --timeout=30s
echo "✓ Cluster control plane node is Ready."

# 4. Namespace setup
echo ""
echo "--- 4. ENSURING MONITORING NAMESPACE ---"
if ! kubectl --context "${CONTEXT_NAME}" get namespace "${NAMESPACE}" >/dev/null 2>&1; then
  kubectl --context "${CONTEXT_NAME}" create namespace "${NAMESPACE}"
  kubectl --context "${CONTEXT_NAME}" label namespace "${NAMESPACE}" kubernetes.io/metadata.name="${NAMESPACE}" --overwrite
  echo "✓ Namespace '${NAMESPACE}' created and labeled."
else
  kubectl --context "${CONTEXT_NAME}" label namespace "${NAMESPACE}" kubernetes.io/metadata.name="${NAMESPACE}" --overwrite
  echo "✓ Namespace '${NAMESPACE}' exists."
fi

# 5. External operator secret provisioning
echo ""
echo "--- 5. PROVISIONING GRAFANA OPERATOR SECRET ---"
SECRET_NAME="grafana-admin-secret"
if ! kubectl --context "${CONTEXT_NAME}" get secret "${SECRET_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
  ADMIN_PASS="${GRAFANA_ADMIN_PASSWORD:-swiftdrop_admin_dev_pass}"
  kubectl --context "${CONTEXT_NAME}" create secret generic "${SECRET_NAME}" \
    --namespace "${NAMESPACE}" \
    --from-literal=admin-user="admin" \
    --from-literal=admin-password="${ADMIN_PASS}"
  echo "✓ Secret '${SECRET_NAME}' provisioned in '${NAMESPACE}'."
else
  echo "✓ Secret '${SECRET_NAME}' already present in '${NAMESPACE}'."
fi

# 6. Helm lint & installation
echo ""
echo "--- 6. LINTING & DEPLOYING MONITORING HELM CHART ---"
helm lint "${CHART_DIR}"
helm upgrade --install swiftdrop-monitoring "${CHART_DIR}" \
  --kube-context "${CONTEXT_NAME}" \
  --namespace "${NAMESPACE}" \
  -f "${VALUES_FILE}"

# 7. Rollout verification
echo ""
echo "--- 7. AWAITING WORKLOAD READINESS ---"
echo "Waiting for Prometheus deployment..."
kubectl --context "${CONTEXT_NAME}" rollout status deployment/swiftdrop-prometheus -n "${NAMESPACE}" --timeout=120s

echo "Waiting for Blackbox Exporter deployment..."
kubectl --context "${CONTEXT_NAME}" rollout status deployment/swiftdrop-blackbox-exporter -n "${NAMESPACE}" --timeout=60s

echo "Waiting for Alertmanager deployment..."
kubectl --context "${CONTEXT_NAME}" rollout status deployment/swiftdrop-alertmanager -n "${NAMESPACE}" --timeout=90s

echo "Waiting for Grafana deployment..."
kubectl --context "${CONTEXT_NAME}" rollout status deployment/swiftdrop-grafana -n "${NAMESPACE}" --timeout=120s

if kubectl --context "${CONTEXT_NAME}" get deployment swiftdrop-loki -n "${NAMESPACE}" >/dev/null 2>&1; then
  echo "Waiting for Loki deployment..."
  kubectl --context "${CONTEXT_NAME}" rollout status deployment/swiftdrop-loki -n "${NAMESPACE}" --timeout=120s
fi

echo ""
echo "================================================================================"
echo "✓ SwiftDrop Monitoring Stack successfully installed in '${NAMESPACE}'."
echo "================================================================================"
echo "Access instructions (private localhost-only port forwarding):"
echo "  • Grafana:      kubectl port-forward -n ${NAMESPACE} svc/swiftdrop-grafana 3000:3000"
echo "  • Prometheus:   kubectl port-forward -n ${NAMESPACE} svc/swiftdrop-prometheus 9090:9090"
echo "  • Alertmanager: kubectl port-forward -n ${NAMESPACE} svc/swiftdrop-alertmanager 9093:9093"
echo "================================================================================"

