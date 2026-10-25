#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Dedicated Local k3d Cluster Creation Script
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
CONFIG_FILE="${SCRIPT_DIR}/cluster.yaml"
NAMESPACES_FILE="${SCRIPT_DIR}/namespaces.yaml"

echo "================================================================================"
echo "          SWIFTDROP DEDICATED LOCAL K3D CLUSTER BOOTSTRAP GATE                  "
echo "================================================================================"

# 1. Toolchain presence validation
echo "--- 1. VERIFYING TOOLCHAIN PREREQUISITES ---"
for cmd in docker k3d kubectl; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "ERROR: Required tool '${cmd}' is not installed or not in PATH!" >&2
    exit 1
  fi
  echo "✓ ${cmd} found: $(command -v "${cmd}")"
done

# 2. Docker daemon readiness
echo ""
echo "--- 2. VERIFYING DOCKER RUNTIME ---"
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon is not running! Please start Docker Desktop." >&2
  exit 1
fi
echo "✓ Docker daemon is active and responsive."

# 3. Storage preflight check
echo ""
echo "--- 3. RUNNING STORAGE PREFLIGHT ---"
"${SCRIPT_DIR}/storage-preflight.sh"

# 4. Collision & safe reuse inspection
echo ""
echo "--- 4. CHECKING EXISTING CLUSTERS ---"
EXISTING_CLUSTERS="$(k3d cluster list --no-headers 2>/dev/null | awk '{print $1}' || true)"
CLUSTER_EXISTS=false
if echo "${EXISTING_CLUSTERS}" | grep -qx "${CLUSTER_NAME}"; then
  CLUSTER_EXISTS=true
  echo "NOTICE: Cluster '${CLUSTER_NAME}' already exists."
fi

# 5. Port availability validation (only if cluster does not yet exist)
if [ "${CLUSTER_EXISTS}" = "false" ]; then
  echo ""
  echo "--- 5. VERIFYING LOOPBACK PORT AVAILABILITY ---"
  check_port() {
    local port="$1"
    local desc="$2"
    if lsof -iTCP:"${port}" -sTCP:LISTEN -P -n 2>/dev/null | grep -v "k3d-${CLUSTER_NAME}" >/dev/null 2>&1; then
      echo "ERROR: Port ${port} (${desc}) is already in use by an external process!" >&2
      lsof -iTCP:"${port}" -sTCP:LISTEN -P -n || true
      exit 1
    fi
    echo "✓ Port ${port} (${desc}) is available on loopback."
  }
  check_port 16443 "Kubernetes API"
  check_port 18080 "HTTP Ingress"
  check_port 18443 "HTTPS Ingress"
fi

# 6. Cluster bootstrap or startup
echo ""
echo "--- 6. ENSURING CLUSTER STATE ---"
if [ "${CLUSTER_EXISTS}" = "true" ]; then
  RUNNING_SERVERS="$(k3d cluster list --no-headers 2>/dev/null | awk -v c="${CLUSTER_NAME}" '$1==c {print $2}')"
  if [ "${RUNNING_SERVERS}" != "0/1" ] && [ "${RUNNING_SERVERS}" != "0" ]; then
    echo "✓ Cluster '${CLUSTER_NAME}' is already running."
  else
    echo "Cluster '${CLUSTER_NAME}' is currently stopped. Starting..."
    k3d cluster start "${CLUSTER_NAME}"
  fi
else
  echo "Bootstrapping dedicated cluster '${CLUSTER_NAME}' from ${CONFIG_FILE}..."
  k3d cluster create --config "${CONFIG_FILE}"
fi

# 7. Explicit context verification
echo ""
echo "--- 7. VERIFYING KUBERNETES CONTEXT ---"
CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || echo "none")"
echo "Current default kubeconfig context: ${CURRENT_CONTEXT}"
echo "Explicitly targeting SwiftDrop context: ${CONTEXT_NAME}"

# 8. Node and CoreDNS readiness check
echo ""
echo "--- 8. WAITING FOR CLUSTER READINESS ---"
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready node/swiftdrop-control-plane --timeout=120s
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod -l k8s-app=kube-dns -n kube-system --timeout=120s

# 9. Apply management namespaces
echo ""
echo "--- 9. APPLYING SWIFTDROP MANAGEMENT NAMESPACES ---"
kubectl --context "${CONTEXT_NAME}" apply -f "${NAMESPACES_FILE}"

echo ""
echo "================================================================================"
echo "✓ Cluster '${CLUSTER_NAME}' is READY and VERIFIED on context '${CONTEXT_NAME}'"
echo "================================================================================"
