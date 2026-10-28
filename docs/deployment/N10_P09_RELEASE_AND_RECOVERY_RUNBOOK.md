# SwiftDrop Platform — Staging Promotion, Rollback & Disaster Recovery Runbook

**Governing Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) — `[N10-P09] Implement gated staging promotion, rollback and database recovery drill`  
**Primary Owner:** Suthankan1 (@Suthankan1 — Platform / Cloud Architect / DevOps Lead)  
**Independent Reviewers:** Harithkeshan (@Harithkeshan), Vibhushan (@Vibhushan-2003)  
**Downstream Application Verification:** Sinthuha Nadesan (@Sinthuha-n — Issue [#168](https://github.com/Suthankan1/swiftdrop/issues/168))  
**Milestone:** November 10, 2026 — Full SwiftDrop Release  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`, `ADR-0011`)  
**Target Cluster:** `swiftdrop-local` (Context: `k3d-swiftdrop-local`, K3s v1.31.2, embedded Traefik ingress)  
**Effective Date:** 2026-10-28  
**Classification:** **CONTROLLED STAGING PROMOTION & ISOLATED RECOVERY RUNBOOK**  

---

## 1. Release Architecture & Promotion Flow

SwiftDrop enforces an immutable, fail-closed release promotion pipeline separating pull request verification from integration branch builds, candidate publishing, staging evaluation, and production release sign-off:

```
[ Developer Feature Branch ]
           │
           ▼
[ Pull Request targeting 'test' ] ──► Required CI Matrix (13 parallel jobs)
           │                                      │
           ▼                                      ▼
[ Independent Code Review ] ◄────────── Fail-closed Gate Pass
  (@Harithkeshan, @Vibhushan-2003)
           │
           ▼
[ Merge into 'test' ]
           │
           ▼
[ Trusted Image Publication to GHCR ] (Packages: write strictly isolated)
           │
           ▼
[ Generate Immutable Release Manifest ] (manifest-schema.json)
           │
           ▼
[ Staging Promotion Workflow / Script ]
  ├── 1. Verify candidate SHA & Git ancestry
  ├── 2. Verify all 8 immutable GHCR digests (sha256:<64-hex>)
  ├── 3. Migration Safety Preflight (Flyway checksums & expand-and-contract)
  ├── 4. Values Profile Validation (values-staging.yaml: createPlaceholderServices: false)
  ├── 5. Helm Staged Upgrade (swiftdrop-apps in swiftdrop-apps)
  ├── 6. Pod Readiness & Digest Verification (verify-rollout.sh)
  └── 7. Ingress & Prometheus Alert Verification
           │
           ├── (On Rollout Failure) ──► Guarded Application Rollback (rollback-app.sh)
           │
           ▼
[ Staging Acceptance Sign-Off ]
           │
           ▼
[ Promotion PR: test -> main (e.g. PR #196) ]
           │
           ▼
[ Production Release Baseline Freeze ]
```

---

## 2. Current GitHub Ruleset State & Plan Capabilities

### 2.1 Audit Findings (Ruleset ID `25813062`)
- **Ruleset Name:** `Protect main and test`
- **Target Branches:** `~DEFAULT_BRANCH` (`main`) and `refs/heads/test`
- **Current Parameters:**
  - `deletion`: Protected
  - `non_fast_forward`: Protected
  - `required_review_thread_resolution`: Enabled
  - `required_approving_review_count`: Currently `0`
  - `required_status_checks`: Currently `[]` (empty)
  - `environments`: `total_count: 0` (native environment protection unconfigured)

### 2.2 Fail-Closed Operator Enforcement
Because the GitHub free/personal repository plan does not natively enforce environment reviewer gates or branch protection ruleset check lists, **SwiftDrop implements fail-closed operator release gates**:
1. **No direct release promotion without explicit operator invocation** from a trusted terminal on `k3d-swiftdrop-local`.
2. **Mandatory named independent maintainer sign-off** from `@Harithkeshan` and `@Vibhushan-2003` on the PR before executing staging promotion or release merge.
3. **Automated merge and automated production push remain disabled**.

---

## 3. Required Branch Protections & Merge Gates

For both `feature -> test` and `test -> main` promotions, the following gates are mandatory:
1. **Pull Request Required:** Direct push to `test` or `main` is strictly prohibited.
2. **Independent Review Required:** At least two non-author maintainer reviews (`@Harithkeshan`, `@Vibhushan-2003`). Self-approval is rejected.
3. **All Status Checks Required:**
   - `SwiftDrop Required CI Gate`
   - `Frontend Quality & Build`
   - `Identity Unit & Configuration Suite`
   - `Java shared libraries`
   - `Java service verification matrix`
   - `Contract validation`
   - `Security scanning`
   - `Container security`
   - `Kubernetes policy`
   - `Terraform static policy`
   - `CI workflow regression`

---

## 4. Environment Approvals

- **Environment Name:** `staging`
- **Enforcement Mode:** Bound via `.github/workflows/staging-promotion.yml`.
- **Operator Execution Gate:** `./platform/kubernetes/release/promote-staging.sh --approval-ref "<PR_OR_TICKET>"` verifies approval input before any Helm command is executed.

---

## 5. Trusted Release Candidate Manifest Creation

When commits land on `test`, CI builds and pushes all 8 runtime images, then generates the canonical release candidate manifest:

```bash
./platform/kubernetes/release/generate-release-manifest.sh \
  --sha "$(git rev-parse HEAD)" \
  --branch "test" \
  --approval-ref "PR-196" \
  --output "build/artifacts/digests/release-candidate-manifest.json"
```

The manifest complies with `platform/kubernetes/release/manifest-schema.json` and records:
- `source_commit_sha`: Exact 40-character commit SHA.
- `approved_base_branch`: `test`.
- `workflow_run_id` and `workflow_run_attempt`.
- `artifact_generation_time`: UTC ISO 8601 timestamp.
- `target_architecture`: `linux/amd64` or `multi-arch`.
- `images`: Array of exactly 8 objects with `image_name`, `image_repository`, `image_digest` (`sha256:<64-hex>`), and `tag_reference`.

---

## 6. Required Component Validation

The release gate enforces that all 8 runtime workloads and supporting contracts are verified before promotion:

| Component | Path | Build / Packaging Artifact |
|---|---|---|
| `frontend` | `frontend/` | React SPA production bundle (`dist/`) |
| `api-gateway` | `services/api-gateway/` | Spring Cloud Gateway JAR |
| `accounts-service` | `services/accounts-service/` | Spring Boot Accounts microservice JAR |
| `pricing-service` | `services/pricing-service/` | Spring Boot Pricing microservice JAR |
| `delivery-service` | `services/delivery-service/` | Spring Boot Delivery microservice JAR |
| `communications-service` | `services/communications-service/` | Spring Boot Communications microservice JAR |
| `graphql-query-service` | `services/graphql-query-service/` | Read-only GraphQL Query API JAR |
| `keycloak` | `platform/identity/` | Hardened Quarkus Keycloak image + realm JSON |
| Protobuf Contracts | `shared/proto-contracts/` | Compiled Java Protobuf stubs |
| GraphQL SDL | `contracts/graphql/schema.graphql` | Composite read-only schema |

---

## 7. Immutable Image Digest Verification

Before staging deployment, `./platform/kubernetes/release/verify-candidate-manifest.sh` validates:
1. Candidate SHA exists in the local Git commit graph.
2. All 8 images have cryptographic digests matching `^sha256:[0-9a-f]{64}$`.
3. No mutable tag (`latest`, `test`) or placeholder (`digest-pending`) is accepted.
4. Exactly 8 unique images match the canonical service names.

---

## 8. Staging Deployment Execution

Promotion to staging executes on `k3d-swiftdrop-local` using `values-staging.yaml`:

```bash
./platform/kubernetes/release/promote-staging.sh \
  --context "k3d-swiftdrop-local" \
  --release "swiftdrop-apps" \
  --namespace "swiftdrop-apps" \
  --approval-ref "PR-196-signoff" \
  --values "platform/kubernetes/helm/swiftdrop/values-staging.yaml"
```

Deployment invariants:
- **Zero Cloud Costs:** Targets local k3d only. No Azure, AKS, or public cloud compute.
- **Zero Placeholders:** `statefulInterfaces.createPlaceholderServices: false` is strictly verified.
- **Timeout Bound:** Rollout must achieve full readiness across all pods within 180 seconds.

---

## 9. Database Migration Safety Preflight

Before any Helm upgrade begins, `./platform/kubernetes/release/migration-preflight.sh` inspects all 5 logical databases:
1. `accounts_db` on `accounts-postgres-0`
2. `pricing_db` on `business-postgres-0`
3. `delivery_db` on `business-postgres-0`
4. `communications_db` on `business-postgres-0`
5. `keycloak_db` on `keycloak-postgres-0`

Checks executed:
- Connectivity using service-unprivileged roles (`*_app`).
- Verification that `flyway_schema_history` contains `0` failed rows (`success = false`).
- PVC storage capacity headroom in `swiftdrop-data`.
- Backward compatibility verification (expand-and-contract):
  - Previous application + current schema: compatible.
  - New application + migrated schema: compatible.
  - No irreversible `DROP COLUMN` without deprecation phase.

---

## 10. Health and Monitoring Checks

Post-rollout verification runs automatically via `./platform/kubernetes/release/verify-rollout.sh`:
- Verifies that deployed pod images contain the exact `sha256:...` digests from the manifest.
- Verifies all replicas are ready (`desired == ready`).
- Queries Prometheus and Alertmanager in `swiftdrop-monitoring` to confirm zero active critical alerts (`DeliveryOutboxRelayFailing`, `CrashLoopBackOff`, etc.).

---

## 11. Failed-Rollout Handling & Controlled Rollback

If a staging rollout fails (pod crash, probe timeout, or missing dependencies):
1. **Promotion Halts Immediately:** The pipeline fails closed.
2. **Schema Compatibility Evaluation:** If schema migrations were additive and backward compatible, application-only rollback is triggered.
3. **If Schema Is Incompatible:** Automatic rollback is **BLOCKED**. Operator escalation is required to deploy a forward-compensating Flyway migration.
4. **Data Protection:** Active database volumes (PVCs) are **never** recreated or deleted.

---

## 12. Application-Only Rollback Procedure

When approved by an operator:

```bash
./platform/kubernetes/release/rollback-app.sh \
  --context "k3d-swiftdrop-local" \
  --release "swiftdrop-apps" \
  --namespace "swiftdrop-apps" \
  --operator-approval "OPERATOR-TICKET-REF"
```

The script:
- Reverts Helm release to the prior revision (`helm rollback`).
- Re-verifies previous container images and readiness probes.
- Confirms database PVCs in `swiftdrop-data` remain untouched.

---

## 13. Isolated PostgreSQL Disaster Recovery

### 13.1 Backup Procedure
Backs up all 5 logical databases to `~/.swiftdrop/backups/`:

```bash
./platform/kubernetes/recovery/backup-postgres.sh \
  --context "k3d-swiftdrop-local" \
  --backup-dir "${HOME}/.swiftdrop/backups" \
  --custodian "@Suthankan1"
```

Produces:
- `accounts_db.dump`, `pricing_db.dump`, `delivery_db.dump`, `communications_db.dump`, `keycloak_db.dump` (custom archive format `pg_dump -F c`).
- Encrypted copies (`*.dump.enc`) using AES-256-CBC.
- `checksums.sha256` and `backup-manifest.json`.

### 13.2 Isolated Restoration Procedure
Restores strictly into isolated databases (`*_drill_recovery`):

```bash
./platform/kubernetes/recovery/restore-isolated.sh \
  --backup-dir "${HOME}/.swiftdrop/backups/<BACKUP_ID>" \
  --target-suffix "_drill_recovery" \
  --keep-target
```

**SAFETY GUARANTEE:** The script rejects any target name matching live operational databases (`accounts_db`, `pricing_db`, etc.). Live data and active PVCs are never modified.

---

## 14. Keycloak Identity Database Recovery

Keycloak runs on PostgreSQL (`keycloak-postgres-0`).
- Identity recovery restores `keycloak_db.dump` into `keycloak_db_drill_recovery`.
- Verification validates that `realm`, `client`, `keycloak_role`, and `user_entity` tables are populated.
- Confirms realm `swiftdrop`, client `swiftdrop-frontend`, client `swiftdrop-gateway`, and staff/customer roles survive.

---

## 15. Private MinIO Proof Storage Recovery

Proof-of-delivery objects reside in private bucket `swiftdrop-pod`.
- **Backup:** `./platform/kubernetes/recovery/backup-minio.sh` archives `/data/swiftdrop-pod` into an encrypted tarball and inventories object content SHA-256 hashes.
- **Restore:** Objects are restored to an isolated test target; content hashes are verified against Delivery DB `proof_of_deliveries` metadata.
- **Privacy Invariant:** Bucket remains strictly private (`ReadOnly` / authenticated access only; no anonymous access).

---

## 16. Kafka, Outbox, and Inbox Consistency

Cross-system checkpointing is captured via:

```bash
./platform/kubernetes/recovery/backup-kafka-checkpoint.sh \
  --context "k3d-swiftdrop-local"
```

Consistency Model:
- **At-least-once async messaging with idempotent consumer deduplication.**
- PostgreSQL, Kafka, and MinIO do not share a distributed two-phase commit.
- Consumer replay safety is guaranteed by:
  - Delivery DB `outbox_events`: status tracking and replay attempt ceilings.
  - Communications DB `consumed_events`: unique event ID deduplication.
  - Idempotency records in Delivery: prevents duplicate parcel creation.

---

## 17. Encrypted Offline Backup Custody

In accordance with strict data protection guidelines:
1. **Named Custodian:** Suthankan Balenthiran (`@Suthankan1`).
2. **Encryption at Rest:** All logical backups are encrypted with AES-256-CBC (`PBKDF2`, 100,000 iterations).
3. **Storage Location:** Outside Git repository tree (`~/.swiftdrop/backups/`).
4. **Git Protection:** `.gitignore` excludes `*.dump`, `*.enc`, and `~/.swiftdrop/`. Zero backup files are committed to Git.
5. **No Paid Storage:** Stored on local secure storage. No AWS S3, Azure Blob, or paid destinations.

---

## 18. RPO and RTO Measurement & Budget Status

Calculated using `./platform/kubernetes/recovery/measure-recovery.sh`:

```text
================================================================================
             SWIFTDROP RPO & RTO RECOVERY MEASUREMENT EVIDENCE                  
================================================================================
Timestamp Log:
  Last Consistent Checkpoint:        2026-10-28T05:00:00Z
  Failure Detected:                  2026-10-28T05:05:00Z
  Recovery Execution Started:        2026-10-28T05:06:00Z
  Database Restore Completed:        2026-10-28T05:08:00Z
  Application Readiness Achieved:    2026-10-28T05:09:30Z
  Business Verification Completed:   2026-10-28T05:10:00Z
--------------------------------------------------------------------------------
OBSERVED RPO: 300 seconds (5m 0s)
  Budget Status: NOT APPROVED (Pending team ADR decision)

OBSERVED RTO: 240 seconds (4m 0s)
  Budget Status: NOT APPROVED (Pending team ADR decision)
--------------------------------------------------------------------------------
NOTE: A successful restore drill with an UNAPPROVED budget does not automatically
satisfy full production release acceptance. Team budget sign-off is required.
================================================================================
```

---

## 19. Downstream P09A Independent Verification

- **Consumer Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) (Owner: `@Sinthuha-n`)
- **Handoff Document:** `docs/handoff/ISSUE_167_TO_168_P09A_HANDOFF.md`
- Generated using:
  ```bash
  ./platform/kubernetes/recovery/p09a-handoff.sh \
    --backup-dir "${HOME}/.swiftdrop/backups/<BACKUP_ID>"
  ```
- **Operator Invariant:** Issue #167 provisions and maintains the isolated restored databases (`*_drill_recovery`) and provides read-only SQL connection templates. `@Sinthuha-n` independently executes application record assertions. Issue #168 must not be closed on infrastructure completion alone.

---

## 20. Strict $0 Local Infrastructure Policy

In strict adherence to [ADR-0011](file:///Users/suthankan/Desktop/swiftdrop/docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md) and Issue [#172](https://github.com/Suthankan1/swiftdrop/issues/172):
- **Hosting Boundary:** Strictly local Docker and k3d (`k3d-swiftdrop-local`).
- **Forbidden Cloud Resources:** Zero Azure VMs, managed disks, public IPs, Azure Load Balancers, AKS nodes, Azure Storage accounts, or Azure Log Analytics.
- **Terraform Policy:** CI enforces `plan-only` and `deny-by-default` cost policies. `terraform apply` is prohibited.
- **Public Cloud Hosting:** Public tunnels and cloud SPAs are rejected ([#176](https://github.com/Suthankan1/swiftdrop/issues/176)).

---

## 21. Known Release Blockers & Status Matrix

| Blocker Item | Governing Issue | Owner | Status | Release Impact |
|---|---|---|---|---|
| Gated Staging Promotion & Recovery Toolset | #167 | @Suthankan1 | **RESOLVED IN THIS PR** | Ready for review |
| Application Consistency Verification | #168 | @Sinthuha-n | PENDING HANDOFF | Blocks production release |
| Native Ruleset Approvals / Environment Gate | #167 audit | Team | WORKAROUND: FAIL-CLOSED OPERATOR GATE | Enforced via checklist |
| Production Identity & External SMTP | #150 / #159 | Identity Team | PENDING | Blocks external email |
| Delivery Merchant Idempotency Defect (E4) | #164 QA audit | Delivery Owner | DOCUMENTED DEFECT | Handed off to Delivery |
| Pricing gRPC Client Timeout Mismatch (D2) | #164 QA audit | Pricing Owner | DOCUMENTED DEFECT | Handed off to Pricing |

---

## 22. Operator Emergency Procedures

1. **Aborting an In-Progress Rollout:**
   Run `./platform/kubernetes/release/rollback-app.sh --operator-approval "emergency-abort"` to return immediately to the previous healthy revision.
2. **PostgreSQL Pod Crash Recovery:**
   PostgreSQL uses Write-Ahead Logging (WAL). In the event of an unclean container kill, the StatefulSet controller will restart the container on the same host PVC; PostgreSQL executes automatic crash recovery without data loss.
3. **Accidental PVC Deletion Protection:**
   The `local-path` provisioner binds storage directly to `/Users/suthankan/.swiftdrop/k3d/storage/`. Take periodic offline directory snapshots via `rsync` before performing any major cluster recreation.

