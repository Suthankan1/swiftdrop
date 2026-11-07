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
- [ ] All 8 SwiftDrop container images verified for `linux/amd64` architecture:
  - `ghcr.io/suthankan1/swiftdrop-frontend`
  - `ghcr.io/suthankan1/swiftdrop-api-gateway`
  - `ghcr.io/suthankan1/swiftdrop-accounts`
  - `ghcr.io/suthankan1/swiftdrop-pricing`
  - `ghcr.io/suthankan1/swiftdrop-delivery`
  - `ghcr.io/suthankan1/swiftdrop-communications`
  - `ghcr.io/suthankan1/swiftdrop-graphql`
  - `ghcr.io/suthankan1/swiftdrop-keycloak`

---

## 2. Secrets Management & Injection Boundary

### Strict Invariant: Zero Secrets in Terraform State
Terraform prepares cloud infrastructure (VM, VNet, Managed Disk, NSG). Application-level secrets (database passwords, Keycloak admin credentials, MinIO access keys, TLS private keys) must **NEVER** be declared as Terraform variables or stored in `.tfstate`.

### Secret Injection Protocol (Post-Infrastructure Bootstrapping)
Once K3s is healthy, secrets are injected directly into the cluster via authenticated SSH or encrypted kubectl session:

```bash
# 1. Database Credentials Secret
kubectl create secret generic accounts-db-credentials \
  --namespace swiftdrop-data \
  --from-literal=username=accounts_user \
  --from-literal=password='<cryptographically-random-password>'

# 2. Keycloak Admin Secret
kubectl create secret generic keycloak-admin-secret \
  --namespace swiftdrop-identity \
  --from-literal=admin-username=admin \
  --from-literal=admin-password='<cryptographically-random-password>'

# 3. MinIO S3 Credentials
kubectl create secret generic swiftdrop-minio-secret \
  --namespace swiftdrop-messaging \
  --from-literal=accesskey=swiftdrop-minio-admin \
  --from-literal=secretkey='<cryptographically-random-password>'

# 4. Ingress TLS Secret
kubectl create secret tls swiftdrop-tls-cert \
  --namespace swiftdrop-apps \
  --cert=/path/to/fullchain.pem \
  --key=/path/to/privkey.pem
```
