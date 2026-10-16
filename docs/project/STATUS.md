# Current project status — update only with evidence

Last documentation update: **2026-10-16**. This file describes the **starter artifact**, not unknown external repositories.

| Area | State | Evidence |
|---|---|---|
| Devarom v1.3 design | **Proposed baseline** | `docs/architecture/BASELINE.md` and 20 supplied editable diagrams |
| Team allocation | **Proposed; names recorded** | `docs/team/OWNERSHIP.md` |
| November 1 report | **Existing external design package reviewed; final submission pending** | `docs/milestones/NOV_01.md` |
| Spring Boot services | **API Gateway verified (Refs #3); Accounts profile & Keycloak JWT security implemented (Refs #5, #37), review pending** | `services/api-gateway/`: 13 tests passed; Accounts `services/accounts-service/`: 37 unit/MVC + 16 PostgreSQL integration tests passed (53 total); OAuth2 Resource Server independently validates Keycloak JWTs, roles, and profile persistence |
| React application | **Single SPA merged (PR #41); shared OIDC implementation in #36 branch, review pending** | `frontend/`: official keycloak-js 26.2.4; strict typecheck, unit tests, lint and production build verified; live login/profile pending runtime dependencies |
| Keycloak/Google login | **Identity foundation implemented & verified (Refs #2)**; Compose hardened (Refs #3) | `platform/identity/` realm with 5 roles, PKCE S256, Google broker definition, hardened admin password requirement; full browser login pending React SPA |
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
Reviewers: **Sinthuha** (Accounts service owner), **Thuvarahan** (Accounts merchant consumer), **Vibhushan** (independent security QA).

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
- Live browser flow (React -> Keycloak -> Gateway -> Accounts -> PostgreSQL) and Google broker login (L03) remain unverified pending interactive browser session verification.

Next small task: Integrate and demonstrate the live browser login flow from React SPA through Keycloak and Gateway to Accounts Service `/api/v1/users/me`.

