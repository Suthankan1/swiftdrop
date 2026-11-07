# SwiftDrop — Azure K3s Operator Deployment Runbook

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Spending Authorization Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../../../../docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  
**Release Verification Reference:** [`docs/deployment/N10_P12_RELEASE_VERIFICATION.md`](../../../../docs/deployment/N10_P12_RELEASE_VERIFICATION.md)  

---

> [!IMPORTANT]
> ### HUMAN AUTHORIZATION CHECKPOINT
>
> This runbook documents the future operational execution procedure. **Do NOT execute stages 2 through 7 without explicit multi-signature spending approval recorded in `azure-spending-gate-policy.yaml`.**

---

## Stage 1: Authorization & Preflight

```bash
# 1. Switch to the Azure K3s Terraform directory
cd platform/terraform/azure-k3s

# 2. Verify static policy and contracts
./scripts/validate.sh

# 3. Create operator variable file (from example template)
cp terraform.tfvars.example terraform.tfvars

# 4. Fill approved variables in terraform.tfvars:
#    - subscription_id
#    - operator_ssh_public_key
#    - allowed_ssh_source_cidrs = ["<operator-ip>/32"]
#    - spending_authorization_confirmed = true
#    - approved_budget_usd = 25.00
#    - approval_reference = "ISSUE-178-AUTH-..."

# 5. Execute preflight verification
./scripts/preflight.sh
```

---

## Stage 2: Infrastructure Execution Plan

```bash
# 1. Initialize Terraform with backend disabled
terraform init -backend=false -input=false -lockfile=readonly

# 2. Generate execution plan
terraform plan -out=tfplan -input=false

# 3. Inspect planned resources (Must show exactly 8 Azure resources):
#    - azurerm_resource_group
#    - azurerm_virtual_network
#    - azurerm_subnet
#    - azurerm_public_ip
#    - azurerm_network_security_group
#    - azurerm_network_interface
#    - azurerm_network_interface_security_group_association
#    - azurerm_managed_disk
#    - azurerm_linux_virtual_machine
#    - azurerm_virtual_machine_data_disk_attachment

# 4. Human Go/No-Go Decision Checkpoint
```

---

## Stage 3: Authorized Infrastructure Creation

```bash
# Execute only after team review and go/no-go sign-off
terraform apply -input=false tfplan
rm -f tfplan

# Capture allocated public IP and VM name
PUBLIC_IP=$(terraform output -raw public_ip)
VM_NAME=$(terraform output -raw vm_name)
echo "Deployed Host Public IP: $PUBLIC_IP"
```

---

## Stage 4: Cloud & K3s Cluster Verification

```bash
# 1. Verify Azure cloud resource status
./scripts/verify-azure-readiness.sh

# 2. Verify K3s cluster, storage mount, and node readiness via SSH
./scripts/verify-k3s-readiness.sh ~/.ssh/swiftdrop-azure-k3s swiftdropadmin

# 3. Verify DNS resolution
dig +short demo.swiftdrop.net
```

---

## Stage 5: Application Deployment & Secret Injection

```bash
# 1. Retrieve cluster kubeconfig via SSH tunnel
ssh -i ~/.ssh/swiftdrop-azure-k3s swiftdropadmin@${PUBLIC_IP} "sudo cat /etc/rancher/k3s/k3s.yaml" > ~/.kube/config-swiftdrop-azure
sed -i.bak "s/127.0.0.1/${PUBLIC_IP}/g" ~/.kube/config-swiftdrop-azure
export KUBECONFIG=~/.kube/config-swiftdrop-azure

# 2. Create core namespaces
kubectl create namespace swiftdrop-apps
kubectl create namespace swiftdrop-identity
kubectl create namespace swiftdrop-data
kubectl create namespace swiftdrop-messaging
kubectl create namespace swiftdrop-monitoring

# 3. Inject application secrets (DB credentials, Keycloak admin, MinIO, TLS)
# (Follow docs/DEPLOYMENT_PREPARATION.md Section 2)

# 4. Deploy Persistent Data Layer
helm upgrade --install swiftdrop-data ../../kubernetes/helm/swiftdrop-data \
  --namespace swiftdrop-data \
  --values ../../kubernetes/helm/swiftdrop-data/values.yaml \
  --values ../../kubernetes/helm/environments/azure-k3s/data-values.yaml

# 5. Deploy Messaging Layer (Kafka KRaft & MinIO)
helm upgrade --install swiftdrop-messaging ../../kubernetes/helm/swiftdrop-messaging \
  --namespace swiftdrop-messaging \
  --values ../../kubernetes/helm/swiftdrop-messaging/values.yaml \
  --values ../../kubernetes/helm/environments/azure-k3s/messaging-values.yaml

# 6. Deploy Application & Identity Services
helm upgrade --install swiftdrop ../../kubernetes/helm/swiftdrop \
  --namespace swiftdrop-apps \
  --values ../../kubernetes/helm/swiftdrop/values.yaml \
  --values ../../kubernetes/helm/environments/azure-k3s/swiftdrop-values.yaml

# 7. Deploy Observability & Monitoring
helm upgrade --install swiftdrop-monitoring ../../kubernetes/helm/swiftdrop-monitoring \
  --namespace swiftdrop-monitoring \
  --values ../../kubernetes/helm/swiftdrop-monitoring/values.yaml \
  --values ../../kubernetes/helm/environments/azure-k3s/monitoring-values.yaml

# 8. Wait for Pod Readiness
kubectl wait --for=condition=Ready pods --all -A --timeout=300s
```

---

## Stage 6: Application Smoke Testing

Execute the authoritative end-to-end acceptance suite against the live Azure FQDN:

1. **Keycloak Authentication:** Authenticate as customer, merchant, courier, dispatcher, and administrator via `https://auth.demo.swiftdrop.net`.
2. **Customer Booking:** Request pricing quote and generate parcel booking.
3. **Dispatch & Assignment:** Reassign booking to courier in dispatcher dashboard.
4. **Courier Lifecycle:** Update status to `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`.
5. **Live Parcel Tracking:** Connect via SSE on `https://demo.swiftdrop.net/track/<trackingNumber>`.
6. **Proof of Delivery Storage:** Verify photo upload persists in MinIO.
7. **GraphQL Composite Queries:** Query courier and customer order statistics via `https://api.demo.swiftdrop.net/graphql`.

---

## Stage 7: Deallocation, Tear-Down, & Billable Verification

Immediately after evaluation completion, deallocate or destroy resources:

```bash
# Option A: Temporary Deallocation (Retains data disk, stops compute billing)
az vm deallocate --resource-group "rg-swiftdrop-demo" --name "$VM_NAME"

# Option B: Complete Infrastructure Destruction
terraform destroy -input=false

# Verify zero billable resources remain in the resource group
az resource list --resource-group "rg-swiftdrop-demo" -o table
```
