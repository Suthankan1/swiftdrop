#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Azure K3s — Static Quality & Policy Validation Script
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: 100% offline, credential-free, backend-disabled static verification.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

echo "================================================================================"
echo "          SWIFTDROP AZURE K3S TERRAFORM STATIC VALIDATION SUITE                 "
echo "================================================================================"

# 1. Terraform Formatting Check
echo "--- 1. TERRAFORM FORMAT CHECK ---"
terraform -chdir="${TF_ROOT}" fmt -check -recursive
echo "✓ Terraform files formatted properly."

# 2. Terraform Initialization (Offline, Readonly Lockfile)
echo ""
echo "--- 2. TERRAFORM BACKEND-DISABLED INITIALIZATION ---"
terraform -chdir="${TF_ROOT}" init -backend=false -input=false -lockfile=readonly
echo "✓ Terraform successfully initialized with locked providers."

# 3. Terraform Validation
echo ""
echo "--- 3. TERRAFORM STATIC SYNTAX & SCHEMA VALIDATION ---"
terraform -chdir="${TF_ROOT}" validate -no-color
echo "✓ Terraform configuration is valid."

# 4. Spending Authorization Gate Verification (Assert Gate is Locked)
echo ""
echo "--- 4. SPENDING AUTHORIZATION GATE VERIFICATION ---"
python3 "${SCRIPT_DIR}/check-cost-authorization.py" --verify-gate-locked
echo "✓ Spending authorization gate is locked in deny-by-default posture."

# 5. Bash Script Syntax Validation
echo ""
echo "--- 5. SHELL SCRIPT SYNTAX CHECK (bash -n) ---"
for script in "${SCRIPT_DIR}"/*.sh; do
  echo "Checking script syntax: $(basename "$script")"
  bash -n "$script"
done
echo "✓ All shell scripts passed syntax check."

# 6. Deployment Contracts & Helm Values Rendering
echo ""
echo "--- 6. HELM VALUES & DEPLOYMENT CONTRACTS CHECK ---"
"${SCRIPT_DIR}/verify-deployment-contracts.sh"

echo ""
echo "================================================================================"
echo "✓ SwiftDrop Azure K3s Terraform preparation successfully validated."
echo "================================================================================"
