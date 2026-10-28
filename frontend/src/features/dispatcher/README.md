# Dispatcher queue and manual assignment — Issue #25

Owner: HarithKeshan (@Harithkeshan). Extend the existing `/dispatcher` page in
the shared SPA. The existing route requires DISPATCHER; ADMIN alone does not
grant entry. Operations mount only after the shared staff self-read verifies
the subject, DISPATCHER business role and ACTIVE status. Missing, suspended,
denied or unavailable staff profiles retain their existing feedback.

## Read and command boundaries

`api/dispatcher.ts` consumes `dispatcherDashboard(pagination)` from the canonical
`contracts/graphql/schema.graphql`, through Gateway `POST /graphql`. The query
selects exactly the approved `unassignedParcels`, `pageInfo` and
`availableCouriers` fields. Page defaults to 0, size to 20; adapters reject
negative/noninteger pages and sizes outside 1..50. UI pages in batches of 20.
The server owns CREATED/no-active-assignment filtering and the deterministic
createdAt/parcelId order. React does not fetch all parcels or filter the queue.

Workload is the provider's ACTIVE assignment count; historical/ended assignments
do not contribute. Accounts owns eligibility. Provider capacity is displayed,
never calculated or used to invent client eligibility thresholds. Only couriers
whose `eligible` flag is true appear in the selection control. Delivery still
rechecks eligibility, state, version and authorization on every command.

Selected details use only the approved queue projection, including parcelId and
version. No extra recipient/contact data is fetched. Manual confirmation sends
exactly `{parcelId, courierSub, expectedVersion}` to Gateway
`POST /api/v1/assignments`, using the existing in-memory shared token getter.
It validates the acknowledged parcel/courier, active assignment and advanced
parcel version before displaying success. Assignment is separate from parcel
lifecycle status and status history.

There is no assignment idempotency-key contract in the current OpenAPI. Commands
are never automatically retried or replayed after 401. Duplicate clicks are
blocked synchronously and while pending. All command outcomes clear selection
and invalidate queue/workload data together. A 409 requires fresh selection and
review. Unknown network/timeout outcomes also require review; a failed refresh
hides the old queue and assignment controls. Neither submission nor local state
changes count as success. Errors are sanitized; raw provider bodies are hidden.

Query keys include private namespace, operation, authenticated subject and page.
No previous-page placeholder data is used. The existing AuthProvider cancels
queries and clears the shared cache on subject changes/logout/expiry. Operations
are keyed by subject and unmount with the verified profile, aborting in-flight
commands and queries. Responses check cancellation after token retrieval,
transport and JSON reads; no old-session command feedback is published. Both
transport and token retrieval have a ten-second deadline. A 401 delegates to
the shared session-expiry handler; no second auth provider is installed.

## Integration status — 2026-10-26

- #24 CLOSED / PR #206 merged: initial assignment provider exists on `test`.
- #113 CLOSED / PR #216 merged: bounded Delivery queue/workload gRPC exists.
- #109 CLOSED / PR #190: Accounts eligibility/scoped courier provider exists.
- #119 CLOSED / PR #217: GraphQL runtime exists, but no Dispatcher resolver.
- #135 OPEN: production `dispatcherDashboard` resolver remains missing. A
  permitted request returns genuine UNAVAILABLE. Tests alone use synthetic data.
- Gateway currently routes only `/api/v1/users/me`; it does not route the required
  `/api/v1/staff/me`, `/graphql` or `/api/v1/assignments`. Security path matchers
  alone do not provide routing. Platform must integrate these routes. No
  Gateway/identity/infrastructure files are changed here.
- Provider mapping remains #135's responsibility: Delivery's queue proto lacks
  sizeCategory, its zone data has documented limitations, and Accounts courier
  summaries lack maxCapacity. Owners must resolve authoritative mappings without
  fabricated values before integrated acceptance.
- #98 OPEN: no approved implemented Dispatcher abort/failure contract; no active
  abort control or fictional cancellation endpoint is exposed.
- #125 OPEN: reassignment/closure/advanced lifecycle actions are deferred.

The user delegated the policy decision after the frontend query was found to
exceed the GraphQL runtime's fixed list multiplier. A separate scoped commit
counts selected fields once: the full query costs 23 instead of 709 under the
unchanged limit 100. Existing depth 5, body 64KiB, list 50, string 4096,
pagination and deadline limits remain. Alias/fragment rejection, exact boundary,
result limits and authorization regression tests pass. This expands accepted
query shapes and needs the ordinary security review before merge; it supplies
no missing business resolver.

## Verification

Use locked pnpm 12.11.2: `pnpm install --frozen-lockfile`, `pnpm typecheck`,
`pnpm test --run`, `pnpm lint`, `pnpm build` from frontend. Corepack's local shim
is broken on this machine; `pnpm.cmd` is the same installed pinned version.
Tests cover shared route denials/profile gating, loading/empty/unavailable,
server pagination, eligibility/workloads, confirmation, duplicate prevention,
conflicts/business denial, unknown outcomes, canonical refresh failures,
session expiry and cancellation/account switching. Existing shared auth tests
cover private-cache clearing on logout. These mocked HTTP tests do not prove
JWT security or live provider integration.

GraphQL: run `mvnw.cmd test -q` in services/graphql-query-service. Contract checks:
`python -X utf8 tests/contracts/validate_contracts.py --mode all --check-compatibility --base-ref origin/test`.
Exact final counts are recorded in the PR and docs/project/STATUS.md.

Real signed Dispatcher login, Gateway-to-GraphQL-to-gRPC queue reads, actual
assignment/conflict and wrong-role provider checks are BLOCKED until #135,
Gateway routing and an isolated authorized dataset are available. Browser/mobile
visual checks are SKIPPED: no browser tool is connected. Next small task: integrate
#135 and its provider mappings, then verify this flow through approved Gateway
routes with controlled test identities and synthetic parcels.
