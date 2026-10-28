# SwiftDrop Recovery Handoff: Isolated State for Issue #168 (P09A)

- **Source Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) — `[N10-P09] Implement gated staging promotion, rollback and database recovery drill`
- **Consumer Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) — `[N10-P09A] Verify application record and durable-job consistency after rollback and restore`
- **Platform / Recovery Owner:** @Suthankan1
- **Application Verification Owner:** @Sinthuha-n
- **Candidate Commit SHA:** `7c0cc6275044d708422c1b1f9e49d577d95a4c88`
- **Backup Set ID:** `PENDING_CAPTURE`
- **Offline Backup Custodian:** `@Suthankan1`
- **Generated:** 2026-10-28T06:20:53.614225+00:00
- **Target Context:** `k3d-swiftdrop-local`

---

## 1. Operator Boundary & Responsibility Model

In strict accordance with the SwiftDrop ownership matrix:
- **Issue #167 (@Suthankan1):** Owns cluster orchestration, persistent storage claims, logical backups, encryption at rest, isolated database provisioning, Keycloak/MinIO restoration, and infrastructure-level readiness.
- **Issue #168 (@Sinthuha-n):** Independently owns application-level business consistency assertions (parcel versions, courier assignment uniqueness, quote/booking provenance, outbox/inbox idempotency, and durable notification/webhook retry state).

---

## 2. Isolated Target Databases & Endpoints

All databases have been restored strictly into isolated target databases. Active operational databases (`accounts_db`, `pricing_db`, `delivery_db`, `communications_db`, `keycloak_db`) and persistent volumes remain 100% untouched.

| Domain Service | Isolated Target Database | Host Pod (Namespace: `swiftdrop-data`) | Unprivileged App User | Canonical Check Relation |
|---|---|---|---|---|
| Accounts | `accounts_db_drill_recovery` | `accounts-postgres-0` | `accounts_app` | `account_profiles` |
| Pricing | `pricing_db_drill_recovery` | `business-postgres-0` | `pricing_app` | `pricing_zones` |
| Delivery | `delivery_db_drill_recovery` | `business-postgres-0` | `delivery_app` | `parcels` |
| Communications | `communications_db_drill_recovery` | `business-postgres-0` | `communications_app` | `notifications` |
| Keycloak | `keycloak_db_drill_recovery` | `keycloak-postgres-0` | `keycloak` | `keycloak_role` |

### Connection Command Templates for P09A:
```bash
# Connect to isolated Delivery database:
kubectl --context k3d-swiftdrop-local exec -it -n swiftdrop-data business-postgres-0 -- \
  psql -U delivery_app -d delivery_db_drill_recovery

# Connect to isolated Communications database:
kubectl --context k3d-swiftdrop-local exec -it -n swiftdrop-data business-postgres-0 -- \
  psql -U communications_app -d communications_db_drill_recovery
```

---

## 3. Restored Runtime Images & Digests

The restored staging environment runs the exact approved release candidate image digests:

| Workload | Repository | Immutable Digest |
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

## 4. Key Application Verification Checkpoints for P09A

Please run independent domain assertions against the isolated databases:
1. **Parcel Monotonicity:** Confirm parcel versions and status histories in `delivery_db_drill_recovery.status_history`.
2. **Assignment Invariant:** Ensure zero double-active courier assignments in `delivery_assignments`.
3. **Outbox Replay Safety:** Verify that replaying `outbox_events` does not produce duplicate external delivery events.
4. **Communications Inbox Idempotency:** Confirm `consumed_events` correctly suppresses duplicate event IDs.
5. **Keycloak Accounts:** Confirm customer, courier, and staff identities survive in `keycloak_db_drill_recovery.user_entity`.
6. **MinIO Private Proof Storage:** Confirm bucket `swiftdrop-pod` objects resolve to matching Delivery DB `proof_of_deliveries` records.

---

## 5. RPO and RTO Measurement Handoff

- **Observed RPO:** Measured between last consistent checkpoint and failure simulation boundary.
- **Observed RTO:** Measured between recovery start and verified application readiness.
- **Status:** **NOT APPROVED** — Formal release budgets await team ADR approval.

Please append your independent verification results and sign-off directly to Issue #168.
