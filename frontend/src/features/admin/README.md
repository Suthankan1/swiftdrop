# Admin workspace

## Zones and pricing — Issue #138

Owner @pushmitha20; domain reviewer @Harithkeshan; Gateway reviewer @Suthankan1.
`/admin/pricing` uses the shared ADMIN route guard, in-memory token getter and
subject/session-scoped React Query cache. It provides real pages of 20 zones,
rate cards and rules; zone create/metadata edit/disable; new unpublished drafts;
draft rule add/edit; immediate activation and superseded-card archival.

The adapter consumes Pricing OpenAPI 1.1.0 and #123's controller/DTOs. Writes
include the reviewed parent/zone revision; activation also includes the reviewed
ACTIVE card ID. No rule deletion, published editing, scheduling or currency
conversion is offered. Every write requires confirmation. Currency is LKR,
weight is kg, bands are inclusive and money stays exact integer minor units,
including the full positive signed-int64 range.

Conflicts retain entered fields and require comparison with refreshed canonical
values, deliberate revision selection and a new confirmation. Interrupted writes
pause further writes until successful reads and explicit administrator review.
Writes never replay, including after 401. Expiry delegates to shared auth; 403
removes privileged content. Reads use abort signals and 15-second HTTP deadlines.
Only allowlisted error codes receive specific safe text; provider details are
never rendered. Frontend gates are UX; Gateway and Pricing enforce ADMIN.

The provider has no active-card filter or version-detail GET. Active/publication
review scans **at most ten pages of 50 cards** and fails closed beyond 500 cards.
Ordinary list/rule reads remain paged. Larger histories need a separately reviewed
bounded active/detail provider; no endpoint or contract is invented here.

Gateway now routes the specific `/api/v1/admin/pricing/**` prefix to Pricing
before the generic Accounts admin route, retaining its existing ADMIN policy.
Platform review is required. No deployment, identity, schema or Pricing business
rules change. Historical issued quotes, accepted bookings and receipts are never
recalculated by this interface.

