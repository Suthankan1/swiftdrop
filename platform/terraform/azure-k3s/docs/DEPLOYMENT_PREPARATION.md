# SwiftDrop — Azure K3s Deployment Preparation & Operator Prerequisites

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Authoritative Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../../../../docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  

---

## 1. Prerequisites Checklist

Before executing any deployment actions, the designated operator (`@Suthankan1`) and independent reviewers must satisfy every prerequisite below:

### 1.1 Governance & Financial Approvals
- [ ] Multi-signature authorization completed in `azure-spending-gate-policy.yaml`.
- [ ] Peer sign-off confirmed by `@Harithkeshan` (Platform) and `@Vibhushan-2003` (Security).
- [ ] Spending ceiling (`approved_budget_usd`) and duration (`max_deployment_hours`) established.
- [ ] Azure billing owner identified.

### 1.2 Azure Subscription & Quota
- [ ] Active Azure subscription confirmed (e.g. Azure for Students `MS-AZR-0170P` or Pay-As-You-Go).
- [ ] Target region verified (default: `westeurope`).
- [ ] Regional VM quota verified for `Standard_B4ms` (requires 4 vCPUs of `Standard BS Family`).
  ```bash
  az vm list-usage --location westeurope -o table | grep "Standard BS"
  ```
- [ ] Operator authenticated with Azure CLI:
  ```bash
  az login
  az account set --subscription "<approved-subscription-id>"
  ```

### 1.3 Cryptographic Identity & SSH Keys
- [ ] Dedicated SSH key pair generated **outside the repository**:
  ```bash
  ssh-keygen -t ed25519 -f ~/.ssh/swiftdrop-azure-k3s -C "swiftdrop-azure-operator"
  chmod 600 ~/.ssh/swiftdrop-azure-k3s
  chmod 644 ~/.ssh/swiftdrop-azure-k3s.pub
  ```
- [ ] Public key content copied to `operator_ssh_public_key` in `terraform.tfvars`.
- [ ] Operator's current public IP identified for NSG firewall restriction:
  ```bash
  curl -s https://api.ipify.org
  # Set allowed_ssh_source_cidrs = ["<operator-ip>/32"]
  ```

### 1.4 Public Domain & DNS Delegation
- [ ] Target public FQDN identified (e.g. `demo.swiftdrop.net`).
- [ ] DNS zone access verified to create an `A` record pointing to the allocated Azure Public IP.

