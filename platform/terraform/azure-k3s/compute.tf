# ==============================================================================
# SwiftDrop Azure K3s Terraform — Linux Virtual Machine Specification
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: SSH public key only; password auth disabled; cloud-init bootstrap.
# ==============================================================================

resource "azurerm_linux_virtual_machine" "k3s" {
  name                            = local.vm_name
  resource_group_name             = azurerm_resource_group.k3s.name
  location                        = azurerm_resource_group.k3s.location
  size                            = var.vm_size
  admin_username                  = var.admin_username
  network_interface_ids           = [azurerm_network_interface.k3s.id]
  disable_password_authentication = true
  custom_data                     = base64encode(local.cloud_init_config)
  tags                            = local.tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = var.operator_ssh_public_key != "" ? var.operator_ssh_public_key : "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholderKeyForTerraformValidationOnlyDummyKey00 swiftdrop-operator"
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.os_disk_type
    disk_size_gb         = var.os_disk_size_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  lifecycle {
    precondition {
      condition     = var.spending_authorization_confirmed == false || (var.spending_authorization_confirmed == true && var.operator_ssh_public_key != "")
      error_message = "AZURE DEPLOYMENT BLOCKED: Operator SSH public key must be provided when deployment is authorized."
    }
  }
}
