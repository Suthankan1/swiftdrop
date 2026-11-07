#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — Operator Preflight Check Script
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Read-only verification. Confirms subscription, quota, SSH key, and authorization.
# Does NOT execute mutating cloud actions or provision resources.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VARS_FILE="${TF_ROOT}/terraform.tfvars"

echo "================================================================================"
echo "          SWIFTDROP AZURE K3S OPERATOR PREFLIGHT VERIFICATION                    "
echo "================================================================================"

# 1. Verify existence of terraform.tfvars
if [ ! -f "$VARS_FILE" ]; then
  echo "ERROR: terraform.tfvars not found at $VARS_FILE" >&2
  echo "Copy terraform.tfvars.example to terraform.tfvars and fill approved values." >&2
  exit 1
fi
echo "✓ Found operator terraform.tfvars."

# 2. Verify Spending Authorization Contract & Variables
echo ""
echo "--- CHECKING SPENDING AUTHORIZATION GATE & OPERATOR VARIABLES ---"
if ! python3 "${SCRIPT_DIR}/check-cost-authorization.py" --verify-vars "$VARS_FILE"; then
  echo "ERROR: Spending authorization gate or terraform.tfvars validation has NOT been satisfied!" >&2
  echo "Complete the 20-point spending checklist in docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md." >&2
  exit 1
fi

# 3. Verify Azure CLI Installation & Active Session
echo ""
echo "--- CHECKING AZURE CLI SESSION ---"
if ! command -v az >/dev/null 2>&1; then
  echo "ERROR: Azure CLI ('az') is not installed. Install Azure CLI before deployment." >&2
  exit 1
fi

CURRENT_ACCOUNT=$(az account show -o json 2>/dev/null || echo "")
if [ -z "$CURRENT_ACCOUNT" ]; then
  echo "ERROR: Not signed in to Azure CLI. Run 'az login' and verify selected subscription." >&2
  exit 1
fi

ACTIVE_SUB_ID=$(echo "$CURRENT_ACCOUNT" | jq -r '.id // empty')
ACTIVE_SUB_NAME=$(echo "$CURRENT_ACCOUNT" | jq -r '.name // empty')
echo "✓ Azure CLI active session:"
echo "    Subscription ID   : $ACTIVE_SUB_ID"
echo "    Subscription Name : $ACTIVE_SUB_NAME"

# 4. Verify Subscription ID match
EXPECTED_SUB_ID=$(grep -E '^\s*subscription_id\s*=' "$VARS_FILE" | awk -F'=' '{print $2}' | tr -d ' "' || echo "")
if [ -n "$EXPECTED_SUB_ID" ] && [ "$EXPECTED_SUB_ID" != "$ACTIVE_SUB_ID" ]; then
  echo "ERROR: Active Azure CLI subscription ($ACTIVE_SUB_ID) does not match terraform.tfvars ($EXPECTED_SUB_ID)!" >&2
  echo "Run 'az account set --subscription <id>' to switch context." >&2
  exit 1
fi
echo "✓ Target subscription matches active CLI session."

# 5. Verify Operator SSH Key
echo ""
echo "--- CHECKING OPERATOR SSH KEY ---"
SSH_KEY_RAW=$(grep -E '^\s*operator_ssh_public_key\s*=' "$VARS_FILE" | awk -F'=' '{print $2}' | tr -d ' "' || echo "")
if [ -z "$SSH_KEY_RAW" ] || [[ "$SSH_KEY_RAW" == *"Placeholder"* ]]; then
  echo "ERROR: Valid operator_ssh_public_key must be configured in terraform.tfvars!" >&2
  exit 1
fi
echo "✓ Operator SSH public key configured."

# 6. Verify Regional VM Quota (Read-only check)
TARGET_LOCATION=$(grep -E '^\s*location\s*=' "$VARS_FILE" | awk -F'=' '{print $2}' | tr -d ' "' || echo "westeurope")
echo ""
echo "--- CHECKING REGIONAL VM QUOTA ($TARGET_LOCATION) ---"
if az vm list-usage --location "$TARGET_LOCATION" -o table 2>/dev/null | grep -i "Standard DSv5\|Standard BS" | head -n 5; then
  echo "✓ Regional compute usage inspected."
else
  echo "NOTE: Could not inspect regional quota automatically; verify quota manually in Azure Portal."
fi

echo ""
echo "NOTE: Terraform IaC checks input parameters at plan/apply time against signed policy."
echo "      Terraform does NOT continuously poll billing or halt running VMs when spending accrues."
echo "      Runtime spend containment requires Azure Cost Management budget alerts and prompt deallocation."

echo ""
echo "================================================================================"
echo "✓ Operator preflight checks completed successfully."
echo "  Next Step: Run 'terraform plan -out=tfplan' and perform multi-signature review."
echo "================================================================================"
