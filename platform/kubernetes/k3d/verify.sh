#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Cluster Readiness, Isolation & Capability Verification Tool
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# Consumed by: Issues #149 and #154 preflight acceptance
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
STORAGE_ROOT="${SWIFTDROP_STORAGE_ROOT:-${HOME}/.swiftdrop/k3d/storage}"

echo "================================================================================"
echo "          SWIFTDROP LOCAL CLUSTER READINESS & ISOLATION VERIFICATION            "
echo "================================================================================"

# 1. Core Readiness
echo "--- 1. VERIFYING CLUSTER CORE READINESS ---"
if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl is not installed or not in PATH." >&2
  exit 1
fi

if ! kubectl --context "${CONTEXT_NAME}" get nodes >/dev/null 2>&1; then
  echo "ERROR: Kubernetes API is not reachable on context '${CONTEXT_NAME}'!" >&2
  exit 1
fi

kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready node/swiftdrop-control-plane --timeout=60s
echo "✓ Node 'swiftdrop-control-plane' is Ready."

kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod -l k8s-app=kube-dns -n kube-system --timeout=60s
echo "✓ CoreDNS is Ready."

kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod -l app.kubernetes.io/name=traefik -n kube-system --timeout=60s
echo "✓ Traefik Ingress Controller is Ready."

# StorageClass check
if ! kubectl --context "${CONTEXT_NAME}" get storageclass local-path >/dev/null 2>&1; then
  echo "ERROR: Default StorageClass 'local-path' not found!" >&2
  exit 1
fi
echo "✓ Default StorageClass 'local-path' is active."

# 2. Loopback Exposure Verification
echo ""
echo "--- 2. VERIFYING LOOPBACK-ONLY NETWORK BINDINGS ---"
LB_CONTAINER="k3d-${CLUSTER_NAME}-serverlb"
LB_PORTS="$(docker port "${LB_CONTAINER}" 2>/dev/null || echo "")"
echo "LoadBalancer container port mappings:"
echo "${LB_PORTS}"

if echo "${LB_PORTS}" | grep -E "0\.0\.0\.0" >/dev/null 2>&1; then
  echo "ERROR: Detected public or 0.0.0.0 binding in LoadBalancer ports! Failsafe triggered." >&2
  exit 1
fi
echo "✓ All external ingress and API ports are bound strictly to loopback (127.0.0.1)."

# 3. Synthetic Storage Contract & Host Mount Verification
echo ""
echo "--- 3. VERIFYING STORAGE CONTRACT (SYNTHETIC PVC TEST) ---"
TEST_NS="swiftdrop-verify-storage-$$-${RANDOM}"
kubectl --context "${CONTEXT_NAME}" create namespace "${TEST_NS}" >/dev/null

