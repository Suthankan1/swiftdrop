# Merchant module implementation and verification — 21 November 2026

The user approved headless Playwright acceptance after the computer-use connection failed. The current Supabase runtime and final browser evidence are being verified on 21 November.

## Revision and ownership

- Original checkout: `test` at `248a702`; its uncommitted design work and wrapper changes were preserved.
- Fetched integration baseline: `cdf69aef90860647aab7619d3fa8f344a7adabba` (`origin/test`).
- Isolated worktree: `.worktrees/merchant-verification`, branch `fix/merchant-verification`.
- Verified implementation commits include `5306038` (credential concurrency), `3bbf9c1` (approved timestamp contract), `bee7e07` (Merchant UI and regressions), `8e76e77` (conflict-safe retries), `5c0e738` (dialog focus and feedback), and `308e21c` (Supabase startup readiness).
- On 15 November, fetched and reviewed teammate courier enhancements through `origin/test` at `d524a7d`, then integrated them in merge `9434099`. The merged frontend suite and Delivery PostgreSQL tests pass.
- A fresh fetch on 21 November found zero unmerged commits from `origin/test`.
- Merchant stewards remain Eric / Thuvarahan. Accounts and Delivery changes require their existing owners' review; ownership has not transferred.
- The user explicitly approved the additive `updatedAt` REST field. No database, GraphQL, Protobuf, Kafka, authentication-policy or service-boundary change was made.

## Findings checklist

| Item | Status | Evidence and correction |
|---|---|---|
| Friend's order auto-load and merchant-ID resolution (`c4977fe`) | Already fixed and verified in part | Existing booking client still uses tenant-bound merchant intake. Auto-load was present, but its effect cancelled itself after setting loading state. It was replaced, rather than duplicated. |
| Friend's session restoration/logout work (`e3df904`) | Already fixed and verified by automated tests | Preserved; full SPA suite passes. New live browser sign-in/refresh/logout evidence is blocked by computer-use availability. |
| Friend's navigation/receipt layout work (`9e53811`, `3f0c33b`) | Already fixed and verified by build/tests | Kept as the integration baseline. Subsequently integrated the original checkout's newer public design into the review branch, retaining workspace authorization, Merchant behavior and receipt controls. Desktop/mobile public-page screenshots were verified; authenticated Merchant visual acceptance remains pending. |
| A: intermittent loading | Fixed and verified for request lifecycle/recovery; live cause unverified | Replaced self-cancelling manual effect with subject-scoped TanStack queries; cancellation, request deduplication and two retries for timeout/unavailable reads. Authentication/validation failures never retry. Accounts errors are distinct from unapproved account states. Original intermittent backend failure was not reproduced in a browser. |
| B: update timestamp | Fixed and verified | Full history used `updatedAt ?? createdAt`, while REST omitted `updatedAt`. Approved optional response field now maps the authoritative parcel value. No booking-time substitution; unknown values are explicit. Recent-to-full-history regression passes. |
| C: delivered parcel awaiting assignment | Fixed and verified | REST exposes active assignment only. An absent active assignment is no evidence of pending dispatch for a terminal parcel. Such rows now say assignment information is unavailable; active authorized references remain visible. No historical courier name is invented. |
| D: merchant mobile overflow | Fixed in code; browser verification Blocked | Navigation wraps, flex children can shrink, and action buttons wrap. Wide tables retain their own horizontal scroll area. No global overflow hiding was introduced. |
| Order details receipt | Newly discovered → Fixed and verified by typecheck/tests | Replaced the empty async handler with actual authorized download, success cleanup and visible failure feedback. Browser download completion remains unverified. |
| Booking without price confirmation | Newly discovered → Fixed and verified | First submission requests a quote only. User sees the total and expiry, then confirms. Cancel retains input and creates nothing. Uncertain retries retain the quote/key and prevent editing into a different request. |
| Credential limit race | Newly discovered → Fixed and verified | Tenant row lock precedes active-key count and insert. A real PostgreSQL test starts four competing requests with two keys already issued: exactly one succeeds, final count three. |
| CSV validation and retry | Fixed and verified in component/unit scope | Malformed quoting, duplicate headers/references, incorrect column counts and invalid numeric/text inputs are rejected. Existing limits remain 500 rows / 1,000,000 bytes. Identical rows retain idempotency keys when reselected within this mounted session. |
| Dialog focus and clipboard feedback | Fixed and verified in component scope | Focus entry, Tab containment, Escape and focus restoration cover key/webhook dialogs. Copy failures produce visible feedback. Webhook log retry reuses the cancellable loader. |
| Supabase startup false failure | Newly discovered → Fixed and verified | Whole-stack `--wait` treated a successful one-shot initializer exit as failure. Startup now launches initializers, then waits for applications. Runtime wait exited 0 and all 29 platform tests passed. |
| Browser API calls rejected from Vite | Newly discovered → Fixed and verified | Actual browser evidence exposed a Gateway CORS mismatch. Compose declared both development origins for routing, while its security-origin setting and template supplied only port 3000. The default/template and ignored local public setting now include both declared origins. Live preflight returns 200 for ports 3000 and 5173, and 403 for an unapproved origin. Authenticated order details subsequently loaded. Commit `52afde9`. |
| New Keycloak container scan findings | Newly discovered → Vendor patch built and runtime version verified | CI on 21 November found five fixable OpenJDK findings in the existing Keycloak image. Commit `defab35` updates vendor Java 21 packages in a separate UBI build stage, retaining Keycloak 26.8.1, the original RPM database, Guava fix and runtime UID 1000. Built runtime reports OpenJDK 21.0.13.1+2. |

