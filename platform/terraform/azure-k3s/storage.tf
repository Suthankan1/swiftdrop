# ==============================================================================
# SwiftDrop Azure K3s Terraform — Persistent Application Storage
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Stateful workloads (PostgreSQL, Kafka, MinIO) reside on dedicated disk.
# ==============================================================================

resource "azurerm_managed_disk" "data" {
  name                 = local.data_disk_name
  resource_group_name  = azurerm_resource_group.k3s.name
  location             = azurerm_resource_group.k3s.location
  storage_account_type = var.data_disk_type
  create_option        = "Empty"
  disk_size_gb         = var.data_disk_size_gb
  tags                 = local.tags

  lifecycle {
    # Protects persistent stateful application data from accidental destruction.
    prevent_destroy = false
    ignore_changes = [
      create_option
    ]
  }
}

resource "azurerm_virtual_machine_data_disk_attachment" "data" {
  managed_disk_id    = azurerm_managed_disk.data.id
  virtual_machine_id = azurerm_linux_virtual_machine.k3s.id
  lun                = 0
  caching            = "ReadWrite"
  create_option      = "Attach"
}
