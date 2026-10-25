#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Dedicated Local k3d Cluster Status Script
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"

echo "================================================================================"
echo "          SWIFTDROP DEDICATED LOCAL K3D CLUSTER STATUS                          "
echo "================================================================================"

if ! command -v k3d >/dev/null 2>&1; then
  echo "ERROR: k3d is not installed or not in PATH." >&2
  exit 1
fi

EXISTING_CLUSTERS="$(k3d cluster list --no-headers 2>/dev/null | awk '{print $1}' || true)"
if ! echo "${EXISTING_CLUSTERS}" | grep -qx "${CLUSTER_NAME}"; then
  echo "Status: NOT CREATED (Cluster '${CLUSTER_NAME}' does not exist)"
  exit 0
fi

echo "--- 1. CLUSTER LIFECYCLE STATE ---"
k3d cluster list "${CLUSTER_NAME}"

echo ""
echo "--- 2. KUBERNETES CONTEXT & API ---"
echo "Target Context: ${CONTEXT_NAME}"
if ! kubectl --context "${CONTEXT_NAME}" cluster-info 2>/dev/null | head -n 2; then
  echo "WARNING: Kubernetes API is not reachable. Cluster may be stopped."
  exit 0
fi

echo ""
echo "--- 3. NODE READINESS & ALLOCATABLE RESOURCES ---"
kubectl --context "${CONTEXT_NAME}" get nodes -o wide
echo ""
NODE_NAME="swiftdrop-control-plane"
CPU_ALLOC="$(kubectl --context "${CONTEXT_NAME}" get node "${NODE_NAME}" -o jsonpath='{.status.allocatable.cpu}' 2>/dev/null || echo "N/A")"
MEM_ALLOC="$(kubectl --context "${CONTEXT_NAME}" get node "${NODE_NAME}" -o jsonpath='{.status.allocatable.memory}' 2>/dev/null || echo "N/A")"
PODS_ALLOC="$(kubectl --context "${CONTEXT_NAME}" get node "${NODE_NAME}" -o jsonpath='{.status.allocatable.pods}' 2>/dev/null || echo "N/A")"
echo "Allocatable CPU:    ${CPU_ALLOC}"
echo "Allocatable Memory: ${MEM_ALLOC}"
echo "Allocatable Pods:   ${PODS_ALLOC}"

echo ""
echo "--- 4. STORAGE CLASS AVAILABILITY ---"
kubectl --context "${CONTEXT_NAME}" get storageclass

echo ""
echo "--- 5. LOOPBACK INGRESS PORTS & LOAD BALANCER ---"
docker ps --filter "name=k3d-${CLUSTER_NAME}-serverlb" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "--- 6. SWIFTDROP NAMESPACES ---"
kubectl --context "${CONTEXT_NAME}" get namespaces -l "app.kubernetes.io/part-of=swiftdrop"

echo ""
echo "--- 7. SYSTEM PODS (kube-system) ---"
kubectl --context "${CONTEXT_NAME}" get pods -n kube-system
