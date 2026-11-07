# SwiftDrop — Azure K3s Work-Mode Deployment Handoff & Operational Guide

**Document Status:** COMPLETE — INFRASTRUCTURE PREPARATION COMPLETED / CLOUD DEPLOYMENT STRICTLY BLOCKED  
**Authoritative Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  
**Governing Architecture:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Target Integration Branch:** `test`  
**Feature Branch:** `feat/azure-k3s-terraform-preparation`  
**Base Test SHA:** `ab8234666d7126090f11fd8e6e31cbc9be66f75a`  
**Author & Platform Lead:** Suthankan1 (@Suthankan1 — Platform / Cloud Architect / DevOps Lead)  

---

> [!CAUTION]
> ### CLOUD PROVISIONING BOUNDARY: STRICTLY NOT AUTHORIZED
>
> **This handoff document synthesizes the completed Azure K3s Terraform infrastructure preparation.**
> No cloud resources have been provisioned. The spending authorization gate remains locked at `$0.00` budget with `spending_authorized: false`. A future human authorization checkpoint is strictly required before any cloud resources can be created.

---

## 1. Current Branch & Commit Context
- **Base Integration Branch:** `test` (`ab8234666d7126090f11fd8e6e31cbc9be66f75a`)
- **Active Feature Branch:** `feat/azure-k3s-terraform-preparation`
- **Working Tree State:** Clean, fully validated, isolated from baseline $0 root.

## 2. PR Target & Governance Status
- **PR Title:** `feat(infra): prepare gated Azure K3s Terraform infrastructure for SwiftDrop`
- **Base Branch:** `test`
- **Head Branch:** `feat/azure-k3s-terraform-preparation`
- **Merge Status:** PENDING INDEPENDENT PEER REVIEW (Do not self-approve or auto-merge).

## 3. Terraform Root Location
- **Isolated Azure Root:** [`platform/terraform/azure-k3s/`](../../platform/terraform/azure-k3s/)
- **Baseline $0 Root (Preserved):** [`platform/terraform/`](../../platform/terraform/)

## 4. Verified Toolchain & Provider Versions
- **Terraform Core:** `>= 1.8.0, < 2.0.0` (Verified on Terraform `v1.16.3`).
- **AzureRM Provider:** `hashicorp/azurerm ~> 4.0` (Pinned to `v4.81.0` in `.terraform.lock.hcl`).
- **Provider Registration:** `resource_provider_registrations = "none"` (Compliant with AzureRM 4.x).
- **Backend Configuration:** Local plan-only; remote state deferred per security policy.

## 5. Exact Proposed Azure Resource Inventory
The configuration prepares exactly 10 Azure resources across compute, networking, security, and persistence:
1. `azurerm_resource_group.k3s`: Named resource group (`rg-swiftdrop-demo`) with spending lifecycle preconditions.
2. `azurerm_virtual_network.k3s`: Virtual network (`vnet-swiftdrop-demo`, `10.10.0.0/16`).
3. `azurerm_subnet.k3s`: Dedicated K3s subnet (`snet-swiftdrop-k3s`, `10.10.1.0/24`).
4. `azurerm_public_ip.k3s`: Static Standard Public IP (`pip-swiftdrop-demo`) for HTTPS ingress.
5. `azurerm_network_security_group.k3s`: Firewall (`nsg-swiftdrop-demo`) allowing port 443 and restricted port 22, dropping all internal service ports.
6. `azurerm_network_interface.k3s`: Primary NIC (`nic-swiftdrop-demo`) bound to subnet and public IP.
7. `azurerm_network_interface_security_group_association.k3s`: Association linking NIC to NSG.
8. `azurerm_managed_disk.data`: Dedicated 32 GB Standard SSD data disk (`disk-swiftdrop-data-demo`).
9. `azurerm_linux_virtual_machine.k3s`: Ubuntu 24.04 LTS VM (`Standard_B4ms`) with SSH key auth and cloud-init bootstrap.
10. `azurerm_virtual_machine_data_disk_attachment.data`: SCSI LUN 0 attachment of data disk to VM.

