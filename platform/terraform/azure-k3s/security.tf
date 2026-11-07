# ==============================================================================
# SwiftDrop Azure K3s Terraform — Network Security Group & Firewall Rules
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Public HTTPS only; restricted SSH; internal services strictly private.
# ==============================================================================

resource "azurerm_network_security_group" "k3s" {
  name                = local.nsg_name
  resource_group_name = azurerm_resource_group.k3s.name
  location            = azurerm_resource_group.k3s.location
  tags                = local.tags

  # Rule 1: Restricted SSH operator access (Fail-closed: created only when CIDRs are supplied)
  dynamic "security_rule" {
    for_each = length(var.allowed_ssh_source_cidrs) > 0 ? [1] : []
    content {
      name                       = "allow-ssh-restricted"
      priority                   = 100
      direction                  = "Inbound"
      access                     = "Allow"
      protocol                   = "Tcp"
      source_port_range          = "*"
      destination_port_range     = "22"
      source_address_prefixes    = var.allowed_ssh_source_cidrs
      destination_address_prefix = "*"
    }
  }

  # Rule 2: Application HTTPS Ingress (Port 443)
  security_rule {
    name                       = "allow-https"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefixes    = var.allowed_https_source_cidrs
    destination_address_prefix = "*"
  }

  # Rule 3: Optional HTTP Ingress (Port 80) for ACME validation or HTTPS redirect
  dynamic "security_rule" {
    for_each = var.enable_http_port_80 ? [1] : []
    content {
      name                       = "allow-http-redirect-acme"
      priority                   = 120
      direction                  = "Inbound"
      access                     = "Allow"
      protocol                   = "Tcp"
      source_port_range          = "*"
      destination_port_range     = "80"
      source_address_prefixes    = var.allowed_http_source_cidrs
      destination_address_prefix = "*"
    }
  }

  # Rule 4: Explicit Deny for All Other Inbound Traffic
  # Protects K8s API (6443), PostgreSQL (5432), Kafka (29092, 9092), MinIO (9000, 9001),
  # gRPC (9081-9083), Grafana (3000), Prometheus (9090), and backend services (8080-8085).
  security_rule {
    name                       = "deny-all-other-inbound"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}
