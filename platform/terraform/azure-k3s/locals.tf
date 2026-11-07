# ==============================================================================
# SwiftDrop Azure K3s Terraform — Local Values & Naming Conventions
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# ==============================================================================

locals {
  project_name = "swiftdrop"

  # Resource Naming Conventions
  resource_group_name = var.resource_group_name
  vnet_name           = "vnet-${local.project_name}-${var.environment}"
  subnet_name         = "snet-${local.project_name}-k3s"
  nsg_name            = "nsg-${local.project_name}-${var.environment}"
  nic_name            = "nic-${local.project_name}-${var.environment}"
  pip_name            = "pip-${local.project_name}-${var.environment}"
  vm_name             = "vm-${local.project_name}-${var.environment}"
  data_disk_name      = "disk-${local.project_name}-data-${var.environment}"

  # Storage Paths for Host & K3s Local-Path Provisioner
  data_disk_mount_point = "/mnt/swiftdrop-data"
  k3s_storage_path      = "${local.data_disk_mount_point}/k3s-storage"

  # Standardized Governance Tags
  default_tags = {
    project                 = local.project_name
    environment             = var.environment
    managed_by              = "terraform"
    system                  = "parcel-delivery"
    cost_gate               = "azure-k3s-spending-authorization"
    approval_reference      = var.approval_reference != "" ? var.approval_reference : "UNAUTHORIZED"
    billing_owner           = var.billing_owner != "" ? var.billing_owner : "UNASSIGNED"
    infrastructure_operator = var.infrastructure_operator != "" ? var.infrastructure_operator : "UNASSIGNED"
  }

  tags = merge(local.default_tags, var.common_tags)

  # Machine-Readable Spending Gate Policy Parsing (Fail-Closed Enforcement)
  spending_gate_policy       = yamldecode(file("${path.module}/azure-spending-gate-policy.yaml"))
  policy_spending_authorized = try(local.spending_gate_policy.spending_authorized, false)
  policy_status              = try(local.spending_gate_policy.status, "")
  policy_approved_budget     = try(local.spending_gate_policy.financial_controls.approved_budget_usd, 0.0)
  policy_cost_ceiling        = try(local.spending_gate_policy.financial_controls.cost_ceiling_usd, 0.0)
  policy_subscription_id     = try(local.spending_gate_policy.approved_parameters.subscription_id, "")
  policy_approved_regions    = try(local.spending_gate_policy.approved_parameters.approved_regions, [])
  policy_approved_vm_skus    = try(local.spending_gate_policy.approved_parameters.approved_vm_skus, [])
  policy_approved_disk_skus  = try(local.spending_gate_policy.approved_parameters.approved_disk_skus, [])
  policy_allowed_resources   = try(local.spending_gate_policy.approved_parameters.allowed_resources, [])
  policy_reviewers           = try(local.spending_gate_policy.governance.required_reviewers, [])
  policy_unsigned_reviewers  = [for r in local.policy_reviewers : r.github_handle if try(r.signed, false) != true]
  policy_approval_reference  = try(local.spending_gate_policy.governance.approval_reference, "")
  policy_billing_owner       = try(local.spending_gate_policy.governance.billing_owner, "")
  policy_infra_operator      = try(local.spending_gate_policy.governance.infrastructure_operator, "")
}
