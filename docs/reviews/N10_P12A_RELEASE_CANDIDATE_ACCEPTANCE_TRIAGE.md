# SwiftDrop Platform — Issue #169 Release-Candidate Acceptance & Blocker Triage Report

- **Issue:** [#169 `[N10-P12A] Coordinate release-candidate feature acceptance and blocker triage`](https://github.com/Suthankan1/swiftdrop/issues/169)
- **Role:** Senior Release Engineer and QA Lead
- **Lead Release & QA Engineer:** Eric Praveen (`@EricPraveen` — Platform / Testing / Communications Lead)
- **Platform Architect & Release Operator:** Suthankan Balenthiran (`@Suthankan1` — Infrastructure / Helm / Staging Gate Execution)
- **Mandatory Independent Reviewers:** Harithkeshan (`@Harithkeshan`), Vibhushan (`@Vibhushan-2003`)
- **Domain Reviewers:** Sinthuha Nadesan (`@Sinthuha-n`), Pushmitha (`@pushmitha20`), Abhinaya (`@abhi190503`), Gajanthan (`@Gajanthan23`), Thuvarahan (`@thuvarahan-t`)
- **Candidate Base Commit SHA:** `98f2d07a91122e67d2b9120eda9302043e1eb86b` (`origin/test`)
- **Active Branch:** `chore/n10-p12a-release-candidate-acceptance-triage`
- **Milestone:** November 10, 2026 — Full SwiftDrop Release
- **Date:** October 28, 2026 (Asia/Colombo)

---

## 1. Executive Summary & Release Recommendation

In accordance with SwiftDrop Issue #169 and its amendments (October 23 feature-gap closure, October 24 strict $0 deployment constraint), this report establishes an evidence-based release candidate acceptance matrix, triages confirmed blockers, validates release readiness across all microservices and user workflows, and documents the formal release decision.

### Release Decision: **NOT READY**

**Decision Rationale:**
While all six backend Spring Boot services, the React SPA frontend, versioned contracts, and security boundaries demonstrate outstanding deterministic test results (**1,743 Java tests passing**, **1,235 Vitest tests passing**, **4/4 contract compatibility gates passing**, **52/52 multi-actor business authorization tests passing**), the release candidate cannot be certified as **READY** or deployed to production due to four unresolved release blockers:
1. **Operator Checkpoint Disaster Recovery Drill (Mode B) Unverified (P1):** Live database restoration against physical PostgreSQL pods on `k3d-swiftdrop-local` has not been completed by recovery operator `@Suthankan1` (`BLOCKED — OPERATOR CHECKPOINT NOT VERIFIED`), though Mode A offline evaluation is 100% verified (Issue #168).
2. **Capacity Budget Numeric Thresholds Unapproved (P1):** Benchmark metrics in `capacity-budgets.v1.json` remain `PENDING_APPROVAL` with `null` values; acceptance load runs refuse execution until reviewers formalize SLA thresholds (Issue #165).
3. **Mandatory Independent Reviewer Sign-Offs Pending (P1):** Formal reviews from designated independent reviewers `@Harithkeshan` and `@Vibhushan-2003` are required prior to test-to-main candidate promotion.
4. **Local Host Missing `helm` Binary (P1):** Automated Kubernetes manifest rendering and security verification (`tests/kubernetes`) cannot execute on local hosts lacking the `helm` executable.

### Acceptance Summary Metrics

| Status Category | Count | Percentage | Description |
|---|:---:|:---:|---|
| **PASS** | **68** | **85.0%** | Requirement verified with direct, reproducible automated evidence |
| **BLOCKED** | **7** | **8.8%** | Requirement blocked by external operator action, missing tooling, or unapproved SLA |
| **NOT RUN** | **3** | **3.8%** | Environment-dependent checks (real browser execution, public webhook receiver) |
| **NEEDS REVIEW** | **2** | **2.5%** | Governance approvals pending independent peer sign-off |
| **FAIL** | **0** | **0.0%** | Zero failing application requirements (all known defects resolved) |
| **Total Acceptance Criteria** | **80** | **100.0%** | Authoritative release-candidate verification surface |

---

## 2. Release-Candidate Scope & Traceability

The release candidate scope encompasses all features implemented for the November 10 release milestone across the five user actors and core platform services:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                    SWIFTDROP ARCHITECTURE CANDIDATE V1.3                   │
└────────────────────────────────────────────────────────────────────────────┘
        │
        ├── API Gateway & Edge Security (Spring Cloud Gateway, port 8080)
        │     └── JWT validation, route filtering, rate limiting, SSRF suppression
        │
        ├── Accounts & Partners Service (Spring Boot, port 8081, DB: accounts_db)
        │     └── Profiles, staff lifecycle, merchant enrollment, Keycloak reconciliation
        │
        ├── Delivery Operations Service (Spring Boot, port 8082, DB: delivery_db)
        │     └── Parcels, state machine, courier assignments, proof, outbox, SSE
        │
        ├── Pricing & Zones Service (Spring Boot, port 8083, DB: pricing_db)
        │     └── Tariff calculations, expiring quotes, immutable snapshots, zones
        │
        ├── Communications Service (Spring Boot, port 8084, DB: comms_db)
        │     └── Notifications, webhooks, HMAC signing, retry engine, idempotency
        │
        ├── GraphQL Query API Service (Spring Boot / GraphQL Java, port 8085)
        │     └── Read-only composite dashboards for Customer, Merchant, Courier, Admin
        │
        └── React TypeScript SPA (Vite / React 19 / React Router, port 5173)
              └── Customer, Merchant, Courier, Dispatcher, and Admin workspaces
```

---

## 3. Release-Candidate Acceptance Matrix

Legend:
- `PASS`: Requirement validated with concrete, passing test evidence.
- `FAIL`: Requirement executed and failed assertion.
- `BLOCKED`: Requirement cannot execute due to missing prerequisite, infrastructure, or configuration.
- `NOT RUN`: Test cannot execute in current environment (e.g. browser automation or public internet receiver).
- `NEEDS REVIEW`: Pending human reviewer decision or policy sign-off.

### 3.1 Merchant Authentication, Authorization & Order Workflow

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-01** | Merchant authentication & profile binding | `openapi-accounts.yaml`, Issue #97 | JWT subject bound to account profile; Customer can submit pending enrollment | `MerchantHttpSecurityTest`, `MerchantEnrollmentServiceTest` (174 pass) | **PASS** | Accounts & Partners | Verified |
| **AC-02** | Role-based merchant workspace gating | `BASELINE.md`, Issue #162 | Callers lacking `ROLE_MERCHANT` receive HTTP 403 Forbidden | `L10-MERCH-01..04` in `test_business_authorization.py` (PASS) | **PASS** | Gateway / Accounts | Verified |
| **AC-03** | Pending / suspended merchant lockdown | Issue #97, Issue #145 | Unapproved/suspended merchants denied order creation and active dashboard | `AC-MO-04`, `AC-XW-05` in `run_merchant_webhook_operator_acceptance.py` | **PASS** | Accounts / Delivery | Verified |
| **AC-04** | Multi-tenant merchant isolation | Issue #162, Issue #163 | Merchant Alpha cannot read, list, or modify Merchant Beta orders or webhooks | `AC-MO-05`, `AC-MO-10` in `test_merchant_webhook_operator_acceptance.py` | **PASS** | Delivery / Comms | Verified |
| **AC-05** | Merchant order dashboard composite read | `schema.graphql`, Issue #145 | GraphQL `merchantDashboard` returns order summaries and metrics | `MerchantDashboardResolverTest` (24 pass), `MerchantOrdersDashboardPage.test.tsx` | **PASS** | GraphQL Query Service | Verified |
| **AC-06** | Idempotent merchant parcel booking | `openapi-delivery.yaml`, Issue #129 | `POST /api/v1/merchant/parcels` requires `Idempotency-Key` | `BookingService.java`, `MerchantParcelBookingControllerTest` | **PASS** | Delivery Operations | Verified |
| **AC-07** | Merchant lost-response replay recovery | Issue #164, Issue #169 | Duplicate booking submission with identical key returns cached canonical parcel | `BookingCorrelationTest.java` (PASS), `BookingRepository.java` (FIXED) | **PASS** | Delivery Operations | Resolved in this PR |
| **AC-08** | Idempotency conflict rejection | Issue #129 | Same `Idempotency-Key` with different request digest returns HTTP 409 Conflict | `AC-MO-08` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-09** | Immutable quote consumption | Issue #108, Issue #140 | Quote consumed at most once globally (`uq_parcels_quote_id`); double use returns 409 | `INV-DEL-01` in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-10** | Expired quote fail-closed rejection | Issue #108 | Quote exceeding `expiresAt` rejected with HTTP 422 ProblemDetail | `AC-12` in `test_customer_courier_journey.py` (PASS) | **PASS** | Pricing / Delivery | Verified |
| **AC-11** | Pricing outage rollback | Issue #140, Issue #164 | Pricing gRPC unavailable aborts booking with zero residue in parcels or outbox | `BookingEntryPointRecoveryPostgresIT` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-12** | Merchant parcel detail & timeline read | Issue #152 | Authorized merchant reads parcel status history and tracking timeline | `AC-MO-09`, `AC-MO-11` in `run_merchant_webhook_operator_acceptance.py` | **PASS** | Delivery Operations | Verified |
| **AC-13** | Nonexistent parcel 404 RFC 7807 | Issue #152 | Invalid parcel UUID returns 404 ProblemDetail with title `PARCEL_NOT_FOUND` | `AC-MO-12` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-14** | Merchant credential generation lifecycle | Issue #22, ADR-0010 | Approved merchant generates API credentials with Argon2id hash storage | `MerchantCredentialLifecycleTest` (PASS), `credentials.test.ts` (PASS) | **PASS** | Accounts & Partners | Verified |

### 3.2 Webhook Subscription, Delivery & Security Lifecycle

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-15** | Webhook subscription registration | `openapi-communications.yaml`, Issue #30 | `POST /api/v1/merchants/webhooks` creates subscription, returns one-time secret | `WebhookSubscriptionServiceTest`, `AC-WH-01` (PASS) | **PASS** | Communications | Verified |
| **AC-16** | Webhook signing secret hygiene | Issue #30, Issue #163 | Subsequent reads of subscriptions omit raw signing secret (`swd_whsec_...`) | `AC-WH-02` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-17** | SSRF: Plain HTTP rejection | DEC-COM-01, Issue #30 | Registration rejects plain `http://` destination endpoints with HTTP 400 | `AC-WH-04` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-18** | SSRF: Loopback & RFC 1918 blocking | DEC-COM-01, Issue #30 | Endpoints resolving to localhost, 127.0.0.1, ::1, 10/8, 172.16/12, 192.168/16 rejected | `AC-WH-05` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-19** | SSRF: Cloud metadata IP blocking | DEC-COM-01, Issue #163 | Endpoints targeting 169.254.169.254 or link-local 169.254.0.0/16 rejected | `AC-WH-06` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-20** | SSRF: HTTP redirect anti-bypass | DEC-COM-01, Issue #33 | HTTP redirects (301, 302, 307, 308) classified as FAILED without following | `AC-WH-11` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-21** | Outbound HMAC-SHA256 signing | DEC-COM-02, Issue #33 | Outbound requests include `X-SwiftDrop-Signature: t={ts},v1={hex}` | `AC-WH-07` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-22** | Cryptographic tamper detection | DEC-COM-02 | Modifying single payload byte or timestamp fails signature verification | `AC-WH-08` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-23** | Signature replay tolerance window | DEC-COM-02 | Signatures with timestamp skew > 300 seconds are rejected | `AC-WH-09` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-24** | Status code classification | Issue #33, Issue #117 | 2xx -> SUCCESS; 3xx/4xx (excl. 408/429) -> FAILED; 408/429/5xx -> PENDING (Retry) | `AC-WH-10..13` in `run_merchant_webhook_operator_acceptance.py` | **PASS** | Communications | Verified |
| **AC-25** | Durable webhook job creation | Issue #131 | Delivery outbox event creates durable job in Communications via Kafka | `AC-XW-01` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Delivery / Comms | Verified |
| **AC-26** | Exponential backoff retry engine | Issue #33, Issue #117 | Lease-based retry execution with exponential backoff and jitter | `WebhookDeliveryExecutionServiceTest`, `AC-WH-13` (PASS) | **PASS** | Communications | Verified |
| **AC-27** | Retry exhaustion handling | Issue #33 | Jobs reaching maximum attempts (5) transition to terminal `EXHAUSTED` | `AC-WH-14` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-28** | Public HTTPS synthetic receiver test | Issue #169 | End-to-end webhook delivery against operator-provided public reflector | None available (local loopback rejected by SSRF defense) | **NOT RUN** | Platform / Comms | Operator provision reflector |
| **AC-29** | Webhook policy & budget approval | Issue #169 | Formal approval of DEC-COM-03 retry timing and SLA budgets | Documented as `PROPOSED — AWAITING REVIEW` | **NEEDS REVIEW** | Communications Lead | Team ADR sign-off |

### 3.3 Operator Management & Administrative Workflows

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-30** | Admin workspace authorization | `openapi-accounts.yaml`, Issue #162 | Callers with `ROLE_ADMIN` access admin APIs; other roles receive 403 | `AC-OP-01`, `AC-OP-02` in `run_merchant_webhook_operator_acceptance.py` | **PASS** | Gateway / Accounts | Verified |
| **AC-31** | Forged admin header anti-spoofing | Issue #162 | Edge gateway strips/rejects unverified `X-Actor-Role: ADMIN` without JWT signature | `AC-OP-03` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | API Gateway | Verified |
| **AC-32** | Merchant enrollment review listing | Issue #121, Issue #146 | Filter applications by `PENDING`, `APPROVED`, `SUSPENDED` | `MerchantReviewPage.test.tsx` (64 pass), `AC-OP-04` (PASS) | **PASS** | Accounts / Frontend | Verified |
| **AC-33** | Merchant approval with reviewToken | Issue #121 | Valid `reviewToken` approves enrollment and reconciles Keycloak `MERCHANT` role | `AC-OP-05` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Accounts & Partners | Verified |
| **AC-34** | Stale reviewToken concurrency conflict | Issue #121 | Stale `reviewToken` returns HTTP 409 Conflict ProblemDetail | `AC-OP-06` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Accounts & Partners | Verified |
| **AC-35** | Merchant suspension with mandatory reason | Issue #146 | Rejects empty suspension rationale; valid reason (<= 500 chars) suspends tenant | `AC-OP-07` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Accounts & Partners | Verified |
| **AC-36** | User & staff lifecycle management | Issue #122, Issue #137 | Bounded pagination (`size <= 100`) and role filtering for couriers and dispatchers | `UserStaffPage.test.tsx` (53 pass), `AC-OP-08` (PASS) | **PASS** | Accounts / Frontend | Verified |
| **AC-37** | Staff provisioning and suspension | Issue #122 | Admin provisions dispatchers/couriers; toggles active status | `AC-OP-09` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Accounts & Partners | Verified |
| **AC-38** | Pricing zone & rate card editor | Issue #123, Issue #138 | Admin configures pricing zones and activates new draft rate cards | `PricingAdminPage.test.tsx` (51 pass), `AdminPricingServiceTest` (18 pass) | **PASS** | Pricing / Frontend | Verified |
| **AC-39** | Pricing update snapshot immutability | Issue #123 | Updating rate card preserves immutable fee on previously booked orders | `AC-XW-06` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Pricing / Delivery | Verified |
| **AC-40** | Daily outcomes & admin reporting | Issue #147 | Admin outcomes dashboard and delivery statistics screens | `ReportsPageFinalAcceptance.test.tsx` (276 pass), PR #288 | **PASS** | Frontend / GraphQL | Verified |
| **AC-41** | Dead-letter webhook replay authorization | Issue #117, Issue #163 | `POST /api/v1/admin/webhooks/deliveries/{id}/replay` restricted to `ROLE_ADMIN` | `AC-OP-12` in `run_merchant_webhook_operator_acceptance.py` (PASS) | **PASS** | Communications | Verified |
| **AC-42** | Composite admin dashboard GraphQL | `schema.graphql`, Issue #139 | `Query.adminDashboard` aggregates pending counts, total parcels, success rates | `AdminDashboardResolverTest` (20 pass), `AC-OP-11` (PASS) | **PASS** | GraphQL Query Service | Verified |

### 3.4 Protocol & Contract Compatibility

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-43** | OpenAPI 3.0.3 REST backward compatibility | `validate_contracts.py`, Issue #88 | 5 service OpenAPI specs parse cleanly; 0 breaking changes against `origin/test` | Gate 1 in `validate_contracts.py` (PASS) | **PASS** | All Services | Verified |
| **AC-44** | GraphQL SDL read-only invariant | `contracts/graphql/schema.graphql` | SDL enforces read-only query schema (6 Query fields); 0 mutations | Gate 2 in `validate_contracts.py` (PASS) | **PASS** | GraphQL Query Service | Verified |
| **AC-45** | Kafka domain event schema validity | JSON Schema 2020-12 | `parcel.status.changed.v1.schema.json` valid; 5 negative tests rejected | Gate 3 in `validate_contracts.py` (PASS) | **PASS** | Shared Contracts | Verified |
| **AC-46** | Versioned Protobuf contract validity | `contracts/grpc/`, Issue #92 | 3 `.proto` files, 50 messages, 20 RPCs valid; 0 breaking changes | Gate 4 in `validate_contracts.py` (PASS) | **PASS** | Shared Contracts | Verified |
| **AC-47** | IF-GRPC-04 quote verification deadline | `SERVICE_INTERFACE_MATRIX.md` | Freezes deadline at 2,000 ms; client config defaults to 2,000 ms | `PricingGrpcClientConfig.java`, `GrpcBookingQuoteProvider.java` (FIXED) | **PASS** | Delivery Operations | Resolved in this PR |
| **AC-48** | gRPC machine-to-machine JWT authentication | Issue #100, Issue #162 | Calls without service bearer or with wrong audience rejected with UNAUTHENTICATED | `L12-GRPC-01..05` in `test_business_authorization.py` (PASS) | **PASS** | Shared Security | Verified |
| **AC-49** | GraphQL query depth & complexity limits | IF-GQL-01 | Depth limit 5; complexity limit 100; oversized queries rejected before provider | `QuerySafetyTest`, `L13-GQL-05..07` (PASS) | **PASS** | GraphQL Query Service | Verified |
| **AC-50** | GraphQL introspection production suppression | DEC-GQL-02 | Production environment suppresses `__schema` and `__type` introspection | `L13-GQL-08` in `test_business_authorization.py` (PASS) | **PASS** | GraphQL Query Service | Verified |

### 3.5 Database Migrations, Persistence & Recovery

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-51** | Flyway schema migrations | V1..V4 SQL scripts across services | All database migrations apply sequentially without checksum mismatch | `DeliveryFlywayMigrationTest`, Service builds (PASS) | **PASS** | Core Services | Verified |
| **AC-52** | Outbox aggregate version sequencing | Issue #31 | Monotonic version sequencing per aggregate; duplicate versions rejected | `NF04` rejection in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-53** | Inbox composite PK deduplication | Issue #32 | Composite PK `(consumer_name, event_id)` prevents duplicate side-effects | `INV-INB-01` in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Communications | Verified |
| **AC-54** | Single active courier assignment invariant | Issue #24 | `uq_delivery_assignments_active_parcel` prevents dual active assignments | `INV-ASG-01` in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-55** | Terminal parcel state immutability | Issue #23 | `DELIVERED` and `FAILED` parcels strictly immutable | `INV-HST-03` in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-56** | Private proof storage separation | Issue #95 | Delivery records store private object key (`pod/...`); 0 public URLs | `INV-POD-01` in `run_recovery_consistency_verification.py` (PASS) | **PASS** | Delivery Operations | Verified |
| **AC-57** | Mode A offline recovery consistency | Issue #168 | 17/17 baseline invariants evaluated; 17/17 negative corruption tests rejected | `run_recovery_consistency_verification.py --mode offline` (PASS) | **PASS** | Platform QA | Verified |
| **AC-58** | Mode B operator checkpoint restore | Issue #168 | Automated consistency assertions against restored physical staging cluster | Fails closed: targets unconfigured (`exit code 2`) | **BLOCKED** | Platform Operator | Suthankan restore cluster |
| **AC-59** | RPO / RTO budget approval | Issue #168 | Measured RPO (300s) and RTO (240s) formal team ADR approval | Flagged as `NOT APPROVED` pending team sign-off | **NEEDS REVIEW** | Platform Architect | Team ADR approval |

### 3.6 Automated Integration, Regression & E2E Coverage

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-60** | Frontend Vitest test suite | `frontend/src/` | All 68 test files pass with 0 failures | `pnpm --dir frontend test --run`: 1,235/1,235 tests PASS | **PASS** | Frontend | Verified |
| **AC-61** | Frontend TypeScript compilation | `tsconfig.json` | `tsc -b` completes with 0 errors | `pnpm --dir frontend typecheck`: 0 errors | **PASS** | Frontend | Verified |
| **AC-62** | Production frontend asset build | `vite.config.ts` | `vite build` bundles production distribution cleanly | 245 modules transformed, `dist/` created | **PASS** | Frontend | Verified |
| **AC-63** | Customer & Courier E2E journeys | Issue #157 | Synthetic actors, quotes, booking, assignment, proof, tracking, PDF receipt | `run_customer_courier_e2e.py`: 13/13 PASS | **PASS** | Platform QA | Verified |
| **AC-64** | Merchant, Webhook & Operator journeys | Issue #163 | Automated journeys covering all 46 acceptance criteria | `run_merchant_webhook_operator_acceptance.py`: 46/46 PASS | **PASS** | Platform QA | Verified |
| **AC-65** | Multi-actor business authorization regression | Issue #162 | IDOR, BOLA, role gates, gRPC delegation, GraphQL fields, secret audit | `test_business_authorization.py`: 52/52 PASS | **PASS** | Platform QA | Verified |
| **AC-66** | Live browser automation (Playwright/Cypress) | Issue #156 | Real browser rendering and user interaction against running stack | No browser harness configured in repository | **NOT RUN** | Frontend QA | Documented limitation |
| **AC-67** | Real SSE client reconnection recovery | Issue #130 | SSE connection drop triggers authoritative REST refetch and reconnect | `customerCourierJourneys.test.tsx` (PASS), `ParcelSseReliabilityTest` | **PASS** | Delivery / Frontend | Verified |

### 3.7 Infrastructure, CI/CD, Capacity & Zero-Cost Hosting

| ID | Feature / Requirement | Source Contract / Issue | Required Validation & Expected Outcome | Available Evidence | Status | Responsible Component | Next Concrete Action |
|---|---|---|---|---|:---:|---|---|
| **AC-68** | GitHub Actions CI workflow regression | `.github/workflows/ci.yml` | 20 pipeline structure and fail-closed gate tests pass | `test_workflow_pipeline.py`: 20/20 PASS | **PASS** | DevOps / CI | Verified |
| **AC-69** | Release digest manifest schema compliance | Issue #167 | Immutable candidate manifest complies with schema; SHA-256 digest format | `test_release_digest_manifest.py` (PASS) | **PASS** | DevOps / Release | Verified |
| **AC-70** | Staging promotion workflow structure | Issue #167 | Concurrency serialization without cancellation; mandatory inputs | `test_release_promotion.py` (PASS) | **PASS** | DevOps / Release | Verified |
| **AC-71** | Release required components completeness | Issue #167 | Physical presence of 6 Java services, frontend, Keycloak, contracts | `test_release_required_components.py` (PASS) | **PASS** | DevOps / Release | Verified |
| **AC-72** | Release approval policy enforcement | Issue #167 | Rejects self-approvals; requires distinct named independent reviewers | `test_release_approval_policy.py`: 5/5 PASS | **PASS** | DevOps / Release | Verified |
| **AC-73** | Local host Helm chart rendering tests | `platform/kubernetes/` | Verifies Helm template rendering and pod security standards | Fails with `FileNotFoundError: 'helm'` on local host | **BLOCKED** | Platform / DevOps | Install Helm on host |
| **AC-74** | Docker container image definitions | `tests/container-images/` | Container definitions comply with non-root user and security standards | `test_container_definitions.py` (PASS) | **PASS** | DevOps / Security | Verified |
| **AC-75** | Platform Compose runtime specification | `platform/docker-compose/` | Docker Compose specs validate service network and volume boundaries | `test_compose_spec.py` (PASS) | **PASS** | DevOps / Compose | Verified |
| **AC-76** | Strict $0 Terraform cost policy | Issue #172, ADR-0011 | Rejects paid Azure resources (AKS, VMs, disks, IP, NAT, Storage) | `test_policy.py`: 85/85 PASS | **PASS** | DevOps / Terraform | Verified |
| **AC-77** | Capacity budget harness self-check | Issue #165 | Simulated capacity test harness validates metrics, workloads, budgets | `test_capacity_budgets.py`: 49/49 PASS | **PASS** | Platform QA | Verified |
| **AC-78** | In-process gRPC fan-out & N+1 capacity | Issue #165 | Benchmarks GraphQL resolvers against gRPC fanout bounds | `DashboardGrpcFanoutCapacityTest`: 8/8 PASS | **PASS** | GraphQL Query Service | Verified |
| **AC-79** | Capacity budget acceptance run against live stack | Issue #165 | Benchmarks p95 latency and throughput under live concurrency | BLOCKED: Numeric thresholds `PENDING_APPROVAL`, stack offline | **BLOCKED** | Platform QA | Approve budgets |
| **AC-80** | Native GitHub ruleset enforcement audit | Issue #167 | Audit confirms ruleset 25813062 has 0 required approvals configured | `test_ruleset_audit_records_exact_current_limitations` (PASS) | **BLOCKED** | Repository Admin | Configure ruleset |

---

## 4. Prioritized Blocker Register

### Blocker Summary

| Priority | Count | Description |
|---|:---:|---|
| **P0 — Release Stop** | 0 | No active security vulnerabilities or data integrity violations |
| **P1 — Release Blocker** | 4 | Mandatory deployment prerequisites, unverified restore drill, unapproved capacity SLAs |
| **P2 — Significant Gap** | 3 | Missing native ruleset enforcement, external receiver, browser automation |
| **P3 — Follow-up** | 2 | Deprecation warnings, frontend chunk size optimization |

---

### 4.1 P1 — Release Blockers

#### BLK-01: Local host missing `helm` binary preventing automated validation of Kubernetes manifests
1. **Title:** Missing `helm` binary on local execution host.
2. **Reproduction:** Run `python3 -m unittest discover -s tests/kubernetes -p "test_*.py"`.
3. **Expected vs. Observed:** Expected tests to render Helm charts from `platform/kubernetes/` and verify security policies; observed `FileNotFoundError: [Errno 2] No such file or directory: 'helm'` causing 8 test class setup failures.
4. **Evidence & Environment:** Local macOS environment (Darwin 25.0.0, arm64). Command exited with code 1.
5. **Affected Component:** `platform/kubernetes`, `tests/kubernetes/`, `tests/hosting-feasibility/test_ingress_security.py`.
6. **Severity Reason:** P1 Release Blocker. Cannot verify Kubernetes deployment templates or Helm ingress definitions locally prior to candidate release promotion.
7. **Smallest Remediation:** Install `helm` CLI on host via `brew install helm` or rely on containerized CI runner where `helm` is provisioned.
8. **Suggested Owner:** `@Suthankan1` (Platform Architect / DevOps Lead).
9. **Retest to Close:** `python3 -m unittest discover -s tests/kubernetes -p "test_*.py"` exits with code 0.

#### BLK-02: Mode B Operator Checkpoint Disaster Recovery Verification BLOCKED awaiting restored databases
1. **Title:** Mode B disaster recovery verification blocked awaiting operator staging cluster.
2. **Reproduction:** Run `python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint`.
3. **Expected vs. Observed:** Expected automated consistency validation across restored PostgreSQL databases; observed exit code 2: `BLOCKED — OPERATOR CHECKPOINT NOT VERIFIED` due to missing database connection targets.
4. **Evidence & Environment:** Offline Mode A passes 17/17 baseline invariants and detects all 17 corruption scenarios. Mode B fails closed because staging PostgreSQL pods on `k3d-swiftdrop-local` are offline.
5. **Affected Component:** `tests/recovery/`, Issue #168, Parent Issue #167.
6. **Severity Reason:** P1 Release Blocker. Per SwiftDrop release policy, promotion cannot claim proven disaster recovery until the physical restored database is audited by the verification engine.
7. **Smallest Remediation:** Operator `@Suthankan1` executes rollback/restore drill runbook (`platform/kubernetes/release/rollback-app.sh`), restores the 5 service databases with `_drill_recovery` suffix, and passes connection targets to the runner.
8. **Suggested Owner:** `@Suthankan1` (Recovery Operator) & `@Sinthuha-n` (Consistency Verification Lead).
9. **Retest to Close:** `python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint --accounts-target ...` passes with exit code 0.

#### BLK-03: Capacity Budgets acceptance execution BLOCKED pending formal threshold approvals and live environment
1. **Title:** Capacity budgets acceptance run blocked by pending numeric threshold approvals.
2. **Reproduction:** Run `python3 tests/capacity/run_capacity_budgets.py --mode self-check`.
3. **Expected vs. Observed:** Expected acceptance load testing; observed `BLOCKED - acceptance run refused before sending load: approval.status is PENDING_APPROVAL, budget p95 latency is null`.
4. **Evidence & Environment:** `tests/capacity/budgets/capacity-budgets.v1.json` lines 40-150. All numeric thresholds have `value: null`.
5. **Affected Component:** `tests/capacity/`, Issue #165.
6. **Severity Reason:** P1 Release Blocker. Issue #165 strictly mandates that performance numbers cannot be certified as passing without prior independent reviewer approval of numeric SLA thresholds.
7. **Smallest Remediation:** Reviewers `@Harithkeshan`, `@Vibhushan-2003`, and `@Gajanthan23` agree on numeric values in `capacity-budgets.v1.json`, compute the digest, and set `approval.status: APPROVED`.
8. **Suggested Owner:** `@pushmitha20` (Capacity Lead) / Reviewers (`@Harithkeshan`, `@Vibhushan-2003`).
9. **Retest to Close:** `python3 tests/capacity/run_capacity_budgets.py --mode acceptance` passes against staging stack.

#### BLK-04: Mandatory independent release approvals pending from designated reviewers
1. **Title:** Independent release approvals pending for candidate promotion.
2. **Reproduction:** Execute `evaluate_human_approval_policy()` from `tests/ci/test_release_approval_policy.py`.
3. **Expected vs. Observed:** Expected `is_approved = True`; observed missing approvals from mandatory reviewers `Harithkeshan` and `Vibhushan-2003`.
4. **Evidence & Environment:** Repository governance policy codifying 2 non-author approvals for release promotion.
5. **Affected Component:** `.github/workflows/staging-promotion.yml`, Issue #167, Issue #169.
6. **Severity Reason:** P1 Release Blocker. Architectural governance baseline prohibits promote-to-main without independent domain and security review sign-offs.
7. **Smallest Remediation:** Open pull request targeting `test`, attach this evidence package, and request review approvals from `@Harithkeshan` and `@Vibhushan-2003`.
8. **Suggested Owner:** `@Suthankan1` (Platform Gate Integration) & Reviewers.
9. **Retest to Close:** Both designated reviewers record `APPROVED` on the pull request.

---

### 4.2 P2 — Significant Gaps

#### GAP-01: GitHub native repository ruleset 25813062 has 0 required approving reviews configured
- **Reproduction:** `tests/ci/fixtures/github_ruleset_audit.json`.
- **Expected vs. Observed:** Expected GitHub repository settings to require 2 approving reviews natively; observed `required_approving_review_count = 0` and empty `required_status_checks`.
- **Impact:** Merging relies on operator manual compliance rather than automated GitHub branch lockouts.
- **Smallest Remediation:** Repository admin `@Suthankan1` updates ruleset 25813062 in GitHub repo settings to require 2 pull request approvals and mandatory status checks.
- **Owner:** `@Suthankan1`.

#### GAP-02: Public HTTPS synthetic webhook test receiver not provisioned
- **Reproduction:** Run webhook delivery tests with external URL.
- **Expected vs. Observed:** Local loopback endpoints are blocked by SSRF defense (`DEC-COM-01`); external public reflector is not provisioned.
- **Impact:** Live external network transport and certificate validation cannot be exercised against public internet infrastructure.
- **Smallest Remediation:** Operator provisions an ephemeral public HTTPS reflector endpoint for staging test runs.
- **Owner:** `@Suthankan1` / `@EricPraveen`.

#### GAP-03: Real browser end-to-end execution (Playwright/Cypress) not configured
- **Reproduction:** Inspect testing configuration in `frontend/`.
- **Expected vs. Observed:** All 1,235 tests execute in Node.js / jsdom; no real browser engine (Chromium, WebKit, Gecko) is driven.
- **Impact:** Visual pixel rendering, CSS flex/grid layout quirks, and native browser touch events are unverified.
- **Smallest Remediation:** Documented acceptable limitation for academic milestone; covered by extensive jsdom accessibility and component testing.
- **Owner:** `@abhi190503`.

---

### 4.3 P3 — Follow-Up Items

#### FOL-01: Deprecation warnings during Java compilation and test execution
- **Impact:** Minor warnings for Hibernate Validator `@Valid` on container collections and Mockito dynamic agent loading in Java 21.
- **Remediation:** Add `-XX:+EnableDynamicAgentLoading` to JVM args; adjust `@Valid` annotations.
- **Owner:** Component maintainers.

#### FOL-02: Vite build chunk size warning (> 500 kB)
- **Impact:** `dist/assets/index-y_vGPlGe.js` is 729 kB (196 kB gzipped).
- **Remediation:** Introduce React lazy loading and dynamic `import()` for major route features.
- **Owner:** `@abhi190503`.

---

## 5. In-Scope Defect Remediation & Verification

### 5.1 Delivery Operations: Merchant Lost-Response Idempotency Replay (Issue #164 / #169)

#### Defect Root Cause Analysis
During integrated recovery testing (documented in `docs/reviews/N10_QA_07_INTEGRATED_RECOVERY_VERIFICATION.md` item E4), a defect in `BookingService.java` and `BookingRepository.java` was identified:
- When a merchant bookings request succeeds, `BookingService.finishMerchant` inserts an idempotency record with `principal_or_tenant` set to `"merchant:" + merchantId` (e.g. `"merchant:a1b2c3d4-..."`).
- However, `BookingRepository.replayMerchant` queried `WHERE principal_or_tenant = ?` passing only `merchantId.toString()` (without the `"merchant:"` prefix).
- Consequently, on a lost-response network retry with the same `Idempotency-Key`, `replayMerchant` returned `Optional.empty()`. The service proceeded down to `repository.quoteUsed(request.quoteId())`, which detected the previously consumed quote and threw HTTP 409 `QUOTE_ALREADY_CONSUMED` instead of returning the cached `ParcelDetailResponse`.

#### Code Changes Applied
1. **`BookingRepository.java`:** Updated `replayMerchant` query to support both formats:
   ```java
   public Optional<Replay> replayMerchant(UUID merchantId, UUID key) {
       return jdbc.query("""
               SELECT request_digest, stored_result::text
               FROM idempotency_records
               WHERE (principal_or_tenant = ? OR principal_or_tenant = ?) AND idempotency_key = ?
               """,
               (rs, n) -> new Replay(rs.getString(1), null, rs.getString(2)),
               "merchant:" + merchantId, merchantId.toString(), key.toString()
       ).stream().findFirst();
   }
   ```
2. **`BookingEntryPointRecoveryPostgresIT.java`:** Removed the `@Disabled("BLOCKED: merchant idempotency is persisted as merchant:<uuid> but replay queries <uuid>; owner fix required")` annotation from `merchantLostResponseRetryReturnsCanonicalBooking()`.
3. **`BookingCorrelationTest.java`:** Added regression test `merchantLostResponseReplayReturnsCachedCanonicalBooking()` verifying that a replayed merchant booking returns the cached canonical response without re-evaluating the quote or touching the database insert methods.

#### Verification Evidence
- PASS: `BookingCorrelationTest.java` (4/4 tests pass in 0.65s).
- PASS: `services/delivery-service` full test suite (**484 tests pass**, 0 failures).

---

### 5.2 Delivery Operations: gRPC Quote Verification Deadline Alignment (IF-GRPC-04)

#### Issue Analysis
Per `docs/contracts/SERVICE_INTERFACE_MATRIX.md`, interface contract `IF-GRPC-04` (`PricingInternalService.ValidateQuote`) is frozen with a **2,000 ms deadline**. However, `PricingGrpcClientConfig.java` and `GrpcBookingQuoteProvider.java` defaulted to **5,000 ms**, causing an unapproved timing variance noted in recovery drill reviews.

#### Code Changes Applied
1. **`PricingGrpcClientConfig.java`:** Changed default deadline property from `5000` to `2000`:
   ```java
   @Value("${PRICING_GRPC_DEADLINE_MS:${swiftdrop.pricing.grpc.deadline-ms:2000}}")
   private long deadlineMs;
   ```
2. **`GrpcBookingQuoteProvider.java`:** Updated fallback constant:
   ```java
   private static final long DEFAULT_DEADLINE_MS = 2000L;
   ```

#### Verification Evidence
- PASS: `GrpcBookingQuoteProviderTest.java` (6/6 tests pass in 0.41s).

---

## 6. Actual Validation Execution Evidence

All commands were executed directly in the repository workspace on October 28, 2026:

| Command Executed | Directory | Tests Run | Result | Duration | Notes / Evidence |
|---|---|:---:|:---:|:---:|---|
| `pnpm --dir frontend typecheck` | `frontend` | 245 modules | **PASS** | 2.1s | `tsc -b` completed with 0 errors |
| `pnpm --dir frontend build` | `frontend` | 245 modules | **PASS** | 1.1s | Production Vite build successful (`dist/`) |
| `pnpm --dir frontend test --run` | `frontend` | 1,235 tests (68 files) | **PASS** | 7.7s | Vitest unit and integration journeys 100% green |
| `python3 tests/contracts/validate_contracts.py --mode all --check-compatibility --base-ref origin/test` | root | 4 gates | **PASS** | 0.8s | OpenAPI (5), GraphQL, Kafka (5 neg), Protobuf (20 RPCs) pass |
| `python3 -m unittest discover -s tests/contracts -p "test_*.py"` | root | 90 tests | **PASS** | 0.13s | Contract regressions 100% pass |
| `python3 -m unittest discover -s tests/ci -p "test_*.py"` | root | 46 tests (2 skipped) | **PASS** | 0.40s | Pipeline structure, release rulesets, manifest tests |
| `python3 tests/security-e2e/test_business_authorization.py` | root | 52 scenarios | **PASS** | 0.06s | L09-DELIV (12), L10-MERCH (9), L11-ADMIN (7), L12-GRPC (5), L13-GQL (9), L14-SSE (5), L15-AUDIT (5) |
| `python3 tests/e2e/run_merchant_webhook_operator_acceptance.py` | root | 46 criteria | **PASS** | 0.05s | AC-MO (12), AC-WH (14), AC-OP (12), AC-XW (8) 100% pass |
| `python3 tests/e2e/run_customer_courier_e2e.py` | root | 13 journeys | **PASS** | 0.01s | Customer booking, courier completion, proof, SSE |
| `python3 -m unittest discover -s tests/terraform-policy -p "test_*.py"` | root | 85 tests | **PASS** | 0.64s | Strict $0 policy, denial of all paid Azure meters |
| `python3 -m unittest discover -s tests/recovery -p "test_*.py"` | root | 32 tests | **PASS** | 0.02s | Recovery invariant runner and verifier unit tests |
| `python3 tests/recovery/run_recovery_consistency_verification.py --mode offline` | root | 17 invariants + 17 negative | **PASS** | 0.01s | Mode A offline evaluation 100% pass |
| `python3 tests/recovery/run_recovery_consistency_verification.py --mode operator-checkpoint` | root | Target evaluation | **BLOCKED** | 0.01s | Fails closed with exit code 2 (cluster targets offline) |
| `python3 -m unittest discover -s tests/capacity -p "test_*.py"` | root | 49 tests | **PASS** | 36.4s | Capacity budgets harness self-check and transport tests |
| `python3 tests/capacity/run_capacity_budgets.py --mode self-check` | root | Harness self-check | **PASS / BLOCKED** | 0.1s | Self-check passes; acceptance load refused (pending SLA) |
| `shared/proto-contracts/mvnw test` | `shared/proto-contracts` | 28 tests | **PASS** | 10.9s | gRPC descriptor and deadline tests 100% pass |
| `shared/security-adapter/mvnw test` | `shared/security-adapter` | 51 tests | **PASS** | 9.3s | JWT decoder, correlation, and delegation tests |
| `services/api-gateway/mvnw test` | `services/api-gateway` | 87 tests | **PASS** | 30.0s | Edge routes, rate limiting, and abuse policy tests |
| `services/pricing-service/mvnw test` | `services/pricing-service` | 167 tests | **PASS** | 17.0s | Tariff calculations, quotes, and draft rate card tests |
| `services/graphql-query-service/mvnw test` | `services/graphql-query-service` | 251 tests | **PASS** | 14.4s | GraphQL resolvers and gRPC fanout capacity benchmarks |
| `services/accounts-service/mvnw test` | `services/accounts-service` | 311 tests | **PASS** | 18.2s | Keycloak reconciliation, profiles, and staff tests |
| `services/delivery-service/mvnw test` | `services/delivery-service` | 484 tests | **PASS** | 18.9s | Parcels, assignments, proof, outbox, SSE, idempotency |
| `services/communications-service/mvnw test` | `services/communications-service` | 364 tests | **PASS** | 7.9s | Webhooks, notifications, HMAC signing, retry engine |

---

## 7. Remaining Risks & Critical Dependencies

1. **Physical Staging Deployment:** All testing documented above was executed in local verified test harnesses, Testcontainers, and unit/integration environments. A full-stack deployment on `k3d-swiftdrop-local` with all 8 container images running simultaneously is the remaining validation phase.
2. **Live Kafka Broker Under Multi-Consumer Load:** The communications webhook focused test suite noted an isolated 10-second readiness timeout during combined suite execution before passing 7/7 alone. Live Kafka broker scaling requires observation during load testing.
3. **Strict $0 Cloud Policy Invariant:** No Azure resources are provisioned. In accordance with ADR-0011, the subscription allowlist starts empty; all deployment remains local.
4. **Keycloak Production Realm Secrets:** Keycloak bootstrap admin credentials and client secrets are maintained strictly outside Git; deployment requires secure injection by `@Suthankan1`.

---

## 8. Next Release Gate & Action Plan

Before promoting the release candidate from `test` to `main`, the following sequential release gates must be executed:

```
[ Gate 1: Code Remediation & Evidence Submission ] ◄── COMPLETE (This PR)
   │  • Merchant idempotency fix applied & verified
   │  • IF-GRPC-04 2,000 ms deadline aligned
   │  • Acceptance matrix & blocker register documented
   │
   ▼
[ Gate 2: Independent Peer Review ] ◄── NEXT IMMEDIATE STEP
   │  • Review from @Harithkeshan (Pricing / Staff Lead)
   │  • Review from @Vibhushan-2003 (Security & QA Lead)
   │  • Merge PR #169 into 'test' branch
   │
   ▼
[ Gate 3: Capacity Budget Formalization ]
   │  • Reviewers approve numeric thresholds in capacity-budgets.v1.json
   │  • Compute approved digest & update revision
   │
   ▼
[ Gate 4: Staging Restore & Mode B Verification Drill ]
   │  • @Suthankan1 starts k3d cluster & restores staging databases
   │  • @Sinthuha-n runs run_recovery_consistency_verification.py --mode operator-checkpoint
   │
   ▼
[ Gate 5: Staging Promotion & Release Sign-Off ]
   │  • Execute promote-staging.sh with candidate SHA
   │  • Release PR: promote 'test' to 'main'
```
