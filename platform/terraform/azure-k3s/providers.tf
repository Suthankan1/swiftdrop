provider "azurerm" {
  features {
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
    virtual_machine {
      delete_os_disk_on_deletion = false
    }
  }

  resource_provider_registrations = "none"
  subscription_id                 = var.subscription_id != "" ? var.subscription_id : null
}
