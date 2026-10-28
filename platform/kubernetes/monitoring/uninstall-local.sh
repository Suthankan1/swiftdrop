#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Monitoring Platform Safe Uninstaller (Issue #160)
# Removes monitoring release without touching application DBs, Kafka or MinIO.
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
NAMESPACE="swiftdrop-monitoring"
DELETE_DATA="${1:-keep-data}"

echo "================================================================================"
echo "          SWIFTDROP MONITORING PLATFORM SAFE UNINSTALLER                        "
echo "================================================================================"

if helm status swiftdrop-monitoring --kube-context "${CONTEXT_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
  echo "Uninstalling swiftdrop-monitoring Helm release..."
  helm uninstall swiftdrop-monitoring --kube-context "${CONTEXT_NAME}" -n "${NAMESPACE}"
  echo "✓ swiftdrop-monitoring release uninstalled."
else
  echo "Notice: Release 'swiftdrop-monitoring' is not currently installed."
fi

if [ "${DELETE_DATA}" = "--delete-data" ]; then
  echo "Purging monitoring PVCs in '${NAMESPACE}'..."
  kubectl --context "${CONTEXT_NAME}" delete pvc --all -n "${NAMESPACE}" 2>/dev/null || true
  echo "✓ Monitoring PVCs deleted."
else
  echo "Notice: PersistentVolumeClaims in '${NAMESPACE}' PRESERVED."
  echo "        Pass '--delete-data' to permanently remove monitoring TSDB data."
fi

echo "✓ Uninstallation completed. Application databases and storage remain untouched."

