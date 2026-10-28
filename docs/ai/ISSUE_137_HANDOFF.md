# Issue #137 implementation handoff — 2026-10-28

Owner: @pushmitha20. Branch: `feat/n10-wf20-admin-user-staff-management`.
Target: `test`. Inspected target SHA: `45f0a35cced45bfa4dcde54446712f41f0a7b5f6`.

## Delivered scope and contract decisions

The existing ADMIN workspace now provides real paged user/staff reads, approved
staff filters, immutable-UUID detail, safe identity audit projections, controlled
existing-identity provisioning, suspension, synchronization and audit recovery.
The [feature README](../../frontend/src/features/admin/README.md#user-and-staff-management--issue-137)
lists all eight integrated operations and security decisions. No backend,
authentication architecture, public contract, schema or dependency was changed.
Activate/deactivate remain proposed under PR #238 and are unavailable. User
search/detail/status/role and staff names are absent from current provider DTOs.

## Exact changed paths

- `frontend/README.md`
- `frontend/src/app/router/routes.tsx`
- `frontend/src/features/admin/pages/AdminPage.tsx`
- `frontend/src/features/admin/pages/AdminPage.test.tsx`
- `frontend/src/features/admin/pages/UserStaffPage.tsx`
- `frontend/src/features/admin/pages/UserStaffPage.test.tsx`
- `frontend/src/features/admin/api/userStaff.ts`
- `frontend/src/features/admin/api/userStaff.test.ts`
- `frontend/src/features/admin/hooks/useAdminManagement.ts`
- `frontend/src/features/admin/components/StaffConfirmation.tsx`
- `frontend/src/test/adminManagementFixture.ts`
- `frontend/src/styles/global.css`
- `frontend/src/features/admin/README.md`
- `docs/ai/ISSUE_137_HANDOFF.md`

## Actual local validation

Windows PowerShell; final checks use existing temporary Node **24.21.0** and
Corepack pnpm **12.11.2**. Default host Node is 24.16.0. Global `corepack enable`
failed with Program Files EPERM; `corepack pnpm` works without global shim edits.
Vitest/Vite required child-process access outside the sandbox.

- PASS frozen-lockfile dependency installation; no manifest/lockfile changes.
- PASS `corepack pnpm typecheck` and `corepack pnpm lint`; zero errors/warnings.
- PASS `corepack pnpm build`; Vite warns of a main bundle above 500 kB. No build
  limit was relaxed or unrelated splitting refactor introduced.
- PASS `corepack pnpm audit --audit-level=high`: no known vulnerabilities found.
- PASS `<temporary-python> -X utf8 tests/contracts/validate_contracts.py
  --check-compatibility --base-ref=origin/test`: all four REST/GraphQL/Kafka/
  Protobuf gates and compatibility checks passed; no contract artifact changes.
  System Python initially lacked PyYAML; an isolated temporary environment supplied
  PyYAML/jsonschema/graphql-core. `-X utf8` resolved the Windows output encoding
  failure without editing the validator.
- First full default-worker run: 732 passed/1 failed. One obsolete Admin landing
  assertion called staff administration future work. Updated it to verify the new
  links while retaining existing denial/self-provisioning assertions.
- Second full default-worker run: 729 passed/4 failed in 51 files, with timeout/
  delayed-render failures in Merchant Webhooks (two), Customer Quote (one) and
  staff cross-session (one). Those files and timeout limits are unchanged. A
  lower-concurrency full rerun passed without modifying those tests, supporting
  host resource contention as the cause.
- PASS `corepack pnpm test --run --maxWorkers=2`: **734 tests in 51 files**,
  zero failures/skips, 152.42 seconds. This includes **135 new Issue #137 tests**
  and the updated existing Admin navigation regression.

The new tests cover malformed/partial responses, paging/filtering, exact request
paths/methods/payloads, error normalization, pending/error/recovery outcomes,
confirmation focus, duplicate submission, provisioning, direct role denials,
manipulated inputs, role loss, expiry and late results across identity changes.
Mocks do not demonstrate real backend authorization or end-to-end integration.

## Initial acceptance and CI evidence (before publication)

No feature push, PR or GitHub Actions run has occurred. The task and repository
policy require authorization before pushing. Intended draft PR targets `test`,
with title `feat(admin): implement user and staff management screens (#137)`.
Intended reviewers: @Harithkeshan (Accounts), @Suthankan1 (security/Gateway) and the
frontend steward; reviews have not been requested.

Remote frontend, backend/shared-library, identity, Terraform/Kubernetes policy,
container/Compose, secret/dependency scanning, contract, workflow regression and
aggregate CI gates have not run for this feature SHA. Gitleaks/Trivy are absent
locally. No green remote checks, deployment or independent approval are claimed.

Computer Use found Chrome but stopped because it could not determine the current
URL confidently enough to enforce policy. Browser automation was stopped. Visual
desktop/tablet/mobile and live browser acceptance remain unverified. The local
frontend dev server was stopped; no screenshots are committed.

No real authorized Keycloak → Gateway → Accounts → PostgreSQL workflow or
controlled-identity backend denial was exercised. Gateway routes and independent
ADMIN rules exist in source. Actual provisioning partial-failure recovery and
current lifecycle policy still need provider/security owner acceptance. No
identity or deployment configuration was changed.

## Git and next bounded task

The starting merchant-review branch was clean and preserved. This branch was
created from updated `test`; final fetch plus `git merge origin/test` reported
`Already up to date.` No conflicts, history rewrites, pushes, shared-branch merges
or deployments occurred. Build artifacts remain ignored.

Next task using [TASK_TEMPLATE.md](TASK_TEMPLATE.md): @Harithkeshan with @Suthankan1
verifies one controlled-identity slice through the existing Gateway: paged staff/
detail/audit, confirmed suspension, 503 partial identity failure plus audit
recovery, and a direct non-admin rejection. Record the feature SHA/environment.
Frontend steward verifies responsive layout. Do not add activate/deactivate
without explicit contract-owner approval.

Refs #137.

## CI remediation follow-up — PR #266

The owner authorized publication and CI repair. PR #266 targets `test`; the
published implementation head was `d7a45b8f81dea2d1af256e2f2fff9604a89403e4`.
[CI run 40621532505](https://github.com/Suthankan1/swiftdrop/actions/runs/40621532505)
passed Frontend Quality & Build, all Java/shared-library tests, contracts,
identity, dependency/secret scanning and policy checks. Six image scans failed
on inherited runtime dependencies; the required gate correctly remained closed.

| Image scan | Reported dependency findings |
| --- | --- |
| Accounts, Pricing | Guava 33.2.1-android, CVE-2026-102554 |
| Delivery, Communications | Guava 33.2.1-android plus Snappy 1.1.10.7, CVE-2026-108106/CVE-2026-93451 |
| GraphQL | direct Guava plus GraphQL Java's shaded Guava 32.1.2-jre, CVE-2026-102554 |
| Keycloak | vendor-bundled Guava 33.6.0-jre, CVE-2026-102554 |

The repair explicitly manages Guava **33.7.2-jre** in the POMs of Accounts,
Pricing, Delivery, Communications, GraphQL, shared/proto-contracts and
shared/security-adapter. Delivery/Communications also manage Snappy **1.1.10.9**.
These match the dependency fixes already present in PR #265, without importing
that PR's unrelated observability code or optional Micrometer dependency.
Service-level management matters: a consuming service can otherwise select the
older gRPC/protobuf transitive version despite a patched shared-library build.

Sources: [Guava maintainer advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94),
[Snappy 1.1.10.9 release](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.9).
No scan, severity, exit code, branch rule, CVE exclusion or workflow was changed.
Public APIs, schemas, identity policy and business code are unchanged.

### Vendor blockers at the first correction (superseded below)

Maven dependency management cannot replace relocated classes inside GraphQL
Java's own JAR. Inspection of the actual cached `graphql-java-25.0.jar` found
`META-INF/maven/com.google.guava/guava/pom.properties` at 32.1.2-jre and
`graphql/com/google/common/collect/MapMakerInternalMap` classes. Adding a patched
ordinary Guava JAR does not remediate those classes. The current
[GraphQL Java build](https://github.com/graphql-java/graphql-java/blob/v26.1/build.gradle)
still relocates/shades Guava; a version upgrade alone has not been verified as a
fix. No shaded class or metadata was stripped to hide the finding.

The [latest official Keycloak release](https://github.com/keycloak/keycloak/releases/latest)
was still **26.8.1** when checked on 2026-10-28. Its flagged Guava is inside the
vendor image. This repair does not substitute an unverified image, mutate the
vendor distribution, change identity configuration or invent a patched release.

@Suthankan1 (Platform/security) must select a verified patched upstream artifact
or approve and own a separately scoped reproducible vendor rebuild, with source/
dependency provenance, Keycloak login/reconciliation regressions, GraphQL API/
authorization regressions and image-scan evidence. The two vendor scans and
required gate must remain failing until that remediation is verified; no blanket
exception is requested or applied.

### Follow-up validation

Local Java **21.0.10**, existing Maven **3.10.0** distribution. The Docker Linux
daemon is not running, so local container scans and PostgreSQL Testcontainers
verification cannot be claimed. Existing unit suites and repackaged executable
JARs are checked; the pushed revision's CI supplies image/integration evidence.

PASS existing Maven `-B -ntp clean install` for the two shared modules and
`-B -ntp clean package` for the five affected services:

| Module | Tests | Failures / errors / skips |
| --- | ---: | --- |
| shared/proto-contracts | 28 | 0 / 0 / 0 |
| shared/security-adapter | 40 | 0 / 0 / 0 |
| accounts-service | 311 | 0 / 0 / 0 |
| communications-service | 287 | 0 / 0 / 0 |
| delivery-service | 459 | 0 / 0 / 0 |
| pricing-service | 167 | 0 / 0 / 0 |
| graphql-query-service | 189 | 0 / 0 / 0 |
| **Total** | **1481** | **0 / 0 / 0** |

All five service JARs were repackaged successfully. ZIP inspection confirms
`BOOT-INF/lib/guava-33.7.2-jre.jar` as the only ordinary Guava JAR in each,
and Snappy 1.1.10.9 in both Kafka-consuming services. The packaged GraphQL JAR
still contains the separate shaded Guava 32.1.2-jre metadata/classes described
above. This is an explicitly unresolved vendor finding, not a successful scan.

PASS contract compatibility validator (all four protocols) and `git diff --check`.
No frontend source changed in this follow-up; the original CI frontend job passed
all **734 tests in 51 files**, lint, typecheck and production build. Maven package
runs do not execute Failsafe `*IT` suites, and no local Docker/Trivy pass is claimed.

## Vendor remediation integration — 2026-10-28

The target branch subsequently merged PR #267 at `e94544c`, including security
commit `742486a`. Its [CI run 40624117168](https://github.com/Suthankan1/swiftdrop/actions/runs/40624117168)
passed all eight image scans, all Java suites and the required gate. This feature
integrates that merged target rather than creating another vendor patch.
The single GraphQL parent-POM conflict was resolved with the target's complete
reactor configuration, retaining Guava 33.7.2-jre. Backend, shared-library,
identity and workflow files now exactly match `origin/test` at `e94544c`.

The merged repair rebuilds the official GraphQL Java 25.0 sources with fixed
Guava 33.7.2-jre, verifies the source SHA-256 and retains the upstream package
relocations and license. Application tests execute against that rebuilt engine;
the original executable-JAR path is preserved. The Keycloak Dockerfile replaces
the actual vulnerable JAR with checksum-pinned Guava 33.7.2-jre and rebuilds
Quarkus metadata, preserving its fast-jar classpath filename. Realm settings,
JWT/role policies, public contracts and scan gates are unchanged. No vulnerable
bytecode or metadata is hidden, and no CVE exclusion is introduced.

Local integration checks: container-definition tests **13 passed**; workflow
tests **19 passed, 1 skipped** (optional local actionlint availability); all four
contract validation/compatibility gates passed against `origin/test`.
GraphQL Maven `-B -ntp clean verify` passed all three reactor modules and all
**189 application tests**, with zero failures, errors or skips. Executable-JAR
inspection confirmed the rebuilt engine contains Guava **33.7.2-jre** metadata,
relocated `MapMakerInternalMap` bytecode and the upstream license.
`git diff origin/test --check` passed. The upstream QA document has an existing
extra blank line at EOF; it is preserved verbatim rather than edited here.

The earlier vendor-blocker text records the state at `87daee6`; it is superseded
by this integration. Docker Linux remains stopped locally, so image validation
comes from GitHub Actions rather than a claimed local scan. The new feature-head
CI must still pass before claiming this PR is green. Live user/staff workflow
and responsive browser acceptance remain the next bounded owner review task
described above; they are independent of this CI repair.
