# SwiftDrop Platform Handoff: Disaster Recovery & Rollout Durability (Issue #154 -> #167)

## 1. Scope & Objective

This document provides the foundational operational and recovery runbook delivered by **Issue #154** (`[N10-P08] Deploy persistent Kubernetes data and readiness-scaled application workloads`) for consumption by **Issue #167** (`[N10-P11] Automated rollback and recovery drill`).

It details the persistent storage hierarchy, database boundaries, data protection mechanisms, measured restart persistence evidence, and standard backup/restore workflows.

---

## 2. Persistent Storage Topology & Volume Mappings

All stateful workloads use the K3s `local-path` provisioner backed by an immutable host directory mount on the developer machine:
- **Host Storage Root:** `/Users/suthankan/.swiftdrop/k3d/storage/` (Host macOS path)
- **Container Storage Root:** `/var/lib/rancher/k3s/storage/` (Mounted into `k3d-swiftdrop-local-server-0`)
- **StorageClass:** `local-path` (ReclaimPolicy: `Delete`, VolumeBindingMode: `Immediate`)

### 2.1 Volume Claims and Persistent Volumes

| Namespace | StatefulSet / Pod | PVC Name | PV Name | Capacity | Access Mode | Container Mount Path |
|:---|:---|:---|:---|:---|:---|:---|
| `swiftdrop-data` | `accounts-postgres-0` | `data-accounts-postgres-0` | `pvc-3f8e1577-7dd2-4fc6-8ded-57a719ea9e28` | 2 GiB | `ReadWriteOnce` | `/var/lib/postgresql/data` |
| `swiftdrop-data` | `business-postgres-0` | `data-business-postgres-0` | `pvc-0ecbfef5-aba5-45f9-aa2f-db9cf7030299` | 3 GiB | `ReadWriteOnce` | `/var/lib/postgresql/data` |
| `swiftdrop-data` | `keycloak-postgres-0` | `data-keycloak-postgres-0` | `pvc-a37c9c38-39c7-4f23-9048-97038d15c90b` | 2 GiB | `ReadWriteOnce` | `/var/lib/postgresql/data` |
| `swiftdrop-messaging` | `kafka-0` | `data-kafka-0` | `pvc-01602652-af85-4c80-9c1d-0cf37d150dc1` | 3 GiB | `ReadWriteOnce` | `/var/lib/kafka/data` |
| `swiftdrop-messaging` | `minio-0` | `data-minio-0` | `pvc-61f5caa8-3df6-4780-b718-1a12da9d9a24` | 2 GiB | `ReadWriteOnce` | `/data` |

**Total Persistent Storage Allocated:** 12 GiB (across 5 bounded claims).

---

## 3. Database Ownership & Isolation Boundaries

In strict compliance with `docs/architecture/DATA_OWNERSHIP.md` and ADR-0002:

```
+-----------------------------------------------------------------------------------+
|                              PostgreSQL Topology                                  |
+--------------------------+------------------------------+-------------------------+
|    accounts-postgres     |      business-postgres       |    keycloak-postgres    |
|   (swiftdrop-data ns)    |     (swiftdrop-data ns)      |   (swiftdrop-data ns)   |
+--------------------------+------------------------------+-------------------------+
| Logical DB: accounts_db  | Logical DB: pricing_db       | Logical DB: keycloak_db |
| Owner: accounts_app      | Owner: pricing_app           | Owner: keycloak         |
|                          +------------------------------+                         |
|                          | Logical DB: delivery_db      |                         |
|                          | Owner: delivery_app          |                         |
|                          +------------------------------+                         |
|                          | Logical DB: communications_db|                         |
|                          | Owner: communications_app    |                         |
+--------------------------+------------------------------+-------------------------+
```

### Security & Privilege Invariants:
1. **Zero Cross-Service Access:** `REVOKE ALL ON DATABASE <db> FROM PUBLIC` is executed at creation. Cross-database queries are rejected at the engine level (`FATAL: permission denied for database`).
2. **Unprivileged Roles:** Application credentials (`*_app`) are non-superusers (`NOSUPERUSER NOCREATEDB NOCREATEROLE`).
3. **Automated Schema Migrations:** Each microservice runs Flyway on startup to validate and migrate only its owned schema. No application can modify another service's tables.

