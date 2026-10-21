# Current project status — update only with evidence

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../branding/PHASE_1_AUDIT.md).

Last documentation update: **2026-10-21**. This file describes the **starter artifact**, not unknown external repositories.

| Area | State | Evidence |
|---|---|---|
| SwiftDrop v1.3 design | **Proposed baseline** | `docs/architecture/BASELINE.md` and 20 supplied editable diagrams |
| Team allocation | **Proposed; names recorded** | `docs/team/OWNERSHIP.md` |
| November 1 report | **Existing external design package reviewed; platform handoff complete, final submission pending** | `docs/milestones/NOV_01.md` and `docs/team/PLATFORM_HANDOFF_NOV01.md` |
| Spring Boot services | **API Gateway & Accounts canonical defaults aligned to SwiftDrop; independent JWT security verified** | `services/api-gateway/`: 19 tests passed; Accounts `services/accounts-service/`: 44 unit/MVC/integration tests passed (63 total); OAuth2 Resource Server independently validates SwiftDrop JWTs (`swiftdrop-api` audience), roles, and profile persistence |
| React application | **Single SPA canonical defaults aligned to SwiftDrop OIDC; flow verified (PR #41, #42, #64)** | `frontend/`: official keycloak-js 26.2.4; strict typecheck, 70 unit tests, lint, and production build verified; live browser login/profile flow through Gateway to Accounts demonstrated with `swiftdrop` realm |
| Keycloak/Google login | **Real Google login operator-verified PASS (2026-10-21); Live realm cutover to `swiftdrop` COMPLETE (PR #64); Fresh bootstrap verified** | Human operator completed Google authentication; agent verified Customer profile and Gateway `/api/v1/users/me` HTTP 200, corroborated Google federated identity/CUSTOMER-only roles. Live cutover and fresh-install bootstrap verified. See `docs/branding/PHASE_2_FINAL_SIGNOFF.md`. |
| GraphQL/gRPC/Kafka/SSE flows | **Designed, implementation pending** | Design documentation; no runtime evidence |
| CI/CD, Compose, Kubernetes/AI | **Local container startup & full-stack readiness verified (PR #53, Refs #17, #19)** | `platform/local/start-local.sh`, `platform/local/check-readiness.sh` (13/13 passed), `tests/security-e2e/demo_smoke.py`; Kubernetes/AI remain bonuses |

When real work begins, record commit IDs, actual test commands/results, known blockers and next slice. Never mark a milestone green just because its diagram exists. See [handoff template](../ai/HANDOFF_TEMPLATE.md).

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
