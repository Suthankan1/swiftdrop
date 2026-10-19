# Current project status — update only with evidence

Last documentation update: **2026-10-19**. This file describes the **starter artifact**, not unknown external repositories.

| Area | State | Evidence |
|---|---|---|
| Devarom v1.3 design | **Proposed baseline** | `docs/architecture/BASELINE.md` and 20 supplied editable diagrams |
| Team allocation | **Proposed; names recorded** | `docs/team/OWNERSHIP.md` |
| November 1 report | **Existing external design package reviewed; final submission pending** | `docs/milestones/NOV_01.md` |
| Spring Boot services | **API Gateway verified (Refs #3); Accounts profile & Keycloak JWT security implemented (Refs #5, #37), review pending** | `services/api-gateway/`: 13 tests passed; Accounts `services/accounts-service/`: 37 unit/MVC + 16 PostgreSQL integration tests passed (53 total); OAuth2 Resource Server independently validates Keycloak JWTs, roles, and profile persistence |
| React application | **Single SPA merged (PR #41); OIDC & branded Keycloak login flow verified (PR #42, Refs #44)** | `frontend/`: official keycloak-js 26.2.4; strict typecheck, unit tests, lint, and production build verified; live browser login/profile flow through Gateway to Accounts demonstrated |
| Keycloak/Google login | **Identity foundation, branded theme, native email verification & authenticator TOTP MFA verified (Refs #2, #44, #47 Phase A & B)**; Compose hardened (Refs #3) | `platform/identity/` realm with 5 roles, PKCE S256, Google broker definition, branded Devarom Keycloak theme (`devarom`), local Mailpit SMTP, RFC 6238 TOTP MFA policy; live browser registration, email verification, TOTP enrollment/challenge/recovery, role confinement, and logout verified with React SPA |
| GraphQL/gRPC/Kafka/SSE flows | **Designed, implementation pending** | Design documentation; no runtime evidence |
| CI/CD, Compose, Kubernetes/AI | **Not implemented by this starter** | Planned; Kubernetes/AI are bonuses |

When real work begins, record commit IDs, actual test commands/results, known blockers and next slice. Never mark a milestone green just because its diagram exists. See [handoff template](../ai/HANDOFF_TEMPLATE.md).

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
