#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Dedicated Local k3d Cluster Start Script
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"

echo "================================================================================"
echo "          STARTING SWIFTDROP DEDICATED LOCAL K3D CLUSTER                        "
echo "================================================================================"

if ! command -v k3d >/dev/null 2>&1; then
  echo "ERROR: k3d is not installed or not in PATH." >&2
  exit 1
fi

EXISTING_CLUSTERS="$(k3d cluster list --no-headers 2>/dev/null | awk '{print $1}' || true)"
if ! echo "${EXISTING_CLUSTERS}" | grep -qx "${CLUSTER_NAME}"; then
  echo "ERROR: Cluster '${CLUSTER_NAME}' does not exist! Run create.sh first." >&2
  exit 1
fi

RUNNING_SERVERS="$(k3d cluster list --no-headers 2>/dev/null | awk -v c="${CLUSTER_NAME}" '$1==c {print $2}')"
if [ "${RUNNING_SERVERS}" != "0/1" ] && [ "${RUNNING_SERVERS}" != "0" ]; then
  echo "✓ Cluster '${CLUSTER_NAME}' is already running."
else
  echo "Starting cluster '${CLUSTER_NAME}'..."
  k3d cluster start "${CLUSTER_NAME}"
fi

echo "Waiting for node readiness..."
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready node/swiftdrop-control-plane --timeout=60s
echo "Waiting for CoreDNS readiness..."
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod -l k8s-app=kube-dns -n kube-system --timeout=60s

echo "✓ Cluster '${CLUSTER_NAME}' is ACTIVE and READY."
