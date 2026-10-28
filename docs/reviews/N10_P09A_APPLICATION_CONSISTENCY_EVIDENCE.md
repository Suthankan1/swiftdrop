# N10-P09A Application Recovery Consistency Evidence Register

- **Governing Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) — `[N10-P09A] Verify application records and durable jobs after operator-run rollback and restore`
- **Parent Recovery Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) — `[N10-P09] Implement gated staging promotion, rollback and database recovery drill`
- **Parent Merge Commit:** `ff29a89b1a1b1b5b22256a634b37c44dad32be4d` (PR [#277](https://github.com/Suthankan1/swiftdrop/pull/277))
- **Application Verification Owner:** Sinthuha Nadesan (`@Sinthuha-n` — Platform QA / Consistency Engineer)
- **Platform & Recovery Operator:** Suthankan Balenthiran (`@Suthankan1` — Platform / Cloud Architect)
- **Reviewers:** Harithkeshan (`@Harithkeshan`), Vibhushan (`@Vibhushan-2003`)
- **Date:** 2026-10-28 (Asia/Colombo)
- **Context:** `k3d-swiftdrop-local`
- **Branch:** `tests/n10-p09a-recovery-consistency`
- **Status:** **APPLICATION VERIFICATION IMPLEMENTED & PASSING IN MODE A — MODE B BLOCKED AWAITING OPERATOR CHECKPOINT CLUSTER TARGETS**

---

## 1. Executive Summary & Evidence Reconciliation

This register establishes the application-level consistency verification baseline for SwiftDrop release candidate promotions, rollbacks, and disaster recovery drills.

### Reconciliation of Revisions & Checkpoint Identifiers
| Artifact / Identifier | Recorded in P09 Handoff | Recorded in P09 Drill Evidence | Reconciled Active Verification Value |
|---|---|---|---|
| **Candidate Commit SHA** | `7c0cc6275044d708422c1b1f9e49d577d95a4c88` | (Refs #167 drill execution) | `7c0cc6275044d708422c1b1f9e49d577d95a4c88` |
| **Backup Set ID** | `PENDING_CAPTURE` (Template) | `backup-drill-20261028-1145` | `backup-drill-20261028-1145` |
| **Snapshot Cutoff Timestamp** | N/A | `2026-10-28T05:00:00Z` | `2026-10-28T05:00:00Z` |
| **Observed Infrastructure RPO** | Measured | 300 seconds (5m 0s) | 300 seconds (**NOT APPROVED**) |
| **Observed Infrastructure RTO** | Measured | 240 seconds (4m 0s) | 240 seconds (**NOT APPROVED**) |
| **Budget Approval Status** | Pending | Pending team ADR sign-off | **PENDING TEAM ADR APPROVAL** |

*Note on Git Branch Prefix:* Following standard Git ref locking rules and the existing repository precedent (`tests/n10-qa07-integrated-recovery`, `tests/n10-qa-02-customer-courier-e2e`), the branch is named `tests/n10-p09a-recovery-consistency` because the base branch `test` occupies the ref prefix on local and remote remotes.

---

## 2. Restored Runtime Images & Digests

The verified recovery boundary enforces pinned immutable SHA-256 digests across all 8 microservices:

| Workload | Repository | Immutable SHA-256 Digest |
|---|---|---|
| `frontend` | `ghcr.io/suthankan1/swiftdrop-frontend` | `sha256:778ce75418fcf9eca3135afec8bd58b77be747d3f38534aca6bbbf6b9aeac1dc` |
| `api-gateway` | `ghcr.io/suthankan1/swiftdrop-api-gateway` | `sha256:b42c2dcd199aaedbe4a5cb8c34198a02fe19de452c0a1b9e2c53ed3decf060a5` |
| `accounts-service` | `ghcr.io/suthankan1/swiftdrop-accounts` | `sha256:450c7671e2e6d4796b13a9226da42ad6429c094a0c8e65523955b46b9e70182c` |
| `pricing-service` | `ghcr.io/suthankan1/swiftdrop-pricing` | `sha256:b75182da6660d8c56f451948580bdbd17262fc2970c9215c61bdc3874fc7e9aa` |
| `delivery-service` | `ghcr.io/suthankan1/swiftdrop-delivery` | `sha256:f40955a3072e3b0d6246a0503530ccd39aac47b6a96c4c44082512eaef224763` |
| `communications-service` | `ghcr.io/suthankan1/swiftdrop-communications` | `sha256:418e65e64ca0af6b2ed7e8e8359e03f5264f6c2bf4a29bb7409f6f52cbc4cac6` |
| `graphql-query-service` | `ghcr.io/suthankan1/swiftdrop-graphql` | `sha256:268a8e7bb919974126b33c047065069955df9f36c790b5b561001710986f2043` |
| `keycloak` | `ghcr.io/suthankan1/swiftdrop-keycloak` | `sha256:75effccfc0bf90a2947bbc9dee322ad7a644ea26477e655a5f0e9d5895cd8501` |

---

## 3. Post-Recovery Verification Matrix (Actual Results)

```
================================================================================
          SWIFTDROP APPLICATION CONSISTENCY INVARIANT EVALUATION MATRIX
================================================================================
```

| Invariant ID | Domain | Assertion / Contract | Evaluated | Status | Violations | Actual Result Detail |
|---|---|---|:---:|:---:|:---:|---|
| `INV-ACC-01` | Accounts | Unique non-blank Keycloak subjects linking to Keycloak users | 7 | **PASS** | 0 | All profiles map to Keycloak user entities |
| `INV-ACC-02` | Accounts | Staff profile roles (`COURIER`, `DISPATCHER`, `ADMIN`) match realm | 4 | **PASS** | 0 | Role consistency verified |
| `INV-ACC-03` | Accounts | Zero privilege escalation: customers never possess admin roles | 7 | **PASS** | 0 | Privilege confinement verified |
| `INV-PRC-01` | Pricing | At most one active rate version per currency | 1 | **PASS** | 0 | Rate version uniqueness verified |
| `INV-PRC-02` | Pricing | Quote interval, integer money arithmetic, and ownership parity | 4 | **PASS** | 0 | Minor units add up; expires_at > created_at |
| `INV-DEL-01` | Delivery | Global single-use quote consumption (`uq_parcels_quote_id`) | 2 | **PASS** | 0 | Zero double-consumption; amount/curr match |
| `INV-DEL-02` | Delivery | Completed idempotency records link to canonical parcels | 2 | **PASS** | 0 | Zero orphan booking residue |
| `INV-HST-01` | StatusHistory | Zero orphan history records | 8 | **PASS** | 0 | All events reference canonical parcels |
| `INV-HST-02` | StatusHistory | Monotonic resulting_version (1..N) and parcel version parity | 2 | **PASS** | 0 | Versions strictly sequential without gaps |
| `INV-HST-03` | StatusHistory | Status transitions follow lifecycle state machine; terminal finality | 8 | **PASS** | 0 | Valid transitions; DELIVERED immutable |
| `INV-ASG-01` | CourierAssignment | At most one active assignment per parcel (`uq_delivery_assignments_active_parcel`) | 2 | **PASS** | 0 | Single active assignment enforced |
| `INV-OTB-01` | DeliveryOutbox | Aggregate version uniqueness, mandatory `parcelId`, lease checks | 8 | **PASS** | 0 | Outbox envelope & sequencing valid |
| `INV-INB-01` | CommunicationsInbox | Composite PK `(consumer_name, event_id)` deduplication | 3 | **PASS** | 0 | Duplicate events suppressed idempotently |
| `INV-JOB-01` | NotificationJobs | Single-intent uniqueness, bounded retries (`attempts < 5`), SENT finality | 3 | **PASS** | 0 | Terminal SENT has no retry; bounded retries |
| `INV-JOB-02` | WebhookJobs | Unique subscription-event, bounded retries, terminal status closure | 1 | **PASS** | 0 | Terminal SUCCESS has no future retry |
| `INV-POD-01` | PrivateProofs | Delivered parcels hold valid proof metadata and private object keys | 1 | **PASS** | 0 | Private `pod/...` key; zero public URLs |
| `INV-RPO-01` | RpoBoundary | Restored state respects snapshot cutoff timestamp (2026-10-28T05:00:00Z) | 10 | **PASS** | 0 | No post-checkpoint leakage in restored targets |

---

## 4. Negative Regression Suite Results

All 17 negative failure-injection scenarios were executed against the verification engine. Every scenario was successfully detected, rejected, and flagged:

| ID | Scenario Injected | Expected Domain | Observed Result | Evidence |
|---|---|---|:---:|---|
| `NF01` | Missing canonical parcel referenced by history | StatusHistory | **REJECTED (PASS)** | Flagged missing parcel foreign reference |
| `NF02` | Invalid parcel version sequence (jump 1 -> 4) | StatusHistory | **REJECTED (PASS)** | Flagged non-monotonic resulting_version |
| `NF03` | Conflicting active courier assignments | CourierAssignment | **REJECTED (PASS)** | Flagged violation of `uq_delivery_assignments_active_parcel` |
| `NF04` | Duplicate logical outbox event version | DeliveryOutbox | **REJECTED (PASS)** | Flagged violation of `uq_outbox_aggregate_version` |
| `NF05` | Outbox event missing aggregate parcel | DeliveryOutbox | **REJECTED (PASS)** | Flagged orphan outbox event |
| `NF06` | Duplicate inbox event application | CommunicationsInbox | **REJECTED (PASS)** | Flagged composite PK duplicate in consumed_events |
| `NF07` | Missing required envelope field in outbox payload | DeliveryOutbox | **REJECTED (PASS)** | Flagged missing `parcelId` payload field |
| `NF08` | Negative retry attempt count (`attempts = -1`) | NotificationJobs | **REJECTED (PASS)** | Flagged invalid negative attempt counter |
| `NF09` | Unbounded retries without transitioning to EXHAUSTED | NotificationJobs | **REJECTED (PASS)** | Flagged attempts >= 5 without EXHAUSTED status |
| `NF10` | Completed job incorrectly eligible for retry | NotificationJobs | **REJECTED (PASS)** | Flagged active next_attempt_at on SENT job |
| `NF11` | Duplicate notification intent | NotificationJobs | **REJECTED (PASS)** | Flagged duplicate `(event_id, recipient, channel)` |
| `NF12` | DELIVERED parcel missing proof of delivery | PrivateProofs | **REJECTED (PASS)** | Flagged DELIVERED parcel without proof record |
| `NF13` | Quote/booking amount mismatch | Pricing | **REJECTED (PASS)** | Flagged parcel amount deviating from quote snapshot |
| `NF14` | Post-checkpoint record leakage (created after cutoff) | RpoBoundary | **REJECTED (PASS)** | Flagged record with created_at > snapshot cutoff |
| `NF15` | Illegal transition out of terminal DELIVERED | StatusHistory | **REJECTED (PASS)** | Flagged illegal mutation of terminal state |
| `NF16` | Duplicate quote consumption across two parcels | Delivery | **REJECTED (PASS)** | Flagged duplicate quote consumption |
| `NF17` | Active courier assignment on terminal parcel | CourierAssignment | **REJECTED (PASS)** | Flagged active assignment on DELIVERED parcel |

---

## 5. Execution Modes & Command Log

| Command | Exit Code | Observed Result |
|---|:---:|---|
| `python3 tests/recovery/run_recovery_consistency_verification.py --mode offline` | `0` | **PASS** — 17/17 baseline invariants pass, 17/17 negative scenarios detected and rejected. |
| `python3 -m unittest discover -s tests/recovery -p "test_*.py" -v` | `0` | **PASS** — 32/32 unit tests pass in 0.017s. |
| `python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint` | `2` | **BLOCKED** — Fails closed with `BLOCKED — OPERATOR CHECKPOINT NOT VERIFIED` when operator targets are not configured. |
| `python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint --accounts-target accounts_db` | `1` | **REJECTED** — Fails closed with security violation because target lacks `_drill_recovery` suffix. |

---

## 6. Outstanding Blockers & Independent Review Handoff

1. **Operator Checkpoint Staging Cluster:** Execution of Mode B against live staging databases awaits `@Suthankan1` restoring the isolated PostgreSQL pods on `k3d-swiftdrop-local`.
2. **Formal RPO/RTO Budget Sign-Off:** The measured RPO (300s) and RTO (240s) remain explicitly flagged as **NOT APPROVED** pending team ADR approval.
3. **Independent Acceptance:** Awaiting review from functional reviewer `@Harithkeshan`, domain reviewer `@Vibhushan-2003`, and recovery operator `@Suthankan1`.
