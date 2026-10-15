# Devarom shared SPA

The single React application for Devarom. Issue [#6](https://github.com/Suthankan1/devarom/issues/6) implements the public shell and Customer profile presentation. **Authentication and full-stack login are not implemented here.**

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
| `/customer` | Customer entry with an honest sign-in unavailable state; no private data/request |
| `/login`, `/auth/callback`, `/logout` | Explicit authentication-pending pages reserved for #36; no identity/session behavior |
| `*` | Not-found page with a route home |

The header links only to public entries. Hiding navigation never enforces authorization; Gateway and each business service must independently enforce it.

## Source layout

```text
src/
  app/                  App, router and QueryClient provider
  auth/contracts.ts     Unimplemented typed authentication seam for #36
  components/layout/    Shared header, mobile navigation, footer and outlet
  components/ui/        Reusable feedback and application error boundary
  features/public/      Landing and not-found pages
  features/customer/    Profile page, presentation component, DTOs and API adapter
  styles/               Shared CSS tokens, components and responsive layout
  test/                 Setup, synthetic fixtures and application/security tests
```

The profile presentation accepts explicit auth-required/loading/empty/unavailable/forbidden/ready states. Production always selects auth-required until #36 is connected. Ready data exists only in automated tests. TanStack Query is provided once at the app boundary; no profile query is active yet.

## Accounts #5 boundary

`src/features/customer/api/contracts.ts` matches `docs/contracts/LOGIN_CONTRACT.md` §4.2: `id`, `keycloakSub`, `email`, `fullName`, `phoneNumber`, `roles`, nullable `merchantProfile`, `createdAt`. IDs remain opaque strings. Merchant status and roles remain strings because the response contract does not freeze complete enums; Accounts must clarify nullability/status evolution before live integration.

`createAccountsApi` in `src/features/customer/api/accounts.ts` accepts an async `getAccessToken` and injectable fetch for isolated tests. It calls only `GET /api/v1/users/me` through Gateway, with Bearer authorization and JSON Accept. Without a token it rejects before making a request. Responses are structurally checked; 401 requires auth, 403/privacy-preserving 404 become access unavailable, other HTTP/network failures become service unavailable. Raw error bodies are not displayed or logged. Abort signals are supported; cookies, HTTP caching and redirects are disabled for this request.

The public default Gateway URL is `http://localhost:8080`. Optional public configuration: set `VITE_API_GATEWAY_URL=http://localhost:8080` in the shell before running/building. Vite values are bundled publicly; never put credentials in `VITE_*`. No `.env` file is needed or committed. The adapter is deliberately not called by the current page. Accounts app/database remain pending #5; no backend is generated here.

## Authentication #36 handoff

Extend these exact points after merging this PR:

1. Implement `AuthIntegration`/`AuthSnapshot` from `src/auth/contracts.ts` in `src/auth/`. Status, subject, roles, async token retrieval, login/logout and session-expiry action are typed; no AuthProvider is implemented by #6.
2. Mount the real provider in `src/app/providers/AppProviders.tsx` and replace reserved route elements in `src/app/router/routes.tsx`.
3. Connect `CustomerPage.tsx` to verified auth state and the Accounts adapter. Never query before authentication, compare returned `keycloakSub` with the current verified subject, key queries by subject, cancel in-flight profile requests and clear all private QueryClient data on logout, expiry or identity changes. Never render a previous subject’s cached profile.
4. #36 owns the single silent-refresh attempt after 401 and the login fallback, sanitized callback errors, role navigation and expiry UX. Inject its in-memory token getter; do not duplicate refresh logic in the Accounts adapter.

Public next-task settings: Keycloak `http://localhost:8090`, realm `devarom`, client `devarom-frontend`, callback `http://localhost:5173/auth/callback`, post-logout `http://localhost:5173/`, Gateway `http://localhost:8080`. Use Keycloak code + PKCE S256 and Google brokering per the reviewed contract. No OIDC library, client secret, persistent token, fake session or local user identity is present in #6.

#36 can start after this scaffold merges; proving a real profile round trip also needs #5 and #37. Existing older paragraphs in the login contract/identity README assigning OIDC to Customer are superseded by the ownership banner and #36. Merchant self-registration vs actual CUSTOMER-only defaults, and Accounts field nullability/status details, still need owner coordination for those later features. No decision blocks this scaffold or starting local Customer authentication.

Deferred: real login/signup/callback/logout/Google/PKCE/JWT testing, live Accounts integration, booking, quotes, tracking, role dashboards, other services, infrastructure and deployment. The November 1 login acceptance register remains unverified by this frontend scaffold.