## 6. Required Operator-Supplied Variables
Before a deployment can be planned, the operator must populate `terraform.tfvars`:
```hcl
spending_authorization_confirmed = true
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

## 8. Missing Subscription Information
- Explicit Azure Subscription ID is unconfigured by default. The future authorized operator must supply their target subscription ID via `ARM_SUBSCRIPTION_ID` or `terraform.tfvars`.

## 9. Missing Regional Quota Information
- Regional VM quota for `Standard BS Family` (4 vCPUs) must be inspected in the designated region prior to apply using `az vm list-usage`.

## 10. Missing DNS & HTTPS Delegation Information
- Public domain ownership and DNS zone delegation must be established so that an `A` record points `demo.swiftdrop.net` to the allocated Azure Public IP.

## 11. Credential & Secret Prerequisites
Application-layer secrets must be prepared for injection into K3s (never stored in Terraform state):
- Keycloak administrator password (`keycloak-admin-secret`)
- PostgreSQL passwords for accounts, business, and keycloak databases
- MinIO root credentials (`swiftdrop-minio-secret`)
- Ingress TLS certificates (`swiftdrop-tls-cert`)

## 12. Commands Already Executed & Verified
```bash
# 1. Baseline & Azure recursive formatting
terraform -chdir=platform/terraform fmt -check -recursive

# 2. Azure K3s static validation
terraform -chdir=platform/terraform/azure-k3s init -backend=false -input=false -lockfile=readonly
terraform -chdir=platform/terraform/azure-k3s validate -no-color

# 3. Azure spending gate lock check
python3 platform/terraform/azure-k3s/scripts/check-cost-authorization.py --verify-gate-locked

# 4. Helm environment templates rendering
platform/terraform/azure-k3s/scripts/verify-deployment-contracts.sh

# 5. Full test suites
pytest tests/azure-k3s/ -v
pytest tests/terraform-policy/ tests/hosting-feasibility/ tests/kubernetes/
```

## 13. Test Outcomes
- **tests/azure-k3s/:** 23 passed (100%)
- **tests/terraform-policy/:** 100 passed (100%)
- **tests/hosting-feasibility/:** 25 passed (100%)
- **tests/kubernetes/:** 108 passed (100%)
- **tests/ci/ (Workflow pipeline):** 20 passed (100%)
- **Zero regressions detected.**

## 14. Checks Still Blocked
- Live Azure authentication (`az login`)
- Remote cloud plan generation (`terraform plan` against live Azure API)
- Cloud provisioning (`terraform apply`)
- Public TLS certificate issuance

## 15. Infrastructure Deployment Preflight Procedure
Execute `platform/terraform/azure-k3s/scripts/preflight.sh` to audit Azure CLI credentials, subscription match, SSH key presence, and spending contract sign-off.

## 16. Reviewed Plan Generation Procedure
```bash
cd platform/terraform/azure-k3s
terraform plan -out=tfplan -input=false
```
Reviewers verify that the plan contains exactly 10 expected resources and no unapproved additions.

## 17. Human Approval Checkpoint
Prior to `terraform apply`, obtain written sign-off in `azure-spending-gate-policy.yaml` from:
1. `@Suthankan1` (Platform Lead / Cloud Architect)
2. `@Harithkeshan` (Platform Architecture)
3. `@Vibhushan-2003` (Security & FinOps)

## 18. Post-Deployment Verification Procedure
1. Execute `scripts/verify-azure-readiness.sh` to check VM state and NSG rules.
2. Execute `scripts/verify-k3s-readiness.sh` to check node readiness, storage mount, and Traefik pods.
3. Deploy Helm charts in order: `swiftdrop-data` -> `swiftdrop-messaging` -> `swiftdrop` -> `swiftdrop-monitoring`.
4. Perform end-to-end smoke testing per [`docs/DEPLOYMENT_RUNBOOK.md`](../../platform/terraform/azure-k3s/docs/DEPLOYMENT_RUNBOOK.md).

## 19. Rollback & Resource Cleanup Requirements
- For temporary pause: `az vm deallocate` (stops compute charges; retains data disk).
- For complete teardown: `helm uninstall` workloads, execute `terraform destroy`, verify zero orphaned disks or public IPs via `az resource list`, and delete empty resource group.

## 20. Known Architectural Risks & Limitations
- **Single-Node SPOF:** Host VM termination halts all services; resilience relies on persistent disk reattachment.
- **Burstable CPU Credits:** `Standard_B4ms` relies on accumulated CPU credits; sustained load testing can exhaust credits and cause CPU throttling.
- **Shared Disk I/O:** 8 stateful workloads share one 32 GB Standard SSD data disk (~500 IOPS, 60 MB/s throughput); concurrent heavy batch writes may experience I/O wait.