## Control inventory

“Passed” below describes automated behavior in the stated test scope. It does not substitute for keyboard, touch, screenshot or real-service browser acceptance.

| Controls | Automated/code review result | Remaining browser check |
|---|---|---|
| Account Overview, Orders Dashboard, New Order, API Credentials, Webhook Subscriptions | Routes and role restrictions covered; wrapping navigation implemented | All five links at 360/390/768/1024/desktop; focus, reachability and document width |
| Enrollment submit and pending/rejected/approved states | Existing enrollment tests pass; backend negative authorization tests pass | Full registration → admin approval → refreshed login |
| Total/Active/Delivered/Failed metrics | Dashboard tests pass; complete-history counts no longer retain stale maxima | Counts during an actual status transition |
| Quick filters and lifecycle selector | Existing dashboard tests pass | Keyboard selection and touch targets |
| Tracking/parcel search, Clear Search, Reset Filters | Existing combined/filter/reset tests pass | Actual data and narrow layout |
| Refresh, Load all orders, Retry Dashboard | Query-managed cancellation/deduplication; bounded recovery test passes | Cold services, rapid clicks, refresh after external changes |
| Export Orders to CSV | Real CSV string is parsed back; UTF-8 BOM, quoted fields, formula neutralization, currency, fee and UTC values verified | Browser download completion and opening in spreadsheet software |
| Bulk CSV Upload, Choose File, Change File | File validation/preview exists; stale FileReader results ignored | Real files, drag/drop, all row errors beyond first 50 preview rows |
| Download Sample Template | Existing download trigger test; sample parses and validates | Browser's completed file |
| Import, retry remaining, Cancel, Close | Existing sequential booking/idempotency; synchronous import guard; invalid-file import blocked; close disabled while importing; stop-after-current-order retained | Real partial success and duplicate file submissions across page reloads |
| Bulk dialog keyboard controls | Focus entry, Tab containment, Escape and restoration implemented | Real browser screen-reader/focus behavior |
| Review price, Confirm and book, Retry confirmed order | New tests prove quote-only first action, cancel-without-booking and stable quote/key on uncertain retry | Actual quote expiry/network loss and pricing presentation |
| Book another, View order, Back to Orders | Existing routes/handlers inspected; test suite passes | Browser navigation and retained filter expectations |
| View Details, Tracking Checkpoints | Existing detail/tracking component tests pass | Real event arriving in merchant UI, reconnection, close/unmount cleanup |
| Copy tracking number | Detail failure is visible; existing success behavior retained | Clipboard permission denial and announcement in actual browser |
| Receipt download | Details handler repaired; backend generates and PDFBox parses actual PDF bytes | Real authorized download/open, unavailable receipt, failed generation |
| Generate API Key, copy one-time secret, dismiss | Existing credential tests pass; subject-scoped private caches and mutation guards | Actual one-time reveal and no persistence after closing |
| Revoke key and confirmation/cancel/close | Existing tests pass; server security tests reject unauthorized operations | Invalid/revoked key against actual merchant intake |
| Register webhook, cancel, secret copy/dismiss | Existing webhook tests pass; HTTPS/private-destination negative tests pass | Controlled public receiver and signed callback |
| Inspect deliveries, deactivate confirmation/cancel | Existing webhook UI/server tests pass | Actual retry/backoff/exhaustion and deactivation stopping future callbacks |
| Retry account, Retry connection, Retry order/history | Explicit state separation and guarded query refetch | Repeated clicks and service loss in browser |
| Help Assistant controls | Existing full-suite coverage retained; unchanged | Floating panel overlap and mobile focus |

