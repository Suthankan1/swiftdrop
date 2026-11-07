#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — Secure Operator Kubernetes Administration Access Script
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Port 6443 is strictly private (blocked by NSG); access is mediated
# via secure, authenticated SSH local port-forwarding with TLS certificate validation.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SSH_KEY_PATH="${SSH_KEY_PATH:-${HOME}/.ssh/swiftdrop-azure-k3s}"
ADMIN_USER="${ADMIN_USER:-swiftdropadmin}"
LOCAL_KUBECONFIG="${LOCAL_KUBECONFIG:-${HOME}/.kube/config-swiftdrop-azure}"
SOCKET_PATH="/tmp/swiftdrop-azure-k8s-tunnel.sock"
DEFAULT_LOCAL_PORT=6443
ALT_LOCAL_PORT=16443

usage() {
  cat << EOF
Usage: $(basename "$0") <COMMAND> [OPTIONS]

Commands:
  connect     Fetch kubeconfig, establish background SSH tunnel, and verify connectivity
  start       Start background SSH tunnel only
  stop        Terminate active SSH tunnel
  status      Inspect SSH tunnel status and test kubectl cluster access
  fetch-only  Download cluster kubeconfig without opening tunnel

Options:
  --key <PATH>         SSH private key path (default: ~/.ssh/swiftdrop-azure-k3s)
  --user <USER>        Admin SSH username (default: swiftdropadmin)
  --kubeconfig <PATH>  Local kubeconfig path (default: ~/.kube/config-swiftdrop-azure)
  --port <PORT>        Local forwarded port (default: 6443, fallback: 16443)
  -h, --help           Display this help message
EOF
  exit 1
}

COMMAND="${1:-connect}"
[ $# -gt 0 ] && shift

LOCAL_PORT="$DEFAULT_LOCAL_PORT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --key) SSH_KEY_PATH="$2"; shift 2 ;;
    --user) ADMIN_USER="$2"; shift 2 ;;
    --kubeconfig) LOCAL_KUBECONFIG="$2"; shift 2 ;;
    --port) LOCAL_PORT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

resolve_public_ip() {
  local ip
  ip=$(terraform -chdir="${TF_ROOT}" output -raw public_ip 2>/dev/null || echo "")
  if [ -z "$ip" ] || [ "$ip" = "NONE" ]; then
    ip=$(terraform -chdir="${TF_ROOT}" output -raw vm_private_ip 2>/dev/null || echo "")
  fi
  if [ -z "$ip" ]; then
    echo "ERROR: Could not resolve target host IP from Terraform outputs. Has infrastructure been provisioned?" >&2
    exit 1
  fi
  echo "$ip"
}

check_local_port_available() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    if lsof -i "tcp:${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      return 1
    fi
  elif command -v nc >/dev/null 2>&1; then
    if nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then
      return 1
    fi
  fi
  return 0
}

fetch_kubeconfig() {
  local public_ip="$1"
  local port="$2"

  echo "Fetching cluster kubeconfig from ${ADMIN_USER}@${public_ip}..."
  mkdir -p "$(dirname "$LOCAL_KUBECONFIG")"
  
  if ! ssh -i "$SSH_KEY_PATH" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new \
    "${ADMIN_USER}@${public_ip}" "sudo cat /etc/rancher/k3s/k3s.yaml" > "$LOCAL_KUBECONFIG.tmp"; then
    echo "ERROR: Failed to retrieve /etc/rancher/k3s/k3s.yaml via SSH!" >&2
    rm -f "$LOCAL_KUBECONFIG.tmp"
    exit 1
  fi

  # Enforce strict secret permissions: 0600 (owner read/write only)
  chmod 600 "$LOCAL_KUBECONFIG.tmp"

  # Maintain 127.0.0.1 endpoint for TLS SAN validation, updating port if alternative used
  if [ "$port" != "$DEFAULT_LOCAL_PORT" ]; then
    sed "s|https://127.0.0.1:${DEFAULT_LOCAL_PORT}|https://127.0.0.1:${port}|g" "$LOCAL_KUBECONFIG.tmp" > "$LOCAL_KUBECONFIG"
    rm -f "$LOCAL_KUBECONFIG.tmp"
  else
    mv "$LOCAL_KUBECONFIG.tmp" "$LOCAL_KUBECONFIG"
  fi
  chmod 600 "$LOCAL_KUBECONFIG"

  echo "✓ Saved hardened cluster kubeconfig to: $LOCAL_KUBECONFIG (permissions: 0600)"
  echo "  API Endpoint: https://127.0.0.1:${port} (TLS SAN matches 127.0.0.1)"
}

