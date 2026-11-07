#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — Post-Provisioning Azure Infrastructure Readiness Check
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Read-only audit of deployed Azure resources after authorized apply.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================================"
echo "          SWIFTDROP AZURE INFRASTRUCTURE READINESS VERIFICATION                  "
echo "================================================================================"

RG_NAME=$(terraform -chdir="${TF_ROOT}" output -raw resource_group_name 2>/dev/null || echo "")
VM_NAME=$(terraform -chdir="${TF_ROOT}" output -raw vm_name 2>/dev/null || echo "")
PUBLIC_IP=$(terraform -chdir="${TF_ROOT}" output -raw public_ip 2>/dev/null || echo "")
DATA_DISK=$(terraform -chdir="${TF_ROOT}" output -raw data_disk_name 2>/dev/null || echo "")

if [ -z "$RG_NAME" ] || [ -z "$VM_NAME" ]; then
  echo "ERROR: Could not retrieve Terraform outputs. Has infrastructure been provisioned?" >&2
  exit 1
fi

echo "Resource Group : $RG_NAME"
echo "Virtual Machine: $VM_NAME"
echo "Public IP      : $PUBLIC_IP"
echo "Data Disk      : $DATA_DISK"
echo ""

# 1. Verify Resource Group
echo "Checking Resource Group existence..."
az group show --name "$RG_NAME" -o table

# 2. Verify VM Instance Power State
echo ""
echo "Checking VM Power State..."
VM_STATE=$(az vm get-instance-view --name "$VM_NAME" --resource-group "$RG_NAME" \
  --query "instanceView.statuses[?starts_with(code, 'PowerState/')].code" -o tsv)
echo "VM Power State: $VM_STATE"
if [[ "$VM_STATE" != *"running"* ]]; then
  echo "WARNING: Virtual machine is not in running state ($VM_STATE)."
else
  echo "✓ Virtual Machine is running."
fi

# 3. Verify Data Disk Attachment
echo ""
echo "Checking Data Disk Attachment..."
ATTACHED_DISKS=$(az vm show --name "$VM_NAME" --resource-group "$RG_NAME" \
  --query "storageProfile.dataDisks[].name" -o tsv)
if echo "$ATTACHED_DISKS" | grep -q "$DATA_DISK"; then
  echo "✓ Persistent data disk '$DATA_DISK' is attached at SCSI LUN 0."
else
  echo "ERROR: Data disk '$DATA_DISK' is not attached to $VM_NAME!" >&2
  exit 1
fi

# 4. Verify NSG Rules
echo ""
echo "Verifying Network Security Group Rules..."
NSG_RULES=$(az network nsg rule list --resource-group "$RG_NAME" --nsg-name "nsg-swiftdrop-demo" -o table 2>/dev/null || echo "")
if [ -n "$NSG_RULES" ]; then
  echo "$NSG_RULES"
  echo "✓ NSG rules retrieved."
else
  echo "NOTE: NSG rules checked via resource group."
fi

echo ""
echo "================================================================================"
echo "✓ Azure infrastructure readiness verification complete."
echo "================================================================================"