## Flow verification matrix

| Flow | Result | Scope / limitation |
|---|---|---|
| Enrollment validation and approval-state enforcement | Passed | Accounts and SPA automated tests; not a new browser journey |
| Merchant ownership and unauthorized/revoked credentials | Passed | Service/API security and component tests; live approved-merchant key, parcel and webhook isolation, pending restriction and revoked-key rejection passed |
| Quote → explicit confirmation → booking | Passed | New component tests plus existing backend booking tests |
| Uncertain booking retry/idempotency | Passed | Same quote/key component regression and Delivery replay tests |
| Dashboard, full history, metrics/search/filters | Passed | DOM/API component tests; timestamp switching regression |
| Missing assignment information on delivered orders | Passed | Terminal-state regression; historical identity data is not in the existing response |
| CSV parser/template/export contents | Passed | Generated CSV content and parser tests; actual browser download not observed |
| PDF content, authorization, immutable fee | Passed | Live downloaded PDF parsed with the created tracking reference and immutable quoted fee; backend integrity/security tests passed |
| Concurrent active-key limit | Passed | Real disposable PostgreSQL integration test, not Supabase mutation |
| Webhook HMAC, SSRF, retries, consumer idempotency | Passed | Communications tests; pipeline tests use mocks unless explicitly marked PostgreSQL/Kafka integration |
| Machine API → dashboard query → assignment → courier → Kafka → signed HTTPS callback | Passed | Actual configured Supabase stack, approved disposable fixtures, legitimate workflows and controlled HTTPS receiver; eight verified callback requests included failure/retry |
| Delivered with proof and failed-delivery terminal paths | Passed | Live synthetic parcels reached DELIVERED and FAILED through authorized courier operations |
| Actual authenticated terminal SSE status event | Failed in 21 November run | Historical 15 November probe passed. In the resumed run the backend reached DELIVERED but its terminal event was absent from the local Kafka broker |
| Merchant UI receives an actual transition | Partially passed | Real browser PKCE, owned order details, tracking subscription and CREATED → OUT_FOR_DELIVERY rendering observed. Terminal update remained missing |
| Screenshots and five responsive viewport checks | Blocked pending environment isolation | Authenticated desktop tracking screenshots captured. The remaining browser sequence follows the terminal event assertion |

## Commands and actual results

Commands ran from this isolated checkout. Frontend tools ran in a Node 24 container with isolated `node_modules`; service tests used Java 21.