start_tunnel() {
  local public_ip="$1"
  local port="$2"

  if [ -S "$SOCKET_PATH" ]; then
    if ssh -S "$SOCKET_PATH" -O check "${ADMIN_USER}@${public_ip}" 2>/dev/null; then
      echo "✓ SSH tunnel is already actively running on socket $SOCKET_PATH."
      return 0
    else
      echo "Removing stale socket file $SOCKET_PATH..."
      rm -f "$SOCKET_PATH"
    fi
  fi

  if ! check_local_port_available "$port"; then
    echo "WARNING: Local port ${port} is already in use by another process (e.g. local k3d/minikube)!"
    if [ "$port" = "$DEFAULT_LOCAL_PORT" ]; then
      echo "Switching to alternative local port ${ALT_LOCAL_PORT}..."
      port="$ALT_LOCAL_PORT"
      LOCAL_PORT="$ALT_LOCAL_PORT"
      # Re-adjust kubeconfig endpoint for alternative port
      sed -i.bak "s|https://127.0.0.1:[0-9]*|https://127.0.0.1:${port}|g" "$LOCAL_KUBECONFIG" 2>/dev/null || true
      rm -f "${LOCAL_KUBECONFIG}.bak"
    fi
  fi

  echo "Starting background SSH tunnel (127.0.0.1:${port} -> remote 127.0.0.1:6443)..."
  ssh -f -N -M -S "$SOCKET_PATH" \
    -L "${port}:127.0.0.1:6443" \
    -i "$SSH_KEY_PATH" \
    -o ExitOnForwardFailure=yes \
    -o StrictHostKeyChecking=accept-new \
    "${ADMIN_USER}@${public_ip}"

  echo "✓ SSH tunnel established in background (Control Socket: $SOCKET_PATH)."
}

stop_tunnel() {
  local public_ip="$1"

  if [ -S "$SOCKET_PATH" ]; then
    echo "Stopping SSH tunnel on socket $SOCKET_PATH..."
    ssh -S "$SOCKET_PATH" -O exit "${ADMIN_USER}@${public_ip}" 2>/dev/null || true
    rm -f "$SOCKET_PATH"
    echo "✓ SSH tunnel terminated."
  else
    echo "No active tunnel socket found at $SOCKET_PATH."
  fi
}

verify_access() {
  echo ""
  echo "Verifying cluster access through tunnel..."
  if command -v kubectl >/dev/null 2>&1; then
    KUBECONFIG="$LOCAL_KUBECONFIG" kubectl get nodes -o wide
    echo "✓ Kubernetes API access successfully verified via secure tunnel."
  else
    echo "kubectl not found locally. Install kubectl to run cluster commands."
  fi
}

PUBLIC_IP=$(resolve_public_ip)

case "$COMMAND" in
  connect)
    fetch_kubeconfig "$PUBLIC_IP" "$LOCAL_PORT"
    start_tunnel "$PUBLIC_IP" "$LOCAL_PORT"
    verify_access
    echo ""
    echo "================================================================================"
    echo "Ready for cluster management:"
    echo "  export KUBECONFIG=${LOCAL_KUBECONFIG}"
    echo "  kubectl get pods -A"
    echo ""
    echo "To terminate tunnel when finished:"
    echo "  ${SCRIPT_DIR}/setup-kube-access.sh stop"
    echo "================================================================================"
    ;;
  start)
    start_tunnel "$PUBLIC_IP" "$LOCAL_PORT"
    ;;
  stop)
    stop_tunnel "$PUBLIC_IP"
    ;;
  status)
    if [ -S "$SOCKET_PATH" ]; then
      ssh -S "$SOCKET_PATH" -O check "${ADMIN_USER}@${PUBLIC_IP}"
    else
      echo "SSH tunnel is NOT running."
    fi
    verify_access
    ;;
  fetch-only)
    fetch_kubeconfig "$PUBLIC_IP" "$LOCAL_PORT"
    ;;
  *)
    echo "Unknown command: $COMMAND" >&2
    usage
    ;;
esac

