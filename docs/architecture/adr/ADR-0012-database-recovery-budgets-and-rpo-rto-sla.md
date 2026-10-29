# ADR-0012: Database Disaster Recovery Target RPO and RTO Thresholds for Local k3d Hosting

**Status:** PROPOSED FOR ADOPTION — PLATFORM & DATA RELIABILITY CONSENSUS  
**Baseline:** SwiftDrop Architecture v1.3  
**Date:** 2026-10-29  
**Milestone:** November 10, 2026 — Full SwiftDrop Release  
**Decision Owners:** Suthankan1 (@Suthankan1 — Platform & DevOps Lead), Sinthuha-n (@Sinthuha-n — Application Consistency & QA Lead)  
**Required Independent Reviewers:** Harithkeshan (@Harithkeshan — Architecture Reviewer), Vibhushan (@Vibhushan-2003 — Security & Quality Reviewer), EricPraveen (@EricPraveen — Release QA Lead)  
**Related Issues:** Refs [#167](https://github.com/Suthankan1/swiftdrop/issues/167), [#168](https://github.com/Suthankan1/swiftdrop/issues/168), [#170](https://github.com/Suthankan1/swiftdrop/issues/170), [#177](https://github.com/Suthankan1/swiftdrop/issues/177)  
**Decisions Formalized:** `DEC-DR-01`, `DEC-DR-02`, `DEC-DR-03`, `DEC-DR-04`  

---

## 1. Executive Summary & Context

Under **ADR-0011**, SwiftDrop's authoritative release topology is frozen to **local k3d / K3s hosting on existing hardware (`arm64`)** under a strict **$0.00 cost ceiling**. SwiftDrop maintains five isolated logical PostgreSQL databases:
1. `accounts_db` (User profiles, merchant API keys, audit logs)
2. `pricing_db` (Zones, versioned rate cards, snapshotted quotes)
3. `delivery_db` (Parcels, courier assignments, status history, transactional outbox)
4. `communications_db` (Durable inbox, notification attempts, webhook jobs)
5. `keycloak_db` (Realm metadata, user credentials, role mappings)

In disaster recovery engineering, defining unambiguous, mathematically bounded **Recovery Point Objective (RPO)** and **Recovery Time Objective (RTO)** SLAs is essential to prevent silent data loss, duplicate event dispatching, or unbounded downtime.

Previously, disaster recovery drills ([#167](https://github.com/Suthankan1/swiftdrop/issues/167), [#168](https://github.com/Suthankan1/swiftdrop/issues/168)) measured baseline restoration times, but these parameters remained categorized as `NOT APPROVED` pending formal team ADR adoption (cataloged as release blocker `BLK-02` in Issue #170).

This Architecture Decision Record formally establishes the binding RPO and RTO service level agreements, backup cadence, and restoration invariants for the SwiftDrop November 10 release.

---

## 2. Formal Decisions & Service Level Agreements

### 2.1 Decision 1: Maximum Recovery Point Objective (RPO) $\le 300$ Seconds (`DEC-DR-01`)
The team formally adopts a target **Recovery Point Objective (RPO) of 300 seconds (5 minutes)**:
- **Scope:** In the event of catastrophic container crash, host power loss, or unrecoverable filesystem corruption, the maximum permissible data loss window across all 5 databases is 300 seconds.
- **Transactional Outbox Guarantee:** All domain events published to Kafka prior to the 300-second cutoff boundary must exist in `delivery_db.outbox_events` in either `SENT` or `PENDING` status.
- **Quote-Booking Provenance:** No parcel booking record may exist without its immutable upstream rate-card quote snapshot.
- **Idempotency Reconciliation:** Upon restoration, the Communications Service inbox deduplication mechanism (`communications_db.inbox_events`) drops any replayed Kafka events with timestamps older than the restored snapshot boundary.

### 2.2 Decision 2: Maximum Recovery Time Objective (RTO) $\le 240$ Seconds (`DEC-DR-02`)
The team formally adopts a target **Recovery Time Objective (RTO) of 240 seconds (4 minutes)**:
- **Scope:** From the declaration of an emergency outage to full cluster readiness (all 5 databases accepting read/write connections and application health probes returning HTTP 200), total elapsed recovery time must not exceed 240 seconds.
- **Measured Breakdown on Reference Host (Apple Silicon 10-core, 16 GiB RAM, NVMe SSD):**
  - Container teardown and clean volume detachment: $\le 30$ seconds.
  - PostgreSQL container initialization: $\le 45$ seconds.
  - Decompression and parallel `pg_restore` across 5 schemas: $\le 90$ seconds.
  - Application readiness probe stabilization: $\le 60$ seconds.
  - Margin / buffer: 15 seconds.
  - **Total:** 240 seconds.

### 2.3 Decision 3: Local Storage Backup Custody & Encryption (`DEC-DR-03`)
In accordance with ADR-0011 ($0 cost policy) and the Terraform State and OIDC Boundary specification:
- **Storage Root:** `~/.swiftdrop/backups/` located strictly outside the Git repository checkout.
- **POSIX Mode:** Mode `0700` (`drwx------`) on backup directories; mode `0600` on backup archive files.
- **Encryption at Rest:** macOS FileVault (XTS-AES-128 with 256-bit key) on the host NVMe SSD. Offline long-term archives encrypted with AES-256-GCM.
- **Zero Cloud Storage:** No Azure Blob Storage accounts, AWS S3 buckets, or third-party paid backup agents are utilized.

### 2.4 Decision 4: Dual-Mode Verification Cadence (`DEC-DR-04`)
Disaster recovery readiness is verified via a dual-mode testing framework ([`tests/recovery/run_recovery_consistency_verification.py`](../../tests/recovery/run_recovery_consistency_verification.py)):
- **Mode A (Continuous CI / Offline Deterministic):** Runs on every pull request to validate data models, RPO snapshot boundary logic, and assert detection across 17 negative corruption scenarios.
- **Mode B (Operator Checkpoint):** Executed by the platform operator (`@Suthankan1`) against isolated physical targets (`*_drill_recovery`) prior to milestone release tagging.

---

## 3. Financial & Operational Consequences

### Positive Consequences
- **Zero Financial Cost ($0.00 USD):** Local snapshot and `pg_dump` automation incurs zero cloud egress, API transaction, or storage fees.
- **Deterministic Bounds:** RPO $\le 300$s and RTO $\le 240$s provide measurable engineering SLAs for academic demonstration and examiner review.
- **Data Isolation Preserved:** Each microservice restores exclusively into its dedicated schema, preventing cross-tenant leakage.

### Known Limitations & Mitigations
- **Single-Machine Host Dependency:** In local hosting, if the host laptop suffers total hardware failure, local backups on that drive would be lost.
  - *Mitigation:* Team members maintain synchronized Git clones and can bootstrap an identical clean cluster on any secondary machine within 10 minutes using `platform/local/create-cluster.sh`.

---

## 4. Resolution of Blocker BLK-02

With the formal adoption of this ADR:
- **Blocker Status:** `RESOLVED` (Subject to independent review sign-offs below).
- **Target Parameters Approved:** RPO: 300s, RTO: 240s.

