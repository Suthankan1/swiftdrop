# SwiftDrop — Azure K3s Persistent Storage & Disaster Recovery Specification

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Database & Persistence Reference:** [`docs/deployment/N10_P09_RELEASE_AND_RECOVERY_RUNBOOK.md`](../../../../docs/deployment/N10_P09_RELEASE_AND_RECOVERY_RUNBOOK.md)  

---

## 1. Storage Topology & Mounting Architecture

SwiftDrop stateful workloads require persistent storage that survives container terminations, pod rescheduling, and VM reboots or resizing.

### Separation of OS vs Application Data
1. **OS Disk (`/dev/sda`):** 30 GB `StandardSSD_LRS`. Houses Ubuntu 24.04 LTS, container runtime binaries, system logs, and ephemeral files.
2. **Dedicated Managed Data Disk (`/dev/sdc`):** 32 GB `StandardSSD_LRS` attached at SCSI LUN 0. Houses all database tables, Kafka commit logs, MinIO object blobs, and Prometheus metrics.

```
+-------------------------------------------------------------------------------+
| HOST LINUX VIRTUAL MACHINE                                                     |
|                                                                               |
|   /dev/sda (30 GB OS Disk) --------> / (Root Filesystem)                      |
|                                                                               |
|   /dev/sdc (32 GB Azure Data Disk) -> /mnt/swiftdrop-data (ext4 via UUID)    |
|                                         |                                     |
|                                         +--> /k3s-storage (755 root:root)     |
|                                                 |                             |
|   +---------------------------------------------v-------------------------+   |
|   | K3s LOCAL-PATH PROVISIONER (PVC Allocation)                           |   |
|   |                                                                       |   |
|   |  - accounts-db-data   : 2 GiB (Accounts PostgreSQL)                   |   |
|   |  - business-db-data   : 3 GiB (Pricing, Delivery, Comms PostgreSQL)   |   |
|   |  - keycloak-db-data   : 2 GiB (Keycloak Identity PostgreSQL)          |   |
|   |  - kafka-data         : 3 GiB (Kafka KRaft Log Segments)              |   |
|   |  - minio-data         : 2 GiB (MinIO Delivery POD Proof Storage)      |   |
|   |  - prometheus-data    : 2 GiB (Prometheus Time-Series TSDB)           |   |
|   |  - grafana-data       : 1 GiB (Grafana Dashboards & User Settings)    |   |
|   |  - loki-data          : 1 GiB (Loki Compressed Log Chunks)            |   |
|   |                                                                       |   |
|   |  Allocated: ~16 GiB | Free Overhead: ~16 GiB (50% Headroom)           |   |
|   +-----------------------------------------------------------------------+   |
+-------------------------------------------------------------------------------+
```

---

## 2. Safe Mount & Idempotent Formatting Protocol

The initialization script (`templates/storage-mount.sh.tftpl`) executes during cloud-init and enforces data preservation:

1. **SCSI Device Discovery:** Identifies the LUN 0 device via `/dev/disk/azure/scsi1/lun0` or `/dev/sdc`.
2. **Filesystem Probe (`blkid`):**  
   - If **NO** filesystem signature is detected: Formats with `mkfs.ext4 -m 1 -L swiftdrop-data <device>`.
   - If an existing `ext4` filesystem is detected: **PRESERVES EXISTING DATA** and skips formatting.
3. **UUID Identification:** Extracts the filesystem UUID to ensure mounting is resilient to device name reordering across reboots.
4. **Fstab Persistence:** Idempotently appends the mount entry to `/etc/fstab`:
   ```text
   UUID=<uuid> /mnt/swiftdrop-data ext4 defaults,nofail,discard 0 2
   ```
5. **StorageClass Symlink:** Symlinks `/var/lib/rancher/k3s/storage` to `/mnt/swiftdrop-data/k3s-storage`, ensuring the standard K3s `local-path` provisioner stores volumes on the managed data disk.

---

## 3. Disaster Recovery & Snapshot Runbooks

### 3.1 Azure Managed Disk Point-in-Time Snapshots
Before performing significant demonstrations, database migrations, or maintenance drills, take a crash-consistent disk snapshot:

```bash
# 1. Capture snapshot of the persistent data disk
az snapshot create \
  --resource-group "rg-swiftdrop-demo" \
  --source "disk-swiftdrop-data-demo" \
  --name "snapshot-swiftdrop-data-$(date +%Y%m%d%H%M%S)" \
  --sku Standard_LRS

# 2. Verify snapshot creation
az snapshot list --resource-group "rg-swiftdrop-demo" -o table
```

### 3.2 Logical PostgreSQL Cold Backup Runbook
To export database state outside the cloud boundary for safe disaster recovery:

```bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="${HOME}/.swiftdrop/azure-backups/${TIMESTAMP}"
mkdir -p "$BACKUP_DIR"

# 1. Accounts Database Dump
kubectl exec -n swiftdrop-data statefulset/accounts-postgres -- \
  pg_dump -U accounts_user -d accounts_db > "${BACKUP_DIR}/accounts_db.sql"

# 2. Business Databases Dump (Pricing, Delivery, Comms)
kubectl exec -n swiftdrop-data statefulset/business-postgres -- \
  pg_dump -U business_user -d pricing_db > "${BACKUP_DIR}/pricing_db.sql"
kubectl exec -n swiftdrop-data statefulset/business-postgres -- \
  pg_dump -U business_user -d delivery_db > "${BACKUP_DIR}/delivery_db.sql"
kubectl exec -n swiftdrop-data statefulset/business-postgres -- \
  pg_dump -U business_user -d communications_db > "${BACKUP_DIR}/communications_db.sql"

# 3. Keycloak Identity Database Dump
kubectl exec -n swiftdrop-data statefulset/keycloak-postgres -- \
  pg_dump -U keycloak_user -d keycloak_db > "${BACKUP_DIR}/keycloak_db.sql"

echo "✓ Logical database backups completed to: $BACKUP_DIR"
```

---

## 4. Host Replacement & Disk Re-Attachment Protocol

If the host VM is corrupted, deallocated, or deleted, persistent state is preserved on the independent Azure managed data disk:

1. The data disk (`disk-swiftdrop-data-demo`) has `prevent_destroy = false` (or guarded by lifecycle).
2. Terraform or the operator creates a replacement VM.
3. The data disk is attached to the new VM at SCSI LUN 0.
4. During boot, `storage-mount.sh` detects the existing `ext4` filesystem UUID, mounts `/mnt/swiftdrop-data` without formatting, and preserves all database records.
5. K3s starts, reads `/mnt/swiftdrop-data/k3s-storage`, and rebinds persistent volume claims to their existing data directories seamlessly.
