#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Dedicated Local k3d Cluster Stop Script
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"

echo "================================================================================"
echo "          STOPPING SWIFTDROP DEDICATED LOCAL K3D CLUSTER                        "
echo "================================================================================"

if ! command -v k3d >/dev/null 2>&1; then
  echo "ERROR: k3d is not installed or not in PATH." >&2
  exit 1
fi

EXISTING_CLUSTERS="$(k3d cluster list --no-headers 2>/dev/null | awk '{print $1}' || true)"
if ! echo "${EXISTING_CLUSTERS}" | grep -qx "${CLUSTER_NAME}"; then
  echo "NOTICE: Cluster '${CLUSTER_NAME}' does not exist. Nothing to stop."
  exit 0
fi

echo "Stopping cluster '${CLUSTER_NAME}' (preserving container state, data, and volumes)..."
k3d cluster stop "${CLUSTER_NAME}"

echo ""
echo "NOTICE: The cluster containers have been stopped."
echo "        • Persistent host storage in \${HOME}/.swiftdrop/k3d/storage is PRESERVED."
echo "        • Unrelated Docker containers, Compose services, and volumes are UNTOUCHED."
echo "        • WARNING: Stopping a cluster is NOT an application data-backup procedure."
echo "✓ Cluster '${CLUSTER_NAME}' stopped safely."
