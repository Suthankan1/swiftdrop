# ==============================================================================
# SwiftDrop Azure K3s Terraform — Spending & Security Invariants Gate
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Fail-closed evaluation. Prevents accidental cloud provisioning.
# Note: Terraform 1.5+ check blocks produce informative warnings during plan/apply.
# Strictly blocking enforcement halting terraform plan and terraform apply is
# implemented via lifecycle preconditions on azurerm_resource_group.k3s in resource-group.tf.
# ==============================================================================

check "spending_authorization_gate" {
  assert {
    condition     = local.policy_spending_authorized == true && var.spending_authorization_confirmed == true && var.approved_budget_usd > 0.0 && var.approval_reference != "" && var.billing_owner != "" && var.infrastructure_operator != ""
    error_message = "FAIL-CLOSED: Azure cloud deployment is NOT authorized in azure-spending-gate-policy.yaml. Multi-signature governance sign-off is required before setting spending_authorization_confirmed=true per docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md."
  }
}

check "network_security_invariants" {
  assert {
    condition     = length(var.allowed_ssh_source_cidrs) > 0 && length([for c in var.allowed_ssh_source_cidrs : c if c == "0.0.0.0/0" || c == "::/0" || c == "*" || lower(c) == "internet"]) == 0
    error_message = "FAIL-CLOSED: SSH access must be restricted to explicitly approved operator CIDRs and must never allow 0.0.0.0/0 or ::/0."
  }
}
