# Devarom

**Parcel Delivery & Courier Management System** · Group EAD assignment · **Architecture baseline v1.3** · documentation-first monorepo.

> **Current state:** This is an intentionally **documentation-only starter**. No React/Spring Boot/Keycloak application has been generated, no infrastructure is running, and no login has been implemented or tested by this starter. Team members generate actual applications later in their assigned folders.

## Read first

1. [Project context](docs/project/PROJECT_CONTEXT.md) — assignment, scope, protocol choices.
2. [Team ownership](docs/team/OWNERSHIP.md) — who builds what; the ninth member owns platform and four specifically bounded cross-module slices.
3. [November 1 milestone](docs/milestones/NOV_01.md) — report and **working initial logins**, with proof to collect.
4. [Architecture baseline](docs/architecture/BASELINE.md) and [protocol matrix](docs/architecture/PROTOCOL_MATRIX.md).
5. [Diagrams](docs/diagrams/README.md) — **20 original editable Draw.io files plus lightweight SVG previews**, included from the group's design pack.
6. [AI agent instructions](AGENTS.md) and [prompt playbook](docs/ai/PROMPT_PLAYBOOK.md).
7. [Open decisions](docs/architecture/OPEN_DECISIONS.md) — not everything in the diagrams is an approved implementation contract.

## Planned source layout (currently placeholder READMEs only)

| Folder | Intended owner/boundary |
|---|---|
| `frontend/` | One React + TypeScript + Vite SPA for role-specific UX |
| `services/api-gateway/` | Spring Cloud Gateway, public routing and cross-cutting edge policy |
| `services/accounts-service/` | Accounts & Partners profiles/approvals, internal eligibility APIs |
| `services/pricing-service/` | Pricing & Zones rules and expiring quotes |
| `services/delivery-service/` | Parcel, booking, assignment, status/history, receipt/proof and outbox |
| `services/communications-service/` | Notifications and signed merchant webhook delivery |
| `services/graphql-query-service/` | **One read-only** dashboard aggregation API; no domain database |
| `contracts/` | Future reviewed REST/GraphQL/Protobuf/Kafka contracts (no runtime code yet) |
| `platform/identity/` | Future Keycloak realm/config — **no actual secrets** |
| `platform/local/` | Future Docker Compose setup by the team, not supplied here |
| `platform/kubernetes/` | **Later bonus**, no manifests included |
| `ai-assistant/` | **Later optional bonus**, no implementation included |
| `tests/` | Future independently executable cross-service/integration tests |
| `docs/` | Architecture, scope, requirements, decisions, diagrams and AI instructions |

**Generation:** Each owner uses Spring Initializr for their own Java application or Vite for the SPA **inside the assigned folder** after team agreement. Do not generate a monolith, share another service's persistence entities, or ask an agent to silently scaffold the entire repo.

## Suggested first Git steps

Create an empty GitHub repository named `devarom` (subject to group naming approval). Copy this starter's *contents* into the clone's root; verify `README.md` is at the repository root. Commit documentation first. Protect `main` with PR review. Have owners create thin, independently compiling app scaffolds in small PRs; make the first end-to-end milestone **login**, not complete booking/Kafka.

- [Team-run Spring Initializr and Vite setup guide](docs/development/STARTER_GENERATION_GUIDE.md)
- [Git workflow](docs/development/GIT_WORKFLOW.md)
- [Change / documentation policy](docs/development/CHANGE_POLICY.md)
- [Module boundaries](docs/architecture/DATA_OWNERSHIP.md)
- [November 1 evidence register](docs/milestones/LOGIN_ACCEPTANCE.md)

## Important provenance

Diagrams derive from the team's uploaded **Devarom November 1 Design Package**. The latest hybrid v1.3 design supersedes older REST-only/RabbitMQ or SwiftDrop naming. The academic assignment is the requirement source; architectural choices are **team design decisions**, not all prescribed by the lecturer. Original report PDFs/PNGs and generator scripts are **deliberately excluded** from this software repository starter.

**Administrative open item:** the assignment says 10 students; the team has 9. Confirm permission with the lecturer. Do not invent a final submission date/year or claim that test evidence already exists.
