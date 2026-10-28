# SwiftDrop Application Consistency Handoff (Issue #168 to Operator & Reviewers)

- **Source Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) — `[N10-P09A] Verify application records and durable jobs after operator-run rollback and restore`
- **Parent Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) (PR [#277](https://github.com/Suthankan1/swiftdrop/pull/277))
- **Application Verification Owner:** Sinthuha Nadesan (`@Sinthuha-n`)
- **Platform / Recovery Owner:** Suthankan Balenthiran (`@Suthankan1`)
- **Functional Reviewer:** Harithkeshan (`@Harithkeshan`)
- **Domain Reviewer:** Vibhushan (`@Vibhushan-2003`)
- **Date:** 2026-10-28 (Asia/Colombo)
- **Status:** **APPLICATION VERIFICATION READY FOR OPERATOR CHECKPOINT & INDEPENDENT REVIEW**

---

## 1. Summary of Delivered Capabilities

In accordance with the SwiftDrop ownership matrix, `@Sinthuha-n` has implemented an independent, reproducible application verification suite proving that after `@Suthankan1` executes rollback and restore:

1. **Quote and Booking Provenance:** Restored parcels preserve single-use quote consumption (`uq_parcels_quote_id`), quote snapshot integrity, integer minor units, and LKR currency invariants.
2. **Parcel Version Monotonicity & Lifecycle Finality:** Resulting versions in status history increase strictly monotonically (1..N) with zero gaps; terminal states (`DELIVERED`, `FAILED`) are strictly immutable.
3. **Courier Assignment Invariant:** At most one active courier assignment exists per parcel (`uq_delivery_assignments_active_parcel`). Inactive assignments have recorded `ended_at` timestamps.
4. **Durable Job Consistency:** Bounded retry policies (`attempts < 5`), terminal closure for `SENT` and `SUCCESS` jobs, and lease recovery for notifications and webhooks are verified.
5. **Transactional Outbox & Inbox Deduplication:** Outbox aggregate version sequencing and inbox composite PK `(consumer_name, event_id)` deduplication prevent duplicate side-effects under at-least-once Kafka replay.
6. **Point-in-Time Boundary Auditing:** Evaluates records against `snapshot_cutoff` (`2026-10-28T05:00:00Z`), distinguishing approved RPO data boundaries from data corruption.
7. **Negative Regression Suite:** 17 isolated failure-injection factories verify fail-closed detection of corruption, illegal state transitions, and concurrency violations.

---

## 2. Verification Modes & Operator Instructions

### Mode A — Offline Deterministic Self-Test (Independent of Cluster)
Can be run immediately on any developer machine or CI runner:
```bash
python3 tests/recovery/run_recovery_consistency_verification.py --mode offline
python3 -m unittest discover -s tests/recovery -p "test_*.py" -v
```
*Current Result:* **32/32 unit tests pass; 17/17 baseline invariants pass; 17/17 negative corruption scenarios detected.**

### Mode B — Live Operator Checkpoint Verification
Once `@Suthankan1` provisions the isolated target databases (`*_drill_recovery`) on `k3d-swiftdrop-local`:
```bash
python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint \
  --accounts-target "accounts_db_drill_recovery" \
  --pricing-target "pricing_db_drill_recovery" \
  --delivery-target "delivery_db_drill_recovery" \
  --comms-target "communications_db_drill_recovery" \
  --keycloak-target "keycloak_db_drill_recovery" \
  --candidate-sha "7c0cc6275044d708422c1b1f9e49d577d95a4c88" \
  --backup-set-id "backup-drill-20261028-1145" \
  --snapshot-cutoff "2026-10-28T05:00:00Z"
```

### Safety Guards Enforced:
- The runner strictly requires target database names to contain `_drill_recovery`. Connections to live operational databases (`accounts_db`, `delivery_db`, etc.) are aborted immediately.
- Verification connections are restricted to `READ ONLY` transactions.
- Zero secrets, tokens, or PII are written to reports.

---

## 3. Outstanding Limitations & Action Items

1. **Staging Cluster Target Deployment:** Mode B requires `@Suthankan1` to deploy the restored staging databases on `k3d-swiftdrop-local`.
2. **Formal RPO/RTO Release Budget Approval:** The observed infrastructure RPO (300s) and RTO (240s) remain flagged as **NOT APPROVED** pending team ADR sign-off.
3. **Independent Reviews:** Requesting review and acceptance from `@Harithkeshan` and `@Vibhushan-2003`.
