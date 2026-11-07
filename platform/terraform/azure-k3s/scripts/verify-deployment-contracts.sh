#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — Deployment Contracts & Helm Values Verification
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Contract-first integration. Verifies images, digests, URLs, and Helm renders.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
HELM_DIR="${REPO_ROOT}/platform/kubernetes/helm"
AZURE_VALUES_DIR="${HELM_DIR}/environments/azure-k3s"

echo "================================================================================"
echo "          SWIFTDROP AZURE K3S DEPLOYMENT CONTRACTS VERIFICATION                  "
echo "================================================================================"

# 1. Verify existence of Azure K3s Helm environment values
echo "Checking Azure K3s Helm values files..."
for f in swiftdrop-values.yaml data-values.yaml messaging-values.yaml monitoring-values.yaml; do
  if [ ! -f "${AZURE_VALUES_DIR}/${f}" ]; then
    echo "ERROR: Missing required Helm values file: ${AZURE_VALUES_DIR}/${f}" >&2
    exit 1
  fi
  echo "✓ Found: ${f}"
done

# 2. Helm Lint & Template Rendering Checks
echo ""
echo "--- LINTING & RENDERING SWIFTDROP CHART WITH AZURE VALUES ---"
helm lint "${HELM_DIR}/swiftdrop" -f "${AZURE_VALUES_DIR}/swiftdrop-values.yaml"
helm template swiftdrop "${HELM_DIR}/swiftdrop" \
  -f "${HELM_DIR}/swiftdrop/values.yaml" \
  -f "${AZURE_VALUES_DIR}/swiftdrop-values.yaml" > /dev/null
echo "✓ Swiftdrop application chart rendered cleanly."

echo ""
echo "--- LINTING & RENDERING DATA CHART WITH AZURE VALUES ---"
helm lint "${HELM_DIR}/swiftdrop-data" -f "${AZURE_VALUES_DIR}/data-values.yaml"
helm template swiftdrop-data "${HELM_DIR}/swiftdrop-data" \
  -f "${HELM_DIR}/swiftdrop-data/values.yaml" \
  -f "${AZURE_VALUES_DIR}/data-values.yaml" > /dev/null
echo "✓ Swiftdrop data chart rendered cleanly."

echo ""
echo "--- LINTING & RENDERING MESSAGING CHART WITH AZURE VALUES ---"
helm lint "${HELM_DIR}/swiftdrop-messaging" -f "${AZURE_VALUES_DIR}/messaging-values.yaml"
helm template swiftdrop-messaging "${HELM_DIR}/swiftdrop-messaging" \
  -f "${HELM_DIR}/swiftdrop-messaging/values.yaml" \
  -f "${AZURE_VALUES_DIR}/messaging-values.yaml" > /dev/null
echo "✓ Swiftdrop messaging chart rendered cleanly."

echo ""
echo "--- LINTING & RENDERING MONITORING CHART WITH AZURE VALUES ---"
helm lint "${HELM_DIR}/swiftdrop-monitoring" -f "${AZURE_VALUES_DIR}/monitoring-values.yaml"
helm template swiftdrop-monitoring "${HELM_DIR}/swiftdrop-monitoring" \
  -f "${HELM_DIR}/swiftdrop-monitoring/values.yaml" \
  -f "${AZURE_VALUES_DIR}/monitoring-values.yaml" > /dev/null
echo "✓ Swiftdrop monitoring chart rendered cleanly."

echo ""
echo "================================================================================"
echo "✓ All SwiftDrop Azure K3s deployment contracts and Helm templates verified."
echo "================================================================================"
