# Issue #156 — responsive accessibility and session recovery QA

**Status:** INDEPENDENT QA IMPLEMENTED — FINAL ACCEPTANCE BLOCKED

**Owner:** @abhi190503

**Feature branch:** `qa/n10-wf26-responsive-accessibility-recovery`

**Source branch / SHA:** `test` / `409b33447586561c0d36e0a98fff15bb14951b1a`

**Execution date:** 2026-10-28 (Asia/Colombo)

## Environment and evidence boundary

- Windows workspace: `D:\swiftdrop`.
- Isolated validation runtime: official `node:24.21.0-bookworm-slim`, Node 24.21.0, pnpm 12.11.2, frozen `frontend/pnpm-lock.yaml`.
- Host support tools observed: Java 21.0.12.1 and Docker Engine 29.8.1.
- Browser automation dependency/configuration: none in the repository. No Chrome, Edge, Firefox, or WebKit session was executed.
- Local application stack: unavailable. `docker ps` returned no running containers. No Keycloak, Gateway, business service, database, Kafka, or MinIO workflow was started or changed.
- API evidence: none. All frontend provider responses exercised by Vitest were isolated mocks. No mocked result is classified as real API evidence.
- The pre-existing host `node_modules` was incomplete and the host Node version was 22.17.1, outside the repository's Node 24 engine. Validation therefore used a dedicated Docker dependency volume and did not change the lockfile.

## Coverage matrix

