# ==============================================================================
# SwiftDrop Azure K3s Terraform — Spending & Security Invariants Gate
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Fail-closed evaluation. Prevents accidental cloud provisioning.
# ==============================================================================

check "spending_authorization_gate" {
  assert {
    condition     = var.spending_authorization_confirmed == true && var.approved_budget_usd > 0.0 && var.approval_reference != "" && var.billing_owner != "" && var.infrastructure_operator != ""
    error_message = "FAIL-CLOSED: Azure cloud deployment is NOT authorized. Set spending_authorization_confirmed=true, approved_budget_usd>0, approval_reference, billing_owner, and infrastructure_operator per docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md."
  }
}

check "network_security_invariants" {
  assert {
    condition     = length(var.allowed_ssh_source_cidrs) > 0 && length([for c in var.allowed_ssh_source_cidrs : c if c == "0.0.0.0/0" || c == "::/0" || c == "*" || lower(c) == "internet"]) == 0
    error_message = "FAIL-CLOSED: SSH access must be restricted to explicitly approved operator CIDRs and must never allow 0.0.0.0/0 or ::/0."
  }
}
