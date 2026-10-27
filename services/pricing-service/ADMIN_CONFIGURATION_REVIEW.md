# Issue #123 — admin configuration contract and policy review

Refs #123 (N10-WF21). Implementation owner: @pushmitha20. Domain reviewer:
@Harithkeshan; security/integration reviewers: @Vibhushan-2003 and @Sinthuha-n.

**Owner approval reported by assignee — implementation pending independent acceptance.**
On 2026-10-27, @pushmitha20 confirmed in the implementation session that
Harithkeshan had already approved this proposal, after being asked specifically
about its routes, additive schema, disable/coverage/lifecycle rules and old-quote
policy. This records the assignee's confirmation; it does not fabricate a
separate GitHub owner review. Integration base inspected: `609b05b` on `test`.
The Pricing OpenAPI now includes the additive admin contract at version 1.1.0.

**Latest technical verification (2026-10-27): PASS — 187 tests, zero failures,
errors or skips.** This supersedes the earlier Docker-blocked results below:
167 unit/security tests and 20 real PostgreSQL integration tests passed after
the assignee started Docker. Independent owner/security acceptance and the
Platform-owned CI dependency repair remain outstanding.

## Initial evidence and decisions (at the inspected base)

1. `contracts/rest/openapi-pricing.yaml` defines only quote creation/retrieval and
   public active-zone listing. It contains no approved admin commands.
   PR #101 introduced that contract; merged PRs #211, #214 and #220 provide the
   existing domain, quote REST API and gRPC provider respectively.
2. `DEC-PRC-07` in `docs/architecture/RUNTIME_DECISION_REGISTER.md` is labelled
   APPROVED and says deactivated rate versions reject quotes. In contrast, merged
   PR #220 and `docs/handoff/ISSUE_114_PRICING_QUOTE_VERIFICATION_GRPC.md` section
   3 preserve old-version quotes until expiry. `validateQuote` compares the
   request's expected version with the snapshot, without checking current card
   status. A merged implementation does not itself reconcile this conflict.
3. Zone disable reference policy, activation timing, required route coverage and
   exact archive transitions have no reviewed admin contract in the inspected
   files. Existing domain methods alone are not approval of every transition.

The following proposal was approved as reported above and implemented in this
feature. Keep Issue #123 open for independent owner review and rerun evidence.

## Proposed REST extension

Keep public `GET /api/v1/zones` unchanged. Put admin operations under
`/api/v1/admin/pricing`; require a verified Keycloak ADMIN role and nonblank
JWT subject at both the service HTTP boundary and application command boundary.
Do not grant DISPATCHER permission. Use the shared JWT adapter; never infer
identity, roles, tenancy or delegation from caller-supplied headers.

| Method and proposed suffix | Request | Success |
|---|---|---|
| GET `/zones?page=0&size=20` | Page >= 0, size 1..50; includes inactive zones | 200 paged Zone views |
| GET `/zones/{zoneId}` | UUID | 200 Zone view |
| POST `/zones` | ZoneCreate | 201 Zone view with Location |
| PUT `/zones/{zoneId}` | ZoneUpdate | 200 Zone view |
| POST `/zones/{zoneId}/disable` | ExpectedRevision | 200 Zone view |
| GET `/rate-versions?page=0&size=20` | Same page bounds, includes historical cards | 200 paged Version views |
| GET `/rate-versions/{versionId}/rules?page=0&size=20` | UUID, same page bounds | 200 paged Rule views |
| POST `/rate-versions` | DraftCreate | 201 Version view with Location |
| POST `/rate-versions/{versionId}/rules` | RuleCreate | 201 Rule view with Location |
| PUT `/rate-versions/{versionId}/rules/{ruleId}` | RuleUpdate | 200 Rule view |
| POST `/rate-versions/{versionId}/activate` | Activate | 200 Version view |
| POST `/rate-versions/{versionId}/archive` | ExpectedRevision | 200 Version view |

All bodies reject unknown properties. All response views are DTOs, never JPA
entities. Paged envelopes contain `items`, `page`, `size`, `totalElements`.
Sort zones by code, versions by creation time then ID, rules by route/category/
minimum weight then ID for stable pagination.

### Proposed request and response fields

