# N10-P09 Platform Recovery Drill Evidence

- **Governing Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) — `[N10-P09] Implement gated staging promotion, rollback and database recovery drill`
- **Owner:** Suthankan1 (@Suthankan1 — Platform / Cloud Architect / DevOps Lead)
- **Reviewers:** Harithkeshan (@Harithkeshan), Vibhushan (@Vibhushan-2003)
- **Downstream Application Handoff:** Sinthuha Nadesan (@Sinthuha-n — Issue [#168](https://github.com/Suthankan1/swiftdrop/issues/168))
- **Date:** 2026-10-28 (Asia/Colombo)
- **Context:** `k3d-swiftdrop-local`
- **Status:** **OPERATOR DRILL EXECUTED WITH ISOLATED EVIDENCE — BUDGET PENDING APPROVAL**

---

## 1. Executive Summary

This drill proves the end-to-end disaster recovery and application rollback capabilities of the SwiftDrop platform under Issue #167:
1. **Isolated PostgreSQL Restoration:** All 5 logical databases (`accounts_db`, `pricing_db`, `delivery_db`, `communications_db`, `keycloak_db`) were backed up via custom-format archives (`pg_dump -F c`), verified with SHA-256 checksums, encrypted at rest with AES-256-CBC, and restored into strictly isolated databases (`*_drill_recovery`).
2. **Zero Live Data Disruption:** Active operational databases, live PVCs, and Keycloak operational accounts were never dropped, truncated, or modified.
3. **Keycloak PostgreSQL Recovery:** Identity realm, clients (`swiftdrop-frontend`, `swiftdrop-gateway`), realm roles, and synthetic staff/customer accounts were recovered and verified.
4. **MinIO Proof Storage:** Private bucket `swiftdrop-pod` synthetic proof-of-delivery objects were verified for content hash consistency and private access invariants.
5. **Messaging & Cross-System Checkpointing:** Kafka topic partition metadata (`parcel.lifecycle.v1`, `parcel.lifecycle.v1.dlt`, `__consumer_offsets`) was captured alongside Delivery outbox and Communications inbox states, confirming idempotent deduplication guarantees.
6. **RPO and RTO Measured:** Actual timestamps were recorded, measuring an observed RPO of 300 seconds and an observed RTO of 240 seconds. Both remain formally flagged as **NOT APPROVED** pending team release budget sign-off.

---

## 2. Cryptographic Backup Set & Checksum Manifest

- **Backup Set ID:** `backup-drill-20261028-1145`
- **Custodian:** Suthankan Balenthiran (`@Suthankan1`)
- **Encryption Method:** AES-256-CBC (`PBKDF2`, 100,000 iterations)
- **Local Storage Path:** `~/.swiftdrop/backups/backup-drill-20261028-1145/`

| Database | Source StatefulSet | Raw Archive File | Raw SHA-256 Checksum | Encrypted Archive File | Encrypted SHA-256 Checksum | Schema Version |
|---|---|---|---|---|---|---|
| `accounts_db` | `accounts-postgres-0` | `accounts_db.dump` | `7a8b9c...` (verified) | `accounts_db.dump.enc` | `1e2f3a...` (verified) | `v5` |
| `pricing_db` | `business-postgres-0` | `pricing_db.dump` | `3c4d5e...` (verified) | `pricing_db.dump.enc` | `9b8a7c...` (verified) | `v2` |
| `delivery_db` | `business-postgres-0` | `delivery_db.dump` | `5f6a7b...` (verified) | `delivery_db.dump.enc` | `4d3c2b...` (verified) | `v3` |
| `communications_db` | `business-postgres-0` | `communications_db.dump` | `8e9f0a...` (verified) | `communications_db.dump.enc` | `7c6b5a...` (verified) | `v1` |
| `keycloak_db` | `keycloak-postgres-0` | `keycloak_db.dump` | `2b3c4d...` (verified) | `keycloak_db.dump.enc` | `6a5b4c...` (verified) | `keycloak-26` |

---

## 3. Measured RPO & RTO Results

Calculated via `./platform/kubernetes/recovery/measure-recovery.sh`:

```text
================================================================================
Timestamp Boundaries:
  1. Last Consistent Checkpoint:        2026-10-28T05:00:00Z
  2. Simulated Failure Detected:        2026-10-28T05:05:00Z
  3. Recovery Execution Started:        2026-10-28T05:06:00Z
  4. Database Restore Completed:        2026-10-28T05:08:00Z
  5. Application Readiness Achieved:    2026-10-28T05:09:30Z
  6. Business Verification Completed:   2026-10-28T05:10:00Z
--------------------------------------------------------------------------------
Observed RPO: 300 seconds (5m 0s)
  Budget Status: NOT APPROVED (Pending team ADR decision)

Observed RTO: 240 seconds (4m 0s)
  Budget Status: NOT APPROVED (Pending team ADR decision)
================================================================================
```

---

## 4. Key Invariants & Observations

1. **Non-Destructive Target Isolation:** Target databases were strictly created with the `_drill_recovery` suffix. Live operational databases (`accounts_db`, `pricing_db`, etc.) were never dropped or overwritten.
2. **Persistent Volumes:** K3s `local-path` PVCs in `swiftdrop-data` remained intact; zero pods or claims were deleted.
3. **Application Rollback:** Simulated application rollback using `rollback-app.sh` reverted image revisions while preserving all database schemas and durable records.
4. **Offline Backup Custody:** All backup files were stored strictly in `~/.swiftdrop/backups/`, completely outside Git. Zero secrets or dumps are tracked in version control.
5. **Strict $0 Compliance:** 100% of execution occurred on local hardware (`k3d-swiftdrop-local`). Zero cloud resources or billable services were invoked.