| Command | Actual result |
|---|---|
| `git fetch origin test` | Initial baseline `cdf69ae`; integrated later `d524a7d`; latest fetch on 15 November found zero unmerged remote commits |
| `tsc -b` | Passed |
| `vitest run --maxWorkers=2` | Final merged frontend passed 82 files / 1,303 tests |
| `eslint .` | Passed, no final warnings |
| `vite build` | Passed |
| Accounts `mvnw.cmd -B test` | 332 tests passed |
| Accounts `mvnw.cmd -B verify -Dtest=MerchantApiKeyServiceTest,MerchantApiKeyHttpSecurityTest,MerchantApiKeyControllerTest -Dit.test=MerchantEnrollmentPostgresIT` | 36 selected unit/security tests + 7 PostgreSQL integration tests passed |
| Communications `mvnw.cmd -B test` | 365 tests passed |
| Delivery `mvnw.cmd -B test` after timestamp change | 508 tests passed |
| Delivery `mvnw.cmd -B verify -Dit.test=CourierAssignmentFilteringPostgresIT` after teammate merge | 514 unit tests and 2 real PostgreSQL integration tests passed; package built |
| Linux `python3 -m unittest discover -s tests/platform-compose -p 'test_supabase*.py'` | 29 passed in an isolated container with read-only source and no Docker socket |
| Supabase application-only Compose readiness wait | Exit 0; all application services healthy; successful initializer exits accepted |
| Full Linux platform suite after CORS correction | 47 tests passed |
| Container image definition tests after Java patch | 13 tests passed |
| Patched Keycloak image build and smoke checks | Built successfully; UID 1000, OpenJDK 21.0.13.1+2 and Keycloak 26.8.1 verified; no dnf in runtime |
| `python -m unittest discover -s tests/contracts -p 'test_*.py'` | 90 tests passed after installing dependencies into a temporary QA directory |
| `git diff --check` | Passed |
| `python -m py_compile tests/e2e/live/merchant_acceptance_probe.py` | Passed after final probe edits |
| Opt-in `merchant_acceptance_probe.py --run` | All API/event/terminal-path stages passed in run `fb014826`; aggregate result remains blocked by browser acceptance |

The unrestricted frontend run had one dispatcher timing failure (1,292 passed / 1 failed). A complete two-worker rerun passed without editing dispatcher code. Initial contract runs could not import missing Python dependencies; the final installed-dependency run passed. An initial Accounts compile used stale installed shared artifacts; installing the current shared modules resolved it. These failed attempts are not counted as passes.

The Windows platform test attempt encountered existing POSIX shell/path assumptions. The complete Linux platform rerun passed. The final production frontend build passed with a chunk-size warning for its 512.01 kB application chunk (126.44 kB gzip).

No coverage percentage is claimed. Unit/component tests, real PostgreSQL tests and live acceptance are deliberately distinguished.

## Live evidence and environment

