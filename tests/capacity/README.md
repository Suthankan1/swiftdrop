# SwiftDrop capacity budgets — dashboards, tracking and SSE (N10-QA-08)

- **Issue:** [#165](https://github.com/Suthankan1/swiftdrop/issues/165) — Measure dashboard, tracking and SSE capacity against release budgets
- **Owner:** @pushmitha20 (Platform / Testing) · **Reviewers:** Harithkeshan, Gajanthan23, @Vibhushan-2003
- **Operator (environment, seeding, Kubernetes/Helm, secrets, deployment):** @Suthankan1 — this suite never performs those actions.
- **Status:** harness implemented and self-validated; **acceptance run BLOCKED** until the numeric budgets are approved and an integrated non-production environment is provided.

## What is measured

| Workload | Endpoint (real contract) | Checks |
|---|---|---|
| Customer dashboard | `POST /graphql` `customerDashboard` | latency p50/p95/p99, errors, timeouts, own-parcels-only, empty-result customer |
| Merchant dashboard | `POST /graphql` `merchantDashboard` | concurrent tenants, tenant-scoped orders only, pending merchant denied |
| Dispatcher dashboard | `POST /graphql` `dispatcherDashboard(pagination)` | page sizes 1/20/50 incl. one page past the end, zone scope, `pageInfo` consistency |
| Public tracking | `GET /api/v1/tracking/{n}` | latency, response size, PII allowlist (IF-REST-04), 400/404 probes, separate rate-limit burst probe |
| Authorized SSE | `GET /api/v1/parcels/{id}/events` | ≥ 20 simultaneous streams over several identities/tenants, heartbeats, frame gap, duplicates, ordering, version gaps, controlled disconnect → REST catch-up → reconnect (DEC-SSE-03), final REST reconciliation, cross-stream leaks |
| Public SSE | `GET /api/v1/tracking/{n}/events` | 3 anonymous streams (gateway cap is 5/IP); every event checked against the public allowlist |
| Status events | `PATCH /api/v1/parcels/{id}/status` | operator-named drivers move fixture parcels forward (PICKED_UP→IN_TRANSIT→OUT_FOR_DELIVERY) to produce real events; command-to-event delivery latency |
| Security probes | all of the above | no/malformed/expired/forged JWT → 401, wrong role / cross-tenant / cross-customer → denied, page size 0/51/−1 and 101-alias complexity attack → rejected |
| gRPC fan-out | `swiftdrop_grpc_client_requests_total` (#158) | calls/query per dashboard and dispatcher page-size 1 vs 50 (N+1); requires a metrics URL |
| Backlog | Delivery `swiftdrop_outbox_pending`, `…oldest_age_seconds`, `swiftdrop_sse_connections_active` | sampled trend and least-squares slope; requires a metrics URL |

The deterministic **in-process** regression for gRPC fan-out, N+1, page bounds, deadlines and concurrent tenant isolation lives in
`services/graphql-query-service/src/test/java/com/swiftdrop/graphql/capacity/DashboardGrpcFanoutCapacityTest.java`
(runs in the normal `./mvnw verify`; evidence written to `target/capacity/graphql-grpc-fanout.json`).

## Budgets — `budgets/capacity-budgets.v1.json`

- `APPROVED` values only where an approved/frozen source exists: depth 5, complexity 100 (IF-GQL-01), page ceiling 50 (GRAPHQL.md), gRPC deadline 2000 ms (IF-GRPC-*), ≥ 20 SSE connections, ≥ 100 parcels, ≥ 2 tenants (issue #165).
- Every release threshold the issue asks the owner and reviewers to agree (p95 per workload, error rate, request duration, dashboard concurrency, reconnect recovery, frame gap, backlog growth, gRPC calls/query, run duration) is `PENDING_APPROVAL` with `value: null`. `illustrative_value` is non-authoritative and never evaluated.
- Invariants (zero cross-tenant records, zero SSE cross-stream events, zero public PII, zero unexpected probe successes, zero reconciliation mismatches, zero out-of-order events, no N+1) fail the run regardless of approval.
- A pending budget is never reported as PASS; a simulated run is never acceptance evidence.

### Approval procedure (reviewers)

1. Replace each `PENDING_APPROVAL` entry with `"status": "APPROVED", "value": <agreed number>` **before** the acceptance run; never after seeing results without a recorded reviewer decision.
2. Compute the digest: `python3 -c "from tests.capacity.budgets import load_budgets; print(load_budgets().digest())"`
3. Set `approval.status: APPROVED`, `approved_by`, `approved_at`, `record_url` (issue comment) and `approved_budget_digest` to that digest, and bump `revision`.
4. `--mode acceptance` refuses to send any load while any blocker remains, including a digest that no longer matches (i.e. a threshold edited after approval), a missing exact deployed revision, resource snapshot, or status driver.

## Modes and commands

```bash
# Unit + harness self-validation (no network beyond 127.0.0.1)
python3 -m unittest discover -s tests/capacity -p "test_*.py" -v

# Fixture manifest + binding template -> build/reports/capacity/
python3 tests/capacity/run_capacity_budgets.py --mode plan

# Self-check against the in-process SIMULATED stub (verdict is always NOT_ACCEPTANCE_EVIDENCE)
python3 tests/capacity/run_capacity_budgets.py --mode self-check

# Exploratory measurement on an authorized non-production environment (verdict at best BLOCKED_PENDING_APPROVAL)
python3 tests/capacity/run_capacity_budgets.py --mode measure \
  --environment-id k3d-swiftdrop-local --api-base-url http://localhost:8080 \
  --binding /secure/outside-repo/capacity-binding.json --target-revision <deployed sha> \
  [--graphql-metrics-url http://<graphql-mgmt>/actuator/prometheus] \
  [--delivery-metrics-url http://<delivery-mgmt>/actuator/prometheus] \
  [--tracking-base-url <delivery-direct-url>] [--resource-file kubectl-top.json] \
  --clients 10 --iterations 20 --sse-connections 20 --sse-duration 60

# Release acceptance (refused until budgets are approved and digest-pinned)
python3 tests/capacity/run_capacity_budgets.py --mode acceptance \
  --environment-id k3d-swiftdrop-local --api-base-url http://localhost:8080 \
  --binding /secure/outside-repo/capacity-binding.json --target-revision <deployed sha> \
  --resource-file kubectl-top.json --clients 10 --iterations 20 \
  --sse-connections 20 --sse-duration 60
```

Acceptance bindings must include at least one valid `status_drivers` entry for an assigned courier
and a parcel in `PICKED_UP` or `IN_TRANSIT`; the acceptance gate requires a successful transition,
an observed authorized SSE event, and at least one disconnect/recovery cycle. Resource snapshots
must be non-empty JSON objects. Exploratory `measure` runs may omit those inputs, but reports mark
unavailable evidence explicitly.

```bash
# In-process fan-out/N+1/isolation regression
(cd services/graphql-query-service && ./mvnw -B -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardGrpcFanoutCapacityTest test)
```

Exit codes: `0` pass / clean self-check, `1` budget or invariant failure, `2` blocked (approval, evidence, binding, unsafe or unreachable target).

## Operator inputs (@Suthankan1)

1. Seed a **non-production** environment from `fixture-manifest.json`: synthetic Keycloak users per actor alias (5 customers, 3 approved + 1 pending merchant, 2 zone-scoped dispatchers, 1 admin, 4 couriers) and ≥ 100 parcels with the listed owners, zones and statuses.
2. Produce a binding from `binding.template.json`: server-generated `parcel_id`/`tracking_number` per alias, one token **file** per actor (outside the repository; the loader rejects token files under `tests/`), optional `expired` / `invalid_signature` tokens, and at least one valid `status_drivers` entry for acceptance (assigned courier + parcel in PICKED_UP/IN_TRANSIT; these parcels are moved forward, so re-seed between runs).
3. Provide the exact `--target-revision` and a non-empty `--resource-file` snapshot for acceptance; metrics URLs (internal management port per ISSUE_158 §7) remain optional and unavailable metrics are reported as such.

Targets are refused unless loopback, `*.localhost`, `*.local`, `*.internal`, `*.svc.cluster.local`, or explicitly passed with `--allow-host` **and** `--confirm-non-production`; production-like and well-known third-party hosts are always refused.

## Evidence classes in reports

`MEASURED_LIVE` (this run, integrated environment) · `MEASURED_IN_PROCESS` (Java MockMvc + in-process gRPC) · `STATIC_SOURCE` (limits read from the checked-out source) · `SIMULATED` (stub self-check) · `OPERATOR_SUPPLIED` · `UNAVAILABLE` / `NOT_MEASURED` / `NOT_EXECUTED`. Reports are written to `build/reports/capacity/` (git-ignored) and scanned for token values and JWT/Bearer patterns before writing.

## Known constraints and proposed linked work

| Item | Effect | Proposed owner |
|---|---|---|
| GraphQL service exposes only `/actuator/health` | live gRPC calls/query is `UNAVAILABLE` without a management/metrics endpoint; in-process Java evidence covers fan-out meanwhile | @Suthankan1 (management port / Prometheus, ISSUE_158 §7) |
| Gateway public limit 60/min per IP, burst 10 (`RATE_LIMIT_PUBLIC_*`) | single-IP tracking load through the gateway is throttled; 429s are reported separately and excluded from error rate and latency; use `--tracking-base-url` toward Delivery for capacity numbers | reviewers decide which path the tracking budget applies to |
| Delivery SSE caps 10/user, 5/parcel; gateway 10/subject, 5/IP public | the 20-stream plan spreads over 7 identities and 20 parcels; raising concurrency needs more fixture identities, not cap changes | — |
| Depth > 5 is not expressible with the live schema | depth limit evidenced by `QuerySafetyTest` and static source, not by a live probe | — |
| Not in CI yet | suite is stdlib-only and CI-safe (loopback only); adding a job touches the required CI gate | @Suthankan1 (CI gate) — proposed next task |
