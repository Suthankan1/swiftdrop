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
      condition     = var.spending_authorization_confirmed == true
      error_message = "AZURE DEPLOYMENT BLOCKED: Spending authorization has not been confirmed per Issue #178."
    }
    precondition {
      condition     = var.approved_budget_usd > 0.0
      error_message = "AZURE DEPLOYMENT BLOCKED: Approved budget must be greater than $0.00."
    }
    precondition {
      condition     = var.approval_reference != ""
      error_message = "AZURE DEPLOYMENT BLOCKED: Formal approval reference token is required."
    }
    precondition {
      condition     = var.billing_owner != ""
      error_message = "AZURE DEPLOYMENT BLOCKED: Named billing owner is required."
    }
    precondition {
      condition     = var.infrastructure_operator != ""
      error_message = "AZURE DEPLOYMENT BLOCKED: Designated infrastructure operator is required."
    }
    precondition {
      condition     = var.subscription_id != ""
      error_message = "AZURE DEPLOYMENT BLOCKED: Target Azure subscription ID must be supplied."
    }
  }
}
