# SwiftDrop — Azure K3s Work-Mode Deployment Handoff & Operational Guide

**Document Status:** COMPLETE — PRE-DEPLOYMENT HARDENING & READINESS VERIFIED / CLOUD DEPLOYMENT STRICTLY BLOCKED  
**Authoritative Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  
**Governing Architecture:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Target Integration Branch:** `test`  
**Feature Branch:** `fix/azure-k3s-predeployment-readiness`  
**Base Test SHA:** `a9bf3cf5c0866a9b4642d743d4f3d33b4b6003d9` (Merge PR #310)  
**Author & Platform Lead:** Suthankan1 (@Suthankan1 — Platform / Cloud Architect / DevOps Lead)  

---

> [!CAUTION]
> ### CLOUD PROVISIONING BOUNDARY: STRICTLY NOT AUTHORIZED
>
> **This handoff document synthesizes the completed Azure K3s Terraform pre-deployment hardening and readiness verification.**
> No cloud resources have been provisioned. The spending authorization gate remains locked at `$0.00` budget with `spending_authorized: false`. A future human authorization checkpoint is strictly required before any cloud resources can be created.

---

## 1. Current Branch & Commit Context
- **Base Integration Branch:** `test` (`a9bf3cf5c0866a9b4642d743d4f3d33b4b6003d9`)
- **Active Feature Branch:** `fix/azure-k3s-predeployment-readiness`
- **Preceding PR:** PR #310 (`feat/azure-k3s-terraform-preparation` merged into `test`)
- **Working Tree State:** Clean, fully validated, persistent storage protected, deny-by-default spending gate locked.

## 2. PR Target & Governance Status
- **PR Title:** `fix(infra): harden Azure K3s bootstrap and deployment safety`
- **Base Branch:** `test`
- **Head Branch:** `fix/azure-k3s-predeployment-readiness`
- **Merge Status:** PENDING INDEPENDENT PEER REVIEW (Do not self-approve or auto-merge).

## 3. Terraform Root Location
- **Isolated Azure Root:** [`platform/terraform/azure-k3s/`](../../platform/terraform/azure-k3s/)
- **Baseline $0 Root (Preserved):** [`platform/terraform/`](../../platform/terraform/)

## 4. Hardened Infrastructure Safety Controls (Fix Summary)
1. **Data Disk Initialization Race Fixed:**
   - Script `templates/storage-mount.sh.tftpl` implements a 300-second polling retry loop for SCSI controller 1, LUN 0 attachment (`/dev/disk/azure/scsi1/lun0`).
   - Explicitly rejects OS root disk and ephemeral resource disks before any operation.
   - Preserves existing filesystem UUIDs; never formats populated disks or disks with existing partition tables.
   - Deploys systemd unit drop-in `/etc/systemd/system/k3s.service.d/storage-dependency.conf` (`RequiresMountsFor=/mnt/swiftdrop-data`, `ConditionPathIsMountPoint=/mnt/swiftdrop-data`), guaranteeing that K3s refuses to start if the data disk is not mounted, preventing stateful workloads from writing to the OS root filesystem.
2. **Persistent Storage Protection:**
   - Resource `azurerm_managed_disk.data` in `storage.tf` enforces `lifecycle { prevent_destroy = true }`. Accidental destruction or destructive resource replacement is halted by Terraform.
   - Documented explicit operator decommissioning procedure requiring prior disk snapshot, workload shutdown, and reviewed PR setting `prevent_destroy = false`.
3. **Financial Authorization Gate Genuinely Blocking:**
   - Resource `azurerm_resource_group.k3s` enforces 12 fail-closed lifecycle preconditions evaluating `azure-spending-gate-policy.yaml`.
   - Freely editable variables or fabricated approval reference tokens cannot bypass the signed policy contract.
   - Operator preflight (`scripts/preflight.sh` and `scripts/check-cost-authorization.py`) validates subscription, region, SKUs, budget ceiling, deployment duration, and 3 peer reviewer signatures.
   - Clarified that Terraform IaC validates input parameters at plan/apply time; runtime spend containment relies on Azure Cost Management budget alerts and operator deallocation.
4. **Private Kubernetes Administration Access:**
   - Port 6443 remains strictly private and blocked from the internet by NSG Rule 4000.
   - Implemented `platform/terraform/azure-k3s/scripts/setup-kube-access.sh` enabling secure operator administration via authenticated SSH local port-forwarding over port 22 (restricted to operator CIDRs).
   - Kubeconfig permissions locked to `0600`.
   - Validates TLS certificate against `127.0.0.1` and `localhost` SANs without insecure flags.
5. **Hardened Cloud-Init & K3s Startup:**
   - Script `/usr/local/bin/swiftdrop-bootstrap.sh` executes with `set -euo pipefail`.
   - UFW host firewall configured with K3s CNI bridge/overlay forwarding rules (`cni0`, `flannel.1`, `10.42.0.0/16`, `10.43.0.0/16`, UDP 8472).
   - Critical initialization failures halt bootstrap immediately with error logs and do not falsely report successful completion.
6. **Application Deployment & Secret Compatibility:**
   - Complete secret injection procedures documented across all 4 namespaces (`swiftdrop-data`, `swiftdrop-messaging`, `swiftdrop-apps`, `swiftdrop-identity`).
   - Ingress TLS certificates documented for both `swiftdrop-apps` and `swiftdrop-identity`.
   - Spring Cloud API Gateway `SecurityConfig` updated to parse comma-separated `CORS_ALLOWED_ORIGIN`.
   - Resource requests and limits verified on `Standard_B4ms` (allocatable usage: 43% CPU, 24% RAM).

## 5. Exact Proposed Azure Resource Inventory
The configuration prepares exactly 10 Azure resources across compute, networking, security, and persistence:
1. `azurerm_resource_group.k3s`: Named resource group (`rg-swiftdrop-demo`) with spending lifecycle preconditions.
2. `azurerm_virtual_network.k3s`: Virtual network (`vnet-swiftdrop-demo`, `10.10.0.0/16`).
3. `azurerm_subnet.k3s`: Dedicated K3s subnet (`snet-swiftdrop-k3s`, `10.10.1.0/24`).
4. `azurerm_public_ip.k3s`: Static Standard Public IP (`pip-swiftdrop-demo`) for HTTPS ingress.
5. `azurerm_network_security_group.k3s`: Firewall (`nsg-swiftdrop-demo`) allowing port 443 and restricted port 22, dropping all internal service ports.
6. `azurerm_network_interface.k3s`: Primary NIC (`nic-swiftdrop-demo`) bound to subnet and public IP.
7. `azurerm_network_interface_security_group_association.k3s`: Association linking NIC to NSG.
8. `azurerm_managed_disk.data`: Dedicated 32 GB Standard SSD data disk (`disk-swiftdrop-data-demo`) with `prevent_destroy = true`.
9. `azurerm_linux_virtual_machine.k3s`: Ubuntu 24.04 LTS VM (`Standard_B4ms`) with SSH key auth and hardened cloud-init bootstrap.
10. `azurerm_virtual_machine_data_disk_attachment.data`: SCSI LUN 0 attachment of data disk to VM.

## 6. Required Operator-Supplied Variables
Before a deployment can be planned, the operator must populate `terraform.tfvars`:
```hcl
spending_authorization_confirmed = false # Set true ONLY after completing azure-spending-gate-policy.yaml
approval_reference               = "ISSUE-178-AUTH-20261110"
approved_budget_usd              = 25.00
max_deployment_hours             = 72
billing_owner                    = "Suthankan1"
infrastructure_operator          = "Suthankan1"
subscription_id                  = "<valid-azure-subscription-uuid>"
operator_ssh_public_key          = "ssh-ed25519 AAAAC3... operator@example"
allowed_ssh_source_cidrs         = ["<operator-ip>/32"]  # NEVER 0.0.0.0/0
location                         = "westeurope"
public_domain_name               = "demo.swiftdrop.net"
```

## 7. Approved Spending Status
- **Current Status:** `BLOCKED_PENDING_SEPARATE_HUMAN_AUTHORIZATION`
- **Cost Ceiling:** `$0.00 USD`
- **Approved Budget:** `$0.00 USD`
- **Gate Contract:** `platform/terraform/azure-k3s/azure-spending-gate-policy.yaml`

## 8. Verified Toolchain & Provider Versions
- **Terraform Core:** `>= 1.8.0, < 2.0.0` (Verified on Terraform `v1.16.3`).
- **AzureRM Provider:** `hashicorp/azurerm ~> 4.0` (Pinned to `v4.81.0` in `.terraform.lock.hcl`).
- **Provider Registration:** `resource_provider_registrations = "none"` (Compliant with AzureRM 4.x).
- **Backend Configuration:** Local plan-only; remote state deferred per security policy.

## 9. Checks Still Blocked
- Live Azure authentication (`az login`)
- Remote cloud plan generation (`terraform plan` against live Azure API)
- Cloud provisioning (`terraform apply`)
- Public TLS certificate issuance

## 10. Human Approval Checkpoint
Prior to `terraform apply`, obtain written sign-off in `azure-spending-gate-policy.yaml` from:
1. `@Suthankan1` (Platform Lead / Cloud Architect)
2. `@Harithkeshan` (Platform Architecture)
3. `@Vibhushan-2003` (Security & FinOps)
