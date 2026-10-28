# Issue #138 — admin zones and versioned pricing editor

Owner: @pushmitha20. Domain review: @Harithkeshan. Gateway review: @Suthankan1.
Branch: `feat/n10-wf22-admin-zone-pricing-editor`; PR target: `test`.
Inspected starting base `23dc3fd`; fast-forwarded to `d2e2b49` before final
validation. There were no merge conflicts or discarded changes.

## Implemented slice

`/admin/pricing` is linked from Administration and protected by the existing
ADMIN route guard plus a feature-level role/session check. Existing merchant,
user and staff links remain. The workspace provides:

- Server pages of 20 zones, cards and rules, manual refresh/retry, loading,
  empty/error states, IDs, status text, revisions and effective metadata.
- Zone create, metadata edit and soft disable. Code is immutable on edit.
  Confirmation identifies the zone and explains active-rule/unexpired-quote
  blockers; only the provider can authorize the transition.
- New unpublished LKR drafts with explicit UTC interval, optional end and
  description. The backend has no draft metadata-update or clone operation;
  neither is invented. Add/edit rules only in DRAFT, with kg bands, category
  limits, threshold, base fee and per-kg surcharge.
- Exact signed-int64 minor units: integer tokens are preserved before JSON
  parsing, validated as decimal strings, serialized as integer JSON values and
  formatted using BigInt. No floating-point monetary calculation or conversion.
- Canonical draft confirmation compares equivalent UTC instants, allowing
  provider timestamp precision normalization such as `.000Z` to `Z`.
- Immediate DRAFT activation, reviewed active-card supersession and
  SUPERSEDED-only archive. The provider validates full route/category coverage,
  effective time, references, overlaps and monetary overflow.
- Accessible labeled fields, inline errors, first-error focus, responsive
  scrollable semantic tables and shared confirmation with keyboard focus trap,
  inert background, Cancel/Escape and focus restoration. A stale confirmation
  disables Confirm while leaving Cancel available.

## Authoritative operations consumed

Source: `contracts/rest/openapi-pricing.yaml` 1.1.0;
`AdminPricingController`, `AdminPricingDtos`, `AdminPricingService` and
`PricingProblemHandler`; merged #123, PRs #236 and #237. No public contract,
schema, identity posture or Pricing business rule changes.

Every path below is relative to `/api/v1/admin/pricing` through the configured
Gateway. POST/PUT bodies are explicitly projected; arbitrary caller fields and
role headers are never forwarded. Successful creates return 201; other reads
and mutations return 200 with the canonical DTO. The service's X-Request-ID is
not used as a concurrency token or displayed as success evidence.

| Method | Path | Inputs / concurrency |
| --- | --- | --- |
| GET | `/zones?page&size` | UI size 20; provider maximum 50 |
| GET | `/zones/{id}` | UUID; conflict/reconciliation read |
| POST | `/zones` | code, name, description, positive integer transit hours |
| PUT | `/zones/{id}` | name, description, transit hours, expectedRevision |
| POST | `/zones/{id}/disable` | expectedRevision |
| GET | `/rate-versions?page&size` | size 20; active review uses size 50 |
| POST | `/rate-versions` | versionCode, LKR, effectiveFrom, nullable effectiveTo, description |
| GET | `/rate-versions/{id}/rules?page&size` | size 20; total supplies selected card's rule count |
| POST | `/rate-versions/{id}/rules` | parent expectedRevision, route/category/weights/minor-unit fees |
| PUT | `/rate-versions/{id}/rules/{ruleId}` | same; existing rule UUID |
| POST | `/rate-versions/{id}/activate` | expectedRevision and nullable expectedActiveVersionId |
| POST | `/rate-versions/{id}/archive` | expectedRevision; SUPERSEDED only |

There is no active filter or rate-version detail GET. Review reads at most ten
50-card pages and rejects histories over 500, duplicate IDs, contradictory
ACTIVE cards and incomplete scans. No unbounded fetch is used. Current ACTIVE
display and draft mutation controls fail closed if this canonical review is
unavailable. Larger histories need a separately reviewed provider improvement.

## Security and recovery

The existing in-memory token getter supplies Authorization Bearer. Restricted
`profileFetch` is not expanded. Requests omit cookies, forbid redirects, disable
caching, consume cancellation and use a 15-second HTTP deadline. No tokens enter
UI state, storage, URLs, logs or fixtures in production.

Query keys include private scope, verified subject and mounted session ID.
Teardown, role loss, expiry or account change cancels/removes feature reads and
aborts commands; late responses cannot populate a new session. 401 delegates to
shared session-expiry handling, without command replay. 403 removes privileged
content. Backend signature/issuer/audience/expiry/ADMIN checks remain authoritative.

All writes require confirmation and a synchronous in-flight lock, with no retry
or optimistic success. Only matched, validated canonical responses confirm
persistence. Reads refresh only the feature's query prefix. A stale revision
never silently adopts a new revision: inputs remain, latest zone/rule values
are displayed, the administrator selects the reviewed revision and reconfirms.
Publication also compares the reviewed ACTIVE ID; a race requires a new review.

400/422 validation, 401 expiry, 403 denial, 404 missing, 409/412 conflicts,
429 and provider failures use fixed safe text, with specific messages only for
allowlisted Pricing error codes. Raw ProblemDetail messages are dropped.
Network/5xx/malformed successful write responses mean **unknown outcome**.
Further writes pause until successful safe refreshes and deliberate review;
the administrator must inspect the affected resource, including other pages,
before deciding which changes remain necessary. Commands never replay.

