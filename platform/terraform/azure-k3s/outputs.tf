# ==============================================================================
# SwiftDrop Azure K3s Terraform — Outputs Specification
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Strictly sanitized; zero credentials, private keys, or tokens.
# ==============================================================================

output "resource_group_name" {
  value       = azurerm_resource_group.k3s.name
  description = "Target Resource Group name."
}

output "vm_name" {
  value       = azurerm_linux_virtual_machine.k3s.name
  description = "Deployed Linux VM name."
}

output "vm_private_ip" {
  value       = azurerm_network_interface.k3s.private_ip_address
  description = "Internal private IP address of the K3s host."
}

output "public_ip" {
  value       = var.enable_public_ip ? azurerm_public_ip.k3s[0].ip_address : "NONE"
  description = "Allocated Azure Standard Public IP address for HTTPS ingress."
}

output "data_disk_name" {
  value       = azurerm_managed_disk.data.name
  description = "Persistent data disk name backing K3s stateful workloads."
}

output "data_disk_size_gb" {
  value       = azurerm_managed_disk.data.disk_size_gb
  description = "Persistent data disk capacity in GB."
}

output "k3s_version" {
  value       = var.k3s_version
  description = "Installed K3s runtime version."
}

output "public_domain_name" {
  value       = var.public_domain_name
  description = "Configured public FQDN for Keycloak and Ingress routing."
}

output "spending_authorization_confirmed" {
  value       = var.spending_authorization_confirmed
  description = "Status of the financial spending authorization gate."
}

output "approval_reference" {
  value       = var.approval_reference != "" ? var.approval_reference : "UNAUTHORIZED"
  description = "Auditable approval reference token."
}

output "ssh_connection_instruction" {
  value       = "ssh -i <private-key-path> ${var.admin_username}@${var.enable_public_ip ? azurerm_public_ip.k3s[0].ip_address : azurerm_network_interface.k3s.private_ip_address}"
  description = "Operator SSH access instruction (requires pre-registered SSH key)."
}
