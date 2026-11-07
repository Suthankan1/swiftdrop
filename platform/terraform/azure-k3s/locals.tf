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
}
