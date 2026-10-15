# Agent instructions — Devarom repository

Read this document **before any plan, code generation or change**. Applies to coding assistants, autonomous agents, IDE assistants and human contributors.

## Source-of-truth order

1. The human's **current task and explicit constraints**.
2. `docs/requirements/ASSIGNMENT_SCOPE.md` and the academic assignment when available (required features).
3. `docs/architecture/BASELINE.md` and approved `docs/architecture/adr/` (architecture v1.3).
4. `docs/team/OWNERSHIP.md`, module README and reviewed contracts (file ownership and boundaries).
5. Current repo state/tests and `docs/project/STATUS.md` (actual implementation; never infer from diagrams).

Resolve a conflict by **asking** for a decision or proposing a concise ADR. Don't quietly overwrite the baseline.

## Non-negotiable architecture

- Name: **Devarom**. Independent Spring Boot business services: **Accounts & Partners; Pricing & Zones; Delivery Operations; Communications**. Separate **Spring Cloud Gateway** and **one read-only GraphQL Query API**. React/TypeScript/Vite SPA, Keycloak/Google OIDC + PKCE, per-business-service PostgreSQL ownership.
- **REST** = external commands/simple reads/merchant APIs; **GraphQL** = read-only composite dashboards; **gRPC/Protobuf** = selected internal synchronous calls; **Kafka** = async domain event delivery; **SSE** = browser's one-way live parcel updates.
- Delivery owns parcel transitions, assignment consistency, history and transactional outbox. Communications owns notification/webhook retries. Do not perform cross-service SQL joins or share business entity JARs. Do not replace Kafka with RabbitMQ or invent extra microservices.
- API Gateway routes and applies edge policy; core services enforce JWT, roles, merchant tenant/resource ownership and assignment authorization **independently**. Keycloak brokers Google identity; a Google token is **not** a Devarom API bearer token.
- GraphQL must not query business DBs directly, implement mutations, or bypass permissions. gRPC internal endpoints require service authentication, actor context validation and deadlines. Kafka consumers require idempotency and version checks; outbox publication is at-least-once. Avoid sensitive data in events/logs.
- Nov 1 goal is design report + demonstrable **initial logins**, not a full completed system. Kubernetes/AI assistant are later optional bonuses.

## Mandatory working discipline

1. **Inspect before editing.** Read module README, relevant diagrams/contracts/ADRs, working tree and affected tests; confirm task owner and acceptance criteria.
2. **Smallest useful slice.** Propose files + tests and limit the change to one feature. No speculative future development, surprise large refactors, generating all services or changing another pair's module without review.
3. **No fabricated progress.** Distinguish planned design from implemented/verified code; only report commands/tests you actually ran.
4. **Security by design.** No secrets, passwords, token dumps, fake production credentials, API keys, `.env`, local certificates or OAuth client secret in browser (`VITE_*` is public).
5. **Contract-first integration.** Coordinate reviewed REST paths, GraphQL types, Protobuf packages, Kafka topic/schema/partition key/version and error semantics before callers are implemented. Prefer backward-compatible changes.
6. **Tests and validation.** Add relevant unit/integration/security tests in each PR; include negative auth/ownership checks. Never claim 100% coverage without measured evidence.
7. **Review/approval.** Ask before changing the v1.3 baseline, service boundaries, public API contracts, auth posture, schemas, deployment secrets, paid resources or ownership distribution. Never push, merge or deploy without authorization.
8. **Git.** Keep changes scoped, conventional commits, no generated builds or binaries. Do not commit screenshots/PDF exports unless the team explicitly changes its repository policy.
9. **Handoff.** Finish with changed files, decisions, commands + actual results, risks and **one next small task**. Use `docs/ai/TASK_TEMPLATE.md` for future agent prompts.

## Documentation-only starter guard

**Until the team explicitly assigns an implementation task, do not generate application code, pom.xml, package.json, Spring Initializr output, frontend screens, .proto files, Dockerfiles, workflows or Kubernetes manifests.** Folder READMEs are intentional placeholders. Do not remove them merely to 'clean up'.

## People and ownership

See `docs/team/OWNERSHIP.md` for the eight named members and ninth platform developer (name intentionally unknown), including four small cross-module features. Assign branch/task ownership, avoid overlap, and request team approval for transfers. Do not equate an assignment's UI role with its deployable service.
