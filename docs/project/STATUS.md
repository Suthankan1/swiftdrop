# Current project status — update only with evidence

Last documentation update: **2026-10-16**. This file describes the **starter artifact**, not unknown external repositories.

| Area | State | Evidence |
|---|---|---|
| Devarom v1.3 design | **Proposed baseline** | `docs/architecture/BASELINE.md` and 20 supplied editable diagrams |
| Team allocation | **Proposed; names recorded** | `docs/team/OWNERSHIP.md` |
| November 1 report | **Existing external design package reviewed; final submission pending** | `docs/milestones/NOV_01.md` |
| Spring Boot services | **API Gateway implemented & verified (Refs #3)**; business services pending | `services/api-gateway/` (Java 21 LTS, Spring Boot 4.1.1, Spring Cloud 2025.1.3 Oakwood); 13 tests passed (routing, JWT, CORS, header sanitization, 503 error handling); Accounts Service pending Issue #5 |
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
