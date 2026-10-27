# Admin merchant review — Issue #136

Owner: @pushmitha20. Frontend/provider reviewer: @thuvarahan-t. Base: `test`.

## Frontend slice

`/admin/merchants` reuses the shared ADMIN route guard and existing Admin entry.
It lists merchants awaiting review (server pages of 20, filterable by status),
shows safe details and audit history, and offers only the approve/suspend
actions Accounts advertises. Confirmation names the business, merchant ID and
registration; it traps focus, supports Escape/Cancel before submission and
restores focus. Status badges are text, not colour alone.

## Accounts contract consumed (via the Gateway)

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

## Safeguards

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

## Verification status

Component and adapter tests use synthetic data and do not prove backend
integration. A real React → Gateway → Accounts → PostgreSQL approval, with a
non-admin and stale-token attempt, still needs a running stack and a controlled
synthetic pending merchant.