- ZoneCreate: `zoneCode` (1..32, `^[A-Z][A-Z0-9_]*$`), `name` (nonblank,
  <=128), `description` (nonnull, <=512), `standardTransitHours` (positive int).
  IDs/timestamps are server generated; create active zones. ZoneUpdate contains
  only name, description, transit hours and `expectedRevision` (nonnegative long).
  Code and ID remain immutable; there is no deletion or reactivation command.
- Zone view: ID, zoneCode, name, description, standardTransitHours, active,
  revision, createdAt, updatedAt (UTC).
- DraftCreate: `versionCode` (1..64, nonblank), `currency` (exactly `LKR`),
  `effectiveFrom` (UTC instant), optional `effectiveTo` (strictly after from),
  optional `description` (<=512). Status is server assigned DRAFT.
- Version view: ID, versionCode, currency, status, effectiveFrom/effectiveTo,
  description, revision, createdAt, activatedAt, archivedAt.
- RuleCreate: `expectedRevision` of parent version, pickupZoneCode,
  dropoffZoneCode, sizeCategory (SMALL/MEDIUM/LARGE/OVERSIZED), minWeightKg,
  maxWeightKg, baseFeeMinorUnits, perKgSurchargeMinorUnits, weightThresholdKg.
  RuleUpdate has the same fields. The path binds rule ID and parent version ID;
  requests cannot change either ID. Rule view adds ID and rateVersionId, omits
  expectedRevision and includes the resulting parent revision.
- Weights: exact decimal with at most two fractional digits, min >=0.01,
  max <=30.00 and max >=min. Threshold >=0.00 and <=30.00. Fees: nonnegative
  signed 64-bit integers. Reuse `RateRule`, `Money` and the existing calculation
  strategy; test maximum surcharge/total for overflow before accepting a rule.
  Weight-band endpoints are inclusive as in existing `matchesWeight`: [0.01,1]
  overlaps [1,2], while [0.01,1] and [1.01,2] do not.
- ExpectedRevision: `{ "expectedRevision": <nonnegative long> }`.
- Activate: expectedRevision plus `expectedActiveVersionId` (UUID or explicit
  null). This binds a command to the active card the administrator reviewed;
  after another publish it conflicts rather than immediately replacing that
  newly published card. All modifying responses return the resulting revision.

### Proposed status and errors

Reuse RFC 7807 Problem Details with `code`, `timestamp` and request instance.
400: malformed JSON/UUID, blank/overlength fields, invalid enum/precision/bounds.
401: missing/invalid/expired/untrusted JWT. 403: wrong role, missing privileged
actor identity or account/delegation policy denial. 404: missing path resource
or rule outside the requested parent. 409: duplicate code/band, stale revision,
published-card mutation, invalid lifecycle transition, active-card compare
failure. 422: overlapping bands, inactive/nonexistent referenced zones,
referenced-zone disable, invalid effective interval or incomplete rate card.
Preserve the current quote endpoint's existing error semantics.

## Proposed lifecycle and quote semantics

- Edit rules only in DRAFT. Activation is DRAFT -> ACTIVE, atomically replacing
  the prior ACTIVE with SUPERSEDED. No independent supersede command that leaves
  domestic quotes without an active card; no reactivation of published cards.
- Publish only when `effectiveFrom <= serverNow` and effectiveTo is absent or
  `serverNow < effectiveTo`. No scheduled activation or scheduler in this slice.
  Set the old card's effectiveTo to the publish instant, preserving its rules,
  identity, currency and audit history. Reject publication when that instant
  would violate the old card's strict effective interval.
- New quotes use ACTIVE cards only within `[effectiveFrom,effectiveTo)`; an
  expired active card fails closed. Existing query service currently selects
  by status alone and needs a focused clock-based extension after approval.
- Recommended conflict resolution: honour a previously issued quote until
  its original expiry after supersession, retaining the merged #114 behavior.
  Correct DEC-PRC-07 explicitly if owners approve; otherwise leave activation
  blocked and coordinate a separately reviewed #114 policy correction. Do not
  change gRPC or Delivery in this feature to hide the disagreement.
- Archive only SUPERSEDED -> ARCHIVED. Do not archive ACTIVE or DRAFT in this
  slice. Archive is retention/status only; never delete historical cards/rules
  or mutate quotes. Accepted Delivery fee snapshots remain Delivery-owned.
