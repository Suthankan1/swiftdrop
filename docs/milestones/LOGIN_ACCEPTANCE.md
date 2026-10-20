# Initial login vertical slice — planned acceptance and evidence

**Acceptance is recorded only from observed evidence.** L03 now has user-completed Google authentication with agent-observed customer/profile and logout evidence from 2026-10-20; independent teammate review remains pending. Earlier automated checks below remain historical evidence, not proof of human sign-in. Shared authentication code is owned end-to-end by Suthankan1 (issues #2, #3, #36, #37); business profiles and role landing views remain with their functional owners. When code appears, replace placeholders with actual commit/test evidence, including failures; don't check a box based on a mocked screen. See [Login Integration Contract](../contracts/LOGIN_CONTRACT.md) for the agreed integration contract and port registry.

| ID | Demonstration / negative case | Owner (proposed) | Evidence / result |
|---|---|---|---|
| L01 | Independently start Keycloak and generated React + Gateway + Accounts login subset | Suthankan1 (auth orchestration); Customer (SPA shell/Accounts provider) | **Verified (Platform)**: Live local stack (React :5173, Keycloak :8090, Gateway :8080, Accounts :8081, PostgreSQL :5438, Mailpit :8025) started and verified end-to-end; QA handoff pending independent confirmation |
| L02 | Local customer sign-up/login, linked profile by Keycloak `sub`, no business password storage | Suthankan1 (identity UI); Sinthuha (profile data) | **Verified (Platform)**: Keycloak OIDC PKCE registration/login exchanges code, Accounts JIT creates profile row bound to `keycloak_sub`; password stored only in Keycloak |
| L03 | Real Google login: Keycloak broker, S256 PKCE, correct callback/state/nonce | Suthankan1 (implementation); Gajanthan (UI review) | **PASS (2026-10-20; user-completed authentication, agent-observed result)**: Authorized Google account linked through the enabled Keycloak Google broker; Customer page showed the matching owner profile with CUSTOMER-only role and Gateway `GET /api/v1/users/me` returned HTTP 200. Sign out returned home; protected `/customer` redirected to `/login`; read-only Keycloak checks found zero online or frontend offline sessions. Earlier PKCE/state/nonce checks remain recorded below; independent teammate review pending. |
| L04 | Logout and expired token/session behavior documented/tested | Suthankan1 | **Verified (Platform)**: RP-initiated logout clears Keycloak SSO and redirects to `http://localhost:5173/`; expired tokens rejected with 401 and cleared from in-memory state |
| L05 | Five roles have appropriate role entry pages; user cannot self-grant staff | Role UI pairs (entry views); Suthankan1 (auth guard/roles) | **Partially verified (Platform)**: Self-registration privilege confinement strictly verified (CUSTOMER role only, zero staff roles); role landing pages for Courier/Dispatcher/Admin open under Issues #9, #15, #16 |
| L06 | No token returns 401, invalid/expired JWT is rejected | Suthankan1 (implementation & platform self-verification); Vibhushan (original QA owner, independent review pending) | **Verified (Platform Self-Verification)**: 27 automated tests passing across Gateway (8080) and Accounts (8081); missing/malformed/unsigned/untrusted/wrong-iss/wrong-aud/expired/not-yet-valid tokens rejected with 401 RFC 7807 ProblemDetail; independent QA reproduction pending Vibhushan |
| L07 | Wrong role returns 403 (or reviewed privacy-preserving 404 per contract) | Suthankan1 (security & platform self-verification); Vibhushan (original QA owner, independent review pending) | **Verified (Platform Self-Verification)**: Authenticated tokens without approved Devarom roles return 403 Forbidden RFC 7807 ProblemDetail on Accounts and forwarded by Gateway; staff operations unmapped and default-denied (404/401); independent QA reproduction pending Vibhushan |
| L08 | Customer A cannot access customer B's profile; similar merchant/courier ownership probes | Sinthuha/feature owner (business ownership); Suthankan1 (auth & platform self-verification); Vibhushan (original QA owner, independent review pending) | **Verified (Platform Self-Verification)**: Customer A and B isolation verified with real PKCE tokens; spoofed identity headers and query parameters stripped by Gateway and ignored by Accounts; IDOR on `/users/{id}` and merchant tenant endpoints documented as blocked dependencies |
| L09 | Login cancellation/bad callback/Keycloak down does not create false authenticated state | Suthankan1 | **Verified (Platform)**: Frontend session tests verify cancellation (`access_denied`), invalid callback, provider outage, and expired session do not establish authenticated state |
| L10 | Clean clone setup followed successfully by a different group member | Independent reviewer: Eric (@EricPraveen, Issue #17) | Not verified (Platform dry-run verified by Suthankan1; independent peer reproduction pending Eric @EricPraveen) |
| L11 | Google client secret absent from frontend and all secrets absent from Git/logs | Suthankan1 + independent reviewer | **Verified (Platform)**: Public Vite client has no client secrets; `.gitignore` strictly protects `.env` and `secrets/`; zero committed secrets in git history; zero tokens in test logs |
| L12 | Report figures and role/integration rationale reviewed by relevant pair | All nine | Not verified (Final report review pending) |

**Scope integrity:** Google login requires a real Keycloak/Google configuration; if unavailable, document the blocker rather than claim success. Don't demand gRPC/Kafka for a working login slice. The backend app scaffolds are generated by teammates via Spring Initializr; don't borrow code from this docs package.

## Evidence template (copy row for each actual run)

| Date/time | Commit SHA | Environment | Command / demo steps | Observed result | Reviewer |
|---|---|---|---|---|---|
| 2026-10-15 | Pending PR (Refs #2) | macOS Docker Desktop (Keycloak 26.8.1 container on port 8090) | `./platform/identity/validate-identity.sh` (JSON schema, 5 roles, default role CUSTOMER only, public client `devarom-frontend`, PKCE S256, strict origins, custom aud `devarom-api`, live discovery, negative ROPC/redirect/PKCE tests) | **PASSED (All 7 static + 5 live smoke tests passed)**; Google IdP pre-wired with runtime `.env` injection; full browser Google login pending external verification (L03 unverified) | Suthankan1 (Platform) |
| 2026-10-15 | Pending PR (Refs #3) | macOS OpenJDK 21.0.12.1 + Spring Boot 4.1.1 + Spring Cloud 2025.1.3 (Oakwood) | `cd services/api-gateway && ./mvnw clean test` (Context load, public health, 401 on missing/malformed/untrusted/wrong-iss/wrong-aud/expired JWT with RFC 7807 ProblemDetails, route `GET /api/v1/users/me` -> Accounts port 8081 with preserved Auth header, spoofed header stripping, allowed CORS `http://localhost:5173`, disallowed CORS rejection, downstream 503 error handling) | **PASSED (All 13 automated tests passed)**; L06 edge token validation verified; Compose hardened to require explicit admin password; full L01/L02 pending Accounts Service (Issue #5) and React SPA (Issue #6) | Suthankan1 (Platform) |
| 2026-10-16 | PR #45 (`c6b31bb`) | macOS OpenJDK 21 + Spring Boot 4.1.1 + PostgreSQL 16.14 Testcontainers | `cd services/accounts-service && ./mvnw clean verify` (37 unit/MVC + 16 PostgreSQL integration tests; independent OAuth2 Resource Server validation via Keycloak JWKS, role mapping, RFC 7807 ProblemDetails, profile isolation between Customer A and B, concurrent first login safety) | **PASSED (All 53 tests passed)**; direct Accounts JWT security verified; live browser login/profile flow through Gateway to Accounts demonstrated in PR #45 | Suthankan1 (Platform) |
| 2026-10-17 | PR #48 (`ab65336`) | Keycloak 26.8.1 (8090) + Mailpit 1.27.4 (8025) | `python3 platform/identity/tests/email_verification_smoke.py` (Phase A email verification: native registration, Mailpit delivery, 60s cooldown, link completion, password setup, PKCE exchange, Gateway & Accounts profile access, logout) | **PASSED (All suites passed)**; native email verification link flow verified | Suthankan1 (Platform) |
| 2026-10-19 | PR #49 (`7ecfc33`) | Keycloak 26.8.1 (8090) | `python3 platform/identity/tests/totp_smoke.py` (Phase B TOTP MFA: runtime policy, unenrolled customer login, branded setup, negative code rejections, single-use replay prevention, recovery reset, voluntary AIA, gateway REST probe) | **PASSED (All 8 suites passed)**; native RFC 6238 TOTP MFA policy and challenge flow verified | Suthankan1 (Platform) |
| 2026-10-19 | PR #50 (`3d05454`) | Live stack: Keycloak (8090), Gateway (8080), Accounts (8081), Postgres (5438), Mailpit (8025) | `./tests/security-e2e/run-security-e2e.sh` (L06: 27 JWT rejection & edge tests across Gateway & Accounts; L07: role denial on 8081/8080 and privilege confinement; L08: Customer A vs B isolation, Gateway header stripping, Accounts parameter injection resistance, IDOR & merchant dependency tracking; full identity, TOTP, gateway, accounts, and frontend regressions) | **PASSED (36 passed, 0 failed, 4 blocked by missing provider endpoints)**; all 13 Gateway, 37 Accounts, and 56 Frontend unit tests passed; zero secrets or tokens committed or logged | Suthankan1 (Platform self-verification); Independent QA pending Vibhushan (@Vibhushan-2003) |
| 2026-10-20 | PR #53 (`chore/nov1-demo-readiness`) | Live full stack: Keycloak (8090), Gateway (8080), Accounts (8081), Postgres (5438), Mailpit (8025), Frontend (5173) | `./platform/local/check-readiness.sh` & `python3 tests/security-e2e/demo_smoke.py` & `./tests/security-e2e/run-security-e2e.sh` (Full toolchain and 13 readiness checks passed; demo runner passed Scenarios A, B, C, D; 40 security tests: 36 passed, 0 failed, 4 blocked; 13 Gateway, 37 Accounts, 56 Frontend tests passed) | **PASSED (Readiness: 13/13; Demo: 4/4; Security E2E: 36 passed, 0 failed, 4 blocked by missing provider endpoints)**; clean-clone runbook authored; clean startup helper verified | Suthankan1 (Platform Self-Verification & Rehearsal); Independent clean-clone reproduction (L10) pending Eric (@EricPraveen) |
| 2026-10-20 | `test` (`882c65e`) / branch `tests/google-oidc-e2e` | Live stack: Keycloak (8090), Gateway (8080), Accounts (8081), Postgres (5438), Mailpit (8025), Frontend (5173) | `python3 platform/identity/tests/google_broker_smoke.py` & `python3 platform/identity/tests/test_google_broker_configuration.py` & `./platform/identity/validate-identity.sh` (Broker delegation, `kc_idp_hint=google`, S256 PKCE, redirect_uri `http://localhost:8090/realms/devarom/broker/google/endpoint`, live Google probe HTTP 302, negative URI mismatch, negative callback state/access_denied rejection HTTP 400, CUSTOMER-only role confinement, zero frontend secrets) | **BLOCKED (Automated broker invariants, contract parameters, Google probe and negative tests ALL PASSED; live interactive sign-in BLOCKED pending human developer test-user consent on accounts.google.com)**; historical L03 BLOCKED result, superseded by the user-completed run below | Suthankan1 (Platform) |
| 2026-10-20 | Application checkout `60faff2`; PR #54 merged to `test` at `d2f9b08` | Local Chrome; React :5173, Keycloak :8090, Gateway :8080, Accounts :8081 | User completed Google registration in another tab; agent inspected Google Console, signed-in Customer page, Gateway profile HTTP 200, read-only Google federated identity/roles, and logout/session counts | **PASS (L03)**; owner profile matched, CUSTOMER-only role, protected route denied after logout, zero online/frontend offline sessions. Google sign-in prompts were completed by the user; logout HTTP response was not captured. See current evidence below. | User (Google authentication); Codex (browser/runtime verification); independent teammate review pending |

## L06, L07, and L08 Detailed Security Verification Matrix (Platform Self-Verification)

- **Executed by:** Suthankan1
- **Verification Type:** Platform self-verification (temporary QA owner during Vibhushan's temporary unavailability)
- **Independent Peer Reviewer:** Pending (Vibhushan @Vibhushan-2003)
- **Tested Commit SHA:** `3d05454`
- **Test Command:** `./tests/security-e2e/run-security-e2e.sh`

| Test ID | Test Case | Service / Endpoint | Expected HTTP Status | Actual HTTP Status | Result | Test Command / Module | Evidence / Verification Notes |
|---|---|---|---|---|---|---|---|
| L06-GW-01 | Missing bearer token rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-02 | Malformed bearer token rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-03 | Unsigned JWT (`alg=none`) rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-04 | Untrusted key signature rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-05 | Wrong issuer JWT rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-06 | Wrong audience JWT rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-07 | Expired JWT rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-08 | Not-yet-valid JWT (`nbf` in future) rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-09 | Missing subject JWT rejected | Gateway (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-GW-10 | Public actuator health accessible without auth | Gateway (`GET /actuator/health`) | 200 OK | 200 | **PASS** | `test_jwt_rejection.py` | UP status JSON returned |
| L06-GW-11 | Unmapped protected route requires auth | Gateway (`GET /api/v1/unmapped-endpoint`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | Edge default-deny authentication |
| L06-GW-12 | CORS preflight permits approved frontend origin | Gateway (`OPTIONS /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_jwt_rejection.py` | Allowed origin `http://localhost:5173` |
| L06-GW-13 | CORS preflight rejects disallowed attacker origin | Gateway (`OPTIONS /api/v1/users/me`) | 403 Forbidden | 403 | **PASS** | `test_jwt_rejection.py` | CORS policy rejection |
| L06-GW-14 | Valid Keycloak PKCE token accepted | Gateway (`GET /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_jwt_rejection.py` | Customer profile JSON returned (Positive Control) |
| L06-ACC-01 | Direct missing bearer token rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-02 | Direct malformed bearer token rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-03 | Direct unsigned JWT (`alg=none`) rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-04 | Direct untrusted key signature rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-05 | Direct wrong issuer JWT rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-06 | Direct wrong audience JWT rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-07 | Direct expired JWT rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-08 | Direct not-yet-valid JWT rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-09 | Direct missing subject JWT rejected | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | RFC 7807 ProblemDetail returned |
| L06-ACC-10 | Direct Basic Auth disabled (no challenge) | Accounts (`GET /api/v1/users/me`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | No WWW-Authenticate: Basic |
| L06-ACC-11 | Unexposed actuator (`/actuator/env`) rejected | Accounts (`GET /actuator/env`) | 401 Unauthorized | 401 | **PASS** | `test_jwt_rejection.py` | Actuator exposure prevention |
| L06-ACC-12 | Public actuator health accessible without auth | Accounts (`GET /actuator/health`) | 200 OK | 200 | **PASS** | `test_jwt_rejection.py` | UP status JSON returned |
| L06-ACC-13 | Direct valid Keycloak PKCE token accepted | Accounts (`GET /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_jwt_rejection.py` | Customer profile JSON returned (Positive Control) |
| L07-ROLE-01 | Token lacking approved role returns 403 | Accounts (`GET /api/v1/users/me`) | 403 Forbidden | 403 | **PASS** | `test_role_and_ownership.py` | RFC 7807 403 ProblemDetail |
| L07-ROLE-02 | Gateway forwards 403 from downstream | Gateway (`GET /api/v1/users/me`) | 403 Forbidden | 403 | **PASS** | `test_role_and_ownership.py` | Gateway forwards downstream 403 |
| L07-ROLE-03 | Public signup assigns only CUSTOMER role | Keycloak Admin API | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Zero staff roles assigned (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN` empty) |
| L07-ROLE-04 | CUSTOMER cannot access staff admin ops | Gateway (`GET /api/v1/admin/users`) | 403 Forbidden | 404 Not Found | **BLOCKED** | `test_role_and_ownership.py` | Unimplemented staff endpoint (Issue #15/16). Default-denied by Gateway |
| L07-ROLE-05 | CUSTOMER cannot access courier ops | Gateway (`GET /api/v1/courier/deliveries`) | 403 Forbidden | 404 Not Found | **BLOCKED** | `test_role_and_ownership.py` | Unimplemented courier endpoint (Issue #9). Default-denied by Gateway |
| L08-OWN-01 | Customer A receives Customer A profile | Gateway & Accounts (`GET /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Profile A bound to Sub A |
| L08-OWN-02 | Customer B receives Customer B profile | Gateway & Accounts (`GET /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Profile B bound to Sub B |
| L08-OWN-03 | Customer A and B have distinct profiles | Accounts (`account_profiles`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | UUID A != UUID B, Sub A != Sub B |
| L08-OWN-04 | Repeated access retains immutable profile ID | Accounts (`GET /api/v1/users/me`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Repeated call returns same UUID A |
| L08-OWN-05 | Gateway strips spoofed headers & query params | Gateway (`GET /api/v1/users/me?id=...`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Customer A profile returned; spoofed headers stripped |
| L08-OWN-06 | Direct Accounts ignores spoofed headers | Accounts (`GET /api/v1/users/me?id=...`) | 200 OK | 200 | **PASS** | `test_role_and_ownership.py` | Customer A profile returned from SecurityContext only |
| L08-OWN-07 | Explicit `/users/{id}` cross-user lookup | Accounts (`GET /api/v1/users/{id}`) | 403 Forbidden | 404 Not Found | **BLOCKED** | `test_role_and_ownership.py` | Provider endpoint unmapped in Accounts (Issue #5/37) |
| L08-OWN-08 | Merchant tenant isolation probe | Accounts (`GET /api/v1/merchants/orders`) | 403 Forbidden | 404 Not Found | **BLOCKED** | `test_role_and_ownership.py` | Merchant backend endpoint unmapped (Issue #8/14) |

## Historical L03 Google OIDC Broker Verification & Blocker Register (PR #54)

- **Executed by:** Suthankan1 (Platform & Security)
- **Reviewer:** Pending Gajanthan (@Gajanthan17, Customer UI) & Vibhushan (@Vibhushan-2003, Security QA)
- **Tested Commit SHA:** `882c65e` (branch `tests/google-oidc-e2e`)
- **Keycloak Runtime:** Keycloak 26.8.1 (Quarkus) on port 8090
- **Test Harness:** `platform/identity/tests/google_broker_smoke.py` & `platform/identity/tests/test_google_broker_configuration.py`
- **Status at the automated run:** **BLOCKED / NOT VERIFIED (External Human Consent Gate)**. Superseded by the user-completed run recorded below; the matrix preserves the earlier observations.

| Check ID | Verification Scope | Endpoint / Component | Expected Behavior | Actual Behavior | Result | Evidence / Diagnostic Notes |
|---|---|---|---|---|---|---|
| L03-CFG-01 | Keycloak Google IdP declaration | `realm-devarom.json` | Single IdP alias `google`, provider `google` | Declared; `alias=google`, `providerId=google` | **PASS** | `test_google_broker_configuration.py` |
| L03-CFG-02 | Keycloak broker scopes & syncMode | Keycloak Admin API | `openid profile email`, `syncMode=IMPORT`, `useJwksUrl=true` | Configured per OIDC standard | **PASS** | `google_broker_smoke.py` check 1 |
| L03-CFG-03 | Email trust & first login flow | Keycloak 26.8.1 | `trustEmail=true`, `firstBrokerLoginFlowAlias="first broker login"` | Matches baseline; verified Google emails trusted | **PASS** | `test_google_broker_configuration.py` |
| L03-CFG-04 | Privilege confinement on federated signup | Keycloak `default-roles-devarom` | Solely `CUSTOMER` assigned; zero staff roles | Confined: `CUSTOMER` assigned, zero staff roles | **PASS** | `test_google_broker_configuration.py` check 5 |
| L03-CFG-05 | Secrets hygiene in source control | Git repository / `.env` | Zero hardcoded client secrets; `.env` gitignored | `.gitignore:2:.env` protects secrets; zero secrets in git | **PASS** | Git check & regex scanner |
| L03-FLO-01 | Frontend Google sign-in trigger | React SPA `/login` | `auth.googleLogin()` passes `kc_idp_hint=google` with S256 PKCE | Generates S256 challenge, state, nonce; invokes Keycloak | **PASS** | `frontend/src/auth/session.ts` & Vitest |
| L03-FLO-02 | Keycloak broker delegation redirect | Keycloak `:8090/realms/devarom/...` | HTTP 303 redirect to `/realms/devarom/broker/google/login?...` | HTTP 303 See Other returned with session cookies | **PASS** | `google_broker_smoke.py` check 2 |
| L03-FLO-03 | Google OAuth2 authorization URL construction | Keycloak Broker Engine | Redirects to `https://accounts.google.com/o/oauth2/v2/auth` with exact params | URL constructed with registered client ID, redirect URI, scope, state, nonce | **PASS** | `google_broker_smoke.py` check 3 |
| L03-FLO-04 | Google Cloud Console client ID & redirect URI acceptance | Google Authorization Server | Google accepts registered client ID and authorized redirect URI | HTTP 302 to Google signin identifier flow | **PASS** | `google_broker_smoke.py` check 4 (Live Google probe) |
| L03-NEG-01 | Unapproved redirect URI rejection | Google Authorization Server | Google rejects unauthorized redirect URI with `redirect_uri_mismatch` | Google returns HTTP 302 to error page with `redirect_uri_mismatch` | **PASS** | `google_broker_smoke.py` check 4 (Negative probe) |
| L03-NEG-02 | Tampered / unmatched callback state rejection | Keycloak `:8090/.../broker/google/endpoint` | Keycloak rejects callback with invalid state with HTTP 400 | HTTP 400 Bad Request; branded error page; zero session | **PASS** | `google_broker_smoke.py` check 5 (Negative probe) |
| L03-NEG-03 | OAuth cancellation (`access_denied`) handling | Keycloak & React SPA | Handled cleanly with HTTP 400 at broker, and `/login?error=cancelled` on frontend | HTTP 400 at broker; frontend displays cancellation notice; zero false session | **PASS** | `google_broker_smoke.py` check 5 & `session.test.ts` |
| L03-E2E-01 | Live human interactive sign-in on accounts.google.com | Google Account + Keycloak + React + Gateway + Accounts | Interactive Google login completes -> Keycloak exchange -> Gateway -> Accounts profile | **BLOCKED**: Requires human developer Google account credentials and explicit Test User entry in Google Cloud Console | **BLOCKED** | External dependency gate (cannot automate human Google credentials) |

### Historical Blocker Details & Human Configuration Checklist for L03

The following was the pending checklist at PR #54. The current run below records the inspected configuration and resolved human interaction gate; this checklist is retained for reproduction:

1. **Google Cloud Console OAuth Consent Screen:**
   - URL: `https://console.cloud.google.com/apis/credentials/consent`
   - Publishing Status: **Testing**
   - User Type: **External**
   - **Action Required:** Under **Test users**, click **Add Users** and enter the human tester's Google email address (e.g. `suthankan@...` or developer account). Without this, Google halts login with `403 access_denied` ("This app is blocked").
2. **Authorized Redirect URI Verification:**
   - Client ID: `999856063696-ae6aineb7tmng7pcje9me3902h547h0g.apps.googleusercontent.com`
   - Verified Redirect URI: `http://localhost:8090/realms/devarom/broker/google/endpoint`
   - Verified JavaScript Origin: `http://localhost:8090`
3. **Interactive Human Demonstration Steps:**
   - Open browser to `http://localhost:5173/login`.
   - Click **Continue with Google**.
   - Authenticate on `accounts.google.com` using the registered Test User Google account.
   - Consent to the requested scopes (email, profile, openid).
   - Verify redirection through Keycloak broker -> React `/auth/callback` -> Customer Space (`/customer`).
   - Verify customer profile loaded through Gateway (`:8080`) -> Accounts (`:8081`).
   - Click **Sign out** and confirm return to unauthenticated landing page.

## Current L03 Real Google Login Evidence — 2026-10-20

**Result: PASS for the authorized local Google login/profile/logout run.** This supersedes the earlier human-interaction blocker. Application checkout: `60faff2`; PR #54 is merged into `test` at `d2f9b08`. This run combines user-completed Google authentication with agent-observed browser and read-only runtime evidence. It is not independent teammate verification; Gajanthan and Vibhushan review remains pending.

| Check | Observed evidence | Result |
|---|---|---|
| Local service availability | React `/login` (:5173), Keycloak discovery (:8090), Gateway health (:8080), and Accounts health (:8081) all returned HTTP 200; Gateway and Accounts reported `UP`. | **PASS** |
| Actual Google Cloud project | Console project `devarom` was External / Testing; the explicitly authorized owner account was already registered among eight test users. The web OAuth client matched runtime configuration and authorized `http://localhost:8090/realms/devarom/broker/google/endpoint`. No audience, test-user or credential changes were needed. | **PASS** |
| Human Google authentication | The user explicitly confirmed closing the agent-opened chooser and completing Google registration in another Chrome tab. The agent then inspected the actual authenticated Customer page. Google credential, consent and identity-verification interactions belong to the user; a continuous trace of every Google prompt/redirect was not captured. | **PASS (user-completed, result corroborated)** |
| Customer profile through Gateway | Browser observed `GET http://localhost:8080/api/v1/users/me` returning HTTP 200 and the real Customer page. Profile name/email matched the authorized owner; account and identity references were present; response roles contained only `CUSTOMER`. Gateway source routes this endpoint to Accounts :8081; a separate downstream network trace was not collected. Raw profile values are withheld. | **PASS** |
| Runtime Google identity and privileges | Read-only Keycloak admin checks found exactly one matching owner account, enabled and email verified, with a federated identity under provider alias `google` whose email matched the authorized owner. Google broker was enabled, its client matched ignored local configuration, external token storage was disabled, CUSTOMER was present and staff roles were absent. | **PASS** |
| Logout and protected route | Agent clicked Sign out, observed the public home page, then confirmed `/customer` redirected to `/login`. Subsequent read-only Keycloak checks found zero online sessions and zero offline sessions for `devarom-frontend`. The logout HTTP response was not captured because browser network events were truncated on navigation; server-session absence corroborates the observed logout result. | **PASS** |

The prior automated PKCE, state/nonce and negative-control results above remain historical and were not rerun for this documentation update. No screenshots, personal profile values, account/session IDs, raw admin responses, secrets, tokens or OTPs are included. Independent peer reproduction and review remain separate pending work.