cleanup_storage() {
  kubectl --context "${CONTEXT_NAME}" delete namespace "${TEST_NS}" --wait=false --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup_storage EXIT

TOKEN="swiftdrop-storage-contract-test-$(date +%s)"
cat << EOF | kubectl --context "${CONTEXT_NAME}" apply -f - >/dev/null
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: verify-pvc
  namespace: ${TEST_NS}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: local-path
  resources:
    requests:
      storage: 32Mi
---
apiVersion: v1
kind: Pod
metadata:
  name: verify-writer
  namespace: ${TEST_NS}
spec:
  containers:
  - name: writer
    image: busybox:1.36.1
    command: ["sh", "-c", "echo '${TOKEN}' > /mnt/storage/token.txt && sleep 300"]
    volumeMounts:
    - name: data
      mountPath: /mnt/storage
  volumes:
  - name: data
    persistentVolumeClaim:
      claimName: verify-pvc
EOF

kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready "pod/verify-writer" -n "${TEST_NS}" --timeout=60s >/dev/null
READ_BACK="$(kubectl --context "${CONTEXT_NAME}" exec -n "${TEST_NS}" verify-writer -- cat /mnt/storage/token.txt 2>/dev/null || echo "")"
if [ "${READ_BACK}" != "${TOKEN}" ]; then
  echo "ERROR: Storage read-back failed! Expected '${TOKEN}', got '${READ_BACK}'" >&2
  exit 1
fi

# Verify data file exists on host in STORAGE_ROOT
if ! find "${STORAGE_ROOT}" -name "token.txt" -exec grep -q "${TOKEN}" {} + 2>/dev/null; then
  echo "ERROR: Token file not found in host storage root (${STORAGE_ROOT})!" >&2
  exit 1
fi
echo "✓ Synthetic PVC write/read verified and confirmed in host storage root (${STORAGE_ROOT})."

cleanup_storage
trap - EXIT

# 4. NetworkPolicy Capability Verification
echo ""
echo "--- 4. VERIFYING CNI & NETWORKPOLICY ENFORCEMENT ---"
NETPOL_NS="swiftdrop-verify-netpol-$$-${RANDOM}"
kubectl --context "${CONTEXT_NAME}" create namespace "${NETPOL_NS}" >/dev/null

cleanup_netpol() {
  kubectl --context "${CONTEXT_NAME}" delete namespace "${NETPOL_NS}" --wait=false --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup_netpol EXIT

cat << 'EOF' | kubectl --context "${CONTEXT_NAME}" apply -n "${NETPOL_NS}" -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: target-server
  labels:
    app: target-server
spec:
  containers:
  - name: server
    image: busybox:1.36.1
    command: ["sh", "-c", "while true; do echo -e 'HTTP/1.1 200 OK\n\nOK' | nc -l -p 8080; done"]
---
apiVersion: v1
kind: Service
metadata:
  name: target-svc
spec:
  selector:
    app: target-server
  ports:
  - port: 8080
    targetPort: 8080
---
apiVersion: v1
kind: Pod
metadata:
  name: client-auth
  labels:
    role: authorized
spec:
  containers:
  - name: client
    image: busybox:1.36.1
    command: ["sleep", "300"]
---
apiVersion: v1
kind: Pod
metadata:
  name: client-unauth
  labels:
    role: unauthorized
spec:
  containers:
  - name: client
    image: busybox:1.36.1
    command: ["sleep", "300"]
EOF

kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod/target-server -n "${NETPOL_NS}" --timeout=60s >/dev/null
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod/client-auth -n "${NETPOL_NS}" --timeout=60s >/dev/null
kubectl --context "${CONTEXT_NAME}" wait --for=condition=Ready pod/client-unauth -n "${NETPOL_NS}" --timeout=60s >/dev/null

# Step A: Baseline connectivity (both should succeed)
BASELINE_AUTH="$(kubectl --context "${CONTEXT_NAME}" exec -n "${NETPOL_NS}" client-auth -- wget -qO- -T 3 http://target-svc:8080 2>/dev/null || echo "FAIL")"
BASELINE_UNAUTH="$(kubectl --context "${CONTEXT_NAME}" exec -n "${NETPOL_NS}" client-unauth -- wget -qO- -T 3 http://target-svc:8080 2>/dev/null || echo "FAIL")"

if [ "${BASELINE_AUTH}" != "OK" ] || [ "${BASELINE_UNAUTH}" != "OK" ]; then
  echo "ERROR: Baseline pod communication failed before applying policy!" >&2
  exit 1
fi
echo "✓ Baseline intra-cluster pod networking verified."

# Step B: Apply scoped NetworkPolicy allowing only 'role: authorized'
cat << 'EOF' | kubectl --context "${CONTEXT_NAME}" apply -n "${NETPOL_NS}" -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-auth-only
spec:
  podSelector:
    matchLabels:
      app: target-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          role: authorized
    ports:
    - protocol: TCP
      port: 8080
EOF

sleep 2

# Step C: Verify authorized succeeds and unauthorized is blocked
AUTH_RESULT="$(kubectl --context "${CONTEXT_NAME}" exec -n "${NETPOL_NS}" client-auth -- wget -qO- -T 3 http://target-svc:8080 2>/dev/null || echo "BLOCKED")"
UNAUTH_RESULT="$(kubectl --context "${CONTEXT_NAME}" exec -n "${NETPOL_NS}" client-unauth -- wget -qO- -T 3 http://target-svc:8080 2>/dev/null || echo "BLOCKED")"

if [ "${AUTH_RESULT}" != "OK" ]; then
  echo "ERROR: Authorized pod was unexpectedly blocked by NetworkPolicy!" >&2
  exit 1
fi

if [ "${UNAUTH_RESULT}" != "BLOCKED" ]; then
  echo "ERROR: Unauthorized pod was NOT blocked by NetworkPolicy! Kube-router enforcement failed." >&2
  exit 1
fi
echo "✓ NetworkPolicy ingress enforcement verified (kube-router embedded controller is ACTIVE)."

cleanup_netpol
trap - EXIT

echo ""
echo "================================================================================"
echo "✓ All SwiftDrop cluster readiness, storage, and security gates: PASSED"
echo "================================================================================"
