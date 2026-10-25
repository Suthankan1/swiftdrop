# Current project status — update only with evidence

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../branding/PHASE_1_AUDIT.md).

Last documentation update: **2026-10-25**. This file describes the **starter artifact**, not unknown external repositories.

| Area | State | Evidence |
|---|---|---|
| SwiftDrop v1.3 design | **Proposed baseline** | `docs/architecture/BASELINE.md` and 20 supplied editable diagrams |
| Team allocation | **Proposed; names recorded** | `docs/team/OWNERSHIP.md` |
| November 1 report | **Existing external design package reviewed; platform handoff complete, final submission pending** | `docs/milestones/NOV_01.md` and `docs/team/PLATFORM_HANDOFF_NOV01.md` |
| GitHub repository | **Official rename complete (`Suthankan1/swiftdrop`); Git remotes and documentation updated (Phase 3B)** | Merged PR #65 into `test`, release PR #66 into `main`; repository renamed; remote verified at `https://github.com/Suthankan1/swiftdrop.git` |
| Spring Boot services | **Pending Merchant Enrollment Command IMPLEMENTED (Issue #97); API Gateway Edge Security & Abuse Policy IMPLEMENTED (Issue #106); Delivery Foundation Verified (Issue #126)** | `services/accounts-service/`: 174 tests passed (0 failures); `services/api-gateway/`: 48 tests passed (0 failures); `services/delivery-service/`: 18 tests passed; edge Keycloak JWT validation, merchant enrollment command (`POST /api/v1/merchants`), CUSTOMER role gate, verified subject binding, PENDING status lifecycle, strict JSON rejection, and concurrency safety verified |
| React application | **Single SPA canonical defaults aligned to SwiftDrop OIDC; flow verified (PR #41, #42, #64, #80)** | `frontend/`: official keycloak-js 26.2.4; strict typecheck, 101 unit tests, lint, and production build verified; live browser login/profile flow through Gateway to Accounts demonstrated with `swiftdrop` realm; `/customer` and `/merchant` protected routes verified |
| Keycloak/Google login | **Real Google login operator-verified PASS (2026-10-21); Live realm cutover to `swiftdrop` COMPLETE (PR #64); Fresh bootstrap verified** | Human operator completed Google authentication; agent verified Customer profile and Gateway `/api/v1/users/me` HTTP 200, corroborated Google federated identity/CUSTOMER-only roles. Live cutover and fresh-install bootstrap verified. See `docs/branding/PHASE_2_FINAL_SIGNOFF.md`. |
| Email Verification & MFA Policy (Issue #47) | **Native Email Verification (PR #48) & RFC 6238 TOTP (PR #49) VERIFIED; Production Policy & ADR-0009 Finalized** | 14/14 static invariant checks PASS; 93 unit tests PASS (pytest 49 subtests, unittest parity); production policy in `docs/security/EMAIL_VERIFICATION_MFA_POLICY.md`; ADR-0009 recommendation Outcome A (no typed email OTP); handoff to #150 and #159 |
| GraphQL/gRPC/Kafka/SSE flows | **Shared JWT Resource Server & Authenticated gRPC Delegation Adapters IMPLEMENTED (Issue #100)** | 38 automated security tests PASS (JWT validation, gRPC service auth, actor delegation spoof rejection, deadline enforcement, direct service access) |
| Keycloak Role Reconciliation | **Controlled Keycloak Staff & Merchant-Role Reconciliation Adapter IMPLEMENTED (Issue #107)** | 52 automated reconciliation/security tests PASS (193 total in Accounts Service); server-only least privilege Keycloak administration; idempotent staff/merchant reconciliation; stale-retry protection; V5 durable audit schema; Spring Security admin guard (`hasRole('ADMIN')`) |
| Strict $0 Hosting Decision & Azure Allowlist (Issue #172) | **Strict $0 Local Hosting Decision Frozen; Azure Allowlist INITIALIZED TO EMPTY (ADR-0011)** | Complete resource-by-resource decision matrix; Azure for Students credit disqualified; SWA Free blocked on missing subscription hard-stop & local reachability; local k3d/K3s topology frozen for all 8 workloads; machine-readable policy contract published in `platform/security/azure-allowlist-policy.yaml`; handoff to #173-#178 |
| CI/CD, Compose, Kubernetes/AI | **Local container startup, full-stack readiness & independent clean-clone reproduction verified (PR #53, Issue #17)** | `platform/local/start-local.sh`, `platform/local/check-readiness.sh` (13/13 passed), `tests/security-e2e/demo_smoke.py` (4/4 passed), `run-security-e2e.sh` (36 PASS / 4 BLOCKED), independent clean-clone reproduction (L10 PASS); Kubernetes/AI remain bonuses |

## Issue #97 Pending Merchant Enrollment Command — 2026-10-25

Primary Implementer: **Thuvarahan (@thuvarahan-t)** (Merchant / Backend); branch `feat/n10-wf07-merchant-enrollment`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Accounts Business Service & Database Owner), **Harithkeshan (@Harithkeshan)** (Merchant/Admin Boundary Reviewer), **Eric Praveen (@EricPraveen)** (Merchant Frontend Contract Handoff), **Suthankan1 (@Suthankan1)** (Platform Identity Coordination).

Delivered tenant-bound, secure merchant enrollment command inside Accounts & Partners Service, adhering strictly to SwiftDrop v1.3 architecture and OpenAPI contract:
- **CUSTOMER Reachable Enrollment (`POST /api/v1/merchants`):**
  - Updated Spring Security filter chain (`ProfileSecurityConfiguration`) enabling newly authenticated users with `CUSTOMER` role to submit merchant enrollment without a pre-existing `MERCHANT` role or second Keycloak signup.
  - Fail-closed security matcher ensures general `/api/v1/merchants/**` routes continue to require `MERCHANT` or `ADMIN`.
- **Verified Identity & Account Profile Binding:**
  - Authenticated subject is resolved strictly from the verified JWT via `CurrentIdentityProvider.currentIdentity()`. Never accepts user identity or tenant ownership from request JSON or unverified headers.
  - Reuses trusted `AccountProfileRepository` to bind the merchant tenant to the owning account profile, leveraging existing conflict-safe `insertIfAbsent` JIT provisioning when absent.
- **Server-Controlled PENDING Approval Lifecycle:**
  - Initial merchant enrollment strictly forces `MerchantStatus.PENDING`. No client-controlled approval, roles, or order execution capabilities are granted.
  - Privileged merchant actions (e.g. API credential generation under Issue #22) remain fail-closed via `requireApprovedTenantAccess`.
- **Strict Input Validation & Prohibited Field Rejection:**
  - DTO `MerchantRegistrationRequest` validated with Jakarta Bean Validation (`@NotBlank`, `@Size`).
  - Strict Jackson deserializer (`MerchantRegistrationRequestDeserializer`) rejects client-supplied privileged/ownership fields (`merchantId`, `id`, `accountProfileId`, `keycloakSub`, `status`, `approved`, `roles`, `ADMIN`, `MERCHANT`, `createdAt`, `updatedAt`, `ownerId`) with HTTP 400 Bad Request ProblemDetail.
- **Duplicate Prevention & Concurrency Safety:**
  - Evaluates single-merchant-per-owner constraint (`findByKeycloakSub`) and registration number uniqueness (`findByRegistrationNumber`), returning RFC 7807 409 Conflict.
  - Database engine constraints `uq_merchants_keycloak_sub` and `uq_merchants_registration_number` protect against concurrent request races, mapped cleanly to HTTP 409 while letting general persistence errors propagate without masking.
- **Same-Subject Pending Status Retrieval:**
  - `MerchantAuthorizationService` provides same-subject pending read exception allowing authenticated `CUSTOMER` owners to check their own pending tenant status via `GET /api/v1/merchants/{merchantId}/status` and retrieve their record via `GET /api/v1/merchants/me`.
  - Probes across tenant boundaries fail closed with HTTP 403 Forbidden.
- **Automated Validation Evidence:**
  - Automated Accounts test suite: **174 tests PASS** (0 failures, 0 skips, 0 errors).
  - Unit tests: `MerchantRegistrationRequestTest` (9 PASS), `MerchantEnrollmentServiceTest` (11 PASS), `MerchantControllerTest` (8 PASS), `MerchantAuthorizationServiceTest` (22 PASS).
  - Security slice tests: `MerchantHttpSecurityTest` (16 PASS) verifying CUSTOMER access, 401 unauthenticated, 403 cross-tenant/unauthorized roles, 400 prohibited fields, 409 conflicts.
  - Testcontainers PostgreSQL IT harness (`MerchantEnrollmentPostgresIT`) created for full failsafe verification against real PostgreSQL 16 Alpine and Flyway migrations.

## Issue #172 Strict $0 Hosting Decision & Subscription-Specific Azure Allowlist — 2026-10-25

Primary Implementer: **Suthankan1 (@Suthankan1)** (Platform / DevOps / Security Lead); branch `docs/n10-zc01-strict-zero-cost-hosting`, targeting `test`.
Reviewers: **Harithkeshan (@Harithkeshan)** (Platform & Staff/Admin Lead), **Vibhushan (@Vibhushan-2003)** (Independent Security & Quality Lead).

Delivered comprehensive research, financial risk analysis, architectural decision formalization, and downstream execution contracts for GitHub Issue #172 ([N10-ZC01]):
- **Strict $0 Cost Policy Formalization:**
  - Codified the non-negotiable definition of Strict $0: zero unauthorized cloud charges and zero consumption of Azure Students promotional credits ($100 credit balance).
  - Explicitly disqualified promotional credits, pricing calculators, trial offsets, and marketing "Free" labels as proof of non-billable hosting.
  - Enforced fail-closed governance: any unverified SKU, dependency, or overage behavior automatically fails closed to `BLOCKED` or `REJECTED`.
- **Azure Allowlist Default State:**
  - Initialized subscription-specific Azure allowlist to **EMPTY (`allowed_resources: []`)**.
  - Set `azure.deployment_authorized: false`.
  - Machine-readable policy contract published in `platform/security/azure-allowlist-policy.yaml`.
- **Comprehensive Resource-by-Resource Decision Matrix (21 Categories):**
  - Evaluated all candidate Azure resources (VMs, Managed Disks, Public IPs, NAT Gateways, Load Balancers, AKS Nodes, Storage Accounts, Terraform State Storage, ACR, Flexible PostgreSQL, Functions, App Insights, Monitor/Log Analytics, Paid Alerts, Key Vault, Front Door, Purchased Domains, Paid SSL, Egress Networking, Static Web Apps, and Cloud K3s VMs).
  - Categorized each with exact SKUs, meter units, failure conditions, official Microsoft Learn citations, and retrieval timestamps (UTC).
- **Rigorous Evaluation of Azure Static Web Apps Free Candidate:**
  - Evaluated strictly for compiled SPA bundle (`frontend/dist/`).
  - Found candidate **BLOCKED / NOT APPROVED** due to: (1) unverified subscription overage hard stops without active session inspection; and (2) the architectural browser reachability gap (public SPA calling local Gateway/Keycloak causes browser mixed-content rejections, localhost resolution failures on client machines, and OIDC redirect/CORS mismatch).
- **Frozen Local Release Topology for November 10:**
  - Assigned all eight SwiftDrop application and identity workloads (React SPA, API Gateway, Accounts, Pricing, Delivery, Communications, GraphQL, and Keycloak) to deploy locally on existing hardware via **k3d / K3s**.
  - State dependencies frozen on local host: 5 dedicated PostgreSQL databases (`accounts_db`, `pricing_db`, `delivery_db`, `communications_db`, `keycloak_db`), Kafka KRaft broker, private MinIO S3 storage, and Mailpit SMTP.
  - Suthankan1 recorded as Host Capacity Owner. Measured host metrics: Darwin `arm64`, 10 CPU cores, 16 GiB RAM, 93 GiB available disk, Docker 29.9.2. Full stack estimated working set (~6.5 to 7.5 GiB) fits comfortably within 16 GiB memory budget.
  - Honest distinction between local demonstration release and Internet-hosted release (`PUBLIC INTERNET ACCESS — NOT VERIFIED / LOCAL-ONLY RELEASE`).
- **Canonical Deliverables & Downstream Handoff:**
  - Published Architecture Decision Record: `docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`.
  - Published Technical Handoff Document: `docs/handoff/ISSUE_172_TO_173_178_ZERO_COST_HOSTING.md`.
  - Linked policy to downstream infrastructure chain (#173, #174, #175, #176, #177, #178) and existing deployment tasks (#128, #149, #154, #150, #160, #167, #170). Issue #178 confirmed as an optional future spending gate, not a release prerequisite.

## Issue #47 Keycloak Email Verification, MFA Policy & Security Acceptance — 2026-10-25

Primary Implementer: **Suthankan1 (@Suthankan1)** (Platform / Identity & Security Lead); branch `chore/later-15-identity-mfa-acceptance`, targeting `test`.
Reviewers: **Vibhushan (@Vibhushan-2003)** (Independent Security Reviewer), **Harithkeshan (@Harithkeshan)** (Staff/Admin Business Policy).

Delivered complete verification, production policy formalization, ADR-0009 review status recording, and downstream operational handoffs for GitHub Issue #47 ([LATER-15]):
- **Historical Implementation Verification on Current `test` SHA (`0bc41e7`):**
  - **Phase A (Native Email Verification — PR #48, commit `b72c005`):** Verified `verifyEmail: true`, `registrationAllowed: true`, 300s token lifetime, 60s resend cooldown, branded FreeMarker `login-verify-email.ftl`, and isolated local Mailpit (`mailpit:1025`) Compose network binding without public exposure.
  - **Phase B (Authenticator-App TOTP MFA — PR #49, commit `7ecfc33`):** Verified RFC 6238 TOTP policy (`HmacSHA1`, 6 digits, 30s period, lookahead 1, `otpPolicyCodeReusable: false` replay prevention), unenrolled customer pass-through via `conditional-user-configured`, voluntary enrollment (`kc_action=CONFIGURE_TOTP`), branded QR screens, and non-destructive loopback recovery helper (`configure-totp.py reset-user`).
  - **Phase C (Email OTP Feasibility & ADR-0009 — PR #52, commit `169d78e`):** Maintained architectural recommendation **OUTCOME A** (Do NOT implement custom typed email OTP; retain native signed links and app TOTP). Formal status recorded as **`PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL`** pending independent human signoff. Custom email OTP is strictly rejected under Issue #47.
- **Production Email Verification & MFA Policy Formalization:**
  - Published comprehensive policy in `docs/security/EMAIL_VERIFICATION_MFA_POLICY.md` specifying approved baseline vs proposed production settings for new registrations, existing unverified users, Google brokered identities (`trustEmail`), email change re-verification, expired link handling, failed SMTP recovery, account-enumeration privacy protections, role-based voluntary vs mandatory MFA, and lost device recovery.
- **Production SMTP Readiness & $0 Budget Boundary:**
  - Documented strict criteria for production external SMTP relay (SendGrid free tier / university relay), TLS 1.2+ / STARTTLS encryption, verified sender identity (`no-reply@swiftdrop...`), SPF/DKIM/DMARC alignment, server-side environment secret injection (`KC_SPI_EMAIL_DEFAULT_*`), and zero secret logging.
- **Downstream Handoff Contracts:**
  - Published formal acceptance handoff in `docs/handoff/ISSUE_47_TO_150_159_IDENTITY_ACCEPTANCE.md` establishing clear boundaries for **Issue #150** (production external SMTP configuration, deployment secrets, DNS/TLS cutover) and **Issue #159** (independent synthetic browser E2E tests, external mailbox delivery validation, lost device recovery acceptance).
- **Automated Regression Verification Evidence:**
  - `./platform/identity/validate-identity.sh`: **14/14 static invariant checks PASSED**.
  - `python3 -m pytest platform/identity/tests -v`: **93 passed, 49 subtests passed** in 18.18s.
  - `python3 -m unittest discover -s platform/identity/tests -p "test_*.py" -v`: **93 passed** in 18.06s (100% discovery parity across runners).
  - Strengthened `test_totp_configuration.py` and `test_email_configuration.py` to validate both canonical `swiftdrop` and legacy `devarom` realm configurations via subtests.
  - Live smoke tests: `LIVE SMOKE — NOT EXECUTED` (local Docker daemon offline).


## Issue #107 Controlled Keycloak Staff & Merchant-Role Reconciliation Adapter — 2026-10-25

Primary Implementer: **Suthankan1 (@Suthankan1)** (Platform / IAM / Backend Lead); branch `feat/n10-p10-keycloak-role-reconciliation`, targeting `test`.
Reviewers: **Harithkeshan (@Harithkeshan)** (Staff/Admin Business Policy), **Vibhushan (@Vibhushan-2003)** (Independent Security Reviewer), **Thuvarahan (@thuvarahan-t)** (Merchant Domain Steward - Issues #97 & #121), **Eric Praveen (@EricPraveen)** (Merchant Frontend UI Steward - Issue #120).

Delivered the controlled, least-privilege Keycloak identity administration adapter, idempotent staff and merchant role reconciliation services, partial-failure recovery with stale-grant prevention, and durable audit persistence:
- **Server-Only Keycloak Administration Adapter (`com.swiftdrop.accountsservice.identity.keycloak`):**
  - Implemented `KeycloakIdentityAdapter` via Java 21 `HttpClient` using standard Keycloak REST Administration APIs (`/admin/realms/{realm}/users/{id}/role-mappings/realm`).
  - Zero browser administrative credentials: credentials are strictly server-only (`KEYCLOAK_ADMIN_CLIENT_ID`, `KEYCLOAK_ADMIN_CLIENT_SECRET`) managed via `KeycloakAdminCredentialProvider` with cached service-account access tokens and automatic token renewal.
  - Strict allowlist enforcement: Only `COURIER`, `DISPATCHER`, `ADMIN`, and `MERCHANT` are reconcilable (`KeycloakRoleAllowlist`). Arbitrary or client-supplied role parameters are strictly rejected.
- **Controlled Staff-Role Reconciliation (`StaffRoleReconciliationService`):**
  - Requires authenticated `ADMIN` identity verified from JWT principal.
  - Checks Keycloak user existence before creating domain profiles (`IdentityNotFoundException` on missing subject).
  - Persists staff profile in `accounts_db` and synchronizes operational role in Keycloak.
  - Enforces business status: Only `ACTIVE` staff retain privileged roles. Suspension/deactivation revokes operational roles in Keycloak immediately and enforces fail-closed authorization in Accounts.
- **Controlled Merchant-Role Reconciliation (`MerchantRoleReconciliationService`):**
  - Implements configurable role trigger policy (`MerchantReconciliationTriggerPolicy`):
    - `ON_APPROVAL` (default): Pending enrollment (Issue #97) creates a `PENDING` merchant profile in Accounts but does NOT grant the `MERCHANT` role in Keycloak. When approved by an admin (Issue #121), the `MERCHANT` role is synchronized in Keycloak.
    - `ON_ENROLLMENT`: Alternative policy where role is granted on enrollment with business access blocked until approved.
  - Immediate role revocation on merchant suspension or rejection.
  - Authoritative status enforcement: Accounts business endpoints enforce `status == APPROVED`, ensuring stale or unrevoked JWTs cannot perform merchant operations.
- **Durable Audit & Stale-Grant Recovery (`ReconciliationRecoveryService`, `JpaReconciliationAuditService`):**
  - Flyway V5 migration `V5__create_identity_reconciliation_audit.sql` creates `identity_reconciliation_audit` table with check constraints and index coverage.
  - Audit logging with `REQUIRES_NEW` transaction propagation and automatic credential scrubbing (masks passwords, client secrets, and bearer tokens with `***`).
  - Stale-retry protection: Retries re-read the authoritative state from Accounts. If a merchant or staff member was suspended after an earlier failure, the retry strictly enforces role revocation and never restores privileges.
- **Protected Administrative Endpoints (`AdminReconciliationController`):**
  - Exposes `/api/v1/admin/reconciliation/**` protected by Spring Security requiring `hasRole('ADMIN')`.
  - Maps domain and Keycloak exceptions to RFC 7807 `ProblemDetail` via `ReconciliationProblemHandler` (503 for Keycloak unavailable, 404 for missing identity, 400 for unapproved role, 409 for duplicate profile).
- **Integration Contract & Cross-Team Handoffs:**
  - Published comprehensive integration specification in `docs/contracts/ROLE_RECONCILIATION_CONTRACT.md` detailing contracts for Issue #97, Issue #121, Issue #120, Harithkeshan, and Vibhushan.
- **Automated Validation Evidence:**
  - Automated Java test suite: **52 new tests PASS** (193 total passing in Accounts Service, 0 failures, 0 skips):
    - `HttpKeycloakIdentityAdapterTest` (12 tests PASS)
    - `DefaultKeycloakAdminCredentialProviderTest` (4 tests PASS)
    - `StaffRoleReconciliationServiceTest` (7 tests PASS)
    - `MerchantRoleReconciliationServiceTest` (6 tests PASS)
    - `ReconciliationRecoveryServiceTest` (7 tests PASS)
    - `JpaReconciliationAuditServiceTest` (3 tests PASS)
    - `AdminReconciliationHttpSecurityTest` (13 tests PASS)
  - Regression test suites preserved: `shared/security-adapter` (38/38 PASS), `platform/identity/tests` (93/93 PASS, 41 subtests).


## Issue #106 Gateway Business-Edge Authentication & Abuse Policy — 2026-10-25

Primary Implementer: **Suthankan1 (@Suthankan1)** (Platform / Platform); branch `feat/n10-p03-gateway-edge-security`, targeting `test`.
Reviewers: **Vibhushan (@Vibhushan-2003)** (Independent Security Reviewer), **Harithkeshan (@Harithkeshan)** (Functional Reviewer).

Delivered comprehensive business-edge authentication, role-based authorization hooks, merchant machine credential validation, case-insensitive identity and gRPC internal delegation header sanitization, and bounded rate-limiting / payload / SSE connection abuse policies for Spring Cloud Gateway WebFlux:
- **Reactive Keycloak JWT Edge Security & Role Policy Hooks (`com.swiftdrop.apigateway.security`):**
  - Preserved verified Keycloak JWT signature, issuer URI (`/realms/swiftdrop`), required audience (`swiftdrop-api`), and lifetime verification with RFC 7807 Problem Detail responses.
  - Implemented `KeycloakReactiveJwtAuthenticationConverter` mapping `roles` and `realm_access.roles` claims to Spring Security `ROLE_*` authorities (`ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_COURIER`, `ROLE_DISPATCHER`, `ROLE_ADMIN`), filtering internal Keycloak roles.
  - Configured explicit pathMatcher rules across approved route families (Customer booking, Courier status/proof, Dispatcher assignments, Admin approvals, GraphQL composite queries, authenticated user profile) backed by a fail-closed `.anyExchange().denyAll()` boundary.
- **Merchant Machine Credential Validation Boundary (`com.swiftdrop.apigateway.security.merchant`):**
  - Implemented `MerchantApiKeyAuthenticationFilter` attached at `SecurityWebFiltersOrder.AUTHENTICATION`, scoped strictly to `POST /api/v1/merchant/parcels`.
  - Distinguishes Bearer JWT authentication from machine `X-API-Key` authentication without introducing universal bypasses.
  - `DefaultMerchantApiKeyValidator` enforces syntax regex `^swd_live_[0-9a-zA-Z]{32}$`, constant-time candidate lookup (`MessageDigest.isEqual`), scope verification (`parcels:create`), merchant approval status (`APPROVED` vs `PENDING`/`SUSPENDED`), expiration, and revoked checks. Raw API keys are masked in logs.
- **Hardened Client Identity & Internal gRPC Header Sanitization (`com.swiftdrop.apigateway.filter`):**
  - `StripUntrustedHeadersGatewayFilter` case-insensitively strips caller-supplied identity headers (`X-User-Id`, `X-Roles`, `X-Tenant-Id`, `X-Keycloak-Sub`, `X-User-Email`) and all Issue #100 internal gRPC delegation headers (`x-swiftdrop-service-token`, `x-swiftdrop-actor-*`, `x-swiftdrop-correlation-id`).
  - Strictly preserves valid `Authorization: Bearer <token>` and `Idempotency-Key` headers for downstream independent validation.
- **Bounded In-Memory Abuse & Rate-Limiting Policy (`com.swiftdrop.apigateway.filter.ratelimit`):**
  - Implemented `InMemoryRateLimiter` using token-bucket algorithm with bounded cardinality (max 10,000 tracked keys) and idle bucket eviction to prevent memory exhaustion from randomized caller keys.
  - `RateLimitingGatewayFilter` enforces quotas:
    - Public IP rate limit: 60 req/min (burst capacity 10)
    - Verified merchant identity rate limit: 120 req/min (burst capacity 20)
    - Unauthenticated probe limit: 10 req/min (burst capacity 5)
  - Emits RFC 7807 Problem Detail on HTTP 429 `Too Many Requests` with standard `Retry-After` header.
- **Payload Size Enforcement (`PayloadSizeLimitGatewayFilter`):**
  - Enforces `Content-Length` limits: 64 KB for quotes calculation (`/api/v1/quotes`), 5 MB for courier proof uploads (`/api/v1/parcels/*/proof`), and 256 KB for standard parcel commands.
  - Rejects oversized requests with HTTP 413 `Payload Too Large` Problem Detail before forwarding downstream.
- **SSE Connection Policy (`SseConnectionPolicyGatewayFilter`):**
  - Enforces connection concurrency caps: max 5 concurrent streams for public tracking SSE (`/api/v1/tracking/events`), max 10 concurrent streams for authenticated parcel SSE.
  - Zero response body buffering; connection counters release upon disconnect (`.doFinally()`).
- **Downstream Route Integration Handoff:**
  - Published comprehensive handoff document `docs/handoff/ISSUE_127_GATEWAY_ROUTE_INTEGRATION.md` for @Gajanthan23 (Issue #127).
- **Validation Evidence:**
  - Automated Java test suite: **48 tests PASS** (0 failures, 0 skips, 0 errors):
    - `GatewayBusinessEdgeSecurityTest`: 21 tests PASS
    - `GatewayAbuseAndSsePolicyTest`: 8 tests PASS
    - `ApiGatewaySecurityAndRoutingTest`: 12 tests PASS
    - `SwiftDropTargetContractGatewayTest`: 6 tests PASS
    - `ApiGatewayDownstreamUnavailableTest`: 1 test PASS
    - `ApiGatewayApplicationTests`: 1 test PASS
  - Preserved shared security adapter passing suite: `shared/security-adapter` (38/38 PASS).

## Issue #126 Delivery Service Foundation & Bounded Context Packages — 2026-10-25

Primary Implementer: **Sinthuha (@Sinthuha-n)** / **Gajanthan (@Gajanthan23)** (Delivery / Platform Lead); branch `feat/n10-b24-delivery-foundation`, targeting `test`.
Reviewers: **Vibhushan (@Vibhushan-2003)** (Delivery domain & courier status transition steward), **Harithkeshan (@Harithkeshan)** (Dispatcher courier assignment steward), **Suthankan1 (@Suthankan1)** (Platform / integration coordinator).

Delivered the Delivery Operations Spring Boot microservice foundation, independently owned base schema, and bounded package contracts:
- **Application Foundation:** Scaffolded Delivery Service in `services/delivery-service/` using pinned Java 21 LTS and Spring Boot 4.1.1 on port 8083. Package root `com.swiftdrop.deliveryservice`.
- **Independent Database & Schema Ownership (`delivery_db`):** Flyway V1 migration `V1__create_delivery_schema.sql` establishes canonical parcel persistence (`parcels`), immutable chronological status transition audit log (`status_history`), and mutation de-duplication (`idempotency_records`). Zero cross-service database access, shared tables, or foreign keys to Accounts, Keycloak, or Pricing.
- **Base Opaque Schema Boundaries:** External entities are stored strictly as opaque scalar values (`sender_sub VARCHAR(64)`, `merchant_id UUID`, `quote_id UUID`, `proof_id UUID`, `actor_sub VARCHAR(64)`). Fee snapshotting preserved (`amount_minor_units BIGINT`, `currency CHAR(3)`, `rate_version VARCHAR(32)`).
- **Bounded Feature Package Contracts:** Established clear package responsibilities and interfaces across team members to prevent competing scaffolds:
  - `core`: `Parcel` aggregate root entity, `ParcelStatus` enum, `ParcelRepository`.
  - `status`: `StatusHistory` entity, `StatusHistoryRepository`, `ParcelLifecycleTransitionContract` defining forward transition permissions and terminal state immutability for Issue #23.
  - `idempotency`: `IdempotencyRecord` entity, `IdempotencyRecordRepository`, `IdempotencyServiceContract`.
  - `booking`: `BookingContract`, `BookParcelCommand`, `BookingResult` for Issue #20.
  - `assignment`: `CourierAssignmentContract` for Issue #24.
  - `proof`: `ProofMetadataContract` for Issue #29.
  - `query`: `ParcelProjection`, `ParcelQueryService` for Issue #94 and gRPC `GetParcelInternal`.
- **Fail-Closed Configuration & Health:** Requires `DELIVERY_DB_URL`, `DELIVERY_DB_USERNAME`, `DELIVERY_DB_PASSWORD`. Startup fails immediately if database configuration is missing or invalid. Exposes `/actuator/health` on port 8083.
- **Automated Validation Evidence:**
  - Delivery Service automated unit and slice test suites: **18 tests PASS** (0 failures, 0 skips).
  - Tests verify clean Flyway V1 script discovery and constraints, zero cross-service relationships, entity lifecycles and defaults, state machine forward/backward transitions, terminal state immutability, mandatory failure reason/proof requirements, and missing datasource startup failure.
  - Testcontainers PostgreSQL IT harness (`DeliveryPersistencePostgresIT`) created for full failsafe verification on real PostgreSQL 16 Alpine.
  - Strict scope bounds preserved: No delivery booking business workflows, quote calculations, courier assignments, proof uploads, Kafka publishers, or gRPC stubs claimed (cleanly handed off to Issues #20, #23, #24, #29, #31, #94).

## Issue #100 Shared Service JWT & Authenticated gRPC Actor Delegation Adapters — 2026-10-25

Primary Implementer: **Suthankan1 (@Suthankan1)** (Platform / Backend Lead); branch `feat/n10-p02-shared-jwt-grpc-delegation`, targeting `test`.
Reviewers: **Vibhushan (@Vibhushan-2003)** (Independent Security Reviewer), **Harithkeshan (@Harithkeshan)** (Functional Reviewer).

Delivered reusable security adapter module (`shared/security-adapter`) extending independent resource-server verification and production-grade internal gRPC authentication with cryptographic actor delegation:
- **Reusable OAuth2 Resource Server Adapter (`com.swiftdrop.security.jwt`):**
  - Configures Spring Security 6 resource server (`SwiftDropSecurityConfiguration`) with statelessness, CSRF disabled, default-deny boundary (`anyRequest().denyAll()`), and RFC 7807 Problem Detail handlers for 401 Unauthorized and 403 Forbidden.
  - Reusable `AudienceValidator` (`swiftdrop-api`), `SubjectValidator` (opaque non-blank sub), and `SwiftDropJwtDecoderFactory`.
  - Strict extraction of approved SwiftDrop business roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) via `KeycloakRoleExtractor`. Default and internal Keycloak roles (e.g. `offline_access`, `uma_authorization`) are explicitly ignored.
  - Exposes `ServiceSecurityCustomizer` functional hook allowing Delivery, Pricing, Communications, and GraphQL Query API to register domain-specific endpoint access rules without duplicating security boilerplate.
- **Independent Resource Server Enforcement & Direct Service Access Defense:**
  - Directly addressed requirement 5.B: Verified that requests to service boundaries are evaluated independently; bypass attempts with forged headers (`X-Forwarded-For`, `X-Forwarded-Host`, `X-User-Sub`) without a valid Bearer token fail closed with HTTP 401 Unauthorized (`DirectServiceSecurityTest`).
- **Cryptographic Internal gRPC Service Authentication (`com.swiftdrop.security.service`):**
  - Strongly typed `SwiftDropServiceIdentity` for registered microservices (`DELIVERY_SERVICE`, `PRICING_SERVICE`, `ACCOUNTS_SERVICE`, `COMMUNICATIONS_SERVICE`, `GRAPHQL_QUERY_SERVICE`, `API_GATEWAY`).
  - Implemented `JwtServiceTokenValidator` validating `x-swiftdrop-service-token` signatures, issuer, internal audience (`swiftdrop-internal`), and expiration.
  - Missing, expired, forged, or unapproved service callers fail closed with gRPC `Status.UNAUTHENTICATED`.
- **Verified Actor Delegation & Spoofing Defense (`com.swiftdrop.security.grpc`):**
  - Authoritative `GrpcSecurityPolicy` mapping every internal RPC to allowed calling services, delegation requirement (`REQUIRED`, `FORBIDDEN`, `OPTIONAL`), and required actor roles per `docs/contracts/SERVICE_INTERFACE_MATRIX.md`.
  - `ActorDelegationValidator` strictly denies blind trust of caller-forwarded strings: requires cryptographic delegation proof via verified actor JWT (`x-swiftdrop-actor-token`) or signed delegation claims in the service token.
  - Forged actor subjects, forged role elevation (e.g. claiming `ADMIN` with `COURIER` token), unverified forwarding headers, and cross-tenant violations are rejected with gRPC `Status.PERMISSION_DENIED`.
  - Service-only RPCs (e.g. `ValidateMerchantKeyDigest`, `ResolveRecipientContact`) reject synthetic human impersonation; background jobs execute safely without synthetic identities.
- **gRPC Server & Client Interceptors (`SwiftDropGrpcServerSecurityInterceptor`, `SwiftDropGrpcClientInterceptor`):**
  - Server interceptor binds validated service identity and delegated actor to thread-isolated `SwiftDropGrpcContext` (`io.grpc.Context`), preventing static cross-talk.
  - Client interceptor injects service token, propagates authenticated actor context from `SecurityContextHolder`, attaches correlation ID, and enforces default 2000ms deadline (`DEFAULT_DEADLINE_MS`) while preserving shorter upstream deadlines.
- **CI & Build Integration:** Added `shared/security-adapter` gate to `.github/workflows/ci.yml`.
- **Validation Evidence:**
  - Automated Java test suite: **38 tests PASS** (0 failures, 0 skips, 0 errors):
    - `JwtValidationTest` (11 tests PASS): Valid token, missing token, malformed token, invalid signature, untrusted issuer, wrong audience, expired, not yet valid, missing role, unapproved role filtering, forged forwarding headers rejection.
    - `GrpcServiceAuthenticationTest` (7 tests PASS): Valid signed service identity, missing service token, forged service token, expired service token, wrong audience, unauthorized caller service, metadata forwarding without service token.
    - `GrpcDelegationSpoofingTest` (10 tests PASS): Valid delegation, missing actor when required, unverified actor forwarding rejected, forged actor subject, forged role elevation rejected, non-admin denied admin RPC, admin allowed admin RPC, service-only rejects synthetic actor, background job without synthetic actor, signed delegation claims.
    - `GrpcDeadlineAndCorrelationTest` (6 tests PASS): Default 2000ms deadline, shorter upstream deadline preservation, timeout fail-closed (`DEADLINE_EXCEEDED`), correlation ID propagation, malformed correlation sanitization, concurrent thread isolation.
    - `DirectServiceSecurityTest` (4 tests PASS): Direct service access without token fails 401, forged gateway headers fail 401, valid token succeeds independently, insufficient role fails 403.
  - Preserved existing passing suites: `shared/proto-contracts` (14/14 PASS), `services/api-gateway` (19/19 PASS), `services/accounts-service` (141/141 PASS), `services/communications-service` (19/19 PASS).


## Issue #92 Freeze Versioned Protobuf Contracts & Reproducible Java Generation — 2026-10-25


Primary Implementer: **Gajanthan (@Gajanthan23)** (Platform / Protobuf Lead); branch `feat/n10-b01-protobuf-contracts`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Accounts/Platform steward), **Harithkeshan (@Harithkeshan)** (Pricing domain steward), **Vibhushan (@Vibhushan-2003)** (Delivery domain steward), **Thuvarahan (@thuvarahan-t)** (Communications domain steward).

Delivered versioned Protocol Buffer contracts and reproducible Java generation tooling:
- **Canonical Contracts Committed:** Created versioned Protocol Buffer v3 definitions under `contracts/grpc/swiftdrop/`:
  - `accounts/v1/accounts.proto`: `AccountsInternalService` (`CheckCourierEligibility`, `ValidateMerchantKeyDigest`, `ResolveRecipientContact`, `GetPendingMerchantsSummary`).
  - `pricing/v1/pricing.proto`: `PricingInternalService` (`ValidateQuote`).
  - `delivery/v1/delivery.proto`: `DeliveryInternalService` (`GetParcelInternal`, `GetCourierWorkload`).
  - Invariants preserved: `proto3`, `java_multiple_files = true`, `com.swiftdrop.<service>.v1` package, UTC timestamps (`google.protobuf.Timestamp`), integer minor units for monetary quantities (`int64`), and stable permanent field tags.
- **Admin Pending-Merchant Summary RPC (#121/#139 Gap Closure):** Defined `GetPendingMerchantsSummary` on `AccountsInternalService` with bounded pagination (page, size 1-50), status filter ("PENDING"), and total pending count for GraphQL `adminDashboard` query projection.
- **Java Generation Module (`shared/proto-contracts`):** Pinned `protobuf-maven-plugin:0.6.1`, `protoc:3.25.5`, `io.grpc:protoc-gen-grpc-java:1.68.1`, `io.grpc:grpc-bom:1.68.1`, and Java 21 LTS (`com.swiftdrop:proto-contracts:0.0.1-SNAPSHOT`).
- **Zero Shared Entity JAR Invariant:** Packages wire contracts and stubs only; zero database entities or shared models.
- **Security & Actor Delegation Helper:** Implemented `SwiftDropGrpcMetadata` providing constants for `x-swiftdrop-service-token`, `x-swiftdrop-actor-sub`, `x-swiftdrop-actor-roles`, `x-swiftdrop-correlation-id`, and `DEFAULT_DEADLINE_MS` (2000ms bounded timeout).
- **Validation Evidence:**
  - Automated Java test suite: **14 tests PASS** across 5 test classes (0 failures, 0 skips):
    - `AccountsContractTest`: Courier eligibility, merchant key validation, contact resolution, pending merchant summary.
    - `PricingContractTest`: Quote validation, minor units, mismatch detection.
    - `DeliveryContractTest`: Parcel internal projection, courier workload count.
    - `SecurityMetadataAndDeadlineTest`: Service token authentication rejection, metadata propagation, 50ms deadline timeout (`DEADLINE_EXCEEDED`).
    - `ContractDescriptorAndSerializationTest`: Descriptor validation, binary serialization roundtrip, JSON parser/printer.
  - Python contract validation gate: **PASS** across all 4 gates (OpenAPI, GraphQL, Kafka, Protobuf).
  - Python contract unit test suite (`test_protobuf_contracts.py`): **4 tests PASS**.
  - Bit-for-bit reproducibility: Clean builds compared across two independent clean runs; 36 generated files verified with **zero SHA-256 hash mismatches**.

## Issue #96 Communications Service Foundation & Durable Job Schema — 2026-10-24

Primary Implementer: **Thuvarahan (@thuvarahan-t)** (Communications / Backend Lead); branch `feat/n10-b16-communications-foundation`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Accounts/data ownership & persistence review), **Suthankan1 (@Suthankan1)** (Platform/integration architecture review).

Delivered the Communications Spring Boot microservice foundation and independently owned durable job schema:
- **Application Foundation:** Scaffolded Communications Service in `services/communications-service/` using pinned Java 21 LTS and Spring Boot 4.1.1. Package root `com.swiftdrop.communicationsservice`.
- **Independent Database & Schema Ownership (`communications_db`):** Flyway V1 migration `V1__create_communications_schema.sql` establishes the 5 service-owned tables: `consumed_events`, `notifications`, `webhook_subscriptions`, `webhook_deliveries`, and `webhook_attempts`. Zero cross-service database access, shared tables, or foreign keys to Accounts or Delivery.
- **Durable Deduplication & Invariants:** `consumed_events` enforces composite PK `(consumer_name, event_id)` enabling independent consumer groups to process identical events without key conflicts. `notifications` enforces `(event_id, recipient_sub, channel)` uniqueness; `webhook_deliveries` enforces `(subscription_id, event_id)` uniqueness. `webhook_subscriptions` enforces HTTPS-only endpoints (`^https://`).
- **Atomic Transaction Boundary (`DurableJobPersistenceService`):** Created the transaction service providing atomic persistence of inbox receipts and durable notification/webhook jobs (`persistInboxAndNotification`, `persistInboxAndWebhookDeliveries`). Rolls back atomically on constraint violations; raises `DuplicateEventException` on replayed events. Ready for Kafka consumer handoff under Issue #32.
- **Fail-Closed Configuration & Health:** Requires `COMMUNICATIONS_DB_URL`, `COMMUNICATIONS_DB_USERNAME`, `COMMUNICATIONS_DB_PASSWORD`. Startup fails immediately if database configuration is missing or invalid. Exposes `/actuator/health` check without credentials.
- **Automated Validation Evidence:**
  - Communications Service unit and slice test suites: **19 tests PASS** (0 failures, 0 skips).
  - Tests verify atomic commit, transaction rollback, composite PK deduplication, Flyway V1 schema constraints, entity lifecycle callbacks, and missing datasource startup failure.
  - Testcontainers PostgreSQL IT harness (`CommunicationsPersistencePostgresIT`) created for full failsafe execution on real PostgreSQL 16.
  - Strict scope bounds preserved: No Kafka consumers, email/SMS senders, webhook HTTP dispatchers, or retry workers implemented (handoff to Issues #32 and #21).

## Issue #22 Merchant Credential Lifecycle & Tenant-Bound Order Contract — 2026-10-24

Primary Implementer: **Thuvarahan (@thuvarahan-t)** (Merchant Backend Lead); branch `feat/issue-22-merchant-credential-lifecycle`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Accounts provider ownership & database boundaries), **Vibhushan (@Vibhushan-2003)** (Delivery contract & independent security boundary review — 1h), **Eric (@EricPraveen)** (Merchant contract & UI consumer review — 1.5h), **Suthankan1 (@Suthankan1)** (Platform/security contract coordination).

Implemented the bounded Merchant API Credential Lifecycle in Accounts & Partners Service and established the reviewed tenant-bound order contract:
- **Credential Security Decision Formalized:** Published [ADR-0010](../architecture/adr/ADR-0010-merchant-credential-lifecycle.md) establishing Option A: Scoped API Keys (`swd_live_<base62_32_chars>`), SHA-256 one-way hashing, constant-time validation (`MessageDigest.isEqual`), 3-key active rotation limit, and scopes `parcels:create`, `parcels:read`, `webhooks:manage` (`DEC-MER-01`, `02`, `04`).
- **PostgreSQL Persistence & Immutability:** Accounts owns `merchant_api_keys` table and Flyway migration `V4__create_merchant_api_keys.sql`. Includes foreign key to `merchants(id)`, unique key digest constraint, index on `key_prefix`, and PostgreSQL trigger `merchant_api_key_immutable` preventing updates to `id`, `merchant_id`, `key_digest`, and `created_at`.
- **Zero Plaintext Secrets:** Cryptographically secure `SecureRandom` Base62 generator (256-bit entropy). Plaintext key returned strictly once in `POST /api/v1/merchants/{id}/api-keys` response. Metadata listing `GET /api/v1/merchants/{id}/api-keys` strictly omits secret and digest.
- **Strict Tenant & Approval-State Isolation:** Enforces Keycloak JWT subject binding. Non-merchant and cross-tenant callers fail closed with RFC 7807 403 Forbidden (`TENANT_ACCESS_DENIED`). Merchants in `PENDING`, `SUSPENDED`, or `REJECTED` states are denied key issuance and key validation (`MERCHANT_NOT_APPROVED` -> 403 Forbidden).
- **Durable Revocation:** `DELETE /api/v1/merchants/{id}/api-keys/{keyId}` durably marks `revoked_at = NOW()`. Subsequent verification fails closed (`401 Unauthorized`). Idempotent revocation returns `204 No Content`.
- **In-Process gRPC Provider Foundation:** Implemented `MerchantApiKeyValidator` matching `AccountsInternalService.ValidateMerchantKeyDigest` (`IF-GRPC-02`).
- **Contract & Frontend Handoff:** Updated `contracts/rest/openapi-accounts.yaml`, documented routes in `docs/contracts/REST.md` and `SERVICE_INTERFACE_MATRIX.md`, and authored comprehensive frontend handoff in `docs/team/MERCHANT_CREDENTIAL_FRONTEND_HANDOFF.md` for Eric's Issue #120.
- **Validation Evidence:**
  - Accounts Service unit and security test suites: **141 tests PASS** (36 new credential tests: 18 unit tests, 14 Spring Security / WebMvc slice tests with real signed JWTs, 4 controller tests; 0 failures, 0 skips).
  - Testcontainers PostgreSQL IT execution: Blocked by host Docker daemon unavailability (`failed to connect to docker API`); unit and MockMvc slice suites fully cover persistence constraints, DTO contracts, and security rules.
  - Delivery Operations runtime: Downstream service runtime (`services/delivery-service`) un-scaffolded (placeholder README only); full delivery order creation reported honestly as **BLOCKED** on Delivery Operations implementation.

## Issue #17 Independent Clean-Clone Reproduction (L10) & Merchant Review — 2026-10-22

Primary Reviewer & QA: **Eric Praveen Alric Prashanth (@EricPraveen)**; branch `feat/nov-09-merchant-login-reproduction`, targeting `test`.
Reviewers: **Suthankan1 (@Suthankan1)** (Platform lead), **Vibhushan (@Vibhushan-2003)** (Security QA lead), **Thuvarahan (@thuvarahan-t)** (Merchant provider owner).

Delivered independent peer verification of milestone acceptance item **L10** from a clean clone state and conducted comprehensive Merchant architecture review:
- **Independent Full-Stack Startup:** Successfully provisioned and verified Docker containers (`swiftdrop-accounts-postgres` on :5438, `devarom-keycloak` on :8090, `devarom-mailpit` on :8025), Accounts & Partners Service on :8081, Spring Cloud Gateway on :8080, and Vite Frontend SPA on :5173.
- **Preflight Toolchain & Health (`check-readiness.sh`):** **13/13 PASSED** with 0 warnings and 0 failures. All dependencies (Java 21, Node 24, pnpm 10, Docker 28, Python 3.9), endpoints, JWKS, custom theme, Mailpit API, and profile isolation verified.
- **Automated Demo Runner (`demo_smoke.py`):** **4/4 Scenarios PASSED**:
  - Scenario A: Self-registration & email verification via Mailpit.
  - Scenario B: Verified customer login and profile inspection.
  - Scenario C: Authenticator app TOTP MFA challenge.
  - Scenario D: Edge security denials and boundary controls.
- **Security E2E Suite (`run-security-e2e.sh`):** **ALL 4 PHASES PASSED**:
  - 40 security tests: 36 PASS, 0 FAIL, 4 BLOCKED by planned downstream provider endpoints (admin users, courier deliveries, explicit user lookup, merchant orders).
  - 14 static platform identity invariant checks PASSED.
  - 8/8 live TOTP MFA test suites PASSED.
  - Spring Cloud Gateway: 19/19 tests PASSED.
  - Accounts Service: 44/44 tests PASSED.
  - Frontend SPA: 101/101 tests PASSED.
- **Interactive Browser Journey (`login_journey_verification_1792651905640.webp`):** Verified full interactive customer registration, Mailpit email link retrieval, TOTP MFA challenge, `/customer` profile loading, role boundary guard blocking `/merchant` for CUSTOMER role (`<FeedbackState>` access denial), and clean token wipe upon logout.
- **Setup Blockers Identified & Fixed:**
  - Resolved host PostgreSQL port collision in `platform/local/start-local.sh` by hardening port discovery to default safely to `5438`.
  - Configured Keycloak admin user with valid `.test` mailbox to pass Mailpit preflight SMTP checks.
  - Fixed composite role query in `platform/identity/tests/totp_smoke.py` to target `default-roles-{self.realm}` (`default-roles-swiftdrop`).
  - Documented Python `cryptography` dependency for synthetic RSA signature generation.
- **Merchant Architectural Review (Figures 02, 11, 12, 16, 20):**
  - Confirmed alignment of `MerchantStatus` model (`PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`) and `<MerchantApprovalBanner>` with Figures 02 & 11.
  - Confirmed tenant boundary isolation (`merchantId`) and fail-closed handling for unapproved states.
  - Verified Figure 20 honesty: clear boundary maintained between implemented November 1 login slice and planned enterprise services (Kafka KRaft, GraphQL read-only gateway, SSE parcel tracking).

## Issue #16 Dispatcher/Admin role landings — 2026-10-22

Pushmitha (@pushmitha20), branch `feat/issue-16-dispatcher-admin-landing`, targeting `test`: distinct `/dispatcher` and `/admin` pages, exact-role `ProtectedRoute` guards, role-aware shared navigation, responsive staff cards, shared logout and honest staff-profile unavailability. No shared authentication implementation or other role page was changed. Admin alone cannot enter Dispatcher space, and Dispatcher alone cannot enter Admin space.

Implementation commit: `fca90f6e853f8da593fbb7977e63e70893ffc3ee`; base `test`: `692a35e905fc4af0f529eff79489e6d1230ea1a6`. Windows/Node 24.21.0/pnpm 12.11.2: frozen install, typecheck, **12 Vitest files / 143 tests passed, 0 failed**, ESLint (0 errors) and production build passed. Synthetic auth-context tests cover direct URL denials, all other roles, no provider requests, session/role loss, URL/browser flag rejection, multi-role navigation and shared logout. These are UI tests, not real token/backend security evidence.

**Issue #14 remains open with no merged staff provider. API consumed: none.** No staff DTO/endpoint, basic `/me` workaround, query cache, fake identity or business metric is introduced. Real staff sign-in/positive reads, backend cross-role/anonymous/subject denials through Gateway and direct Accounts, profile error/retry/ready states and live provisioning evidence are **BLOCKED**, not passing. No browser is connected, so browser/mobile demos/screenshots were not captured. Project #5 read/update is blocked by missing project scope. See [#16 evidence and next integration step](../milestones/LOGIN_ACCEPTANCE.md#issue-16-dispatcheradmin-ui-evidence--2026-10-22). Independent HarithKeshan/Gajanthan/Platform review and full Issue #16 acceptance remain pending.

## Issue #7 OIDC Sequence, Accounts ERD & Master Login Evidence Review — 2026-10-22

Lead Architectural Reviewer: **Gajanthan (@Gajanthan23)** (Customer Lead FE & Customer Feature Steward); branch `docs/nov07-oidc-erd-evidence`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Customer Lead BE & Accounts DB Steward), **Suthankan1 (@Suthankan1)** (Platform Lead, shared OIDC auth, Gateway & Accounts JWT), **Vibhushan (@Vibhushan-2003)** (Security QA Lead, Issue #18), **HarithKeshan (@Harithkeshan)** (Academic report assembly, Issue #19), **Eric (@EricPraveen)** (Clean-clone reproduction, Issue #17).

Delivered comprehensive Customer pair architectural review of the OIDC login sequence, Accounts database ERD vs. actual schema, and master login acceptance evidence mapping for the November 1 milestone:
- **OIDC Login Sequence Review (Figure 13):** Traced complete 12-step local customer registration/login flow and brokered Google OIDC authentication flow. Corrected obsolete wording in Figure 13 note claiming Google login is "planned, not verified" — now formally verified PASS (L03) with user-completed Google sign-in, profile load (HTTP 200), CUSTOMER role confinement, and clean logout with zero sessions. Auth tokens are stored strictly in browser memory, with immediate address-bar URL sanitization (`history.replaceState`).
- **Accounts Database ERD Review (Figure 15):** Compared conceptual ERD against Flyway migrations (`V1__create_account_profiles.sql`, `V2__create_merchants.sql`) and JPA entities. Rigorously verified table renames (`account_profiles`, `merchants`), UUID PKs (`gen_random_uuid()`), and database triggers enforcing immutable identity (`account_identity_immutable`, `merchant_identity_immutable`). Documented architectural justification for the intentional elimination of `ROLES` and `USER_ROLES` tables from PostgreSQL: Keycloak is single source of truth; roles are extracted statelessly from signed JWT claims (`realm_access.roles`) into `TrustedIdentity`. Documented that operational courier data lives in Delivery Operations rather than `accounts_db`.
- **Master Login Acceptance Evidence Mapping:** Mapped all 12 acceptance criteria (L01–L12) to requirements, application components, automated test harnesses, evidence records, observed results, and blockers. L01, L02, L03, L04, L06, L08, L09, and L11 verified PASS; L05 partially verified (CUSTOMER registration confined; staff role views in progress); L07 verified for existing endpoints and fail-closed for unmapped endpoints; L10 pending Eric's clean-clone reproduction; L12 in progress with review package.
- **Security & Privacy Hygiene:** Zero secrets or bearer tokens in Git, logs, or address bar. Confirmed public Vite bundle contains zero client secrets.
- **Deliverable:** Created [`docs/reviews/NOV_07_OIDC_ACCOUNTS_LOGIN_REVIEW.md`](../reviews/NOV_07_OIDC_ACCOUNTS_LOGIN_REVIEW.md), updated [`docs/architecture/OPEN_DECISIONS.md`](../architecture/OPEN_DECISIONS.md) with `DEC-ACC-01`, and prepared report captions for Issue #19. Zero application code or security changes (strictly design review).

## Issue #9 Merchant Role Landing and Approval-State UI — 2026-10-22

Primary Implementer: **Eric Praveen Alric Prashanth (@EricPraveen)**; branch `feat/nov-08-merchant-approval-ui`, targeting `test`.
Reviewers: **Thuvarahan (@thuvarahan-t)** (Merchant provider owner), **Gajanthan (@gajan250)** (Customer/Frontend SPA owner), **Suthankan1 (@Suthankan1)** (Platform lead).

Delivered protected merchant landing page and approval-state UI adhering strictly to existing design system and backend contracts:
- **Canonical Route Registration:** Wired `/merchant` inside `frontend/src/app/router/routes.tsx` using `<ProtectedRoute role="MERCHANT" />`. Non-merchant and unauthenticated sessions fail closed and redirect without issuing backend profile requests.
- **Approval-State UI (`MerchantApprovalBanner`):** Grounded in Accounts Service `MerchantStatus` enum (`PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`) and Contract 1:
  - `PENDING`: Explains administrative verification in progress; locks commercial dispatching, batch order entry, and webhook API access; provides expected verification timeline (1–2 business days).
  - `APPROVED`: Displays active and verified status; unlocks operational capabilities overview.
  - `REJECTED` / `DENIED`: Displays registration rejection notification with support appeal guidance (`support@swiftdrop.test`).
  - `SUSPENDED`: Displays operational hold notice and support contact instructions.
  - `UNKNOWN` / Missing: Provides safe fallback without crashing.
- **Account Context & Details (`MerchantProfile`):** Displays verified fields from `CurrentUserProfile` and `merchantProfile` (`businessName`, `registrationNumber`, `merchantId`, `fullName`, `email`, `phoneNumber`, `keycloakSub`, `createdAt`). Displays empty state if `merchantProfile` is null.
- **Security & Cross-Tenant IDOR Guards:** Enforces `keycloakSub` match against authenticated actor context; mismatches fail closed with `AccountsError('forbidden')` without rendering profile details.
- **Navigation Integration:** Conditionally renders "Merchant space" nav link in `AppLayout` only for authenticated `MERCHANT` sessions.
- **Verification:** 10 Vitest test suites (101 tests passed), zero lint errors (`eslint .`), strict TypeScript check (`tsc -b`), and production build (`vite build`) passing cleanly.

## Issue #13 Dispatcher/Admin design review — 2026-10-22

Pushmitha (@pushmitha20): [review artifact](../reviews/DISPATCHER_ADMIN_REVIEW.md), corrected editable/SVG Figures 04/08, scoped role/use-case narratives, assignment/concurrency exception walkthroughs and NOV-19 captions/contribution evidence prepared. **Independent review pending; Issue #13 remains open.** Courier/Delivery, report and Platform owners must review the cross-module findings and DA-01–DA-04 decisions; no policy approval or runtime assignment feature is claimed. GitHub Project #5 status could not be read/updated because the available credential lacks project scope; board status is unverified.

## Issue #10 Communications ERD, Merchant Integration & Reliability Review — 2026-10-21

Lead Architectural Reviewer: **Thuvarahan (@thuvarahan-t)**; branch `docs/nov11-communications-merchant-review`, targeting `test`.
Reviewers: **Vibhushan (@Vibhushan-2003)** (Delivery events & SSE topology), **Suthankan1 (@Suthankan1)** (Platform lead, Kafka contracts & webhook retry engine), **Eric (@EricPraveen)** (Merchant contract), **HarithKeshan (@Harithkeshan)** (academic report assembly).

Delivered comprehensive architectural review of Communications ERD, Merchant integration, and reliability rationale for November 1 milestone:
- **Communications ERD Review (Figure 16):** Confirmed Communications service ownership of `WEBHOOK_SUBSCRIPTIONS`, `WEBHOOK_DELIVERIES`, `CONSUMED_EVENTS`, `NOTIFICATIONS`, and child `WEBHOOK_ATTEMPTS` in dedicated `communications_db`. Enforces strict invariant that Communications does **not** own parcel transitions. Local FKs, external references (`EXT merchant_id`, `EXT parcel_id`, `EXT recipient_sub`), and unique constraints (`UK (subscription_id, event_id)`) verified.
- **Merchant Integration Review (Figures 02, 11, 12):** Mapped four integration seams: Webhook Subscription Management (REST), Order API (REST with synchronous Accounts key validation gRPC), Asynchronous Status Event Streaming (`parcel.lifecycle.v1`), and Signed Webhook Dispatch. Confirmed decoupling of order ingestion from downstream notification latency.
- **Reliability Rationale & SSE Topology (Figure 18):** Justified Kafka KRaft at-least-once asynchronous domain events with per-parcel ordering (`key = parcelId`) and state versioning. Formally documented that universal exactly-once external side-effects are not possible; merchant receivers must deduplicate via `eventId`. Rigorously analyzed SSE scaling invariant: each Delivery tracking replica requires a unique consumer group (`tracking.<replica-pod-id>`) rather than a shared group, with canonical REST fallback on reconnect.
- **Security & Tenant Isolation:** Verified strict tenant isolation in Communications webhooks matching Accounts `MerchantTenantAuthorizationProvider` boundary. Strips client headers at Gateway. Enforced event payload sanitization (zero customer PII in Kafka). Prohibited storing raw webhook response bodies.
- **Deliverable:** Created [`docs/reviews/NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.md`](../reviews/NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.md), updated [`docs/architecture/OPEN_DECISIONS.md`](../architecture/OPEN_DECISIONS.md) with `DEC-COM-01` to `DEC-COM-04`, and prepared report-ready captions for Issue #19. Zero application code generated (strictly design review).

## Issue #8 Merchant Approval-State & Tenant Authorization Provider — 2026-10-21

Primary Implementer: **Thuvarahan (@thuvarahan-t)**; branch `feat/nov10-merchant-approval-tenant-auth`, targeting `test`.
Reviewers: **Sinthuha (@Sinthuha-n)** (Accounts ownership), **HarithKeshan (@Harithkeshan)** (approval policy), **Eric (@EricPraveen)** (Merchant UI consumer), **Vibhushan (@Vibhushan-2003)** (independent security QA).

Delivered minimal merchant approval-state representation, in-process provider interfaces, and fail-closed tenant authorization boundary for Accounts Service:
- **Approval Lifecycle Model:** Introduced `MerchantStatus` (`PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`). Authentication via Keycloak establishes identity only; registration defaults to non-operational pending status and cannot self-elevate to approved or staff status.
- **PostgreSQL Persistence & Immutability:** Accounts owns the `merchants` table and Flyway migration `V2__create_merchants.sql`. Unique constraints enforce 1-to-1 binding between Keycloak subject and tenant, and trigger `merchant_identity_immutable` prevents tampering with tenant identity or subject binding on UPDATE.
- **Provider Interfaces Implemented:**
  - `MerchantApprovalProvider`: In-process provider resolving `MerchantStatus` by tenant ID or Keycloak subject (`getApprovalStatus`, `isApproved`).
  - `MerchantTenantAuthorizationProvider`: Evaluates tenant authorization (`authorizeTenantAccess`, `requireTenantAccess`, `requireApprovedTenantAccess`, `resolveMerchantForIdentity`).
- **Fail-Closed Security & Tenant Isolation:**
  - Merchant A cannot access Merchant B resources (rejected with `TenantAccessDeniedException` -> RFC 7807 403 Forbidden).
  - Unauthenticated requests rejected with 401 Unauthorized.
  - Non-merchant/customer roles attempting tenant access rejected with 403 Forbidden.
  - Nonexistent merchant IDs return RFC 7807 404 Not Found.
  - Operations requiring approval fail closed (`MerchantNotApprovedException` -> 403 Forbidden) if status is PENDING, SUSPENDED, or REJECTED.
  - Admin role permitted tenant inspection for onboarding review/audit.
- **Current User Profile Integration:** `GET /api/v1/users/me` resolves the caller's bound merchant profile and returns server-controlled state (`merchantId`, `businessName`, `registrationNumber`, `status`) for `MERCHANT` role, while returning `null` for `CUSTOMER`.
- **Protected REST Seams:** `GET /api/v1/merchants/me`, `GET /api/v1/merchants/{merchantId}`, `GET /api/v1/merchants/{merchantId}/status`.
- **Validation Evidence:**
  - Accounts unit/MVC/security suite: **79 tests PASS** (35 new tests added, 0 failures, 0 skips).
  - Gateway tests: **19 tests PASS**.
  - Frontend suite: **80 tests PASS**.

## Issue #18 Independent Security End-to-End QA Verification — 2026-10-21

Independent QA Lead: **Vibhushan (@Vibhushan-2003)**; branch `tests/issue-18-independent-security-qa`, targeting `test` (based on `origin/test` commit `b974af0`).
Formal independent peer verification of milestone acceptance items L06, L07, and L08 completed:
- **L06 (JWT Authentication Rejection):** 27 automated tests passing across Gateway (:8080) and Accounts (:8081). Missing, malformed, unsigned (`alg=none`), untrusted signature, wrong issuer, wrong audience, expired, and not-yet-valid tokens strictly rejected with HTTP 401 RFC 7807 ProblemDetail.
- **L07 (Role Denial & Privilege Confinement):** Authenticated tokens without approved application roles rejected with HTTP 403 Forbidden. Keycloak self-registration verified strictly confined to `CUSTOMER` role (zero staff self-elevation).
- **L08 (Resource Ownership & Tenant Isolation):** Customer A and B profile isolation verified using authentic Keycloak PKCE tokens. Gateway strips spoofed identity headers and query parameters; Accounts ignores forged headers and strictly resolves identity from verified JWT `SecurityContext`.
- **Blocked Downstream Dependencies (4 cases):** Unmapped staff admin (`/api/v1/admin/users`), courier (`/api/v1/courier/deliveries`), explicit ID lookup (`/api/v1/users/{id}`), and merchant tenancy (`/api/v1/merchants/orders`) endpoints fail-closed (HTTP 404/401) and remain tracked honestly as BLOCKED against Issues #5, #8, #14, and #15.
- **Regressions Verified:** Full Python security runner (36 PASS / 0 FAIL / 4 BLOCKED), Gateway tests (19/19 PASS), Accounts tests (37/37 PASS), and Frontend tests (70/70 PASS).
- **Formal QA Report:** Authored [`docs/milestones/ISSUE_18_INDEPENDENT_SECURITY_QA_REPORT.md`](../milestones/ISSUE_18_INDEPENDENT_SECURITY_QA_REPORT.md).

## SwiftDrop Phase 4B Team Kickoff, QA Handoff & Release Prep — 2026-10-21

Owner **Suthankan1**; branch `chore/nov1-phase4b-handoff`, based on `origin/test` (`0d44d15`).
Phase 4B coordinates team onboarding, independent QA handoffs, and release preparation:
- **Collaborator Access:** Verified 7 active members and 2 pending invitations (`@thuvarahan-t`, `@Vibhushan-2003`).
- **Independent Clean-Clone QA (Issue #17):** Handoff instructions posted to [Issue #17](https://github.com/Suthankan1/swiftdrop/issues/17#issuecomment-6312161904) for Eric (@EricPraveen). Acceptance criterion L10 remains PENDING independent execution.
- **Independent Security QA (Issue #18):** Handoff instructions posted to [Issue #18](https://github.com/Suthankan1/swiftdrop/issues/18#issuecomment-6312165950) for Vibhushan (@Vibhushan-2003). Acceptance criteria L06, L07, and L08 remain PLATFORM SELF-VERIFIED (36 PASS / 0 FAIL / 4 BLOCKED), with independent peer verification PENDING.
- **Academic Report Handoff (Issue #19):** Evidence and diagram package posted to [Issue #19](https://github.com/Suthankan1/swiftdrop/issues/19#issuecomment-6312174255) for HarithKeshan (@Harithkeshan). Strict separation of IMPLEMENTED vs PLANNED features documented.
- **Five-Role UI Route Blueprint:** Documented route guards, component location, and test pattern for missing role views (`/merchant`, `/courier`, `/dispatcher`, `/admin`) reusing existing `ProtectedRoute` and `useAuth()`.
- **Release Preparation:** Branch divergence confirmed (`0d44d15` vs `c11bf89`); working tree diff is strictly documentation; zero code conflicts.

## SwiftDrop Phase 4A Milestone Readiness Tracker — 2026-10-21

Owner **Suthankan1**; branch `chore/nov1-team-onboarding-readiness`, merged into `test` as PR #71 (`0d44d15`).
Phase 4A delivered the comprehensive November 1 Milestone Readiness & Team Onboarding Tracker:
- **Milestone Tracker Published:** Created `docs/milestones/NOV_01_READINESS_TRACKER.md` with full toolchain prerequisites, clean clone runbook, service startup instructions, and live demo script.
- **Milestone Scope Alignment:** Updated `docs/milestones/NOV_01.md` with direct references to the active tracker.
- **Team Onboarding Runbook:** Standardized step-by-step local workstation setup across Java 21, Node 24, Docker Compose, Keycloak, PostgreSQL, Gateway, Accounts, and React SPA.

## SwiftDrop Phase 3C Complete Technical Rebranding — 2026-10-21

Owner **Suthankan1**; branch `refactor/swiftdrop-complete-technical-rebrand`, based on `origin/test` (`39a1f1c`).
Phase 3C completes the technical naming migration (`Devarom` -> `SwiftDrop`) across all active codebases, Maven coordinates, Java packages, Keycloak login themes, container naming conventions, application metadata, test fixtures, and developer runbooks:
- **Java Packages Migrated:** `com.devarom.*` -> `com.swiftdrop.*` across `services/api-gateway` (11 files) and `services/accounts-service` (23 files).
- **Maven Group ID:** `<groupId>com.swiftdrop</groupId>` standardized in both `pom.xml` files with updated project metadata and SCM URLs pointing to `https://github.com/Suthankan1/swiftdrop.git`.
- **Frontend Branding:** `@swiftdrop/frontend` in `package.json`, `SwiftDropRole` enum introduced with `DevaromRole` alias. All 70 unit tests, typecheck, lint, and build PASS.
- **Keycloak Login Theme:** Created `platform/identity/themes/swiftdrop` with custom CSS, dual-class support (`.swiftdrop-*` and `.devarom-*`), SVGs, and messages. Updated canonical `realm-swiftdrop.json` to `"loginTheme": "swiftdrop"`. Applied theme dynamically to running Keycloak container via `apply-theme.sh`.
- **Docker Naming Strategy:** Dual-mode design implemented. Fresh installations use canonical `swiftdrop-keycloak`, `swiftdrop-mailpit`, `swiftdrop-accounts-postgres`, and volume `swiftdrop-accounts-postgres-data`. Running environments preserve `devarom-*` containers and volumes via parameterized compose and automatic container discovery in scripts (`start-local.sh`, `check-readiness.sh`, `run-security-e2e.sh`, `backup-restore-identity.py`). Zero data loss.
- **Full Regression Validation:**
  - Gateway: 19 unit & routing tests PASS, package JAR created.
  - Accounts: 44 unit & PostgreSQL integration tests PASS (63 total), package JAR created.
  - Frontend: 70 unit tests PASS, typecheck, lint, build PASS.
  - Identity: 14/14 static invariant checks PASS, all live container smoke checks PASS.
  - Security E2E: 36 PASS, 0 FAIL, 4 BLOCKED (retained per missing teammate endpoint baseline).
  - Demo Smoke: Scenarios A, B, C, D all PASS.
- **Audit Documentation:** Completed `docs/branding/PHASE_3_TECHNICAL_REBRAND_AUDIT.md`.

## SwiftDrop Phase 3B Post-GitHub-Rename Cleanup & Verification — 2026-10-21

Owner **Suthankan1**; branch `chore/swiftdrop-repository-links`, based on `origin/test` (`97742bf`).
Phase 3B completes the repository migration following the official GitHub rename from `Suthankan1/devarom` to `Suthankan1/swiftdrop`:
- Canonical remote URL verified: `https://github.com/Suthankan1/swiftdrop.git`.
- Release PR #66 merged to `main` (`8961ea2`).
- `test` branch is at `97742bf` (2 commits behind `main` due to release merge PR #66, with 0 test-only commits diverging).
- Documentation updated: `README.md`, `CLEAN_CLONE_RUNBOOK.md`, `GIT_WORKFLOW.md`, `PLATFORM_HANDOFF_NOV01.md`, `PHASE_2_FINAL_SIGNOFF.md`, `STATUS.md`.
- Clone instructions canonicalized to `git clone -b test https://github.com/Suthankan1/swiftdrop.git`.
- Team Git workflow instructions documented for local remote migration: `git remote set-url origin https://github.com/Suthankan1/swiftdrop.git`.
- GitHub Actions verification: `.github/workflows` is **NOT CONFIGURED** (by architectural design; CI is not yet introduced).
- Preserved identity and application data: Zero changes to Keycloak realm storage, Docker containers (`devarom-keycloak`, `devarom-accounts-postgres`, `devarom-mailpit`), Docker volumes, PostgreSQL schemas, user UUIDs, Flyway migrations, or Java package namespace `com.devarom.*`.
- Identity contract remains canonical: Realm `swiftdrop`, Client `swiftdrop-frontend`, Audience `swiftdrop-api`.

## SwiftDrop Phase 2 Identity Migration Finalization — 2026-10-21

Owner **Suthankan1**; branch `fix/swiftdrop-phase2-finalization`, based on merged PR #64 (`0b01f0ae0af73c8d5d196b529d749706a7881a2c`).
Canonical SwiftDrop identity contract across the repository:
- Realm: `swiftdrop` (canonical development default; legacy immutable internal container ID preserved)
- Public Client: `swiftdrop-frontend` (standard flow, PKCE S256, direct grant disabled)
- JWT Audience: `swiftdrop-api`
- Issuer: `http://localhost:8090/realms/swiftdrop`
- Broker Callback: `http://localhost:8090/realms/swiftdrop/broker/google/endpoint`

**Verification Summary:**
- **Fresh-Install Bootstrap:** Isolated Keycloak container imported `realm-swiftdrop.json` without errors. All 14 static invariant checks and live smoke checks passed on fresh container (`http://localhost:8190`).
- **Live Local Environment:** Existing container `devarom-keycloak` intact and healthy. Discovery at `http://localhost:8090/realms/swiftdrop/.well-known/openid-configuration` verified. Legacy `/realms/devarom` returns HTTP 404.
- **Frontend SPA:** Canonical defaults in `src/auth/session.ts` updated to `swiftdrop` / `swiftdrop-frontend`. All 70 unit tests, typecheck, lint, and build passed.
- **Gateway & Accounts Services:** Canonical application defaults updated to `http://localhost:8090/realms/swiftdrop` and `swiftdrop-api`. All 19 Gateway tests and 44 Accounts tests passed.
- **Full Security E2E Suite:** 36 PASS / 0 FAIL / 4 BLOCKED (retained honestly per missing-endpoint baseline).
- **Readiness Verification:** `platform/local/check-readiness.sh` reported 13/13 PASS.
- **Human Interactive Google Login:** Operator-verified PASS on migrated environment. Customer profile and token persistence preserved without data loss.
- **Zero Secrets Committed:** Confirmed no passwords, Google OAuth secrets, or tokens committed in repo.

## SwiftDrop Phase 1 branding — 2026-10-20

Owner **Suthankan1**; branch `chore/swiftdrop-branding`, based on `origin/test`
`eff629af085299a5560e0b3c912e46ea4d056d42`. Current SPA/Keycloak theme copy,
wordmarks, current documentation and all 20 editable/rendered diagram pairs now
use **SwiftDrop**. Authentication/runtime identifiers and historical evidence
remain unchanged. The source rebrand does not rename the GitHub repository.

**Verified:** frontend typecheck, 64 tests, lint and build; 62 identity Python
tests; 14 static identity checks and live identity smoke; Gateway 13 tests;
Accounts 37 tests; readiness 13/13; login demo scenarios A–D; security E2E
36 PASS / 0 FAIL / 4 existing missing-provider BLOCKED cases. Browser home,
login and native Keycloak login/registration display SwiftDrop; anonymous
Customer access still redirects to login. See [commands, limits and external
branding dependencies](../branding/VALIDATION.md).

**Google:** broker/probe/negative callback checks passed; interactive Google
sign-in was **not rerun** for branding. Existing real L03 PASS below remains
historical evidence. Persisted realm/client/email display labels and Google
consent-screen branding need an owner-applied presentation-only update;
independent peer/clean-clone review remains pending. Phase 2 identity/namespace
and repository changes are [proposed only](../branding/PHASE_2_MIGRATION.md).

Next small task: review the branding PR into `test` and coordinate the remaining
persisted/provider display-label update without recreating identity storage.

## Issue #36 verification — 2026-10-16

Owner Suthankan1; branch `feat/react-oidc-authentication`, based on merged scaffold PR #41 (`b680ce7`). Changes are frontend authentication, reserved route/provider seams, subject-bound customer profile integration and documentation only. Devarom v1.3 and backend contracts/security remain unchanged.

- `pnpm install --frozen-lockfile`: passed using pnpm 12.11.2 / Node 24.21.0.
- `pnpm typecheck`: passed (strict referenced TypeScript projects).
- `pnpm test --run`: 56 tests across 7 files; isolated mocks, including the real adapter with synthetic token responses. No live identity/profile success claimed.
- `pnpm lint`: passed with no warnings.
- `pnpm build`: passed.
- `git diff --check`: passed; staged secret review performed before commit.
- Chrome browser: anonymous customer guard, provider-unavailable feedback, sanitized cancelled callback verified at localhost:5173. Vite started successfully.
- Live local/Google login: **unverified**. `docker ps` reports Docker daemon unavailable; Keycloak discovery on localhost:8090 returns curl connection failure (HTTP 000). Google credentials/consent/test-user status not inspected. L03 stays unverified.
- Live Gateway → Accounts profile: **pending** #5 and #37 (both open); no fake success or backend code generated.
- Existing Issue #36 project card moved To Do → In Progress through the signed-in GitHub browser; API token lacks `read:project`. Move to In Review only after the real PR exists. Review is required; issue stays open.

Next smallest integration task: Sinthuha supplies the #5 Accounts provider/security seam so Suthankan1 can implement #37 separately. For frontend live evidence, first start local Keycloak and run the login/logout smoke steps in `frontend/README.md`.

## Issue #5 verification — 2026-10-16

Original Accounts business steward **Sinthuha**; temporary implementation author
**Suthankan1**, explicitly authorized due to availability. Customer classification
and #8/#14 business ownership remain unchanged. Branch
`feat/accounts-profile-foundation`, based on main `7ebd42a` (merged PR #42).
Implementation slices: `81bc34e` scaffold, `df8ff48` PostgreSQL/profile use case,
`5e4928f` fail-closed HTTP seam and security/contract tests.

- Exactly one Accounts MVC application, Java 21.0.12.1 / Boot 4.1.1 / Maven 3.10.0.
  Accounts-owned `accounts_db`, Flyway V1 and Hibernate schema validation; no
  application passwords, role grants, cross-service persistence or extra services.
- Trusted-identity seam, Accounts-generated UUID, unique immutable subject binding,
  targeted PostgreSQL conflict-safe insert/reload under READ COMMITTED. Optional
  missing profile strings are empty; merchant profile null; roles from trusted context.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) services/accounts-service/mvnw -f services/accounts-service/pom.xml clean verify -q`:
  **passed, 22 unit/MVC + 12 real Testcontainers PostgreSQL integration tests**, no
  failures/errors/skips. Clean migration/readback/constraints, eight concurrent
  first-login requests returning one profile, owner separation, frozen DTO and
  negative HTTP identity-header/bearer/Basic/authenticated-fixture tests verified.
- PostgreSQL 16.14 (`postgres:16-alpine`) migration V1 applied successfully and JPA
  validation passed. Separate packaged-JAR smoke on default port 8081: startup
  passed, health **200 / UP**, anonymous and spoofed `/api/v1/users/me` **401**.
  Temporary smoke app/database container stopped; existing identity setup untouched.
- `git diff --check` passed; staged source/config reviewed for secrets and scope.
  Frontend/Gateway contracts inspected without modifying either module.
- Production `/me` **denies access until #37**. No real JWT verification, Keycloak
  authenticated profile success, role-enforcement completion or full browser E2E
  is claimed. Login acceptance L02/L06/L08 remains pending real integration.

Next small task: #37 replaces `security/ProfileSecurityConfiguration.java` deny
policy and implements `identity/CurrentIdentityProvider.java` from independently
validated JWTs; add Resource Server dependencies and signed-JWT service tests.
See `services/accounts-service/README.md` for exact integration files/run steps.

## Issue #37 verification — 2026-10-16

Owner: **Suthankan1** (Platform & Security integration); branch `feat/accounts-jwt-security`, based on `main` merge commit `09bd6be` (PR #43).
Reviewers: **Sinthuha** (Accounts service owner), **Thuvarahan** (Accounts merchant consumer), **Vvibhu** (independent security QA).

Implemented independent OAuth2 Resource Server JWT security on Accounts Service:
- Added Spring Boot 4.1.1 OAuth2 Resource Server dependencies and MockWebServer test framework.
- Configured environment-backed properties consistent with Gateway (`KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWKS_URI`, `JWT_AUDIENCE=devarom-api`).
- Configured `NimbusJwtDecoder` with composed validation: cryptographic signature against Keycloak JWKS, exact issuer match, audience `devarom-api`, timestamp expiration and not-before, and non-blank subject `<= 255` characters.
- Implemented `KeycloakRoleExtractor` and `KeycloakJwtAuthenticationConverter` extracting authoritative application roles from `roles` and `realm_access.roles` claims, strictly filtering to the five approved roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) and ignoring Keycloak internal roles (`default-roles-devarom`, `offline_access`, `uma_authorization`).
- Implemented `SecurityContextCurrentIdentityProvider` extracting trusted identity (`subject`, `email`, `fullName`, `roles`) exclusively from the verified JWT principal in `SecurityContext`. Request headers (`X-User-Id`, `X-Keycloak-Sub`, etc.) and query parameters are never trusted.
- Configured stateless `SecurityFilterChain`: public access strictly to `/actuator/health` and `/actuator/health/**`, `/api/v1/users/me` requiring verified bearer token with an approved role, all other endpoints denied by default, Basic/form login disabled, and RFC 7807 `ProblemDetail` responses for 401 and 403.
- Extended `ProfileHttpSecurityTest` with 20 real signed-JWT security tests covering missing/malformed/unsigned/untrusted/expired/wrong-issuer/wrong-audience/wrong-role tokens, direct-service access, and actuator restrictions.
- Extended `AccountProfilePostgresIT` with 4 real end-to-end HTTP/PostgreSQL integration tests proving profile isolation between Customer A and B, repeated access returning the same ID, query param/spoof rejection, concurrent first-login safety, rejected token DB isolation, and merchant role mapping.

Verification Results:
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean verify` in `services/accounts-service`: **passed, 37 unit/MVC + 16 real Testcontainers PostgreSQL integration tests (53 tests total)**; zero failures, errors, or skips.
- `git diff --check`: passed; zero committed secrets or private keys.
- Live Keycloak 26.8.1 on port 8090: OIDC discovery, JWKS (2 keys), negative ROPC, and negative plain-PKCE checks passed.
- Live browser flow (React -> Keycloak -> Gateway -> Accounts -> PostgreSQL) verified in Issue #44.

Next small task: Branded Keycloak theme (Issue #44).

## Issue #44 verification — 2026-10-16

Owner: **Suthankan1**; branch `feat/keycloak-devarom-theme`, based on `main` merge commit `c6b31bb` (PR #45).
Reviewers: UI / Accessibility reviewer and Security reviewer.

Implemented custom branded Keycloak theme (`platform/identity/themes/devarom/login`) extending `keycloak.v2`:
- Pure CSS design tokens strictly matching React SPA (`#173f36`, `#20594a`, `#d9ed95`, `#f7f7ef`, `#fcfcf7`, `#dce0d2`).
- Responsive layout with zero horizontal overflow across Desktop (1440px), Tablet (768px), Mobile (390px), and Small Mobile (360px).
- Dark mode theme variants supported (`.pf-v5-theme-dark`).
- Local SVGs: Devarom wordmark (light & dark), brand mark, favicons, and Google broker SVG. Zero external CDN dependencies.
- FreeMarker overrides (`template.ftl`, `footer.ftl`) providing skip links, card framing, error banners, and session indicator.
- Extended message catalog with branded wording (`messages_en.properties`).
- Docker Compose volume mount (`- ./themes:/opt/keycloak/themes:ro`) and realm configuration (`"loginTheme": "devarom"`).
- Safe, idempotent upgrade script `platform/identity/apply-theme.sh` applying the theme to existing running Keycloak instances via `kcadm.sh` with zero data loss.
- Expanded `platform/identity/validate-identity.sh` with 11 static checks and 6 runtime checks (including theme availability).

Live End-to-End Browser Smoke Test:
- Baseline verified before theme: React SPA (5173), Gateway (8080), Accounts (8081), Keycloak (8090), and PostgreSQL (5438).
- Post-theme login verified: Navigating from React `/login` redirects to branded Keycloak login screen.
- Invalid credentials test: Displays clear, accessible alert banner ("Invalid username or password.").
- Successful authentication: Real login with `testcustomer` exchanges authorization code with PKCE S256 at `/auth/callback`, establishing in-memory session.
- Gateway and Accounts integration: React Customer Space (`/customer`) successfully requests `GET /api/v1/users/me` via Gateway (8080) to Accounts (8081). Accounts validates the Keycloak access token and returns the persisted customer profile.
- Registration verified: Self-registration of new user `bobcustomer` succeeds. Inspecting Keycloak user role mappings confirmed only `CUSTOMER` role assigned (no privilege elevation).
- Logout verified: Keycloak RP-initiated logout clears the session and returns user to `http://localhost:5173/`.
- External Google login (L03) remains unverified until external Google Cloud Console consent is performed.

Verification Results:
- `./platform/identity/validate-identity.sh`: **passed (11 static + 6 live runtime checks)**.
- `git diff --check`: **passed; clean diff with zero trailing whitespace or committed secrets**.

## Issue #47 Phase A verification — 2026-10-17

Owner: **Suthankan1**; branch `feat/keycloak-email-verification`, based on `test` branch merge commit `ab65336` (PR #46).

Implemented Phase A of Issue #47 (native Keycloak email verification link + local SMTP via Mailpit):
- Added Mailpit `v1.27.4` container to `platform/identity/docker-compose.identity.yml` bound strictly to `127.0.0.1:8025` for development UI, and internal container port `1025` on the shared Docker network for SMTP.
- Configured native Keycloak realm `realm-devarom.json` with `verifyEmail: true` and local Mailpit SMTP server (`host: mailpit`, `port: 1025`, `from: no-reply@devarom.test`, `auth: false`, `ssl: false`, `starttls: false`).
- Configured native `VERIFY_EMAIL` required action with a 60-second resend cooldown (`emailResendCooldownSeconds: 60`), preserving default 300-second action token lifespan.
- Branded native Keycloak verification/info/error pages extending `keycloak.v2` parent theme with Devarom design tokens and responsive wrapping (`messages_en.properties`, `devarom.css`).
- Created safe, idempotent runtime upgrade utility `platform/identity/configure-email-verification.py` supporting `check`, `apply` (with preflight SMTP receipt check and 0600 recovery snapshot), and `recover`.
- Automated test suites:
  - `platform/identity/validate-identity.sh`: passed (12 static invariant checks + 6 live runtime smoke checks).
  - `python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'`: passed (36 unit tests covering static configuration rules, isolation, and mock Keycloak admin REST interactions).
  - `python3 platform/identity/tests/email_verification_smoke.py`: passed (live synthetic flow covering registration, real Mailpit message capture, gate enforcement, link tampering rejection, 60s cooldown enforcement, resend delivery, link completion, password setup, PKCE exchange, wrong-account context rejection, replay rejection, Gateway + Accounts profile access, and logout).
- Live browser test:
  - Full end-to-end user registration on branded Keycloak page, inbox check notice, Mailpit email delivery, verification link follow, password setup, PKCE code exchange at `/auth/callback`, Customer Space profile display via Gateway to Accounts, and clean RP-initiated logout verified in browser.
- Scope bounds:
  - Phase B (Authenticator TOTP) and Phase C (Email OTP code feasibility) explicitly deferred; Issue #47 remains open.
  - External Google login (L03) remains unverified until external Google Cloud Console consent is performed.

## Issue #47 Phase B verification — 2026-10-19

Owner: **Suthankan1**; branch `feat/keycloak-totp-mfa`, based on `test` branch merge commit `b72c005` (PR #48).

Implemented Phase B of Issue #47 (native Keycloak authenticator-app TOTP MFA):
- Configured RFC 6238 TOTP policy in `realm-devarom.json`: `otpPolicyType: "totp"`, `otpPolicyAlgorithm: "HmacSHA1"`, `otpPolicyDigits: 6`, `otpPolicyPeriod: 30`, `otpPolicyLookAheadWindow: 1`, `otpPolicyCodeReusable: false`, `otpSupportedApplications: ["totpAppGoogleName", "totpAppMicrosoftAuthenticatorName", "totpAppFreeOTPName"]`.
- Configured native `CONFIGURE_TOTP` required action with safe optional enrollment for `CUSTOMER` (`defaultAction: false`), preserving existing user access without lockout.
- Extended custom branded Devarom Keycloak login theme:
  - Updated `template.ftl` with branded page titles and subtitles for `login-config-totp` and `login-otp`.
  - Added localized branded copy to `messages_en.properties`.
  - Implemented responsive styling in `devarom.css` for `#kc-totp-settings`, `#kc-totp-supported-apps`, QR code card enclosure for dark mode legibility, monospace manual secret key, 20px centered digit input (`#totp` / `#otp`), error banners, and responsive breakpoints down to 360px mobile with zero horizontal overflow.
- Created safe, idempotent runtime CLI `platform/identity/configure-totp.py` supporting `check`, `apply`, `recover` (using private 0600 snapshot `.local/totp-recovery.json`), and `reset-user` for lost-device recovery.
- Automated test suites:
  - `platform/identity/validate-identity.sh`: passed (13 static invariant checks + 7 live runtime smoke checks).
  - `python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'`: passed (53 unit/integration tests covering policy, action invariants, and mock loopback administration).
  - `python3 platform/identity/tests/totp_smoke.py`: passed (all 8 suites: runtime policy compliance, unenrolled CUSTOMER login without challenge, branded TOTP setup rendering + negative code rejections [invalid, expired, wrong-secret], subsequent login challenge with replay prevention, administrative credential reset & recovery re-enrollment, voluntary AIA enrollment via `kc_action=CONFIGURE_TOTP`, self-registration privilege elevation invariant [CUSTOMER role only], and downstream resource server probe).
  - `python3 platform/identity/tests/email_verification_smoke.py`: passed (Phase A email verification regression: registration, Mailpit delivery, cooldown, verification completion, and profile access).
- Scope bounds:
  - Phase C (Email OTP code feasibility investigation) remains open; Issue #47 remains open.
  - External Google login (L03) remains unverified until external Google Cloud Console consent is performed.

## Issue #18 verification & temporary QA takeover — 2026-10-19

Temporary QA Owner & Auth Platform Lead: **Suthankan1**
Original Intended QA Owner: **Vibhushan (@Vibhushan-2003)** (independent review pending)
Branch: `tests/auth-security-e2e`, targeting `test` (based on merge commit `3d05454`, PR #50).

**QA Ownership & Integrity Notice:**
The original independent QA owner for Issue #18, Vibhushan (@Vibhushan-2003), is temporarily unavailable. Suthankan1 has taken over execution of the remaining security verification tasks. Because Suthankan1 also owns the authentication implementation, this execution is classified as **PLATFORM SELF-VERIFICATION**, not independent peer QA. Independent peer review remains pending and will be performed when a peer reviewer is available. Vibhushan's original intended role is preserved in the issue history and documentation.

Implemented reproducible, automated Security E2E test harness (`tests/security-e2e/`):
- Built `harness_common.py` providing safe, zero-secret Keycloak Admin API management, authentic interactive Authorization Code + S256 PKCE login, synthetic RS256 JWT builder, and standardized test reporting data models.
- Built `test_jwt_rejection.py` (L06):
  - 14 Gateway edge tests (port 8080): missing, malformed, unsigned, untrusted, wrong-issuer, wrong-audience, expired, not-yet-valid, missing-subject tokens strictly rejected with 401 ProblemDetail; unmapped protected path rejected with 401; allowed origin CORS permits `http://localhost:5173`; disallowed attacker CORS rejected; valid PKCE token accepted with 200 OK.
  - 13 direct Accounts Service tests (port 8081): independent OAuth2 Resource Server validates Keycloak JWKS; missing, malformed, unsigned, untrusted, wrong-issuer, wrong-audience, expired, not-yet-valid, missing-subject tokens rejected with 401 ProblemDetail; Basic auth disabled (no Basic challenge); unexposed actuator routes return 401; public health returns 200 UP; direct valid PKCE token accepted with 200 OK.
- Built `test_role_and_ownership.py` (L07 & L08):
  - L07: Authenticated token lacking approved application role returns 403 Forbidden RFC 7807 ProblemDetail on direct Accounts (8081) and forwarded by Gateway (8080); public user registration composite role inspection proves strict confinement to `CUSTOMER` role with zero self-granted staff roles (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`). Unimplemented staff operations (admin, courier) verified as default-denied and tracked as blocked dependencies.
  - L08: Resource-owner isolation verified between synthetic Customer A and Customer B. Profile A and Profile B have distinct UUIDs and subjects. Spoofed identity headers (`X-User-Id`, `X-Keycloak-Sub`, `X-Roles`, `X-User-Email`) and query parameter injections (`?id=...&subject=...`) stripped by Gateway and ignored by Accounts. Direct `/users/{id}` lookup and merchant tenancy operations verified as default-denied and tracked as blocked dependencies.
- Built `security_e2e_runner.py` and `run-security-e2e.sh`:
  - Preflight service readiness checks against Keycloak (8090), Gateway (8080), Accounts (8081), Postgres (5438), and Mailpit (8025).
  - Runs Python Security E2E test suite (40 tests: 36 passed, 0 failed, 4 blocked by missing dependencies).
  - Runs full platform regression test suites: `validate-identity.sh`, `totp_smoke.py`, Gateway `mvnw test` (13 tests), Accounts `mvnw test` (37 unit/MVC tests), and Frontend `pnpm test --run` (56 tests).
  - Generates machine-readable JSON summary artifact at `tests/security-e2e/reports/security_e2e_results.json` without leaking secrets.

Verification Results (Executed by Suthankan1, Platform Self-Verification):
- `./tests/security-e2e/run-security-e2e.sh`: **passed cleanly (40 security E2E tests: 36 passed, 0 failed, 4 blocked)**.
- `./platform/identity/validate-identity.sh`: passed (13 static invariant checks + 7 live runtime smoke checks).
- `python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'`: passed (53 tests).
- `python3 platform/identity/tests/totp_smoke.py`: passed (all 8 suites).
- `python3 platform/identity/tests/email_verification_smoke.py`: passed (Phase A email verification regression).
- Gateway `./mvnw test`: passed (13 tests, BUILD SUCCESS).
- Accounts `./mvnw test`: passed (37 unit/MVC tests, BUILD SUCCESS; 53 total with integration suite).
- Frontend `pnpm typecheck`, `pnpm test --run`, `pnpm lint`, `pnpm build`: all passed (56 unit tests, clean build).
- `git diff --check`: passed; clean diff with zero trailing whitespace or committed credentials.
- Status: **Platform self-verification complete; independent peer QA pending**.

Scope bounds & Blocked dependencies:
- Cross-user explicit ID lookup API (`/api/v1/users/{id}`) does not exist in Accounts Service (profiles resolved only via authenticated `sub` in `/api/v1/users/me`).
- Staff operations and admin read models open under Issues #14, #15, #16.
- Merchant approval-state and tenant isolation provider open under Issue #8.
- Real Google login (L03) remains unverified until external Google Cloud Console consent is performed.

## Issue #47 Phase C — Email OTP Feasibility, Threat Model & Architecture Decision — 2026-10-19

Lead: **Suthankan1** (Platform & Security) · Independent Review: **Pending Vibhushan (@Vibhushan-2003)**
Branch: `docs/email-otp-feasibility` (based on latest `test`, commit `b433ef7`).

- **Keycloak 26.8.1 Investigation:** Proved Keycloak core provides **no native typed Email OTP authenticator**.
  Upstream discussions (#36910) confirm this is out of tree. Built-in mechanisms are action-token links
  (`VERIFY_EMAIL`, `UPDATE_PASSWORD`) and app-based TOTP/HOTP (`OTPPolicy`).
- **Use Case & Security Evaluation:**
  - *Signup Verification:* Degrades 256-bit signed action token entropy to 20 bits (6 digits); introduces brute-force risks without adding security.
  - *Login MFA:* Violates NIST SP 800-63B §5.1.3.3 (fate sharing with email password reset); unencrypted intermediate SMTP hops; AiTM phishing vulnerability; introduces severe delivery latency/race conditions and transforms external SMTP outages into total authentication DoS.
- **Threat Model ([EMAIL_OTP_THREAT_MODEL.md](../security/EMAIL_OTP_THREAT_MODEL.md)):** Modeled 18 distinct abuse scenarios and defined mandatory technical invariants (CSPRNG, max 3 attempts, 180s expiry, single-use burn-on-read, 60s cooldown, salted HMAC session binding, and zero secret logging).
- **Formal Decision ([ADR-0009](../architecture/adr/ADR-0009.md)):**
  - **Adopted Recommendation (OUTCOME A):** Do NOT implement typed Email OTP. Retain Keycloak's native signed email verification links (PR #48) and native RFC 6238 Authenticator-App TOTP (PR #49).
  - **Gated Contingency (OUTCOME C):** If product management explicitly mandates typed email codes in the future, author an in-house Keycloak SPI (`DevaromEmailOtpAuthenticatorFactory`) under a separate, independently scoped and reviewed issue.
- **Decision Status:** `PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL`.

## Nov 1 Demo Readiness & Clean-Clone Handoff Verification — 2026-10-20

- Platform Lead & Integration: **Suthankan1**
- Independent Clean-Clone Reviewer: **Eric (@EricPraveen)** (Issue #17)
- Independent Security Reviewer: **Vibhushan (@Vibhushan-2003)** (Issue #18)
- Academic Report & Presentation Lead: **HarithKeshan (@HarithKeshan)** (Issue #19)
- Branch: `chore/nov1-demo-readiness`, targeting `test` (based on `test` branch commit `169d78e`).

### Summary of Completed Platform Work
- **Clean-Clone Reproducibility & Runbook:**
  - Authored [`docs/development/CLEAN_CLONE_RUNBOOK.md`](../development/CLEAN_CLONE_RUNBOOK.md) providing step-by-step instructions for prerequisite validation, container bootstrapping, configuration, backend and frontend startup, verification, demonstration script, and troubleshooting.
  - Updated [`docs/milestones/LOGIN_ACCEPTANCE.md`](../milestones/LOGIN_ACCEPTANCE.md) row L10 assigning independent verification to Eric (@EricPraveen).
- **Automated Full-Stack Readiness Validator:**
  - Created [`platform/local/check-readiness.sh`](../../platform/local/check-readiness.sh) checking local toolchain (Java 21, Node 20+, pnpm, Docker, Python 3, OpenSSL), infrastructure containers (Keycloak :8090, Mailpit :8025, PostgreSQL :5438/:5432 with dynamic port detection), backend services (Gateway :8080, Accounts :8081), and frontend dev server (:5173).
- **Automated Login Demo Smoke Test:**
  - Created [`tests/security-e2e/demo_smoke.py`](../../tests/security-e2e/demo_smoke.py) executing 4 end-to-end user journeys against live Gateway and Keycloak:
    - **Scenario A:** Customer self-registration, Mailpit email verification link extraction, verification completion, and authenticated profile retrieval.
    - **Scenario B:** Returning customer username/password authentication via Authorization Code + S256 PKCE, profile retrieval.
    - **Scenario C:** Customer login with RFC 6238 TOTP challenge, token issuance, and authenticated profile retrieval.
    - **Scenario D:** Core security assertions (unauthenticated 401 rejection, unprivileged 403 rejection, and resource-owner profile isolation).
- **Safe Container Startup & Configuration:**
  - Created [`platform/local/start-local.sh`](../../platform/local/start-local.sh) and [`platform/local/README.md`](../../platform/local/README.md) for non-destructive local container initialization.
  - Hardened [`platform/identity/apply-theme.sh`](../../platform/identity/apply-theme.sh) to detect read-only Docker volume mounts cleanly.
  - Updated [`tests/security-e2e/run-security-e2e.sh`](../../tests/security-e2e/run-security-e2e.sh) with dynamic PostgreSQL port resolution.
- **Peer Handoff Documentation:**
  - Authored [`docs/team/PLATFORM_HANDOFF_NOV01.md`](../team/PLATFORM_HANDOFF_NOV01.md) detailing exact actions, commands, and verification criteria for Eric (Issue #17), HarithKeshan (Issue #19), and Vibhushan (Issue #18).

### Measured Verification Evidence
- `./platform/local/check-readiness.sh`: **13/13 passed, 0 warnings, 0 failures**.
- `python3 tests/security-e2e/demo_smoke.py`: **ALL 4 SCENARIOS PASSED** (A: Registration & Mailpit link, B: Returning customer, C: TOTP challenge, D: Security denials & isolation).
- `./tests/security-e2e/run-security-e2e.sh`: **passed cleanly (40 security tests: 36 passed, 0 failed, 4 blocked by missing downstream staff dependencies)**.
- Gateway `./mvnw test`: passed (13/13 tests, BUILD SUCCESS).
- Accounts `./mvnw test`: passed (37 unit/MVC tests, BUILD SUCCESS; 53 total with integration suite).
- Frontend `pnpm test --run`: passed (56/56 tests, 0 failures).
- Frontend `pnpm typecheck` & `pnpm build`: passed cleanly.
- Frontend manual inspection: Vite dev server running on `http://localhost:5173`, HTTP 200, branded Keycloak login redirect and return navigation operational.

### Scope Bounds & Honest Status
- **Clean-Clone Verification (L10):** Platform environment and runbook prepared; marked **NOT VERIFIED** until independently executed from a fresh workstation by Eric (@EricPraveen) under Issue #17.
- **External Google Login (L03), at the readiness run:** Automated broker delegation, Google authorization URI construction, live Google OAuth probe (HTTP 302 acceptance), negative controls and CUSTOMER privilege confinement passed; interactive login was **BLOCKED / NOT VERIFIED**. This historical blocker is superseded by the user-completed Google run recorded below.
- **Security E2E (Issue #18):** Classified as **PLATFORM SELF-VERIFICATION**; independent security sign-off pending Vibhushan (@Vibhushan-2003).

## Historical Automated Google OIDC Broker Verification (L03, PR #54) — 2026-10-20

Lead: **Suthankan1** (Platform & Security) · Reviewers: **Pending Gajanthan (@Gajanthan17, Customer UI) & Vibhushan (@Vibhushan-2003, Security QA)**
Branch: `tests/google-oidc-e2e` (based on `test` branch commit `882c65e`).

### Summary of Broker Verification & Automated Harness
- **Keycloak 26.8.1 Broker Architecture:**
  - Validated Google Identity Provider declaration in `realm-devarom.json` and running Keycloak instance (`providerId: google`, `alias: google`, `trustEmail: true`, `firstBrokerLoginFlowAlias: "first broker login"`, `syncMode: IMPORT`, `useJwksUrl: true`).
  - Confirmed runtime client ID and secret injection via `.env` with `-Dkeycloak.migration.replace-placeholders=true`.
  - Confirmed privilege confinement: self-registration and broker federated accounts default to `default-roles-devarom` containing exclusively `CUSTOMER`; zero staff roles (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`).
- **Automated Google Broker Smoke Test Suite (`platform/identity/tests/google_broker_smoke.py`):**
  - **Check 1 (OIDC Discovery):** Keycloak issuer `http://localhost:8090/realms/devarom` verified.
  - **Check 2 (Broker Redirection):** Request to Keycloak `/protocol/openid-connect/auth` with `kc_idp_hint=google` and S256 PKCE returned HTTP 303 delegating to `/realms/devarom/broker/google/login`.
  - **Check 3 (Authorization URL Construction):** Keycloak broker engine constructed compliant Google OAuth authorization URL to `https://accounts.google.com/o/oauth2/v2/auth` with registered `client_id`, `redirect_uri=http://localhost:8090/realms/devarom/broker/google/endpoint`, `scope=openid profile email`, cryptographic `state`, and `nonce`.
  - **Check 4 (Live Google OAuth Acceptance):** Probed Google authorization endpoint with registered client ID and redirect URI; Google responded with HTTP 302 to Google signin flow, confirming Google Cloud Console has accepted the Client ID and authorized the redirect URI.
  - **Check 4 Negative (Google Redirect URI Mismatch):** Probed Google authorization endpoint with unapproved redirect URI; Google rejected the request with `redirect_uri_mismatch`.
  - **Check 5 Negative (Keycloak State Validation):** Probed Keycloak broker callback endpoint (`/realms/devarom/broker/google/endpoint`) with tampered state; Keycloak rejected the request with HTTP 400 Bad Request (branded error page; zero session created).
  - **Check 5 Negative (OAuth Cancellation):** Probed Keycloak broker callback with `error=access_denied`; Keycloak handled cancellation with HTTP 400 Bad Request.
  - **Check 6 (Role Confinement & Secret Hygiene):** Verified zero client secrets in frontend or Git; verified newly created broker users receive solely `CUSTOMER`.
- **Static Invariant Tests (`platform/identity/tests/test_google_broker_configuration.py`):**
  - 5 static regression tests asserting placeholder hygiene, absence of committed secrets, and default-roles composite constraints.
- **Identity Verification Suite Integration (`platform/identity/validate-identity.sh`):**
  - Integrated Check 14 (static Google broker invariants) and Live Check H (Google broker smoke test); 14/14 static and all live smoke checks PASSED.

### Measured Evidence
- `python3 platform/identity/tests/test_google_broker_configuration.py`: **5/5 tests PASSED**.
- `python3 platform/identity/tests/google_broker_smoke.py`: **ALL 6 CHECKS PASSED**.
- `./platform/identity/validate-identity.sh`: **14/14 static invariant checks passed; Live smoke tests A–H ALL PASSED**.
- `python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'`: **58/58 tests PASSED**.
- `python3 tests/security-e2e/demo_smoke.py`: **ALL 4 SCENARIOS PASSED**.
- `./tests/security-e2e/run-security-e2e.sh`: **passed cleanly (40 security tests: 36 passed, 0 failed, 4 blocked by missing business endpoints; 13 Gateway, 37 Accounts, 56 Frontend tests passed)**.

### Historical Milestone Status for L03
- **L03 Status at PR #54:** **BLOCKED / NOT VERIFIED (External Human Consent Gate)**. Superseded by the user-completed run below; these automated results are preserved as historical evidence.
- **Rationale:** All broker configuration, delegation, URI construction, Google OAuth acceptance, negative controls, and role confinement are verified. Full end-to-end interactive completion requires a human developer entering their Google credentials and consenting on `accounts.google.com` using an account explicitly listed in Google Cloud Console's OAuth consent screen under Test Users. Automated agents must not fabricate credentials or claim unverified human consent.

## Current L03 Real Google Login, Profile and Logout — 2026-10-20

**L03: PASS for this authorized local run; independent teammate verification remains pending.** The application checkout was `60faff2` on `tests/google-oidc-e2e`; PR #54 merged that work to `test` at `d2f9b08`. This documentation follow-up starts from that latest `test` commit and changes no application or identity configuration.

- **Observed environment:** React `http://localhost:5173/login`, Keycloak :8090 discovery, Gateway :8080 health and Accounts :8081 health each returned HTTP 200; both backend health responses were `UP`.
- **Actual Google Console inspection:** Project `devarom`, External audience, Testing status. The authorized owner was already among eight test users. The web OAuth client matched the live Keycloak client configuration and included the exact broker redirect `http://localhost:8090/realms/devarom/broker/google/endpoint`. No cloud audience, test users, credentials or billing settings were changed.
- **Human interaction and browser evidence:** The user explicitly confirmed closing the agent-opened Google chooser and registering with Google in another Chrome tab. The agent inspected that real authenticated Customer page and observed Gateway `GET /api/v1/users/me` returning HTTP 200. Returned name/email matched the authorized owner, account/identity references were present, and roles contained only `CUSTOMER`; personal values are withheld. Gateway source maps this route to Accounts :8081; no separate downstream network trace was collected.
- **Read-only runtime corroboration:** Keycloak contained the exact authorized owner, enabled and email verified, with one Google federated link whose email matched. The runtime Google provider was enabled, its client matched ignored local configuration, external token storage was disabled, and the account had CUSTOMER without staff roles. Credentials and admin tokens were used only internally in memory; no raw admin responses or IDs are included.
- **Logout:** Agent clicked Sign out, observed the public home page, and confirmed protected `/customer` redirected to `/login`. Subsequent Keycloak admin reads found **0 online sessions** and **0 frontend offline sessions** for the authorized owner. Browser events were truncated on navigation, so the logout HTTP response was **not observed**; server-session absence corroborates the UI result.

The Google interaction was completed by the user, not automated probing. The agent did not observe every sign-in/consent prompt or a continuous redirect trace. Combined user confirmation, real authenticated profile, Google federated identity and session checks support this local L03 PASS. Earlier automated PKCE/state/nonce, negative-control and regression results remain historical; those suites were not rerun for this documentation-only update. Screenshots, raw personal profile data, account/session IDs, secrets, tokens and OTPs are excluded.

This run is not an independent review by a different teammate. Gajanthan's Customer UI review and Vibhushan's security QA remain pending, as do L10 clean-clone reproduction and other separately tracked milestone work. Detailed sanitized evidence is in [LOGIN_ACCEPTANCE.md](../milestones/LOGIN_ACCEPTANCE.md#current-l03-real-google-login-evidence--2026-10-20).
