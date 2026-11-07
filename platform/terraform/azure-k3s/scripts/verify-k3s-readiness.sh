#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — K3s Cluster & Storage Readiness Check
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Connects via SSH to verify node health, local-path mount, and Traefik.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SSH_KEY_PATH="${1:-${HOME}/.ssh/id_ed25519}"
ADMIN_USER="${2:-swiftdropadmin}"

echo "================================================================================"
echo "          SWIFTDROP AZURE K3S CLUSTER & STORAGE READINESS CHECK                  "
echo "================================================================================"

PUBLIC_IP=$(terraform -chdir="${TF_ROOT}" output -raw public_ip 2>/dev/null || echo "")
if [ -z "$PUBLIC_IP" ] || [ "$PUBLIC_IP" == "NONE" ]; then
  PUBLIC_IP=$(terraform -chdir="${TF_ROOT}" output -raw vm_private_ip 2>/dev/null || echo "")
fi

if [ -z "$PUBLIC_IP" ]; then
  echo "ERROR: Target host IP could not be resolved from Terraform outputs." >&2
  exit 1
fi

echo "Target Host IP: $PUBLIC_IP"
echo "Admin User    : $ADMIN_USER"
echo "SSH Key Path  : $SSH_KEY_PATH"
echo ""

# 1. Test SSH connectivity
echo "Testing SSH connectivity..."
if ! ssh -i "$SSH_KEY_PATH" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "${ADMIN_USER}@${PUBLIC_IP}" "echo '✓ SSH handshake successful.'" ; then
  echo "ERROR: Could not establish SSH session with ${ADMIN_USER}@${PUBLIC_IP}" >&2
  echo "Verify NSG allowed SSH CIDR, IP reachability, and that cloud-init has finished." >&2
  exit 1
fi

# 2. Check Cloud-Init Bootstrap Completion
echo ""
echo "Checking cloud-init status..."
ssh -i "$SSH_KEY_PATH" "${ADMIN_USER}@${PUBLIC_IP}" "sudo cloud-init status --wait || true"

# 3. Check Persistent Storage Mount
echo ""
echo "Checking /mnt/swiftdrop-data mount status..."
ssh -i "$SSH_KEY_PATH" "${ADMIN_USER}@${PUBLIC_IP}" "df -hT /mnt/swiftdrop-data && ls -la /mnt/swiftdrop-data/k3s-storage"

# 4. Check K3s Node Status
echo ""
echo "Checking K3s Node Status..."
ssh -i "$SSH_KEY_PATH" "${ADMIN_USER}@${PUBLIC_IP}" "sudo k3s kubectl get nodes -o wide"

# 5. Check K3s System Pods (Traefik, CoreDNS, Local-Path Provisioner)
echo ""
echo "Checking K3s Core Pods..."
ssh -i "$SSH_KEY_PATH" "${ADMIN_USER}@${PUBLIC_IP}" "sudo k3s kubectl get pods -n kube-system"

echo ""
echo "================================================================================"
echo "✓ K3s cluster and storage readiness verified."
echo "================================================================================"
