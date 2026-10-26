#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Safe Local Helm Uninstall Script (Issue #149)
# Uninstalls the swiftdrop Helm release safely without erasing persistent volumes
# or deleting the namespaces.
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"
RELEASE_NAME="swiftdrop"
RELEASE_NS="swiftdrop-apps"

echo "================================================================================"
echo "          SWIFTDROP SAFE HELM UNINSTALL                                         "
echo "================================================================================"

if helm status "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" -n "${RELEASE_NS}" >/dev/null 2>&1; then
  echo "Uninstalling Helm release '${RELEASE_NAME}' in namespace '${RELEASE_NS}'..."
  helm uninstall "${RELEASE_NAME}" --kube-context "${CONTEXT_NAME}" -n "${RELEASE_NS}"
  echo "✓ Helm release '${RELEASE_NAME}' uninstalled successfully."
else
  echo "Release '${RELEASE_NAME}' is not currently installed in namespace '${RELEASE_NS}'."
fi

echo "✓ Namespaces and persistent storage volumes remain completely intact."
echo "================================================================================"
