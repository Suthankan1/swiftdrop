# ==============================================================================
# SwiftDrop Azure K3s Terraform — Input Variables Specification
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Invariant: Gated future deployment; default-disabled financial authorization.
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Spending Authorization & Financial Governance Gate
# ------------------------------------------------------------------------------

variable "spending_authorization_confirmed" {
  type        = bool
  default     = false
  description = "Explicit financial authorization flag. Must be confirmed by human authority per Issue #178."
}

variable "approval_reference" {
  type        = string
  default     = ""
  description = "Authoritative sign-off reference or token (e.g. ISSUE-178-AUTH-20261110)."
}

variable "approved_budget_usd" {
  type        = number
  default     = 0.0
  description = "Maximum monetary expenditure envelope in USD authorized for this evaluation deployment."

  validation {
    condition     = var.approved_budget_usd >= 0.0
    error_message = "Approved budget cannot be negative."
  }
}

variable "max_deployment_hours" {
  type        = number
  default     = 0
  description = "Maximum allowed deployment runtime in hours before mandatory deallocation/cleanup."

  validation {
    condition     = var.max_deployment_hours >= 0
    error_message = "Deployment duration cannot be negative."
  }
}

variable "billing_owner" {
  type        = string
  default     = ""
  description = "Identity of the person or entity responsible for cloud expenditures (e.g. Suthankan1)."
}

variable "infrastructure_operator" {
  type        = string
  default     = ""
  description = "Designated engineer authorized to execute the preflight and plan procedures."
}

# ------------------------------------------------------------------------------
# 2. Azure Subscription & Environment Placement
# ------------------------------------------------------------------------------

variable "subscription_id" {
  type        = string
  default     = ""
  description = "Target Azure Subscription ID. Required for execution; must not be committed to Git."
}

variable "resource_group_name" {
  type        = string
  default     = "rg-swiftdrop-demo"
  description = "Name of the Azure Resource Group housing the K3s evaluation environment."
}

variable "location" {
  type        = string
  default     = "westeurope"
  description = "Azure region for deployment. Selected based on quota, student offer availability, and pricing."

  validation {
    condition     = contains(["westeurope", "northeurope", "eastus", "eastus2", "centralus", "uksouth"], var.location)
    error_message = "Location must be an approved evaluation Azure region (westeurope, northeurope, eastus, eastus2, centralus, uksouth)."
  }
}

variable "environment" {
  type        = string
  default     = "demo"
  description = "Environment identifier tag (e.g. demo, staging, evaluation)."
}

# ------------------------------------------------------------------------------
# 3. Virtual Networking & Ingress Boundary
# ------------------------------------------------------------------------------

variable "vnet_address_space" {
  type        = list(string)
  default     = ["10.10.0.0/16"]
  description = "Virtual Network CIDR address space."
}

variable "subnet_address_prefix" {
  type        = list(string)
  default     = ["10.10.1.0/24"]
  description = "Dedicated K3s node subnet CIDR address prefix."
}

variable "enable_public_ip" {
  type        = bool
  default     = true
  description = "Whether to allocate an Azure Standard Public IP for HTTPS ingress access."
}

variable "allowed_ssh_source_cidrs" {
  type        = list(string)
  default     = []
  description = "Explicit operator CIDRs permitted for SSH access on port 22. MUST NOT contain 0.0.0.0/0 or ::/0."

  validation {
    condition = length([
      for cidr in var.allowed_ssh_source_cidrs : cidr
      if cidr == "0.0.0.0/0" || cidr == "::/0" || cidr == "*" || lower(cidr) == "internet"
    ]) == 0
    error_message = "SSH access must NEVER allow 0.0.0.0/0, ::/0, '*', or 'Internet'. Provide specific operator CIDRs."
  }
}

variable "enable_http_port_80" {
  type        = bool
  default     = false
  description = "Whether to allow inbound HTTP port 80 (only required for ACME HTTP-01 challenge or HTTP-to-HTTPS redirect)."
}

variable "allowed_http_source_cidrs" {
  type        = list(string)
  default     = ["0.0.0.0/0"]
  description = "Allowed CIDRs for HTTP port 80 if enabled."
}

variable "allowed_https_source_cidrs" {
  type        = list(string)
  default     = ["0.0.0.0/0"]
  description = "Allowed CIDRs for HTTPS port 443 application ingress."
}

# ------------------------------------------------------------------------------
# 4. Compute & Linux VM Specification
# ------------------------------------------------------------------------------

variable "vm_size" {
  type        = string
  default     = "Standard_B4ms"
  description = "Azure VM SKU sized to accommodate all 8 SwiftDrop apps, 5 DBs, Kafka, MinIO, and Traefik."

  validation {
    condition     = contains(["Standard_B4ms", "Standard_D4s_v5", "Standard_B2ms", "Standard_D2s_v5"], var.vm_size)
    error_message = "VM size must be an evaluated academic demonstration SKU: Standard_B4ms, Standard_D4s_v5, Standard_B2ms, or Standard_D2s_v5."
  }
}

variable "admin_username" {
  type        = string
  default     = "swiftdropadmin"
  description = "Administrative non-root sudo username for the Linux VM."
}

variable "operator_ssh_public_key" {
  type        = string
  default     = ""
  description = "OpenSSH public key for the admin user. Private key must remain outside version control."
}

variable "os_disk_type" {
  type        = string
  default     = "StandardSSD_LRS"
  description = "Managed disk SKU for the operating system disk."

  validation {
    condition     = contains(["StandardSSD_LRS", "Premium_LRS", "Standard_LRS"], var.os_disk_type)
    error_message = "OS disk type must be StandardSSD_LRS, Premium_LRS, or Standard_LRS."
  }
}

variable "os_disk_size_gb" {
  type        = number
  default     = 30
  description = "Capacity in GB of the root operating system disk."

  validation {
    condition     = var.os_disk_size_gb >= 30 && var.os_disk_size_gb <= 128
    error_message = "OS disk size must be between 30 and 128 GB."
  }
}

# ------------------------------------------------------------------------------
# 5. Persistent Application Storage Specification
# ------------------------------------------------------------------------------

variable "data_disk_type" {
  type        = string
  default     = "StandardSSD_LRS"
  description = "Managed disk SKU for the dedicated persistent application data disk."

  validation {
    condition     = contains(["StandardSSD_LRS", "Premium_LRS", "Standard_LRS"], var.data_disk_type)
    error_message = "Data disk type must be StandardSSD_LRS, Premium_LRS, or Standard_LRS."
  }
}

variable "data_disk_size_gb" {
  type        = number
  default     = 32
  description = "Capacity in GB of the persistent data disk backing K3s local-path PVCs."

  validation {
    condition     = var.data_disk_size_gb >= 32 && var.data_disk_size_gb <= 256
    error_message = "Data disk size must be between 32 and 256 GB."
  }
}

# ------------------------------------------------------------------------------
# 6. K3s Engine & Application Ingress
# ------------------------------------------------------------------------------

variable "k3s_version" {
  type        = string
  default     = "v1.31.2+k3s1"
  description = "Pinned K3s version matching local cluster baseline v1.31.2."
}

variable "public_domain_name" {
  type        = string
  default     = "demo.swiftdrop.net"
  description = "Canonical public FQDN configured for Keycloak issuer, Ingress SAN, and CORS headers."
}

variable "common_tags" {
  type        = map(string)
  default     = {}
  description = "Additional metadata tags to apply to all Azure resources."
}