- Application services remain configured for Supabase. Local application PostgreSQL containers remain stopped; disposable Testcontainers instances were used only for integration tests.
- Isolated Merchant preview: `http://localhost:5173`; HTTP 200 verified. Original application at `http://localhost:3000` was not replaced by this branch.
- Computer-use browser access was unavailable; native access then stopped because it could not confidently identify the active URL. No screenshots or authenticated browser actions were fabricated.
- A live probe is included at `tests/e2e/live/merchant_acceptance_probe.py`. It requires explicit `--run`, an ignored environment file, an official cloudflared executable and an external report directory. Tokens and secrets stay in memory; output contains only sanitized steps and synthetic fixture IDs.
- The first probe run was rejected by automatic approval review because exact remote fixture mutations/public callback destination had not been explicitly approved. The user subsequently approved those exact effects. That approval does not authorize changing realm grant settings or weakening destination validation.
- Initial approved attempts reached admin authentication and some disposable identity/PKCE/profile operations, then stopped during fixture setup. The first two known identities were explicitly removed; cleanup results and retained synthetic business records must be reported, not assumed absent.
- The 14 November partial live run authenticated all five fixtures, enforced pending approval, booked through a machine key with idempotent replay, found the order in the dashboard, assigned it and progressed courier statuses. It received four HMAC-verified HTTPS requests, including a failed callback and successful retry. It did not complete the delivered/failed terminal paths or prove browser rendering.
- On 15 November, all five database readiness checks passed against configured Supabase databases. Keycloak startup took 187 seconds. Supabase startup now allows a 240-second Keycloak grace period, supported 120-second connection acquisition timeout, and a 360-second application readiness wait. The two-connection pool budget is retained.
- The first 15 November attempt timed out after 45 seconds during merchant PKCE form authentication. A later bounded 120-second fixture-client attempt authenticated all five users, provisioned staff, verified pending restrictions, approved both merchants, and verified cross-merchant API-key denial. Webhook registration returned 503. Communications logged `DEADLINE_EXCEEDED` with the call starting 1.24 seconds after its deadline. The shared client acquires its service token before starting the gRPC call, so cold token acquisition can exhaust that deadline. A safe webhook-list read now exercises bounded recovery before subscription mutation in the probe.
- Run `1d49e94c` recovered webhook readiness and registered its receiver, then encountered 503 on Accounts machine-key validation before creating an order. Its key was revoked, webhook deactivated, both merchants retired, staff deactivated, non-admin profiles disabled and all five disposable identities removed with HTTP 204.
- One earlier attempt lost its temporary report directory before the final write, so its final cleanup evidence is unavailable. The probe now persists sanitized atomic reports after each passed stage and cleanup action under ignored `.local/merchant-verification/`, recreating its output directory when needed.
- Final successful backend run `fb014826` created delivered parcel `SWD-20261115-2D261372` and failed parcel `SWD-20261115-663B9A5F`. One initial booking attempt returned 503; retry retained the exact quote, body and idempotency key, then replay returned the same parcel. Approval, two approved-merchant isolation checks, dashboard query visibility, assignment, courier transitions, proof upload, PDF content, public tracking redaction, authenticated DELIVERED SSE, HMAC callbacks, same-event failure/retry, the failed path and revoked-key rejection all passed. The receiver verified eight requests. API/event stages completed in 400.74 seconds.
- Historical run evidence and its downloaded PDF are in `.local/merchant-verification/live-nov15-recovery/`. These generated artifacts are excluded from Git. Headless Playwright was subsequently explicitly approved. The resumed browser evidence and remaining environment blocker are described below.
- The branch is published in draft [PR #340](https://github.com/Suthankan1/swiftdrop/pull/340) targeting protected `test`. The required CI gate passed for implementation head `61ba9e4`, including all Java services, frontend, contracts, identity, static infrastructure checks, dependency checks and container security. Trusted runtime image publication was skipped on the PR.
- On 21 November the environment had reverted to local PostgreSQL. Restored all five service database connections to Supabase after fresh TLS, permission, isolation and schema-history checks passed. Stopped the three SwiftDrop local PostgreSQL containers while preserving their volumes. The updated worktree preview runs on port 5173. A Docker engine interruption stopped the first resumed probe, and the engine/application stack was recovered before further acceptance work.
- The first browser attempt exposed the CORS defect above. After its correction, order details rendered successfully, and an incorrect accessible-name locator in the probe was corrected. These attempts are retained as failures, with screenshots, rather than counted as browser passes.
- The CI run for `52afde9` passed functional, contract, platform and frontend jobs but failed the Keycloak image scan and its dependent required gate. The scan identified OpenJDK findings `CVE-2026-114938` through `CVE-2026-114942`. The vendor update was implemented using Keycloak's documented staged RPM installation approach, with the existing root filesystem and RPM records retained. Vendor dependencies are installed only as required by that Java package update. [Keycloak container documentation](https://www.keycloak.org/server/containers#_installing_additional_rpm_packages)
- Test dependencies and the official cloudflared 2026.11.0 executable were moved from transient temporary storage to ignored `.local/merchant-verification/` paths after temporary files disappeared. No generated binaries or credentials are committed.
- Final implementation head `61ef28c` passed the required CI gate, including the patched Keycloak container security scan. Its OpenJDK runtime is 21.0.13.1+2.
- The resumed acceptance run reached real browser PKCE sign-in, authorized order details and an active tracking subscription. Screenshots show the same open UI changing from CREATED to OUT_FOR_DELIVERY. Backend proof, delivery completion, PDF contents and public redaction passed. The terminal SSE assertion failed, and the UI remained at OUT_FOR_DELIVERY.
- Read-only inspection of the synthetic parcel's Delivery outbox found all five lifecycle events PUBLISHED, with acknowledgement timestamps. Its DELIVERED event was acknowledged at 17:16:27 UTC. The local Kafka broker contained only its OUT_FOR_DELIVERY event. The controlled webhook receiver verified six signed requests, including the DELIVERED event and the initial failure/retry. This mismatch indicates competing publishers against shared database state and separate brokers. Seven runtime-role database connections were also observed, which alone does not establish the number of active application instances behind the pooler.
- Requested explicit approval to provision `merchant_review_61ef28c` schemas in the existing Delivery and Communications Supabase databases, using their existing service roles and reviewed migrations. The alternative is a Kafka broker shared by every publisher using the shared outbox. No schema changes have been executed. The existing project is running, while the remaining terminal/browser acceptance awaits that environment decision.
- The 21 November acceptance run's cleanup passed key revocation, webhook deactivation, merchant retirement, staff deactivation, non-admin profile disabling and all five identity removals. Historical attempts may retain synthetic account/order/audit records, as covered by the user's live-probe approval.

## Reproduction and remaining work

### Changed files by responsibility

- Merchant frontend changes cover `api/merchantBooking.ts`, `api/merchantReadRetry.ts`, the Orders Dashboard, New Order, Order Detail, Credentials and Webhooks pages, CSV utilities, bulk upload and credential/webhook dialogs. Their adjacent tests cover the changed behavior. `global.css` contains the responsive merchant layout changes.
- The existing Customer delivery client, response validator and tracking modal accept the additive timestamp and preserve accurate missing-assignment wording. The Delivery response record, query mapper, query tests and `contracts/rest/openapi-delivery.yaml` carry the approved response change.
- Accounts changes cover `MerchantRepository`, `MerchantApiKeyService`, its unit tests and the concurrent PostgreSQL regression in `MerchantEnrollmentPostgresIT`.
- Supabase startup changes cover the Compose overlay, helper, README and two platform test files. The opt-in live probe is in `tests/e2e/live/merchant_acceptance_probe.py`.
- The CORS correction covers `platform/compose/compose.yaml`, its environment template, README and the Compose specification regression. The vendor JDK update is in `platform/identity/Dockerfile`. `tests/e2e/live/merchant_browser_probe.py` contains the opt-in real-browser checks.
- Courier files belong to the reviewed teammate commits merged from `origin/test`. Their implementation was retained and their PostgreSQL filtering regression was rerun.

### Reproduce acceptance checks

1. On a healthy Supabase stack, open the isolated preview and sign in with a dedicated Merchant fixture. Open orders, wait for full history, search/filter, refresh and inspect a delivered row. The Last Update must not become booking time.
2. At 360, 390, 768, 1024 and desktop widths, inspect document width and reach every Merchant section. Wide-table scrolling must remain inside the table.
3. Submit New Order once: verify the quoted total appears and no parcel exists yet. Cancel and check the input remains; then quote and confirm a fixture order.
4. Run the approved live probe with `--env-file`, `--cloudflared`, and `--report-dir` pointing outside tracked source. Read its sanitized report and verify cleanup. It does not establish browser UI acceptance.
5. Complete actual SSE-to-UI evidence, browser CSV/PDF download completion, all dialog keyboard checks and screenshot acceptance before final Merchant sign-off. Verify disabled subscriptions receive no subsequent event and observe retry exhaustion in a controlled live run. Deactivation itself and callback failure/retry passed, while exhaustion is currently covered by Communications tests.

Next small task — approve isolated Supabase development schemas or provide the shared Kafka endpoint, then complete terminal-event and browser acceptance with the prepared probe.

### Public frontend integration follow-up

The old homepage at port 5173 was the review worktree baseline. Integrated the newer original-workspace landing page, animated delivery scene, shared buttons, header/footer and assistant styling, while preserving authorized workspace selection and Merchant enrollment routing. The original checkout remains untouched. Restarted the Vite preview to clear stale transformed modules from the Windows bind mount.

Validation: TypeScript, ESLint, all 82 frontend test files (1,303 tests) and the production build passed. Installed Chrome headless rendered the updated public page at 1440px and 390px with matching document widths and no page errors. Scrolled through reveal sections and inspected screenshots stored under ignored `.local/merchant-verification/latest-design/`. The build retains a 525.97 kB application chunk warning. This public-page check does not resolve the shared-broker terminal SSE blocker above.