### 1.5 Container Image Architectures & GHCR Verification
- [x] All 8 SwiftDrop container images verified for `linux/amd64` architecture (CI Run #42387115452):
  - `ghcr.io/suthankan1/swiftdrop-frontend` (`sha256:5ed569ffac2ec50001c4c3b3f1cf1ac466cc7a30de5ee81f4cbf4361f9a6d76a`)
  - `ghcr.io/suthankan1/swiftdrop-api-gateway` (`sha256:62e0a3e7c2a54d9c0b63be2bb1306ceba5d4b8242009c666d595de816bcf1993`)
  - `ghcr.io/suthankan1/swiftdrop-accounts` (`sha256:96e9b5fba3d12774c8d710ce920a08ea2ca8e299b26657ad782e5039ef4ff60b`)
  - `ghcr.io/suthankan1/swiftdrop-pricing` (`sha256:ba3d2931c67f261bb7cc4433b70615452ff7fecf105a2ec29f87a4632c1cb6f4`)
  - `ghcr.io/suthankan1/swiftdrop-delivery` (`sha256:d97ce899cfd5283201031b04851b91b38e5d31b507f2af8b4ed5ca33891268be`)
  - `ghcr.io/suthankan1/swiftdrop-communications` (`sha256:af6645f62fe7ecc908a7bd80832b6369a802e9b4fd2d86dbc61b7ce6b8e2a370`)
  - `ghcr.io/suthankan1/swiftdrop-graphql` (`sha256:cd21baf610531dce93470cab5e34c59c658cb84076529262399cc83add4521a8`)
  - `ghcr.io/suthankan1/swiftdrop-keycloak` (`sha256:04f3e86b749f9d7b0d5b14e6124272ce76b1d48dc87f053572894af3ca434516`)

---

## 2. Secrets Management & Injection Boundary

### Strict Invariant: Zero Secrets in Terraform State
Terraform prepares cloud infrastructure (VM, VNet, Managed Disk, NSG). Application-level secrets (database passwords, Keycloak admin credentials, MinIO access keys, TLS private keys) must **NEVER** be declared as Terraform variables or stored in `.tfstate`.

### Secret Injection Protocol (Post-Infrastructure Bootstrapping)
Once K3s is healthy and the secure administration SSH tunnel is active, secrets are injected directly into the cluster via authenticated kubectl session:

```bash
# 1. Namespace: swiftdrop-data (Stateful PostgreSQL credentials)
kubectl create secret generic accounts-db-credentials \
  --namespace swiftdrop-data \
  --from-literal=POSTGRES_USER=accounts_user \
  --from-literal=POSTGRES_PASSWORD='<accounts-db-password>'

kubectl create secret generic business-db-credentials \
  --namespace swiftdrop-data \
  --from-literal=POSTGRES_USER=postgres \
  --from-literal=POSTGRES_PASSWORD='<postgres-super-password>' \
  --from-literal=PRICING_DB_PASSWORD='<pricing-db-password>' \
  --from-literal=DELIVERY_DB_PASSWORD='<delivery-db-password>' \
  --from-literal=COMMUNICATIONS_DB_PASSWORD='<communications-db-password>'

kubectl create secret generic keycloak-db-credentials \
  --namespace swiftdrop-data \
  --from-literal=POSTGRES_USER=keycloak_user \
  --from-literal=POSTGRES_PASSWORD='<keycloak-db-password>'

# 2. Namespace: swiftdrop-messaging (MinIO private S3 root credentials)
kubectl create secret generic swiftdrop-minio-secret \
  --namespace swiftdrop-messaging \
  --from-literal=accesskey=swiftdrop-minio-admin \
  --from-literal=secretkey='<minio-secret-key>'

# 3. Namespace: swiftdrop-apps (Service-level DB, S3, and TLS secrets)
kubectl create secret generic accounts-db-secret \
  --namespace swiftdrop-apps \
  --from-literal=username=accounts_user \
  --from-literal=password='<accounts-db-password>'

kubectl create secret generic pricing-db-secret \
  --namespace swiftdrop-apps \
  --from-literal=username=pricing_user \
  --from-literal=password='<pricing-db-password>'

kubectl create secret generic delivery-db-secret \
  --namespace swiftdrop-apps \
  --from-literal=username=delivery_user \
  --from-literal=password='<delivery-db-password>'

kubectl create secret generic communications-db-secret \
  --namespace swiftdrop-apps \
  --from-literal=username=communications_user \
  --from-literal=password='<communications-db-password>'

kubectl create secret generic swiftdrop-minio-secret \
  --namespace swiftdrop-apps \
  --from-literal=accesskey=swiftdrop-minio-admin \
  --from-literal=secretkey='<minio-secret-key>'

kubectl create secret tls swiftdrop-tls-cert \
  --namespace swiftdrop-apps \
  --cert=/path/to/fullchain.pem \
  --key=/path/to/privkey.pem

# 4. Namespace: swiftdrop-identity (Keycloak bootstrap admin, DB, and TLS secrets)
kubectl create secret generic keycloak-admin-secret \
  --namespace swiftdrop-identity \
  --from-literal=admin-username=admin \
  --from-literal=admin-password='<keycloak-admin-password>'

kubectl create secret generic keycloak-db-secret \
  --namespace swiftdrop-identity \
  --from-literal=username=keycloak_user \
  --from-literal=password='<keycloak-db-password>'

kubectl create secret tls swiftdrop-tls-cert \
  --namespace swiftdrop-identity \
  --cert=/path/to/fullchain.pem \
  --key=/path/to/privkey.pem
```

---

## 3. Terraform State Custody & Security

1. **State Sensitivity:**  
   Terraform `.tfstate` files contain full resource identifiers, public/private IP addresses, subnet CIDRs, and NIC configurations.
2. **Repository Exclusion:**  
   Both `.tfstate`, `*.tfstate.backup`, and binary execution plan files (`tfplan`) are strictly excluded from version control via `.gitignore`. Never commit or push state files to Git.
3. **Local Encryption at Rest:**  
   When conducting single-operator evaluation, ensure local operator machine has FileVault / LUKS full-disk encryption enabled.
4. **Remote State Custody:**  
   In multi-operator production workflows, migrate from local state to an Azure Blob Storage backend utilizing:
   - Private storage account endpoint with firewall restriction.
   - Azure AD (Microsoft Entra ID) authentication without shared storage account access keys.
   - Customer-Managed Keys (CMK) encryption via Azure Key Vault.
   - Immutable blob versioning and soft-delete protections.