| Workflow | Automated component/API-adapter result | Real browser | Real API | Overall Issue #156 status |
| --- | --- | --- | --- | --- |
| Customer quote, booking, tracking, receipt | PASS in jsdom for route guards, validation, idempotency header, conflict/error states, receipt wiring, redacted tracking and SSE/REST recovery | NOT RUN | NOT RUN | FAIL — field/error association defect [#283](https://github.com/Suthankan1/swiftdrop/issues/283); live acceptance unavailable |
| Merchant enrollment, approval states, orders, credentials, webhooks | PASS in jsdom for role/tenant isolation, empty/loading/denied/error states, commands and cache clearing | NOT RUN | NOT RUN | FAIL — enrollment field/error association defect [#284](https://github.com/Suthankan1/swiftdrop/issues/284); live acceptance unavailable |
| Courier assignments, parcel detail, status, failure reasons, proof | PASS in jsdom; Courier proof accessibility regression fixed and retested | NOT RUN | NOT RUN | PASS for mocked frontend scope; live acceptance NOT RUN |
| Dispatcher queue, workload and assignment | PASS in jsdom for gating, loading/empty/error, concurrency, duplicate prevention, expiry and stale-session cancellation | NOT RUN | NOT RUN | PASS for mocked frontend scope; live acceptance NOT RUN |
| Existing Admin users/staff, merchant review, zones/pricing | PASS in jsdom for ADMIN gating, pagination/filtering, dialogs, errors, conflicts and session isolation | NOT RUN | NOT RUN | PASS for mocked frontend scope; live acceptance NOT RUN |
| #147 daily outcomes/admin report screens | No production screen exists on `test`; Issue #147 remains OPEN under @pushmitha20 | NOT RUN | NOT RUN | **BLOCKED — awaiting Pushmitha's implementation** |

The PASS entries above are deliberately limited to Vitest/jsdom behavior. They do not prove real JWT enforcement, persistence, visual layout, pointer target size, browser focus rendering, network transport, or service integration.

## Accessibility results

### Verified by automated tests

- Protected-route and wrong-role behavior uses accessible status/alert feedback across Customer, Merchant, Courier, Dispatcher, and Admin routes.
- Existing tests cover semantic labels and keyboard submission for representative forms, public tracking input error association, Admin confirmation focus traps/Escape/focus return, and shared navigation keyboard behavior.
- Courier proof upload now associates file validation text and the format hint with the file input, marks invalid state, exposes determinate upload percentage through a named progressbar, focuses the first confirmation action, traps Tab/Shift+Tab, closes on Escape, and restores focus after cancellation.
- Upload cancellation remains an uncertain outcome and requires authoritative reconciliation before retry. The accessibility change did not weaken this safety rule.

### Defects and limitations

1. **#283 — Customer quote/booking errors are not programmatically associated with invalid fields.** Source and component-test inspection found visible `.form-error` elements without IDs and controls without matching `aria-invalid`/error `aria-describedby`. Owner: @Gajanthan23. Severity: Medium. Retest: pending owner fix.
2. **#284 — Merchant enrollment errors are not programmatically associated with invalid fields.** The form announces one global alert but does not identify the affected input through invalid/error metadata. Owner: @EricPraveen. Severity: Medium. Retest: pending owner fix.
3. Contrast, zoom/reflow, visible focus styling in an actual rendering engine, screen-reader announcements, touch target sizes, and keyboard traps outside the component-level cases were NOT RUN.

This is not a formal WCAG conformance audit or certification.

## Responsive viewport results

| Viewport | Result |
| --- | --- |
| 375 × 812 small phone | NOT RUN — no browser automation/session available |
| 390 × 844 standard phone | NOT RUN — no browser automation/session available |
| 768 × 1024 tablet | NOT RUN — no browser automation/session available |
| 1440 × 900 desktop | NOT RUN — no browser automation/session available |

Source inspection confirmed responsive wrappers, mobile table/card rules, safe long-value wrapping, bounded modal heights and horizontal table scrolling are present. This is not visual evidence, so no viewport is marked PASS and no claim is made about overflow, clipping, target size, fixed-header focus, or rendered contrast.

## Session, expiry and network recovery

Automated jsdom coverage passed for:

- anonymous protected-route redirects and wrong-role denial;
- deduplicated token refresh, one refresh/retry after a profile 401, and expiry after repeated 401;
- local state/private Query cache clearing on expiry, logout, subject change and same-subject new sessions;
- suppression/abortion of late requests and command results after account changes;
- failed server logout while local state still clears;
- Customer SSE reconnect with authoritative REST refresh, refresh failure and logout/subject termination;
- Courier conflict/ambiguous upload reconciliation without blind command replay;
- Dispatcher/Admin network and conflict refresh without false success.

Real Keycloak refresh, logout/end-session, browser reload, role change, offline/online events, Gateway 401/403 handling, and cross-account data isolation against actual services were NOT RUN because the approved local stack and controlled identities were unavailable.

## Test execution

### Passing validation

Executed in the isolated pinned Node container from `frontend/`:

```text
corepack pnpm test --run
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm build
```

Results:

- Vitest: **59 files passed; 1,033 tests passed; 0 failed; 0 skipped**.
- Focused Courier proof regression: **1 file passed; 11 tests passed** (included in the full total).
- TypeScript project build/typecheck: PASS.
- ESLint: PASS.
- Vite production build: PASS; 241 modules transformed.
- Build emitted one non-failing existing warning: the main minified JavaScript chunk is 694.14 kB, above Vite's 500 kB advisory threshold.
- `git diff --check`: PASS.
- Real browser tests: **0**.
- Real API tests: **0**.
- Frontend tests executed under jsdom: **1,033**. These include pure unit and component tests; provider/network behavior is mocked where applicable.

### Non-result diagnostic

An initial direct host invocation started zero tests and reported 59 worker errors because the existing `node_modules` lacked `is-potential-custom-element-name`. This was an environment diagnostic, not a product-test failure and is not included in the passing count. A frozen install in an isolated Docker volume repaired the test environment without changing dependencies.

## Changes and security impact

- `frontend/src/features/courier/components/ProofCaptureForm.tsx`: accessibility-only behavior for validation, progress, and keyboard confirmation.
- `frontend/src/features/courier/components/ProofCaptureForm.test.tsx`: regression coverage for field/error association, progress semantics, focus trap, Escape and focus return.
- No REST/GraphQL/Protobuf/Kafka contract, auth policy, role boundary, storage privacy, backend, schema, secret, infrastructure, or #147 production code changed.
- No real proof image or personal data was used; tests create synthetic in-memory files.

## Outstanding acceptance blockers

- #147 Admin reporting workflow: **BLOCKED — awaiting Pushmitha's implementation.**
- Required real browser runs at the four named viewports: NOT RUN.
- Required real API workflows and recovery against Keycloak/Gateway/business services: NOT RUN.
- Safe approved role-specific identities and synthetic integrated records were unavailable.
- Customer defect #283 and Merchant defect #284 remain open and require owner fixes plus retest.

## Next small task

After #283 and #284 are fixed and an approved local stack with controlled identities is running, execute one Chrome/Edge browser matrix at 375 × 812 and 1440 × 900 first, retest the two field/error defects, and record actual Gateway/API outcomes without changing #147 ownership.

## Final verification — 2026-10-28 (Asia/Colombo)

### Baseline and scope

- Verification branch: `qa/n10-wf26-final-acceptance`.
- Starting baseline: `99418839ea3086e77f28bd6aefcd09fdcc8ffb82`.
- Issue #147 is **CLOSED**. PRs [#287](https://github.com/Suthankan1/swiftdrop/pull/287) and [#288](https://github.com/Suthankan1/swiftdrop/pull/288) are merged into `test`.
- This final pass adds frontend acceptance tests only; it does not change production reporting, auth, service, schema, or ownership code.

### Admin and Dispatcher reporting results

- The active router mounts `ReportsPage` at `/admin/reports` and `/dispatcher/reports`; the merged `AdminReportsPage` is not the active route.
- Mocked jsdom coverage passed for ADMIN and DISPATCHER role restrictions, anonymous and wrong-role denial, navigation, loading, empty results, daily outcomes, authorized merchant review summaries, date-range validation, Asia/Colombo midnight boundaries, success/failure calculations, filters, network errors, timeout and manual retry, partial provider failure, forbidden/session-expiry handling, cross-subject cache isolation, dispatcher server-scoped requests, and accessible loading/error/status feedback.
- Keyboard interaction and accessible labels were verified only through the existing React Testing Library/jsdom coverage. This is not real browser or WCAG conformance evidence.

### GraphQL provider status and API evidence

- `deliveryStats` is present in the approved SDL, `GraphqlRuntimeConfig.ROOTS`, and `GraphqlFieldPolicy`, so the field is exposed and role-gated.
- It is **not operational in the current backend**: no `RootQueryResolver` bean registers the `deliveryStats` field. The runtime therefore fails closed with `UNAVAILABLE` after authorization rather than returning fabricated statistics. The GraphQL service README also documents the production resolvers as deferred.
- No real GraphQL, Gateway, Keycloak, business-service, database, Kafka, or SSE request was executed. All provider responses in the frontend tests were isolated mocks; they are not API success evidence.
- The frontend correctly renders the unavailable-provider and partial-report states when `deliveryStats` is unavailable.

### Accessibility defect retest

- #283 remains **OPEN**, assigned to @Gajanthan23. The customer quote/booking field-error association fix was not merged; retest is pending.
- #284 remains **OPEN**, assigned to @EricPraveen. The merchant enrollment field-error association fix was not merged; retest is pending.
- No other owner's production feature code was changed.

### Browser and viewport verification

| Viewport | Result |
| --- | --- |
| 375 × 812 | **NOT RUN** — no supported browser automation session was available |
| 390 × 844 | **NOT RUN** — no supported browser automation session was available |
| 768 × 1024 | **NOT RUN** — no supported browser automation session was available |
| 1440 × 900 | **NOT RUN** — no supported browser automation session was available |

No screenshots, rendered focus/overflow results, real labels, pointer-target measurements, or screen-reader results are claimed.

### Validation commands and results

Executed from `frontend/` in the approved `node:24.21.0-bookworm-slim` container with frozen `frontend/pnpm-lock.yaml`, Node 24.21.0, pnpm 12.11.2:

```text
corepack pnpm test --run --reporter=dot
  PASS — 68 files, 1,232 tests, 0 failed, 0 skipped
corepack pnpm typecheck
  PASS — exit 0
corepack pnpm lint
  PASS — exit 0
corepack pnpm build
  PASS — exit 0; 245 modules transformed
git diff --check
  PASS
```

The build emitted only the existing non-failing advisory that the minified JavaScript chunk is 725.81 kB, above Vite's 500 kB threshold. The host Node 22.17.1 was not used for validation because the project requires Node 24.21.0; no global installation, lockfile, dependency, Docker volume, database, or platform service was changed.

### Remaining blockers and exact files changed

- Final acceptance remains **BLOCKED**, not passed: real browser/API evidence is unavailable; `deliveryStats` has no production resolver; #283 and #284 remain open.
- Files changed in this final pass:
  - `frontend/src/features/admin/pages/ReportsPageFinalAcceptance.test.tsx`
  - `docs/reviews/N10_WF26_RESPONSIVE_ACCESSIBILITY_SESSION_RECOVERY.md`