Verification commands, exact counts, changed files, remaining real-provider
acceptance and a focused next task are recorded in
[Issue #138 handoff](../../../../docs/ai/ISSUE_138_HANDOFF.md). Isolated UI tests
are not proof of persistence or historical booking/receipt preservation.

## Merchant review — Issue #136

Owner: @pushmitha20. Frontend/provider reviewer: @thuvarahan-t. Base: `test`.

### Frontend slice

`/admin/merchants` reuses the shared ADMIN route guard and existing Admin entry.
It lists merchants awaiting review (server pages of 20, filterable by status),
shows safe details and audit history, and offers only the approve/suspend
actions Accounts advertises. Confirmation names the business, merchant ID and
registration; it traps focus, supports Escape/Cancel before submission and
restores focus. Status badges are text, not colour alone.

### Accounts contract consumed (via the Gateway)

Implemented by #121 (`AdminReadController`, `MerchantController`). Only
`POST /approve` is documented in `contracts/rest/openapi-accounts.yaml`; the
other operations below exist in code but are not yet in the OpenAPI contract.

| Use | Endpoint | Notes |
| --- | --- | --- |
| Review list | `GET /api/v1/admin/merchants?page&size&status` | `status=PENDING` by default; backend orders by `createdAt`, `id`; size ≤ 50 |
| Detail | `GET /api/v1/admin/merchants/{id}` | `reviewToken` (entity version), advertised `actions` and reason policy |
| Audit | `GET /api/v1/admin/merchants/{id}/audit` | actor subject, action, previous/new status, outcome, reason |
| Approve | `POST /api/v1/merchants/{id}/approve` | body `{ reviewToken }`; PENDING only |
| Suspend | `POST /api/v1/merchants/{id}/suspend` | body `{ reviewToken, reason }`; APPROVED only; reason required, ≤ 500 |

Commands return `MerchantProfileResponse`; the UI treats a command as confirmed
only when that body names the same merchant with the expected resulting status.
Anything else is reported as an unconfirmed outcome.

### Safeguards

- Requests use the in-memory token getter with plain `fetch` to the Gateway
  (`credentials: 'omit'`, `cache: 'no-store'`, `redirect: 'error'`). The shared
  `profileFetch` is restricted to profile reads and must not be used here.
- Responses are validated: rows without an id or business name are rejected,
  unknown statuses become `UNKNOWN`, unknown actions or malformed reason
  policies are dropped, and a malformed or failed audit read is shown as
  unavailable rather than synthesized.
- Errors map by HTTP status to fixed messages; ProblemDetail text is never
  rendered (it can include caller roles). 409/412 are conflicts, 429 is rate
  limited, 5xx/network/unreadable 2xx are ambiguous outcomes.
- Commands are never retried. Success, conflict and ambiguous outcomes close
  the confirmation and refresh list and details; a new action needs a fresh
  confirmation with the refreshed `reviewToken`.
- Query keys include private scope, subject and mounted session; logout,
  subject change and role loss abort requests and remove the feature cache.
- Frontend checks are UX only. Accounts enforces ADMIN on `/api/v1/admin/**`
  and on approve/suspend (security filter chain and controller). The Gateway
  explicitly requires ADMIN for `/approve` and `/api/v1/admin/**`; `/suspend`
  currently falls under its `/api/v1/merchants/**` MERCHANT/ADMIN rule, so a
  merchant token reaches Accounts and is rejected there (platform follow-up).

### Verification status

Component and adapter tests use synthetic data and do not prove backend
integration. A real React → Gateway → Accounts → PostgreSQL approval, with a
non-admin and stale-token attempt, still needs a running stack and a controlled
synthetic pending merchant.

## User and staff management — Issue #137

Owner @pushmitha20; Accounts reviewer @Harithkeshan; security/Gateway reviewer
@Suthankan1. Branch `feat/n10-wf20-admin-user-staff-management`; base `test`.

### Implemented behavior

- `/admin/users`: real server pages of 20 Accounts summaries, nullable name/email,
  immutable profile ID, creation time, loading/empty/failure/retry/refresh and
  pagination. A page emptied by concurrent deletion can return to page zero.
- `/admin/staff`: server pages of staff IDs, profile UUIDs, role, department, zone,
  business status and timestamps; exact role/status/zone filters reset page zero.
  Controlled provisioning binds an existing identity through Accounts.
- `/admin/staff/:profileId`: business detail, separate identity audit read,
  suspension of ACTIVE staff, synchronization against current business state and
  recovery of returned FAILED/PENDING_RETRY audit entries. An absent/failed audit
  never becomes successful identity synchronization.
- Confirmation names the immutable target, role and intended effect, traps focus,
  makes the background inert, supports Cancel/Escape before submission and restores
  focus. A synchronous submission lock prevents duplicate commands.

The user provider has no search/filter, status, role or detail operation. The
staff summary has no name/email. These fields/actions are not invented. User
editing/deletion/password reset and public provisioning are unavailable.

### Source-verified API integration

Checked the current Java controllers/records/reconciliation services, Gateway
routes/security and merged [PR #238](https://github.com/Suthankan1/swiftdrop/pull/238).
These are existing provider operations, not new frozen OpenAPI declarations.
Activate/deactivate remain explicitly **proposed** in the Accounts README and
controller and are unavailable here.

| Method | Gateway-relative path | Contract |
| --- | --- | --- |
| GET | `/api/v1/admin/users?page&size` | `AdminPageResponse<AdminUserSummaryResponse>` |
| GET | `/api/v1/admin/staff?page&size&staffRole&businessStatus&assignedZone` | `AdminPageResponse<AdminStaffSummaryResponse>`; optional exact filters |
| GET | `/api/v1/admin/staff/{profileId}` | immutable staff UUID; `AdminStaffSummaryResponse` |
| GET | `/api/v1/admin/reconciliation/audit/{sub}` | safe staff-role audit projection only |
| POST | `/api/v1/admin/reconciliation/staff/provision` | `AdminProvisionStaffRequest` → confirmed `StaffProfileResponse` binding |
| POST | `/api/v1/admin/reconciliation/staff/{sub}/suspend` | no body; `ReconciliationResult` |
| POST | `/api/v1/admin/reconciliation/staff/{sub}/sync` | no body; reconcile current business status |
| POST | `/api/v1/admin/reconciliation/retry/{auditId}` | no body; provider re-reads current business state |

Provisioning accepts the existing allowlisted COURIER/DISPATCHER/ADMIN roles under
the independently enforced ADMIN caller boundary. It does not create an identity
or handle credentials. Inputs check UUIDs, required fields, control characters and
database length bounds; explicit request projection drops extra spoofed fields.
Accounts verifies identity existence, binding consistency, uniqueness and authority.

Business states are ACTIVE/SUSPENDED/INACTIVE; PENDING_RETRY is an audit outcome.
Only matching-subject/role SUCCESS or NO_OP confirms reconciliation. Provisioning
requires a matching ACTIVE response binding. A 503, malformed 2xx, failed outcome
or network interruption cannot produce success. Commands never automatically
replay; success/conflicts/unknown outcomes refresh authoritative reads. Failed
provisioning creates no local row. If no profile exists, the Accounts owner must
inspect reconciliation for the submitted identity before retrying.

### Security and session isolation

The shared ADMIN route guard and feature check protect all entries. Gateway and
Accounts independently require ADMIN on `/api/v1/admin/**`. Queries include the
subject and mounted-session ID, consume AbortSignal, disable automatic retries
and remove their cache on teardown. Logout/identity change/role loss unmounts the
workspace and aborts commands. Late results cannot populate or navigate a new
session. A 401/403 removes feature content and cache; 401 delegates expiry to the
existing session implementation. These frontend tests do not prove JWT enforcement.

The adapter reuses the shared in-memory token getter and feature-fetch conventions:
Gateway origin, omitted cookies, no-store, error on redirects. Auth-owned
`profileFetch` deliberately only supports self-profile GETs; it is not expanded
to replay privileged commands. No tokens enter storage, URLs, logs or UI. Raw
ProblemDetail/result messages, audit errorMessage and correlation IDs are dropped.

Tests use source-verified synthetic DTOs and isolated provider/fetch mocks; they
do not demonstrate a real Keycloak/Gateway/Accounts/PostgreSQL flow. Actual commands,
results, file inventory, unverified acceptance and the next owner task are in the
[Issue #137 handoff](../../../../docs/ai/ISSUE_137_HANDOFF.md).

## Daily outcomes and admin reports — Issue #147

Owner @pushmitha20. Depends on #139 (`adminDashboard`) and #30 (`deliveryStats`).
Base `test`. Read-only; no mutations, exports, charts or new dependencies.

| Route | Guard | GraphQL roots |
| --- | --- | --- |
| `/admin/reports` | shared ADMIN route + feature check | `adminDashboard`, `deliveryStats` |
| `/dispatcher/reports` | shared DISPATCHER route + feature check | `deliveryStats` only, no zone input |

Both roots are POSTed to the single GraphQL Query API at `/graphql` through the
Gateway with the in-memory token (`credentials: 'omit'`, `no-store`,
`redirect: 'error'`, 15-second deadline, no automatic retry). Responses are
explicitly projected and validated: Int32 non-negative counts, status groups
summing to at most the total, a finite 0..1 rate, at most 50 unique UUID merchant
rows, and contract fields only. GraphQL `extensions.code` maps to fixed text;
provider messages are never rendered. 401 delegates to shared session expiry;
401/403 removes report content and the subject/session-scoped cache.

### Reporting semantics (from the frozen contracts)

- Reporting days are `Asia/Colombo` (UTC+05:30) calendar days, shown on screen.
  Filters are sent as `YYYY-MM-DD`; the GraphQL service converts them to UTC
  (LATER-11 Q13). The browser timezone never decides a boundary. One day maps to
  `[00:00, next 00:00)` Colombo, i.e. `[18:30Z previous day, 18:30Z)`.
- Ranges must be ordered, end no later than today in Colombo and at most 90
  inclusive days (provider ceiling Q16). The day-by-day table runs only for 2–7
  days; each row is its own server aggregate and rows are never summed.
- Success/failure rate denominator = delivered + failed (N10-WF23 decision D),
  on every screen. Zero terminal outcomes shows "Not available", never 0%, NaN
  or Infinity. `adminDashboard.terminalSuccessRate` is displayed as returned; the
  contract reports `0.0` when no outcome exists yet, and the card says so.
- `adminDashboard` has no arguments and always covers today; filters do not
  change it. No status filter is offered: under Q8 it zeroes other groups and
  would make rates misleading.

### Integration status

`adminDashboard` has a registered resolver. **`deliveryStats` has none**: it is in
the schema and `GraphqlFieldPolicy`, but `GraphqlRuntimeConfig` answers
`UNAVAILABLE` because no `RootQueryResolver` is registered. Until the #30 resolver
lands, the outcomes section shows a distinct unavailable state (and Admin a
partial-report notice) instead of figures. Dispatcher zone confinement is the
provider's job (Q11); the UI sends no zone for dispatchers. Tests use synthetic
fixtures and stubbed Gateway fetches; they do not prove a real Gateway →
GraphQL → gRPC → PostgreSQL read. Details: [Issue #147 handoff](../../../../docs/ai/ISSUE_147_HANDOFF.md).
