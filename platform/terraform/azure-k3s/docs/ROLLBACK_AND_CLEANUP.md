# SwiftDrop — Azure K3s Rollback, Teardown, & Resource Cleanup Runbook

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Cleanup Verification Reference:** [`docs/deployment/N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md`](../../../../docs/deployment/N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md)  

---

## 1. Application-Level Rollback Runbooks

If a newly deployed image or configuration causes service degradation during evaluation:

### 1.1 Helm Release Rollback
```bash
# Check revision history
helm history swiftdrop -n swiftdrop-apps

# Roll back to previous healthy revision
helm rollback swiftdrop 1 -n swiftdrop-apps

# Verify pod status
kubectl rollout status deployment/delivery-service -n swiftdrop-apps
```

### 1.2 Database Point-in-Time Restore from Disk Snapshot
If databases experience schema corruption during testing:
1. Stop database pods:
   ```bash
   kubectl scale statefulset/accounts-postgres --replicas=0 -n swiftdrop-data
   kubectl scale statefulset/business-postgres --replicas=0 -n swiftdrop-data
   kubectl scale statefulset/keycloak-postgres --replicas=0 -n swiftdrop-data
   ```
2. Unmount `/mnt/swiftdrop-data` on the host VM.
3. In Azure CLI, swap the data disk with a restored disk created from the latest Azure snapshot:
   ```bash
   az disk create --resource-group "rg-swiftdrop-demo" \
     --name "disk-swiftdrop-data-restored" \
     --source "<snapshot-resource-id>"
   ```
4. Re-attach the restored disk at LUN 0 and remount.
5. Scale database statefulsets back to 1 replica.

---

## 2. Complete Infrastructure Teardown Runbook

To ensure that zero recurring charges accrue after demonstration completion:

### Step 1: Pre-Teardown Data Export (Optional)
If database records or audit logs are required for evaluation reports:
```bash
# Execute logical database dump
./docs/STORAGE_AND_BACKUP.md (Section 3.2)
```

### Step 2: Uninstall Kubernetes Helm Releases
```bash
helm uninstall swiftdrop-monitoring -n swiftdrop-monitoring || true
helm uninstall swiftdrop -n swiftdrop-apps || true
helm uninstall swiftdrop-messaging -n swiftdrop-messaging || true
helm uninstall swiftdrop-data -n swiftdrop-data || true
```

### Step 3: Execute Terraform Destruction
```bash
cd platform/terraform/azure-k3s

# NOTE: azurerm_managed_disk.data enforces lifecycle { prevent_destroy = true } to prevent accidental data loss.
# If preserving data: destroy compute/networking while leaving the disk intact:
# terraform destroy -target=azurerm_linux_virtual_machine.k3s ...
# If completely decommissioning:
# 1. Take a final snapshot: az snapshot create --resource-group "rg-swiftdrop-demo" --source "disk-swiftdrop-data-demo" --name "final-snapshot"
# 2. Set prevent_destroy = false in storage.tf (reviewed PR)
# 3. Execute Terraform destroy:
terraform destroy -input=false
```

### Step 4: Verify Zero Residual Azure Resources
Even after Terraform destroy, verify via Azure CLI that no orphaned billable components remain in the subscription:

```bash
# 1. Check for any remaining resources in the resource group
az resource list --resource-group "rg-swiftdrop-demo" -o table

# 2. Check for orphaned managed disks across the subscription
az disk list --query "[?resourceGroup=='rg-swiftdrop-demo'].{Name:name, SizeGB:diskSizeGb, State:diskState}" -o table

# 3. Check for unattached Public IPs (which incur idle hourly fees)
az network public-ip list --query "[?resourceGroup=='rg-swiftdrop-demo'].{Name:name, IP:ipAddress}" -o table

# 4. Delete the Resource Group completely if empty
az group delete --name "rg-swiftdrop-demo" --yes --no-wait
```

### Step 5: Lock Spending Gate
Reset local variables to deny-by-default posture:
1. Set `spending_authorization_confirmed = false` in `azure-spending-gate-policy.yaml`.
2. Delete local `terraform.tfvars`.
3. Verify gate lock with:
   ```bash
   python3 scripts/check-cost-authorization.py --verify-gate-locked
   ```
