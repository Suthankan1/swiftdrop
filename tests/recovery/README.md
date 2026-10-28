# SwiftDrop Application Recovery Consistency Verification Suite

- **Governing Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) — `[N10-P09A] Verify application records and durable jobs after operator-run rollback and restore`
- **Parent Recovery Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) (PR [#277](https://github.com/Suthankan1/swiftdrop/pull/277))
- **Owner:** Sinthuha Nadesan (`@Sinthuha-n` — Application Verification / Platform QA)
- **Operator Owner:** Suthankan Balenthiran (`@Suthankan1` — Platform / Cloud Architect)
- **Target Context:** `k3d-swiftdrop-local`

---

## 1. Purpose & Ownership Boundary

This test suite implements independent application-level consistency assertions following operator-performed rollback and isolated database restore drills.

### Responsibility Separation
- **`@Suthankan1` (Operator):** Exclusively owns Kubernetes orchestration, Helm, container rollback execution, logical PostgreSQL backup/restore, Keycloak/MinIO restoration, and infrastructure RPO/RTO timings.
- **`@Sinthuha-n` (Application):** Independently owns deterministic application fixtures, business invariants, quote/booking provenance, parcel version monotonicity, courier assignment uniqueness, transactional outbox/inbox deduplication, durable notification/webhook retry state, and sanitized recovery evidence.

---

## 2. Verification Modes

### Mode A — Offline Deterministic Verification (`--mode offline`)
- Independent verification runnable without a live Kubernetes cluster or active databases.
- Evaluates complete synthetic domain snapshots against SwiftDrop v1.3 architecture contracts.
- Audits point-in-time recovery boundaries (`snapshot_cutoff`).
- Executes a full regression suite across 17 negative failure-injection scenarios (corruptions, races, leaks).
- Produces structured JSON and markdown evidence reports.

### Mode B — Operator-Checkpoint Verification (`--mode operator-checkpoint`)
- Evaluates live restored databases provided by `@Suthankan1`.
- Enforces strict safety rules:
  * Targets **must** have the `_drill_recovery` suffix (`accounts_db_drill_recovery`, `pricing_db_drill_recovery`, `delivery_db_drill_recovery`, `communications_db_drill_recovery`, `keycloak_db_drill_recovery`).
  * Enforces read-only sessions (`SET TRANSACTION READ ONLY`).
  * Never modifies, drops, or truncates operational data.
  * If targets or prerequisites are unconfirmed, fails closed with `BLOCKED — OPERATOR CHECKPOINT NOT VERIFIED` (exit code 2).

---

## 3. Canonical Execution Commands

```bash
# Run Mode A (Offline Deterministic Verification)
python3 tests/recovery/run_recovery_consistency_verification.py --mode offline

# Run Unit Test Discovery
python3 -m unittest discover -s tests/recovery -p "test_*.py" -v

# Run Mode B against Operator-Provided Restored Targets
python3 tests/recovery/run_recovery_consistency_verification.py \
  --mode operator-checkpoint \
  --accounts-target "accounts_db_drill_recovery" \
  --pricing-target "pricing_db_drill_recovery" \
  --delivery-target "delivery_db_drill_recovery" \
  --comms-target "communications_db_drill_recovery" \
  --keycloak-target "keycloak_db_drill_recovery"
```

---

## 4. Evaluated Invariant Matrix

| Invariant ID | Domain | Rule / Contract | Enforcement |
|---|---|---|---|
| `INV-ACC-01` | Accounts | Unique non-blank Keycloak subject; profile-user mapping | Rejects orphan or blank subjects |
| `INV-ACC-02` | Accounts | Staff profile role & status consistency | Validates against Keycloak realm roles |
| `INV-ACC-03` | Accounts | Zero privilege escalation | Rejects admin roles on customer subjects |
| `INV-PRC-01` | Pricing | At most one active rate version per currency | Single active rate card invariant |
| `INV-PRC-02` | Pricing | Quote interval, integer money arithmetic, ownership | `expires_at > created_at`, minor units sum |
| `INV-DEL-01` | Delivery | Single-use quote consumption and quote snapshot integrity | `uq_parcels_quote_id`, amount/currency match |
| `INV-DEL-02` | Delivery | Idempotency record linkage | Completed records link to canonical parcels |
| `INV-HST-01` | StatusHistory | Zero orphan history records | Every history entry references valid parcel |
| `INV-HST-02` | StatusHistory | Version monotonicity (1..N) and projection parity | Rejects version jumps/regressions |
| `INV-HST-03` | StatusHistory | Lifecycle state machine and terminal finality | Rejects mutations out of `DELIVERED`/`FAILED` |
| `INV-ASG-01` | CourierAssignment | At most one active assignment per parcel | Enforces `uq_delivery_assignments_active_parcel` |
| `INV-OTB-01` | DeliveryOutbox | Unique aggregate version, envelope validation, leases | `uq_outbox_aggregate_version`, `parcelId` payload |
| `INV-INB-01` | CommunicationsInbox | Composite PK deduplication and suppression | `(consumer_name, event_id)` uniqueness |
| `INV-JOB-01` | NotificationJobs | Single-intent binding, bounded retries, terminal state | `attempts < 5` or `EXHAUSTED`, SENT has no retry |
| `INV-JOB-02` | WebhookJobs | Unique subscription-event, bounded retries, terminal closure | Terminal jobs have no future retry |
| `INV-POD-01` | PrivateProofs | Private proof metadata and object checksum | Rejects public URLs, requires proof for DELIVERED |
| `INV-RPO-01` | RpoBoundary | Records respect snapshot cutoff boundary | Rejects post-cutoff leakage in restored state |

---

## 5. Negative Corruption Scenarios

The suite includes 17 isolated failure-injection factories verifying that any application anomaly is detected:
1. `NF01`: Missing canonical parcel referenced by history
2. `NF02`: Invalid parcel version sequence
3. `NF03`: Conflicting active courier assignments
4. `NF04`: Duplicate logical outbox event
5. `NF05`: Outbox event with missing aggregate parcel
6. `NF06`: Duplicate inbox event application
7. `NF07`: Missing required envelope field in outbox payload
8. `NF08`: Negative retry attempt count
9. `NF09`: Unbounded retries without transitioning to EXHAUSTED
10. `NF10`: Completed job incorrectly eligible for retry
11. `NF11`: Duplicate notification intent
12. `NF12`: DELIVERED parcel missing proof of delivery
13. `NF13`: Quote/booking amount mismatch
14. `NF14`: Post-checkpoint record leakage
15. `NF15`: Mutation out of terminal DELIVERED status
16. `NF16`: Duplicate quote consumption across two parcels
17. `NF17`: Active courier assignment on terminal parcel