- Disable only if no ACTIVE card rule references the zone and no quote with
  `expiresAt > serverNow` references it. Return 422 with a stable reference
  conflict code. SUPERSEDED/ARCHIVED rules and expired quotes remain readable.
  Activation revalidates all referenced zones as active.
- Publish a nonempty matrix with nonoverlapping bands. Proposed coverage gate:
  for each currently active ordered zone pair and each size category, require
  contiguous 0.01kg steps covering 0.01kg through that category's existing
  maximum. This policy needs explicit acceptance; partial-route publication
  must not be assumed permissible or forbidden without owner review.

## Proposed persistence, transactions and audit

Reuse ZoneEntity, RateVersionEntity, RateRuleEntity and existing repositories.
Do not alter V1–V3. Proposed additive V4 only in Pricing:

- Add `revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)` to zones and
  rate versions; map JPA `@Version`. Increment parent revision on rule changes.
- Add one Pricing-owned singleton configuration-lock row. Lock it in a common
  order before command locks; quote issuance takes a compatible shared lock
  so zone-disable checks and rate activation cannot race quote persistence.
  Retain the existing partial unique ACTIVE/currency index as a database guard.
- Persist `pricing_admin_audit` in the same transaction: UUID audit ID, bounded
  verified actor subject, operation, resource type/ID, optional rule ID,
  prior/new status and revision, UTC occurredAt, server-generated request UUID.
  Metadata updates include bounded before/after changed fields; record no
  bearer tokens, credentials, customer data or free-form request body.
- Serialize draft rule writes by locking the parent. Verify expected revision
  after locking, validate inclusive overlap against sibling rules, and flush
  before returning. Translate optimistic/unique conflicts to 409 without SQL
  text. Revalidate the full card and all zone references under the same locks
  at publication. Audit failure rolls the command back.
- Consider a database rule-mutation guard for published cards and a PostgreSQL
  overlap trigger under the parent lock. No new extension/persistence framework
  is required; the application and database checks must use identical bounds.

Recovery: additive migration preserves existing rows; stop old writers before
rolling the application back because old code does not take the new locks.
Do not roll back by deleting audit, quotes, cards or zones. A migration failure
must roll back transactionally. Platform owns execution/backups/restore.

## Implementation and verification

Keep changes within Pricing application/domain/persistence/API/security/tests,
one additive migration, the canonical Pricing OpenAPI extension, and this
review record. Reuse existing security adapter without changing its contract.

Added AdminPricingController/DTOs, AdminPricingService, a transactional audit
repository and command validation. Tests cover endpoint roles and verbs with the
real shared filter chain and controlled JWT decoder (including invalid/expired
tokens, header spoofing, missing subject and untrusted authorities). Existing
MockMvc tests using `WithMockUser` alone do not prove verified JWT authorization.

Validation includes focused domain/service tests; PostgreSQL Flyway, overlap/immutability,
rollback, stale revision, racing publishes and racing zone-disable/quote tests;
V1 quote -> V2 publish -> old snapshot retained/new quote V2; explicit selected
supersession policy; then Maven Wrapper clean test and clean verify.

No Gateway/deployment/GraphQL/Kafka/frontend/Delivery/gRPC implementation changes.
Gateway routing for proposed paths is a Platform handoff, not silently included.

## Review checklist

- [x] Assignee reports owner approval of REST paths, DTOs and error semantics.
- [x] Assignee reports approval of honour-until-expiry resolution of DEC-PRC-07.
- [x] Assignee reports approval of disable, activation-time, coverage and archive policies.
- [x] Assignee reports approval of additive revision/lock/audit schema and recovery approach.
- [ ] Independent security review confirms ADMIN and verified-subject policy.
- [ ] Independent implementation acceptance follows a subsequent verified diff.

## Implementation details and limits

`DraftRateRuleValidator` is a pure domain utility used by admin commands. It checks DRAFT
mutability, parent/currency binding, existing numeric column bounds, inclusive
sibling-band overlaps and exact monetary overflow. It reuses RateVersion,
RateRule and Money. AdminPricingService enforces zone existence, full publication
coverage, verified actor identity, expected revisions and lifecycle rules under
the Pricing configuration lock. PESSIMISTIC_FORCE_INCREMENT advances the draft
parent revision on rule writes. The existing partial unique ACTIVE/currency
index remains the final single-active database guard. V4 adds revision columns,
the singleton lock, append-only application audit records and published-rule /
overlap / lifecycle database guards. No existing migration is rewritten.

