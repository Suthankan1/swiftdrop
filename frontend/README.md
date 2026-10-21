# SwiftDrop shared SPA

The single React application for SwiftDrop. Issue [#6](https://github.com/Suthankan1/swiftdrop/issues/6) implements the public shell and Customer profile presentation. **Shared authentication is implemented in #36; live full-stack login remains unverified pending local Keycloak and Accounts #5/#37.**

Temporary implementation: **Suthankan1**, while Gajanthan is unavailable. **Gajanthan remains the original Customer feature steward.** Accounts business profiles remain Sinthuha’s #5; authentication remains Suthankan1’s #36 and backend JWT policy #37.

## Toolchain and startup

Use Node **24 LTS** (verified locally with **24.21.0**, pinned in `.node-version`) and **pnpm 12.11.2** (`packageManager`). With Corepack installed, run from `frontend/`:

```bash
corepack pnpm install --frozen-lockfile
corepack pnpm dev
```

Open `http://localhost:5173`. The server binds to localhost and fails if the contracted port is occupied. No backend or environment file is required to explore these pages. If pnpm 12.11.2 is already installed, the `pnpm` commands below work directly.

The official `create-vite@9.2.1` React TypeScript template was adapted in this existing folder. Registry versions and peer compatibility were checked on 2026-10-15: React/React DOM **19.3.1**, Vite **8.3.4**, React plugin **6.1.2**, React Router **8.4.0**, TanStack Query **5.104.3**, TypeScript **6.0.2**, Vitest **5.0.5**, ESLint **10.12.0**, React Testing Library **16.3.3**, jsdom **30.1.3**. TypeScript 6 is intentional: typescript-eslint 8.71.2 supports TypeScript `<6.1`, so latest TypeScript 7 is outside its compatibility range. All direct dependencies are exact; commit `pnpm-lock.yaml`. pnpm generated `pnpm-workspace.yaml` with exact recent-release exceptions for these selected packages and their transitive dependencies.

See the official [Vite template guide](https://vite.dev/guide/) and [Node release schedule](https://nodejs.org/en/about/previous-releases).

```bash
pnpm install --frozen-lockfile
pnpm exec tsc --noEmit
pnpm typecheck
pnpm test --run
pnpm build
pnpm lint
```

`typecheck` runs `tsc -b` to check both referenced strict application and tooling projects; bare `tsc --noEmit` alone only sees the root references. Tests include test-only synthetic profiles and mocked HTTP responses; they do not demonstrate real authentication. Browser QA and actual results are recorded in the PR and project status.

## Routes

| Route | Current behavior |
|---|---|
| `/` | Public landing page and shared responsive navigation |
| `/customer` | Customer role entry; redirects anonymous sessions to login, reads real profile through Gateway |
| `/login` | Keycloak login, Google broker and registration entries; authenticated role navigation |
| `/auth/callback` | Single adapter-managed code exchange; scrub callback parameters and return to login/session page |
| `/logout` | Clear local session and POST to Keycloak end-session |
| `*` | Not-found page with a route home |

The header links only to public entries. Hiding navigation never enforces authorization; Gateway and each business service must independently enforce it.

## Source layout

```text
src/
  app/                  App, router and QueryClient provider
  auth/                 Keycloak runtime, provider/context, identity pages and route guard
  components/layout/    Shared header, mobile navigation, footer and outlet
  components/ui/        Reusable feedback and application error boundary
  features/public/      Landing and not-found pages
  features/customer/    Profile page, presentation component, DTOs and API adapter
  styles/               Shared CSS tokens, components and responsive layout
  test/                 Setup, synthetic fixtures and application/security tests
```

The profile presentation accepts explicit auth-required/loading/empty/unavailable/forbidden/ready states. Customer profile queries run only for authenticated CUSTOMER sessions, are keyed by subject, and reject mismatched response subjects. Private queries are cancelled and all query cache cleared on subject changes, logout or expiry. TanStack Query is provided once at the app boundary. No production profile is mocked.

## Accounts #5 boundary

`src/features/customer/api/contracts.ts` matches `docs/contracts/LOGIN_CONTRACT.md` §4.2: `id`, `keycloakSub`, `email`, `fullName`, `phoneNumber`, `roles`, nullable `merchantProfile`, `createdAt`. IDs remain opaque strings. Merchant status and roles remain strings because the response contract does not freeze complete enums; Accounts must clarify nullability/status evolution before live integration.

`createAccountsApi` in `src/features/customer/api/accounts.ts` accepts an async `getAccessToken` and injectable fetch for isolated tests. It calls only `GET /api/v1/users/me` through Gateway, with Bearer authorization and JSON Accept. Without a token it rejects before making a request. Responses are structurally checked; 401 requires auth, 403/privacy-preserving 404 become access unavailable, other HTTP/network failures become service unavailable. Raw error bodies are not displayed or logged. Abort signals are supported; cookies, HTTP caching and redirects are disabled for this request.

The public default Gateway URL is `http://localhost:8080`. Optional public configuration: set `VITE_API_GATEWAY_URL=http://localhost:8080` in the shell before running/building. Vite values are bundled publicly; never put credentials in `VITE_*`. No `.env` file is needed or committed. The customer page supplies the shared in-memory token getter and the auth-owned one-refresh retry wrapper. Accounts app/database remain pending #5; no backend is generated here.

## Shared authentication #36

Official **keycloak-js 26.2.4**, exact in package.json and pnpm-lock.yaml. The adapter owns Authorization Code + S256 PKCE, cryptographic state/nonce, callback exchange and refresh; no custom OAuth cryptography. See the [official adapter guide](https://www.keycloak.org/securing-apps/javascript-adapter).

Public optional variables (shell exports; no .env required):

| Variable | Default |
|---|---|
| `VITE_KEYCLOAK_URL` | `http://localhost:8090` |
| `VITE_API_GATEWAY_URL` | `http://localhost:8080` |

Phase 1 changes visible branding only. The legacy realm, public client, audience, package namespace and theme path remain unchanged until a reviewed technical migration.

Realm `devarom`, public client `devarom-frontend`, API audience `devarom-api`. Run the SPA at `http://localhost:5173`; callback must be registered exactly as `http://localhost:5173/auth/callback`, post-logout as `http://localhost:5173/`. HTTPS and matching exact registered origins/redirects are required outside localhost. All VITE values are public; never provide OAuth secrets.

### Local run

1. Start Docker Desktop. Follow `platform/identity/README.md` to supply the ignored local identity configuration, then from the repository root run `docker compose -f platform/identity/docker-compose.identity.yml --project-directory platform/identity up -d` and `./platform/identity/validate-identity.sh`.
2. Run `cd services/api-gateway && ./mvnw spring-boot:run` with Java 21. Real profile reads also require Sinthuha's Accounts provider #5 and independent JWT security #37; neither is implemented by #36.
3. From `frontend/`, run `pnpm install --frozen-lockfile` and `pnpm dev` using the pinned Node/pnpm toolchain. Open `/login`.
4. Choose Sign in or Create account to use Keycloak-managed credentials. Registration supplies no role/status parameters; the current realm defaults to CUSTOMER. Merchant onboarding/approval remains a separate business feature. Staff roles require controlled provisioning.
5. After callback, a CUSTOMER can enter Customer space. Only `GET /api/v1/users/me` through Gateway is consumed; response subject must match the authenticated session. An absent service produces a real unavailable state.

### Session policy

- Access, refresh and ID tokens stay in adapter memory. No token is written to browser storage, readable cookies, application logs or URLs. The adapter temporarily persists **only OIDC transaction data** (state, nonce, PKCE verifier) under its own expiring `kc-callback-*` keys; these are consumed by callback. Do not confuse transaction storage with token persistence.
- Initialization/code exchange is shared once per browser document, including React StrictMode. Callback parameters are removed even on failure. State/nonce rejection clears local credentials, including partially decoded responses.
- Reload starts unauthenticated. Select Sign in to use the library's normal code + PKCE flow again; an existing Keycloak server session may avoid another password prompt. No persisted authenticated flag is trusted. No silent iframe/check-sso page or redirect-loop recovery is added.
- Token retrieval refreshes with 30 seconds of minimum validity; concurrent refreshes are deduplicated. A profile 401 forces one refresh and one GET retry. Another 401 or failed refresh clears credentials/private cache and the protected entry returns to login with expiry feedback. No command replay.
- Logout clears local state/cache immediately and uses the adapter's **POST** end-session flow with the ID token hint in the form body, never a URL. If Keycloak is unreachable, the browser-side session is cleared, but server termination is unverified; the server session may still exist. Reload/sign-in can reuse it until Keycloak logout or expiry.
- Role guards/navigation are UX only. Gateway and Accounts must independently validate JWT issuer/signature/audience/expiry, roles and resource/tenant ownership. The SPA never grants roles or merchant approval. Other teams' role landing screens remain pending.
- Before redirecting, a five-second discovery availability check gives sanitized provider-unavailable feedback. Callback cancellation, invalid state/nonce and session expiry never create a successful local session.

### Google broker verification

Use Continue with Google (`kc_idp_hint=google`), through Keycloak only. Configure/enable Keycloak's existing `google` provider and its server-side credentials per `platform/identity/README.md`. Google Cloud OAuth consent testing must list the intended test user's Google account; broker redirect is `http://localhost:8090/realms/devarom/broker/google/endpoint`. Keep credentials out of frontend/VITE variables. Only a completed Google → Keycloak → React callback proves L03.

### Verification — 2026-10-16

Automated evidence uses isolated mocks only: session lifecycle, real-adapter S256 verifier/challenge and callback exchange, invalid state/nonce, cancellation, refresh/logout races, no persisted tokens, Bearer attachment, role guard and subject/cache isolation. These tests do not verify token signatures or real Google/Accounts integration; backend security remains independently required.

Browser checks in Chrome: anonymous `/customer` returns to `/login` without a profile; Sign in while Keycloak is down shows provider-unavailable feedback; a cancelled callback is scrubbed and returns to login with cancellation feedback. Full local login and Google login remain **unverified**: Docker daemon is stopped and localhost:8090 is unreachable. Google credentials/consent/test-user configuration was not inspected or exercised. Accounts #5 and #37 are open, so the real profile round trip is pending. See `docs/project/STATUS.md` for actual command results.

Deferred: Accounts implementation/security (#5/#37), other role dashboards, merchant business onboarding, booking/tracking, and infrastructure/deployment. No end-to-end milestone checkbox is marked complete by this frontend PR.