The necessary Gateway application route sends the specific Pricing admin prefix
to Pricing at order 5 before Accounts' generic admin route at order 10. Existing
ADMIN edge policy remains unchanged. Signed-JWT routing tests check read/command
forwarding, original body/bearer preservation and all four non-admin roles plus
anonymous denial. Platform retains ownership and must review this small change.

## Validation and environment

Windows, Java 21.0.10, Node 24.21.0, pnpm 12.11.2. Frontend commands use
`npm exec --yes --package=node@24.21.0 -- corepack pnpm ...`; no pins,
dependencies or lockfiles changed. Build/test logs and outputs remain ignored.

| Command | Actual result |
| --- | --- |
| `corepack pnpm install --frozen-lockfile` | PASS |
| `corepack pnpm typecheck` | PASS |
| `corepack pnpm lint` | PASS |
| `corepack pnpm test --run src/features/admin` | PASS, 319 tests / 7 files on starting base; includes 112 new Pricing tests |
| `corepack pnpm test --run --maxWorkers=2 src/features/admin/pages/MerchantReviewPage.test.tsx` | PASS, 64 / 1 on synchronized base |
| `corepack pnpm test --run --maxWorkers=2` | PASS, 913 tests / 53 files, zero failures/skips; 113 new Pricing tests |
| `corepack pnpm build` | PASS; existing >500 kB chunk warning |
| Gateway `./mvnw.cmd -B -ntp -Dtest=GatewayBusinessRoutesIntegrationTest test` | PASS, 23 tests |
| Gateway `./mvnw.cmd -B -ntp test` | PASS, 87 tests, zero failures/errors/skips |
| Pricing `./mvnw.cmd -B -ntp verify` | BUILD SUCCESS; 167 unit/security passes, 20 PostgreSQL ITs SKIPPED |
| `.local/n10-wf21-venv/Scripts/python.exe -X utf8 tests/contracts/validate_contracts.py --mode all --check-compatibility --base-ref origin/test` | All four gates PASS on synchronized base |
| `git diff --check` | PASS |

Earlier setup failures were resolved without changing tools/dependencies:
sandbox cache writes required normal escalation; system Python lacked PyYAML,
so the existing local contract-test venv and UTF-8 mode were used. Initial
unrestricted Vitest execution exhausted local workers. Another full run
overlapped the upstream fast-forward and mixed old/new merchant modules; it was
stopped and the isolated current suite passed. The final full run uses a stable
tree with two workers and no skipped tests or altered timeout assertions.

Frontend tests use isolated synthetic providers/fetch responses. Gateway uses
real signed-JWT HTTP requests with mock upstreams. Pricing's passing unit/security
tests do not demonstrate PostgreSQL or a browser/provider end-to-end flow.

## Historical pricing acceptance — still incomplete

The UI never reads or writes booking/receipt snapshots or computes accepted
prices. Published rules have no edit action. Every publication explanation
distinguishes new eligible quotes from issued quotes and accepted obligations.

The existing real PostgreSQL regression
`AdminPricingPostgresIT.publishRetainsOldQuoteAndRulesAndNewQuotesUseNewCard`
creates a 35000-minor-unit quote, publishes V2 at 45000, checks the new quote
and exact old quote, archives V1 and validates its unexpired quote through the
Pricing provider. It was **skipped in this run**, as were the other 19 ITs:
`docker info` cannot connect to `dockerDesktopLinuxEngine`. Previous #123
PostgreSQL evidence is historical provider evidence, not this PR's execution.

No controlled authenticated Keycloak/Gateway/Pricing/Delivery dataset was
available or started here. No real baseline quote, V2 publication/new quote,
accepted booking or receipt was created/read in this session. No privileged
identity, deployment, credential, infrastructure or business record was created.
Real ADMIN/non-admin provider flow, booking/receipt snapshot preservation,
browser desktop/mobile visual verification and independent acceptance remain
UNVERIFIED. No end-to-end checkbox is marked complete.

## Changed files

- `frontend/src/app/router/routes.tsx`
- `frontend/src/features/admin/README.md`
- `frontend/src/features/admin/api/pricingAdmin.ts`
- `frontend/src/features/admin/api/pricingAdmin.test.ts`
- `frontend/src/features/admin/components/PricingForm.tsx`
- `frontend/src/features/admin/components/StaffConfirmation.tsx`
- `frontend/src/features/admin/pages/AdminPage.tsx`
- `frontend/src/features/admin/pages/AdminPage.test.tsx`
- `frontend/src/features/admin/pages/PricingAdminPage.tsx`
- `frontend/src/features/admin/pages/PricingAdminPage.test.tsx`
- `frontend/src/styles/global.css`
- `frontend/src/test/pricingAdminFixture.ts`
- `services/api-gateway/src/main/java/com/swiftdrop/apigateway/config/GatewayRoutesConfig.java`
- `services/api-gateway/src/test/java/com/swiftdrop/apigateway/GatewayBusinessRoutesIntegrationTest.java`
- `docs/ai/ISSUE_138_HANDOFF.md`

## Next small task (TASK_TEMPLATE scope)

**Task:** #138 controlled historical-price acceptance. **Owners:** @Harithkeshan
with @Suthankan1 providing the authorized runtime. **Allowed changes:** evidence
for #138; application fixes only if a reproducible feature defect is found.
**Contract:** Pricing OpenAPI 1.1 and Delivery's existing booking/receipt APIs.
**Acceptance:** rerun the 20 PostgreSQL ITs, then issue V1, accept a booking and
read its receipt, publish a complete test V2, issue a correctly versioned quote,
and re-read V1/booking/receipt to prove unchanged amounts/currency/version. Include
direct wrong-role and stale-revision attempts and desktop/mobile keyboard QA.
Keep tokens and personal data out of evidence. **Out of scope:** schema/policy
changes, deployment edits and merging this PR. Independent domain and Platform
review remain required.