`DraftRateRuleValidatorTest` covers those guards. `HistoricalQuoteIsolationTest`
uses the real pricing strategy, calculation service and quote service with
mocked storage. It simulates selection of V1 then V2 and checks different new
amounts and unchanged historical snapshot/identity/expiry. It is not evidence
of a publish endpoint or SQL transaction isolation. AdminPricingPostgresIT covers
real admin commands, publication, snapshots, database guards, audit rollback,
competing publishes and shared quote/exclusive command locks when Docker runs.

Security reuses the existing shared stateless resource-server chain, with
service-local ADMIN route matchers and a second actor/role check in the command
service. ADMIN does not gain unrelated customer quote access. No additional
account-scope delegation permission exists in the inspected Pricing contract;
DISPATCHER and forged role/tenant headers grant no command access. A dedicated
HTTP decoder validates the public API audience without changing the existing
gRPC machine-token decoder or provider wiring.

New quote issuance takes the shared configuration lock and rejects active cards
outside `[effectiveFrom,effectiveTo)`. Historical quote reads and the existing
gRPC provider continue returning the immutable snapshot. Delivery booking and
its database remain unchanged.

Successful commands persist before/after resource values, prior/new revision
and status, actor subject, UTC time and server-generated request UUID. HTTP
responses expose that UUID in X-Request-ID; inbound identifiers are not trusted.
Audit failure rolls back the modification in the same transaction.

Operational limit of the approved disable/coverage combination: an established
zone still referenced by the ACTIVE matrix cannot be disabled. New unused zones
can be disabled. Retiring a currently priced zone needs a separately reviewed
retirement protocol; this feature does not bypass the reference guard.

The configuration lock serializes admin writes and briefly blocks new quote
issuance during publication. This favors consistency for the bounded baseline.
Testcontainers tests are explicitly skipped if Docker's Linux engine is absent;
that is not proof of Flyway or concurrency success.

## Local verification — 2026-10-27

Environment: Windows / Java 21.0.10 / Pricing Maven Wrapper. Existing shared
dependencies were built and installed locally without source changes.

| Command | Actual result |
|---|---|
| Pricing wrapper `-B -ntp -f ../../shared/proto-contracts/pom.xml install` | PASS; 28 tests, no failures/errors/skips |
| Pricing wrapper `-B -ntp -f ../../shared/security-adapter/pom.xml install` | PASS; existing security dependency installed |
| Pricing wrapper `-B -ntp clean verify` | PASS; 166 unit/security tests, zero failures/errors/skips |
| Failsafe in the same verify | 19 PostgreSQL tests skipped: 7 AdminPricingPostgresIT + 12 existing PricingPersistencePostgresIT |
| Contract validator `--mode=all --check-compatibility --base-ref origin/test` | All four gates PASS; no breaking changes |
| `git diff --check` | PASS |

Initial attempts encountered Maven-cache permissions/missing local shared
artifacts, then a duplicate-setter compilation error and HTTP test-decoder bean
collision. Those were corrected before the successful final verification.
No PostgreSQL migration/concurrency pass is claimed locally: Docker Desktop's
Linux engine pipe was absent. Platform/reviewer should rerun clean verify with
Docker available before accepting the migration and transaction invariants.

GitHub Projects fields are not exposed by the connected GitHub tools. The
browser fallback was stopped by Computer Use because it could not determine
Chrome's URL confidently enough to enforce policy. The associated board and
starting/final board values remain unverified; use the Project actually linked
to #123 for the manual In Review update, keeping the issue open.

## Follow-up verification — 2026-10-27

