# SwiftDrop — Azure K3s Itemized Cost Assessment & Sizing Scenarios

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Authoritative Sizing Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../../../../docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  
**Pricing Evidence Verified Date:** 2026-11-01 (Microsoft Azure Pricing Calculator / West Europe Region)  

---

## 1. Rate Schedule & Billable Category Breakdown

All pricing reflects official Microsoft Azure retail pay-as-you-go rates in the `westeurope` region as of November 2026.

| Category | Component & Specification | Unit Rate | Billing Basis | Notes |
|---|---|---|---|---|
| **Virtual Machine** | `Standard_B4ms` (4 vCPU, 16 GiB RAM) | $0.166 / hour | Metered by second | Burstable compute with credit accumulation |
| **Virtual Machine (Alt)** | `Standard_D4s_v5` (4 vCPU, 16 GiB RAM) | $0.192 / hour | Metered by second | Non-burstable dedicated compute |
| **OS Managed Disk** | 30 GB `StandardSSD_LRS` (E4 Tier) | $1.54 / month | Flat monthly allocation | Persists while VM is allocated |
| **Data Managed Disk** | 32 GB `StandardSSD_LRS` (E4 Tier) | $1.54 / month | Flat monthly allocation | Retained across VM deallocations |
| **Public IPv4 Address** | Standard SKU Static Public IP | $0.005 / hour | Hourly allocation | Billed regardless of VM power state |
| **Virtual Network** | Azure VNet (`10.10.0.0/16`) | $0.00 | Free | No charge for VNets or subnets |
| **Network Security Group**| NSG (`nsg-swiftdrop-demo`) | $0.00 | Free | No charge for security group rules |
| **Inbound Data Transfer** | External Internet to Azure VM | $0.00 | Free | All ingress data transfer is $0 |
| **Outbound Data Transfer**| Azure VM to External Internet | $0.00 (First 100 GB/mo free) | Tiered | Demo egress is < 2 GB |
| **DNS Hosting** | Custom FQDN / External DNS | $0.00 | Free | Uses free external DNS or existing zone |
| **TLS Certificates** | Let's Encrypt ACME / ZeroSSL | $0.00 | Free | Automated via cert-manager or Traefik |
| **Kubernetes Engine** | Native K3s (Self-managed) | $0.00 | Free | Zero managed cluster fee ($0 vs $73/mo AKS) |
| **Container Registry** | GitHub Packages (GHCR) | $0.00 | Free | Public/authorized private images on GHCR |

---

## 2. Evaluation Scenarios & Budget Envelopes

### Scenario A: Bounded Academic Demonstration (4 Hours)
*Intended for live grading, walkthrough, and team review.*

| Resource | Quantity | Unit Price | Total Cost |
|---|---|---|---|
| Virtual Machine (`Standard_B4ms`) | 4 hours | $0.166 / hr | $0.664 |
| OS Disk (30 GB Standard SSD) | 4 hours (pro-rated) | ~$0.0021 / hr | $0.008 |
| Data Disk (32 GB Standard SSD) | 4 hours (pro-rated) | ~$0.0021 / hr | $0.008 |
| Standard Public IP | 4 hours | $0.005 / hr | $0.020 |
| Inbound & Outbound Bandwidth | ~1.5 GB | $0.00 | $0.000 |
| **TOTAL ESTIMATED EXPENDITURE** | | | **~$0.70 USD** |

### Scenario B: Limited Multi-Day Integration Rehearsal (72 Hours / 3 Days)
*Intended for thorough end-to-end testing, webhook validation, and recovery drills.*

| Resource | Quantity | Unit Price | Total Cost |
|---|---|---|---|
| Virtual Machine (`Standard_B4ms`) | 72 hours | $0.166 / hr | $11.952 |
| OS Disk (30 GB Standard SSD) | 3 days | ~$0.051 / day | $0.153 |
| Data Disk (32 GB Standard SSD) | 3 days | ~$0.051 / day | $0.153 |
| Standard Public IP | 72 hours | $0.005 / hr | $0.360 |
| Inbound & Outbound Bandwidth | ~5 GB | $0.00 | $0.000 |
| **TOTAL ESTIMATED EXPENDITURE** | | | **~$12.62 USD** |

### Scenario C: Full Continuous Month Evaluation (730 Hours)
*Theoretical upper bound if VM is left running 24/7 without deallocation.*

| Resource | Quantity | Unit Price | Total Cost |
|---|---|---|---|
| Virtual Machine (`Standard_B4ms`) | 730 hours | $0.166 / hr | $121.18 |
| OS Disk (30 GB Standard SSD) | 1 month | $1.54 / mo | $1.54 |
| Data Disk (32 GB Standard SSD) | 1 month | $1.54 / mo | $1.54 |
| Standard Public IP | 730 hours | $0.005 / hr | $3.65 |
| Outbound Bandwidth (> 100 GB) | ~10 GB | $0.00 | $0.00 |
| **TOTAL ESTIMATED EXPENDITURE** | | | **~$127.91 USD** |

---

## 3. Financial Controls & Cost Containment Policies

1. **Azure for Students Balance Disqualified:**  
   Per ADR-0011, having a $100 student promotional balance does **NOT** classify cloud resources as $0 or free. Real monetary charges accrue against the subscription quota.
2. **Pre-Provisioning Budget Validation vs Runtime Enforcement:**  
   The Terraform spending authorization gate validates input variables and execution plans against the maximum authorized budget ($25.00 for Scenario B) in `azure-spending-gate-policy.yaml` prior to plan and apply. However, **Terraform does NOT continuously poll billing or enforce hard spend cutoffs at runtime**. Once resources are active in Azure, runtime expenditure containment relies strictly on automated Azure Cost Management budget alerts (item 3) and immediate operator deallocation (item 4).
3. **Budget Alerts in Azure Cost Management:**  
   An automated budget alert must be created at 50% ($12.50) and 80% ($20.00) of the approved spending ceiling, notifying `@Suthankan1`.
4. **Mandatory Immediate Deallocation:**  
   Following demonstration completion, the operator must execute `az vm deallocate` immediately. Deallocated VMs cease compute billing ($0.00/hr), incurring only nominal storage retention fees (~$0.10/day).