---

## 4. Measured Restart & Rollout Durability Evidence

Durability was verified during the Issue #154 implementation cycle using destructive pod terminations:

1. **Database Record Persistence:**
   - Synthetic test data (`ZONE_TEST_154` in `pricing_db.pricing_zones` and `idem-key-154` in `delivery_db.idempotency_records`) was written.
   - `business-postgres-0` was deleted (`kubectl delete pod business-postgres-0 -n swiftdrop-data`).
   - The StatefulSet controller recreated the pod and mounted the existing PVC within 8 seconds.
   - SQL queries verified that 100% of the inserted records and Flyway schema versions survived intact.
2. **MinIO Object Persistence:**
   - Synthetic proof-of-delivery object (`evidence.txt`) was placed in bucket `swiftdrop-pod`.
   - `minio-0` was deleted and re-created.
   - The file and checksum remained 100% intact on the persistent host mount.
3. **Kafka Partition Durability:**
   - Topics `parcel.lifecycle.v1` and `parcel.lifecycle.v1.dlt` with KRaft metadata `__cluster_metadata-0` were provisioned.
   - `kafka-0` was restarted and reloaded all partitions directly from disk with zero message or partition loss.
4. **Zero-Downtime Application Rollout:**
   - Rolling restart of `delivery-service` (`kubectl rollout restart deployment/delivery-service -n swiftdrop-apps`) completed with `1 of 2 updated replicas available` at all times, maintaining full service availability.

---

## 5. Backup Procedures for Issue #167 Drills

### 5.1 Logical PostgreSQL Backups
To take consistent point-in-time database dumps:

```bash
# 1. Accounts DB Backup
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-data accounts-postgres-0 -- \
  pg_dump -U accounts_app -d accounts_db -F c > backup-accounts-$(date +%Y%m%d%H%M%S).dump

# 2. Pricing DB Backup
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-data business-postgres-0 -- \
  pg_dump -U pricing_app -d pricing_db -F c > backup-pricing-$(date +%Y%m%d%H%M%S).dump

# 3. Delivery DB Backup
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-data business-postgres-0 -- \
  pg_dump -U delivery_app -d delivery_db -F c > backup-delivery-$(date +%Y%m%d%H%M%S).dump

# 4. Communications DB Backup
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-data business-postgres-0 -- \
  pg_dump -U communications_app -d communications_db -F c > backup-communications-$(date +%Y%m%d%H%M%S).dump

# 5. Keycloak DB Backup
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-data keycloak-postgres-0 -- \
  pg_dump -U keycloak -d keycloak_db -F c > backup-keycloak-$(date +%Y%m%d%H%M%S).dump
```

### 5.2 MinIO Object Sync
```bash
# Sync all objects from swiftdrop-pod to local backup directory
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-messaging minio-0 -- \
  tar -czf - -C /data swiftdrop-pod > backup-minio-pod-$(date +%Y%m%d%H%M%S).tar.gz
```

### 5.3 Host Storage Volume Snapshot
Because storage is backed by `/Users/suthankan/.swiftdrop/k3d/storage/`, operators can snapshot the local storage directory directly on the host:
```bash
rsync -a ~/.swiftdrop/k3d/storage/ ~/.swiftdrop/k3d/backups/snapshot-$(date +%Y%m%d%H%M%S)/
```

---

## 6. Known Failure Modes & Recovery Boundaries

1. **Single-Host Physical Failure:**
   - Because SwiftDrop adheres to the strict $0 local hosting policy (ADR-0011), persistent storage resides on the developer machine's NVMe drive. Host disk failure requires restoring from an offline directory snapshot or logical SQL backup.
2. **Crash Recovery & WAL Replay:**
   - PostgreSQL and Kafka KRaft both utilize Write-Ahead Logging (WAL). Unclean pod terminations trigger automatic crash recovery upon container restart without requiring manual intervention.
3. **Migration Rollbacks:**
   - Flyway Community Edition does not support automated down migrations (`flyway undo`). Schema rollbacks must be executed via forward compensating migrations (e.g. `V6__revert_...sql`).