PR [#236](https://github.com/Suthankan1/swiftdrop/pull/236) remains open and
mergeable. [Suthankan1 approved the PR](https://github.com/Suthankan1/swiftdrop/pull/236#pullrequestreview-5662774521).
Requested domain/security reviewers Harithkeshan, Sinthuha-n and Vibhushan-2003
had not submitted reviews at this check; the Platform approval is not a claim
of their independent acceptance.

Contract Checks [run 40457171839](https://github.com/Suthankan1/swiftdrop/actions/runs/40457171839)
passed. Dependency and secret scanning jobs passed. General CI
[run 40457094938](https://github.com/Suthankan1/swiftdrop/actions/runs/40457094938)
failed before creating any jobs (jobs API count: 0).

Read-only local validation identified an undefined dependency in the unchanged
`.github/workflows/ci.yml`: `publish-runtime-images.needs` includes
`terraform-quality-gates` at line 425, but the defined job is `terraform-policy`
at line 212. YAML syntax alone passes; the job dependency graph does not. This
is a likely explanation for the workflow startup failure, not a quoted GitHub
diagnostic. Platform should reconcile that job reference in its own change.
No workflow, deployment or infrastructure file was modified by this feature.

Docker's Linux engine remained stopped on recheck. A one-time authorization to
start Docker solely for the existing Pricing Testcontainers tests was requested
from the assignee; no engine start or container orchestration was performed
while that authorization was pending.

Added `concurrentDraftRuleUpdatesRejectLostUpdateAndAuditOnlyWinner` to the
existing AdminPricingPostgresIT: concurrent edits using the same parent
revision must yield one success, one conflict, one revision increment and one
update audit entry. No production code, contract or migration changed in this
follow-up.

Actual follow-up commands:

- Pricing wrapper `-B -ntp -DskipTests test-compile`: PASS (compilation only).
- Pricing wrapper `-B -ntp -Dit.test=AdminPricingPostgresIT
  failsafe:integration-test failsafe:verify`: BUILD SUCCESS; all **8 admin
  integration tests skipped** because Docker remained unavailable.
- `git diff --check`: PASS.

The earlier 166 unit/security passes belong to implementation commit
`a760b0492f9c0a52b04ededc8e8e6835f18fa1e2`. The new concurrent-edit assertion
has compiled but has not executed against PostgreSQL. The full PostgreSQL suite
now consists of 8 admin tests plus the 12 unchanged persistence tests.

## Docker-backed verification completed — 2026-10-27

The assignee reported Docker started. Docker Engine 29.7.2 was reachable using
the authorized test process. Only disposable Testcontainers resources were
started; no Platform Compose stack or deployment was executed.

The first run passed 166 unit/security tests but could not retrieve the
PostgreSQL image: Testcontainers aborted the image pull after 30 seconds without
progress. A direct Docker Hub pull also stalled. [Docker's verified public image
catalog](https://gallery.ecr.aws/docker/) lists the PostgreSQL mirror under `public.ecr.aws/docker/library/postgres`.
Pulled `16-alpine` there and cached it locally as `postgres:16-alpine`, without
changing repository configuration or the test image declarations. Image ID:
`sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab`.
The running database reported PostgreSQL **16.15**.

With the image available, all four Flyway migrations applied successfully and
19 of 20 integration tests passed. The historical quote test exposed a real
precision mismatch: issuance returned nanosecond timestamps, while PostgreSQL
TIMESTAMPTZ stored microseconds. QuoteApplicationService now truncates issuance
time to microseconds before computing expiry, persistence and response mapping.
A focused unit regression covers a nanosecond clock. The integration test keeps
its exact full-snapshot equality assertions; they were not weakened.

Final command: Pricing wrapper **`-B -ntp clean verify`**.

| Suite | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Unit/security, including the new timestamp regression | 167 | 0 | 0 | 0 |
| AdminPricingPostgresIT | 8 | 0 | 0 | 0 |
| PricingPersistencePostgresIT | 12 | 0 | 0 | 0 |
| **Total** | **187** | **0** | **0** | **0** |

BUILD SUCCESS, completed at **11:50:28 Asia/Colombo on 2026-10-27**.
Both PostgreSQL suites applied V1–V4 successfully. Evidence includes concurrent
draft edits, competing publication, shared/exclusive locks, database overlap and
published-rule guards, audit rollback and old quote validation after supersession
and archive. Existing migrations were not changed.

Historical blocked/failed attempts above remain as provenance. The earlier
Docker/PostgreSQL execution blocker is resolved; GitHub CI's undefined job
dependency and manual linked-Project update remain separate Platform/UI blockers.
