# ==============================================================================
# SwiftDrop Azure K3s Terraform — Virtual Network & Subnet Topology
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# ==============================================================================

resource "azurerm_virtual_network" "k3s" {
  name                = local.vnet_name
  resource_group_name = azurerm_resource_group.k3s.name
  location            = azurerm_resource_group.k3s.location
  address_space       = var.vnet_address_space
  tags                = local.tags
}

resource "azurerm_subnet" "k3s" {
  name                 = local.subnet_name
  resource_group_name  = azurerm_resource_group.k3s.name
  virtual_network_name = azurerm_virtual_network.k3s.name
  address_prefixes     = var.subnet_address_prefix
}

resource "azurerm_public_ip" "k3s" {
  count               = var.enable_public_ip ? 1 : 0
  name                = local.pip_name
  resource_group_name = azurerm_resource_group.k3s.name
  location            = azurerm_resource_group.k3s.location
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = local.tags
}

resource "azurerm_network_interface" "k3s" {
  name                = local.nic_name
  resource_group_name = azurerm_resource_group.k3s.name
  location            = azurerm_resource_group.k3s.location
  tags                = local.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.k3s.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = var.enable_public_ip ? azurerm_public_ip.k3s[0].id : null
  }
}

resource "azurerm_network_interface_security_group_association" "k3s" {
  network_interface_id      = azurerm_network_interface.k3s.id
  network_security_group_id = azurerm_network_security_group.k3s.id
}
