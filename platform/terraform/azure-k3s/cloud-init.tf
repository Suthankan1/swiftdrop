# ==============================================================================
# SwiftDrop Azure K3s Terraform — Cloud-Init Rendering
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Template-based rendering; zero secrets in user-data or state.
# ==============================================================================

locals {
  storage_mount_script = templatefile("${path.module}/templates/storage-mount.sh.tftpl", {
    mount_point      = local.data_disk_mount_point
    k3s_storage_path = local.k3s_storage_path
  })

  k3s_config = templatefile("${path.module}/templates/k3s-config.yaml.tftpl", {
    admin_username   = var.admin_username
    k3s_storage_path = local.k3s_storage_path
    public_domain    = var.public_domain_name
  })

  cloud_init_config = templatefile("${path.module}/templates/cloud-init.yaml.tftpl", {
    storage_mount_script = local.storage_mount_script
    k3s_config           = local.k3s_config
    allowed_ssh_cidrs    = var.allowed_ssh_source_cidrs
    enable_http          = var.enable_http_port_80
    k3s_version          = var.k3s_version
  })
}
