# Practical prompts for ChatGPT, Codex, Claude, Cursor, Copilot and other agents

These templates work across tools because **repository files—not chat memory—carry requirements**. Every agent must start with [`AGENTS.md`](../../AGENTS.md), the relevant module README, actual Git state and the assigned issue. User approval is needed to generate or change code.

## A. Context-only onboarding (safe to run now)

> Read `AGENTS.md`, `docs/project/PROJECT_CONTEXT.md`, `docs/architecture/BASELINE.md`, `docs/architecture/PROTOCOL_MATRIX.md`, `docs/team/OWNERSHIP.md`, and `docs/milestones/NOV_01.md`. Summarize the SwiftDrop v1.3 components, intended November 1 login slice, what is **not implemented**, open decisions, and the 9-person ownership. **Do not create/modify any code or files.** Point out conflicts instead of inventing answers.

## B. Plan (without writing code) one Keycloak + SPA login slice

> You are planning, **not implementing**, the SwiftDrop November 1 login vertical slice. Read `AGENTS.md`, `docs/security/AUTHENTICATION.md`, `docs/milestones/LOGIN_ACCEPTANCE.md`, and `frontend/README.md`. Inspect repository for the actual frontend/Keycloak state. Propose one minimal tested issue with allowed paths, role/PKCE safety, actual acceptance tests, and what must be configured manually. Do not generate a Vite app or Keycloak secrets unless separately authorized by the Customer pair and Platform owner.

## C. Spring Initializr-generated module integration (after owner generates it)

> Repo context: read `AGENTS.md`, `docs/architecture/DATA_OWNERSHIP.md`, `docs/contracts/README.md`, and `services/[service]/README.md`. The human has **already generated** the Spring Boot skeleton via Spring Initializr. For [issue ID], in [exact service path], implement [ONE specific use case]. Preserve existing framework/dependency versions and tests; verify only documented module ownership. First identify required request/response or gRPC contract and narrow file/test plan. Do not add unrelated entities/services/infrastructure. Run focused tests and report actual results.

## D. One cross-module extra feature (ninth contributor)

> Read `docs/team/OWNERSHIP.md`, `docs/team/MODULE_HANDOFF.md`, `AGENTS.md`, and module README. Work only on **[PDF receipt backend OR merchant webhook worker OR proof storage adapter OR deliveryStats GraphQL resolver]** as a separate approved issue with the owning pair's reviewer. State what remains owned by that pair; do not take over UI, parcel logic, business rules, or any unrelated module. Start with contract tests/permissions and one small PR plan.

## E. Code review / security review

> Inspect actual diff and `AGENTS.md`, relevant authorization matrix, protocol guidance and module README. **Read-only review only.** Report concrete correctness/security/reliability risks, issue severity, file/line references and missing tests; focus on JWT issuer/audience, resource/tenant ownership, insecure secrets, stale events, idempotency, gRPC deadlines and contract breaking changes. Do not rewrite files or claim tests were executed.

## F. Milestone readiness review

> Read `docs/milestones/NOV_01.md`, `docs/milestones/LOGIN_ACCEPTANCE.md`, `docs/project/STATUS.md` and actual repository/tests. Distinguish requirements, planned design, implemented behavior and missing evidence. Produce a truthful pass/blocker checklist; do not infer a working Google login from a diagram or configuration file.

**Reusable prefix:** “This is **SwiftDrop**. Preserve v1.3 React/Vite, REST + read-only GraphQL, internal gRPC, Kafka, Keycloak and SSE. Respect team/module ownership, no unsanctioned code, and report actual test evidence only.”
