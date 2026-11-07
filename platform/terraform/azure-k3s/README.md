# SwiftDrop — Azure K3s Terraform Infrastructure (Preparation Baseline)

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Target Architecture:** Single-Node Azure Linux VM hosting K3s & Embedded Traefik Ingress  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Spending Authorization Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../../../docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  
**Machine-Readable Gate Contract:** [`azure-spending-gate-policy.yaml`](azure-spending-gate-policy.yaml)  
**Work-Mode Handoff:** [`docs/deployment/AZURE_K3S_WORK_MODE_HANDOFF.md`](../../../docs/deployment/AZURE_K3S_WORK_MODE_HANDOFF.md)  

---

> [!CAUTION]
> ### STRICT SPENDING BOUNDARY: PROVISIONING NOT AUTHORIZED
>
> **This directory contains infrastructure-as-code preparation for a future human-authorized Azure cloud evaluation.**
> - It does **NOT** provision cloud resources.
> - `terraform apply` is strictly prohibited during this phase.
> - The spending authorization gate in `azure-spending-gate-policy.yaml` is set to `spending_authorized: false`.
> - Terraform plans fail closed unless multi-signature financial approval is confirmed.
> - The repository's operational release baseline remains the **strict $0 local k3d/K3s deployment** on local hardware.

---

## 1. Architectural Summary

This Terraform root defines the complete cloud infrastructure required to run the full SwiftDrop enterprise stack on Microsoft Azure using a single-node self-managed K3s cluster.

### Logical Topology
```text
                    INTERNET
                        |
                  HTTPS :443
                        |
                 Azure Public IP (Static, Standard SKU)
                        |
                Network Security Group (nsg-swiftdrop-demo)
                  - TCP 443: Allow Inbound (Application Ingress)
                  - TCP 22 : Allow Inbound ONLY from Approved Operator CIDRs
                  - Default: Deny All Other Inbound (Ports 6443, 5432, etc.)
                        |
              Azure Virtual Network (10.10.0.0/16)
                        |
                  Subnet (10.10.1.0/24)
                        |
            Azure Linux VM (Ubuntu 24.04 LTS, Standard_B4ms)
                        |
             Cloud-Init Bootstrapping (K3s Server v1.31.2)
                        |
           Embedded Traefik Ingress Controller (:443)
                        |
        +---------------+---------------+
        |               |               |
    React SPA      API Gateway      Keycloak
                        |
         +--------------+--------------+
         |              |              |
      Accounts       Pricing        Delivery
         |              |              |
         +--------------+--------------+
                        |
                  GraphQL Query
                        |
                 Communications
                        |
        +---------------+---------------+
        |               |               |
   PostgreSQL         Kafka           MinIO
  (5 Datastores)    (KRaft mode)    (Object S3)
        |               |               |
        +---------------+---------------+
                        |
         Persistent Storage (/mnt/swiftdrop-data)
                        |
          Azure Managed Data Disk (SCSI LUN 0)
```

---

## 2. Directory Layout

```text
platform/terraform/azure-k3s/
├── README.md                           # This architecture and operations overview
├── versions.tf                         # Pinned Terraform >= 1.8.0 and AzureRM ~> 4.0
├── providers.tf                        # AzureRM provider configuration with resource registration safety
├── variables.tf                        # Parameterized variables with strict type validations
├── locals.tf                           # Standardized naming conventions, tags, and paths
├── main.tf                             # Root module structural commentary and links
├── resource-group.tf                   # Dedicated resource group with spend preconditions
├── network.tf                          # Virtual Network, Subnet, Public IP, and NIC
├── security.tf                         # NSG rules restricting SSH and exposing only HTTPS :443
├── compute.tf                          # Ubuntu 24.04 Linux VM with SSH-key auth and cloud-init
├── storage.tf                          # Dedicated managed data disk for stateful K3s local-path volumes
├── cloud-init.tf                       # Cloud-init rendering pipeline
├── outputs.tf                          # Sanitized, credential-free outputs
├── validation.tf                       # Fail-closed check blocks and security assertions
├── terraform.tfvars.example            # Example operator variables template
├── azure-spending-gate-policy.yaml     # Machine-readable spending authorization contract
├── templates/
│   ├── cloud-init.yaml.tftpl           # Cloud-init host initialization template
│   ├── k3s-config.yaml.tftpl           # K3s server configuration template
│   └── storage-mount.sh.tftpl          # Idempotent storage format and mount script
├── scripts/
│   ├── validate.sh                     # Safe offline static validation suite
│   ├── preflight.sh                    # Pre-plan operator preflight verification
│   ├── check-cost-authorization.py     # Spending authorization and plan checker
│   ├── setup-kube-access.sh            # Secure SSH tunnel for private K8s API (port 6443)
│   ├── verify-azure-readiness.sh       # Post-provisioning Azure resource inspection
│   ├── verify-k3s-readiness.sh         # Post-provisioning K3s cluster & mount inspection
│   └── verify-deployment-contracts.sh  # Image digests, contracts, and Helm render tests
└── docs/
    ├── ARCHITECTURE.md                 # In-depth topology, network boundaries, and failure domains
    ├── COST_ESTIMATE.md                # Itemized billable cost analysis across 3 scenarios
    ├── DEPLOYMENT_PREPARATION.md       # Pre-deployment preparation and prerequisites
    ├── SECURITY.md                     # Security architecture, NSGs, SSH, secrets, and identity
    ├── STORAGE_AND_BACKUP.md           # Persistent data disk lifecycle, mount, and snapshot runbooks
    ├── HTTPS_AND_DNS.md                # DNS records, TLS certificates, and Keycloak URLs
    ├── DEPLOYMENT_RUNBOOK.md           # Step-by-step 7-stage operator deployment runbook
    └── ROLLBACK_AND_CLEANUP.md         # Safe teardown, data export, and resource deallocation
```

---

## 3. Financial Authorization & The Fail-Closed Gate

Under **Issue #178**, all Azure cloud spending is blocked by default:
1. `azure-spending-gate-policy.yaml` requires `spending_authorized: true` and 3 peer reviewer signatures before deployment can proceed.
2. `validation.tf` enforces fail-closed `check` blocks during Terraform evaluation.
3. `resource-group.tf` contains resource lifecycle preconditions requiring `var.spending_authorization_confirmed == true`, `var.approved_budget_usd > 0`, and a valid `approval_reference`.
4. Accidental destruction protection: Resources are **NOT** gated by `count = 0`. Omitting authorization variables triggers an authorization abort rather than planning a destructive teardown.

---

## 4. Safe Static Verification (CI / Local)

To validate the entire configuration offline without credentials or cloud spending:

```bash
# Execute the comprehensive validation suite
platform/terraform/azure-k3s/scripts/validate.sh
```

This performs:
- Recursive Terraform formatting check (`terraform fmt -check`)
- Backend-disabled Terraform initialization (`terraform init -backend=false`)
- Static schema and syntax validation (`terraform validate`)
- Spending authorization gate lock verification (`check-cost-authorization.py --verify-gate-locked`)
- Shell script syntax verification (`bash -n`)
- Helm values linting and rendering for all four SwiftDrop charts
