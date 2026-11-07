# ==============================================================================
# SwiftDrop Azure K3s Terraform — Resource Group
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# ==============================================================================

resource "azurerm_resource_group" "k3s" {
  name     = local.resource_group_name
  location = var.location
  tags     = local.tags

  lifecycle {
    precondition {
      condition     = local.policy_spending_authorized == true
      error_message = "AZURE DEPLOYMENT BLOCKED: Machine-readable spending gate policy (azure-spending-gate-policy.yaml) has spending_authorized=false. Multi-signature governance approval is required."
    }
    precondition {
      condition     = local.policy_status == "AUTHORIZED_BY_HUMAN_GOVERNANCE"
      error_message = "AZURE DEPLOYMENT BLOCKED: Policy status is '${local.policy_status}', expected 'AUTHORIZED_BY_HUMAN_GOVERNANCE'."
    }
    precondition {
      condition     = var.spending_authorization_confirmed == true
      error_message = "AZURE DEPLOYMENT BLOCKED: Spending authorization confirmed variable must be set to true per Issue #178."
    }
    precondition {
      condition     = var.approved_budget_usd > 0.0 && var.approved_budget_usd <= local.policy_approved_budget
      error_message = "AZURE DEPLOYMENT BLOCKED: Approved budget ($${var.approved_budget_usd}) must be > $0.00 and cannot exceed signed policy approved budget ($${local.policy_approved_budget})."
    }
    precondition {
      condition     = var.approval_reference != "" && var.approval_reference == local.policy_approval_reference
      error_message = "AZURE DEPLOYMENT BLOCKED: Variable approval_reference ('${var.approval_reference}') must match signed policy approval_reference ('${local.policy_approval_reference}')."
    }
    precondition {
      condition     = var.billing_owner != "" && var.billing_owner == local.policy_billing_owner
      error_message = "AZURE DEPLOYMENT BLOCKED: Variable billing_owner ('${var.billing_owner}') must match signed policy billing_owner ('${local.policy_billing_owner}')."
    }
    precondition {
      condition     = var.infrastructure_operator != "" && var.infrastructure_operator == local.policy_infra_operator
      error_message = "AZURE DEPLOYMENT BLOCKED: Variable infrastructure_operator ('${var.infrastructure_operator}') must match signed policy infrastructure_operator ('${local.policy_infra_operator}')."
    }
    precondition {
      condition     = var.subscription_id != "" && var.subscription_id == local.policy_subscription_id && can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.subscription_id)) && var.subscription_id != "00000000-0000-0000-0000-000000000000"
      error_message = "AZURE DEPLOYMENT BLOCKED: Variable subscription_id must match non-placeholder subscription_id in signed policy."
    }
    precondition {
      condition     = contains(local.policy_approved_regions, var.location)
      error_message = "AZURE DEPLOYMENT BLOCKED: Target region '${var.location}' is not in policy approved_regions."
    }
    precondition {
      condition     = contains(local.policy_approved_vm_skus, var.vm_size)
      error_message = "AZURE DEPLOYMENT BLOCKED: Target VM SKU '${var.vm_size}' is not in policy approved_vm_skus."
    }
    precondition {
      condition     = contains(local.policy_approved_disk_skus, var.data_disk_type)
      error_message = "AZURE DEPLOYMENT BLOCKED: Target disk SKU '${var.data_disk_type}' is not in policy approved_disk_skus."
    }
    precondition {
      condition     = length(local.policy_reviewers) >= 3 && length(local.policy_unsigned_reviewers) == 0
      error_message = "AZURE DEPLOYMENT BLOCKED: Multi-signature approval incomplete in azure-spending-gate-policy.yaml. All required reviewers must sign."
    }
  }
}
