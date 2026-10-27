# Admin merchant review — Issue #136

Owner: @pushmitha20. Frontend/provider reviewer: @thuvarahan-t. Base: `test`.

## Implemented frontend slice

`/admin/merchants` reuses the shared ADMIN route guard and existing Admin entry.
The presentation supports server pages (20 records), safe merchant details,
text status badges, authoritative audit metadata, confirmation/reason policy,
loading/empty/retry/denial/gone/conflict feedback and responsive horizontal table
scrolling. Confirmation names the business, merchant ID and supplied registration;
it traps keyboard focus, supports Escape before submission and restores focus.

`api/merchantReview.ts` is a **transport-free internal presentation port**, not
an approved REST schema or a production mock. Its optional fields represent UI
needs; they do not declare that Accounts exposes them. The future adapter must
project authorized fields only and validate its actual response. An absent audit
read differs from an exposed empty audit result. No timeline is synthesized.

The production port is deliberately `undefined`. The route displays a service
unavailable explanation and disabled commands, with **no review HTTP requests or
fake merchant records**. Functional presentation tests inject synthetic ports only
inside the test file. The feature is ready for provider integration, not business
acceptance.

## Provider evidence and blockers (2026-10-27)

Inspected `test` 654a72c, #136, #121, #97, #88, current Accounts controllers,
README, OpenAPI and provider PR searches. #121 is OPEN with no issue comments or
integrated review-command PR found. #97 and #88 are CLOSED; this does not approve
all PROPOSED policies in the runtime decision register.

| Capability | Current evidence | Frontend integration |
| --- | --- | --- |
| Paged merchant review REST | No list operation under `/api/v1/merchants` in OpenAPI/controller | Blocked on #121; no route invented |
| Merchant detail | `GET /api/v1/merchants/{id}` exists; runtime `MerchantRecord` differs from OpenAPI `MerchantProfileResponse` | Not connected independently of the missing review projection |
| Approve | OpenAPI specifies `POST /api/v1/merchants/{id}/approve`; merchant controller has no handler. #107 implements `POST /api/v1/admin/reconciliation/merchant/{id}/approve` returning `ReconciliationResult` | Disabled pending #121 reconciliation of routes, lifecycle/concurrency and response semantics |
| Suspend | #107 implements `POST /api/v1/admin/reconciliation/merchant/{id}/suspend` with fixed `ADMIN_INITIATED` reason | Disabled pending reviewed #121 command/reason/version policy |
| Audit read | #107 implements `GET /api/v1/admin/reconciliation/audit/{sub}`, returning reconciliation entities with error messages | No reviewed minimal merchant-review projection; do not display raw entities |
| Concurrency | Existing detail DTO has no version/review token | Commands require provider evidence; no client-generated version |

Gateway has `/api/v1/merchants/**` and `/api/v1/admin/**` routes; route existence
does not implement the missing Accounts operations. Existing staff/user admin
reads from #122 are separate projections and are not used as merchant reads.
The internal gRPC pending-summary contract is not a browser REST substitute.

The #107 `MerchantRoleReconciliationService` sets APPROVED/SUSPENDED without a
PENDING/expected-version guard, and treats identity reconciliation outcome
separately from HTTP success. Its audit service can swallow persistence failures.
Do not interpret HTTP 200 as accepted #121 business/audit semantics or add a
browser workaround. @thuvarahan-t must reconcile these existing candidates with
the #121 contract, rather than duplicate them or silently adopt the mismatch.

## Session and command safeguards

- Queries include private scope, verified subject, mounted session identity,
  page and size or merchant ID. Selection and feedback reset on subject changes.
- Reads consume abort signals and reject late responses after cancellation.
  Unmount/logout/role loss cancels and removes the feature cache; shared
  AuthProvider also clears private cache on subject change/logout/expiry.
- Approval requires PENDING, a supplied action, integrated provider capability,
  command method and opaque concurrency evidence. Suspension uses only supplied
  action/reason policy. No local role/status grants or lifecycle rules are added.
- Explicit confirmation, synchronous in-flight guard, no command retries or
  optimistic changes. Success, conflict and unknown outcomes invalidate list and
  detail. A conflict closes confirmation and requires reviewing fresh details.
- 401 delegates to shared `handleSessionExpiry`; 403/404/409/400/422 and network
  failures use fixed safe messages. Raw provider exception/problem bodies are
  never rendered or logged. Only explicitly selected fields appear in UI.
- Future HTTP integration must reuse the shared in-memory token getter, Gateway
  origin, approved session refresh, no-store/omit-credentials policy, and must
  never replay a command on 401. Shared OIDC code is unchanged in this slice.
- Frontend role gates are UX only. Gateway/Accounts must independently validate
  JWTs, ADMIN privileges, lifecycle/concurrency and durable audit writes.

## Acceptance and next task

Local validation on 2026-10-27 (Node 24.21.0; pinned pnpm 12.11.2):

- `corepack pnpm install --frozen-lockfile` — PASS; lockfile unchanged.
- `corepack pnpm typecheck` — PASS.
- `corepack pnpm test --run` — PASS, 515 tests in 41 files.
- `corepack pnpm lint` — PASS.
- `corepack pnpm build` — PASS; Vite reports a >500 kB JavaScript chunk warning.
- `git diff --check` — PASS.

Scripts ran via `npm exec --yes --package=node@24.21.0 -- corepack pnpm ...`;
`corepack pnpm exec node --version` in that environment confirmed v24.21.0.
The frozen install required access to pnpm's external cache. No dependency or
toolchain pins were changed. Production build output remains ignored.

These isolated tests do not prove JWT validation, persistence, business
transitions or durable audit. Visual desktop/mobile browser QA was not performed.
**Real admin approval, backend non-admin denial, persisted audit and browser E2E:
BLOCKED on #121 and controlled full-stack acceptance.** No privileged identities,
business records, infrastructure or external configuration are created here.

Next small task: @thuvarahan-t reviews the port needs and supplies #121's bounded
REST list/detail, supported commands, exact reason/version/error/audit projection;
then wire the reviewed authenticated adapter and verify one persisted approval.

GitHub Project: available tools cannot read/write Projects fields and GitHub CLI
is absent. Actual linked board/status remains unverified. Open #136 in GitHub,
identify its currently linked Project and set In Review after the PR is opened;
keep the issue open pending provider integration and human acceptance. Do not
assume a historical Project number or mark Done based on isolated tests.
