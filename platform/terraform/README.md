# SwiftDrop Terraform Infrastructure Foundation

## 1. Architectural Purpose and Hosting Decision

This directory contains the baseline Terraform configuration for the SwiftDrop platform.

- **Canonical Architecture Reference:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)
- **Machine-Readable Allowlist Contract:** [`platform/security/azure-allowlist-policy.yaml`](../security/azure-allowlist-policy.yaml)
- **State Custody & OIDC Security Policy:** [`docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md`](../../docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md)
- **Machine-Readable OIDC Trust Contract:** [`platform/security/github-oidc-trust-policy.yaml`](../security/github-oidc-trust-policy.yaml)
- **Originating Delivery Tasks:** [Issue #173 (N10-ZC02)](https://github.com/Suthankan1/swiftdrop/issues/173) (Plan-only baseline) and [Issue #174 (N10-ZC03)](https://github.com/Suthankan1/swiftdrop/issues/174) (Safe state & OIDC boundary)

### Strict $0 Hosting Invariants
1. **Deny-by-Default:** Per ADR-0011, the Azure allowlist (`azure.allowed_resources`) is intentionally **EMPTY (`[]`)** and `azure.deployment_authorized` is `false`.
2. **Zero Managed Cloud Resources:** The root module contains zero managed cloud resources. No Azure VMs, managed disks, public IPs, NAT gateways, load balancers, AKS clusters, or Azure Storage accounts are declared.
3. **Authorized Default Target:** All SwiftDrop business microservices, API Gateway, Keycloak, PostgreSQL instances, Kafka, and MinIO deploy locally to **k3d / K3s on existing hardware (`arm64`)**.
4. **No Promotional Credit Justifications:** Azure for Students promotional credits do NOT qualify as non-billable service.

---

## 2. Pinned Tooling and Multi-Platform Lockfile

- **Terraform CLI Constraint:** `>= 1.8.0, < 2.0.0`
- **Pinned Provider:** `hashicorp/azurerm` (`~> 4.0`)
- **Dependency Lockfile:** `.terraform.lock.hcl` is cryptographically signed and committed with multi-platform package hashes for:
  - `darwin_arm64` (local Apple Silicon development)
  - `darwin_amd64` (local Intel macOS)
  - `linux_amd64` (GitHub Actions CI runners)
  - `linux_arm64` (Linux ARM runners / developers)

To regenerate or add platforms to the lockfile without running a live deployment:
```bash
terraform -chdir=platform/terraform providers lock \
  -platform=linux_amd64 \
  -platform=linux_arm64 \
  -platform=darwin_arm64 \
  -platform=darwin_amd64 \
  hashicorp/azurerm
```

---

## 3. Safe Static Validation (Offline / Backend-Disabled)

PR validation and static CI tests **never** contact Azure or require cloud credentials. The backend is disabled explicitly to prevent remote state interactions.

### Standard Commands
```bash
# 1. Format check
terraform -chdir=platform/terraform fmt -check -recursive

# 2. Safe backend-disabled initialization using readonly lockfile
terraform -chdir=platform/terraform init -backend=false -input=false -lockfile=readonly

# 3. Static configuration validation
terraform -chdir=platform/terraform validate -no-color
```

---

## 4. Policy Gate and Security Testing

All Terraform plans (generated or synthetic) are evaluated by the deny-by-default validator:
- Script: `platform/security/validate_terraform_policy.py`
- Test Suite: `tests/terraform-policy/`

```bash
# Run policy validator directly against a plan JSON
python3 platform/security/validate_terraform_policy.py \
  --policy platform/security/azure-allowlist-policy.yaml \
  --plan <path-to-plan.json>

# Run policy test suite via pytest
python3 -m pytest tests/terraform-policy -v
```

---

## 5. Security & Boundary Notice

- Under **no circumstances** execute `terraform apply` or `terraform destroy` in pull request workflows or on unauthorized infrastructure.
- **Remote State:** DEFERRED — Zero cloud backend configured. CI initializes with `-backend=false`.
- **Local State Custody:** Managed exclusively by named custodian `@Suthankan1` under strict single-writer rules, FileVault encryption at rest, and offline encrypted backups (`docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md`).
- **OIDC Workload Identity:** Formally defined but DISABLED (`platform/security/github-oidc-trust-policy.yaml`). Zero live Azure credentials or federated identity credentials exist.
- **Active CI Workflows:** Credential-free and read-only (`permissions: contents: read`). `id-token: write` is strictly prohibited in untrusted workflows.
