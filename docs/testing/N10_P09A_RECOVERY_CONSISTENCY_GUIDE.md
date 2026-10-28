# SwiftDrop Recovery Verification Guide (Issue #168 / N10-P09A)

- **Document:** Post-Rollback Application Consistency Verification Architecture & Guide
- **Governing Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168)
- **Parent Recovery Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167)
- **Application Verification Owner:** Sinthuha Nadesan (`@Sinthuha-n`)
- **Platform & Recovery Drill Owner:** Suthankan Balenthiran (`@Suthankan1`)
- **Milestone:** November 10 Baseline (Architecture v1.3)

---

## 1. Objective & Ownership Boundary

This guide defines the independent application-level verification framework executed following operator-performed staging rollbacks and isolated database restores.

### Clear Boundary of Authority
- **Operator `@Suthankan1` exclusively owns:**
  * K3d cluster lifecycle and persistent volume claims.
  * Container image rollback and Helm releases.
  * PostgreSQL logical backups (`pg_dump -F c`), AES-256 encryption at rest, and isolated target restoration (`*_drill_recovery`).
  * Keycloak and MinIO object store recovery.
  * Kafka broker checkpoints and offset watermarks.
  * Measuring RPO/RTO infrastructure timings.
- **Application Engineer `@Sinthuha-n` exclusively owns:**
  * Deterministic application test fixtures.
  * Pre/post recovery consistency assertions.
  * Quote-to-booking provenance and single-consumption invariants.
  * Parcel version sequence monotonicity and lifecycle transition finality.
  * Courier assignment uniqueness.
  * Transactional outbox sequencing and inbox deduplication.
  * Durable notification and webhook retry state machines.
  * Sanitized evidence generation and independent acceptance handoff.

---

## 2. Supported Verification Modes

The framework operates under two explicitly separated execution modes:

```
+-----------------------------------------------------------------------------------+
|               SwiftDrop Recovery Consistency Verification Engine                 |
+-----------------------------------------+-----------------------------------------+
                                          |
                +-------------------------+-------------------------+
                |                                                   |
                v                                                   v
     [ Mode A: Offline ]                                 [ Mode B: Operator ]
  - Deterministic fixtures                            - Connects to *_drill_recovery
  - 17 baseline invariants                            - Read-only transaction enforcement
  - 17 negative failure-injections                    - Evaluates live restored state
  - RPO cutoff boundary check                         - Fails closed with BLOCKED status
  - Exit 0 if all self-tests pass                       if operator targets unverified
```

### Mode A — Offline Deterministic Verification
Run independently by developers or CI before live cluster deployment. Evaluates synthetic snapshots against all architectural invariants and executes 17 negative corruption scenarios to prove fail-closed anomaly detection.

### Mode B — Operator-Checkpoint Verification
Run against live restored database targets provided by `@Suthankan1`. Requires isolated targets ending in `_drill_recovery` and enforces read-only transactions (`SET TRANSACTION READ ONLY`). If targets or checkpoints are unconfirmed, it reports `BLOCKED — OPERATOR CHECKPOINT NOT VERIFIED` without faking a pass.

---

## 3. Canonical Verification Commands

### Execute Mode A (Offline Deterministic Self-Test)
```bash
python3 tests/recovery/run_recovery_consistency_verification.py --mode offline \
  --output-json build/reports/recovery/recovery-consistency-report.json \
  --output-md build/reports/recovery/recovery-consistency-report.md
```

### Run Python Unit Test Discovery
```bash
python3 -m unittest discover -s tests/recovery -p "test_*.py" -v
```

### Execute Mode B (Live Operator Checkpoint)
```bash
python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint \
  --accounts-target "accounts_db_drill_recovery" \
  --pricing-target "pricing_db_drill_recovery" \
  --delivery-target "delivery_db_drill_recovery" \
  --comms-target "communications_db_drill_recovery" \
  --keycloak-target "keycloak_db_drill_recovery" \
  --snapshot-cutoff "2026-10-28T05:00:00Z"
```

---

## 4. Evaluated Domain Invariants Matrix

| Invariant ID | Domain | Rule / Contract | Failure Classification |
|---|---|---|---|
| `INV-ACC-01` | Accounts | All profiles have unique non-blank `keycloak_sub` linking to Keycloak users | Corruption / Orphan Identity |
| `INV-ACC-02` | Accounts | Staff profiles have valid roles (`COURIER`, `DISPATCHER`, `ADMIN`) matching realm roles | Role Mismatch |
| `INV-ACC-03` | Accounts | Customer accounts do not hold elevated administrative privileges | Privilege Escalation (P0 Security) |
| `INV-PRC-01` | Pricing | At most one active rate version card per currency in `pricing_rate_versions` | Rate Invariant Violation |
| `INV-PRC-02` | Pricing | Quote interval valid (`expires_at > created_at`), integer minor units add up | Arithmetic / State Corruption |
| `INV-DEL-01` | Delivery | Global single-use quote consumption (`uq_parcels_quote_id`), amount/currency match | Quote Double-Consumption |
| `INV-DEL-02` | Delivery | Completed idempotency records reference valid canonical parcels | Idempotency Residue / Desync |
| `INV-HST-01` | StatusHistory | Zero orphan history records: all events reference canonical parcels | Relational Inconsistency |
| `INV-HST-02` | StatusHistory | Resulting versions strictly monotonic (1..N); matches `parcel.version` | Concurrency Regression |
| `INV-HST-03` | StatusHistory | Status transitions follow lifecycle state machine; terminal states immutable | Illegal Mutation / Reopen |
| `INV-ASG-01` | CourierAssignment | At most one active assignment per parcel; terminal parcels have zero active | Double Assignment Violation |
| `INV-OTB-01` | DeliveryOutbox | Aggregate version uniqueness, mandatory `parcelId` payload, valid leases | Outbox Inconsistency |
| `INV-INB-01` | CommunicationsInbox | Composite PK `(consumer_name, event_id)` prevents duplicate event consumption | Duplicate Processing |
| `INV-JOB-01` | NotificationJobs | Single-intent uniqueness, bounded retries (`attempts < 5` or `EXHAUSTED`) | Unbounded Retry / Leak |
| `INV-JOB-02` | WebhookJobs | Unique subscription-event, bounded retries, terminal status closure | Webhook Inconsistency |
| `INV-POD-01` | PrivateProofs | DELIVERED parcels must have proof metadata, private object refs, no public URLs | Missing Evidence / Privacy Leak |
| `INV-RPO-01` | RpoBoundary | Restored state respects snapshot cutoff timestamp; no post-cutoff leakage | Point-in-Time Breach |

---

## 5. Security & Isolation Safeguards

1. **Non-Destructive Target Suffix:** The runner strictly validates that all database names contain `_drill_recovery`. Connections to `accounts_db`, `delivery_db`, or any production name are immediately aborted.
2. **Read-Only Enforced:** Verification queries are executed in read-only sessions (`SET TRANSACTION READ ONLY`). Zero `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, or `DROP` statements are permitted.
3. **Zero Secrets in Evidence:** All reports, JSON summaries, and logs strip credentials, tokens, Authorization headers, and private keys.
4. **Strict $0 Cost Compliance:** Execution runs entirely on local development environments or CI runners without provisioning external cloud resources.
