#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Helm Deployment Script (Issue #149)
# Safely deploys the swiftdrop Helm chart into the k3d-swiftdrop-local cluster.
# Preflights context, namespaces, TLS secrets, and external secret references.
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"
CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../swiftdrop" && pwd)"
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALUES_FILE="${CHART_DIR}/values-local.yaml"
RELEASE_NAME="swiftdrop"
RELEASE_NS="swiftdrop-apps"

echo "================================================================================"
echo "          SWIFTDROP LOCAL HELM RELEASE DEPLOYMENT                               "
echo "================================================================================"

# 1. Verify kube-context
CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "${CURRENT_CONTEXT}" != "${CONTEXT_NAME}" ]]; then
  echo "Switching context to ${CONTEXT_NAME}..."
  kubectl config use-context "${CONTEXT_NAME}" >/dev/null
fi
echo "✓ Active context: ${CONTEXT_NAME}"

# 2. Verify namespaces
for ns in swiftdrop-apps swiftdrop-identity swiftdrop-data swiftdrop-messaging swiftdrop-system; do
  if ! kubectl --context "${CONTEXT_NAME}" get ns "${ns}" >/dev/null 2>&1; then
    echo "ERROR: Required namespace '${ns}' does not exist! Run platform/kubernetes/k3d/create.sh first." >&2
    exit 1
  fi
done
echo "✓ All core SwiftDrop namespaces verified."

# 3. Ensure TLS secrets exist
if ! kubectl --context "${CONTEXT_NAME}" get secret swiftdrop-tls-cert -n swiftdrop-apps >/dev/null 2>&1; then
  echo "TLS secret missing. Generating local development TLS certificates..."
  "${SCRIPTS_DIR}/generate-tls-secret.sh"
else
  echo "✓ TLS secret 'swiftdrop-tls-cert' present."
fi

# 4. Ensure Dev Secrets exist
if ! kubectl --context "${CONTEXT_NAME}" get secret accounts-db-secret -n swiftdrop-apps >/dev/null 2>&1; then
  echo "Dev secrets missing. Generating local development secret references..."
  "${SCRIPTS_DIR}/create-dev-secrets.sh"
else
  echo "✓ Development Kubernetes secrets present."
fi

# 5. Helm Lint Preflight
echo ""
echo "--- LINTING HELM CHART ---"
helm lint "${CHART_DIR}"

# 6. Helm Upgrade / Install
echo ""
echo "--- INSTALLING / UPGRADING HELM RELEASE '${RELEASE_NAME}' ---"
helm upgrade --install "${RELEASE_NAME}" "${CHART_DIR}" \
  --kube-context "${CONTEXT_NAME}" \
  --namespace "${RELEASE_NS}" \
  --values "${VALUES_FILE}" \
  "$@"

echo ""
echo "================================================================================"
echo "✓ SwiftDrop Helm chart successfully deployed to '${CONTEXT_NAME}' (${RELEASE_NS})."
echo "================================================================================"
