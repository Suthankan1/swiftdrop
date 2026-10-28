# Issue #147 — daily outcomes and admin report screens (N10-WF24)

Owner: @pushmitha20. Dependencies: #139 (`adminDashboard`, N10-WF23) and #30
(`deliveryStats`, LATER-11). PR target: `test`.
Working tree was inspected on `feat/n10-wf22-admin-zone-pricing-editor` at
`9ebcd29` (clean). Changes are uncommitted; the operator moves them to a
`feat/n10-wf24-*` branch based on `test` and commits manually.

## Implemented slice

- `/admin/reports` (ADMIN): today's operations overview from `adminDashboard`
  (parcels today, active in the field, terminal success rate, merchants awaiting
  review), a safe pending-merchant summary (business name, registration number,
  submission time in Colombo; link to `/admin/merchants`), and delivery outcomes
  from `deliveryStats` with date range, optional zone and quick periods.
- `/dispatcher/reports` (DISPATCHER): delivery outcomes only, no zone input and no
  merchant data; the provider confines figures to assigned zones.
- Outcome metrics: total, delivered, failed, success rate, failure rate, average
  delivery time; a status-distribution table; a per-day table for 2–7 day ranges
  with a partial-failure state and retry for unavailable days.
- Loading, empty (0 parcels / empty review queue), error, timeout, invalid
  response, access-denied and partial-failure states. A failed refresh hides the
  earlier figures; a superseded filter's late response is never shown.
- Admin and Dispatcher landings link to their report routes. The Admin placeholder
  "Operational reporting is a future feature" is replaced.

## Contracts consumed (unchanged)

`contracts/graphql/schema.graphql`; `docs/contracts/N10_WF23_GRAPHQL_ADMIN_DASHBOARD.md`;
`docs/contracts/LATER_11_GRAPHQL_DELIVERY_STATS.md`; `SafeGraphqlExceptionResolver`
codes. No schema, resolver, policy, Gateway, identity or Delivery change.

| Root | Arguments sent | Role | Backend state on this base |
| --- | --- | --- | --- |
| `adminDashboard` | none | ADMIN | `AdminDashboardResolver` registered |
| `deliveryStats` | `filter { startDate, endDate, zone? }` (dates `YYYY-MM-DD`) | DISPATCHER, ADMIN | **no resolver**; runtime returns `UNAVAILABLE` |

Semantics applied: Asia/Colombo days (Q13 / decision G); 90-day ceiling (Q16);
denominator delivered + failed (decision D); `0.0..1.0` rate shown as
`(rate*100).toFixed(1)%` (decision C); in-transit and created parcels excluded
from rates. The UI shows "Not available" for a zero denominator on
`deliveryStats`-derived rates. `adminDashboard` cannot distinguish "no terminal
outcomes" from "all failed" at `0.0`; the card states that.

## Commands actually run (frontend, Node v24.16.0 local; CI pins 24.21.0)

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | lockfile up to date |
| `pnpm lint` | no findings |
| `pnpm typecheck` | no errors |
| `pnpm test --run` | 56 files, 997 tests passed (84 new) |
| `pnpm build` | built; existing >500 kB chunk advisory |
| `pnpm audit --audit-level=high` | no known vulnerabilities |
| `python -m unittest discover -s tests/contracts` | blocked locally: `yaml`/`graphql-core` not installed |

`pnpm --dir frontend …` refuses to run under Node 24.16 (engines check), so the
scripts were run from `frontend/`. CI's pinned Node was not available locally.

## Remaining acceptance and owner decisions

1. **#30 `deliveryStats` resolver** (owner @pushmitha20, review @Harithkeshan,
   @Vibhushan-2003): register a `RootQueryResolver` calling
   `GetDeliveryStatsInternal` with authorized zones. Until then outcome cards stay
   unavailable by design.
2. **Dispatcher zone source** (Q11): confirm the provider derives `authorized_zones`
   from verified claims or the Accounts staff profile, not caller input.
3. **Boundary semantics**: LATER-11 uses a closed `[start, end-of-day]` interval;
   N10-WF23 uses `[start, next start)`. Equivalent at nanosecond precision; Delivery
   owner should confirm the provider applies one rule.
4. Real React → Gateway → GraphQL → gRPC → PostgreSQL verification with ADMIN,
   DISPATCHER and denied-role tokens has not been performed.

## Next small task

Implement and test the #30 `DeliveryStatsResolver` in
`services/graphql-query-service`, then re-run the report screens against it.
