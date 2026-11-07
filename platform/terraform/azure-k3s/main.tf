# ==============================================================================
# SwiftDrop Azure K3s Terraform Root Module — Infrastructure Preparation
# Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
# Policy Contract: platform/terraform/azure-k3s/azure-spending-gate-policy.yaml
#
# INVARIANT: Strict fail-closed spending gate. Default-disabled future deployment.
# Prepared for human-authorized evaluation on single-node Azure K3s.
# Zero resources provisioned during preparation phase.
# ==============================================================================

# Core infrastructure components are modularized across:
# - resource-group.tf : Dedicated Resource Group with lifecycle preconditions.
# - network.tf        : Virtual Network, Subnet, Public IP, and Network Interface.
# - security.tf       : Network Security Group with restricted SSH and HTTPS ingress.
# - compute.tf        : Azure Linux VM with Ubuntu 24.04 LTS and cloud-init bootstrap.
# - storage.tf        : Dedicated managed data disk for persistent K3s local-path volumes.
# - cloud-init.tf     : Idempotent bootstrapping templates for K3s and storage mounting.
# - validation.tf     : Spending authorization check blocks and security assertions.
