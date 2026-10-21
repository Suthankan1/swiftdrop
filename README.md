# SwiftDrop

**Parcel Delivery & Courier Management System** · Group EAD assignment · **Architecture baseline v1.3**.

> **Current state:** The initial login slice includes the React SPA, Keycloak, API Gateway and Accounts Service. Local/Google login evidence and remaining independent review work are recorded in [project status](docs/project/STATUS.md). Delivery, Pricing, Communications, GraphQL, gRPC, Kafka and SSE remain design targets.

The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on **2026-10-20** by the project owner.
- **Official project:** SwiftDrop
- **GitHub rename:** completed (`https://github.com/Suthankan1/swiftdrop.git`)
- **Phase 1 visible branding:** complete
- **Phase 2 identity migration:** complete
- **Current identity realm:** `swiftdrop`
- **Current OIDC client:** `swiftdrop-frontend`
- **Current JWT audience:** `swiftdrop-api`

See the [rename impact audit](docs/branding/PHASE_1_AUDIT.md), [Phase 2 migration record](docs/branding/PHASE_2_MIGRATION.md), and [Phase 2 final signoff](docs/branding/PHASE_2_FINAL_SIGNOFF.md).

## Read first

1. [Project context](docs/project/PROJECT_CONTEXT.md) — assignment, scope, protocol choices.
2. [Team ownership](docs/team/OWNERSHIP.md) — who builds what; Suthankan1 owns platform and four specifically bounded cross-module slices.
3. [November 1 milestone](docs/milestones/NOV_01.md) — report and **working initial logins**, with proof to collect.
4. [Login integration contract](docs/contracts/LOGIN_CONTRACT.md) — frozen versions, port registry, and minimal REST/OIDC contract.
5. [Architecture baseline](docs/architecture/BASELINE.md) and [protocol matrix](docs/architecture/PROTOCOL_MATRIX.md).
6. [Diagrams](docs/diagrams/README.md) — **20 editable Draw.io files and regenerated SVG previews**, derived from the group's design pack with current branding.
7. [AI agent instructions](AGENTS.md) and [prompt playbook](docs/ai/PROMPT_PLAYBOOK.md).
8. [Open decisions](docs/architecture/OPEN_DECISIONS.md) — not everything in the diagrams is an approved implementation contract.

## Source layout

| Folder | Intended owner/boundary |
|---|---|
| `frontend/` | One React + TypeScript + Vite SPA; shared login and customer profile implemented, other role features pending |
| `services/api-gateway/` | Implemented Spring Cloud Gateway routing and JWT edge policy for the login slice |
| `services/accounts-service/` | Implemented subject-bound profiles and independent JWT security; further business features pending |
| `services/pricing-service/` | Pricing & Zones rules and expiring quotes |
| `services/delivery-service/` | Parcel, booking, assignment, status/history, receipt/proof and outbox |
| `services/communications-service/` | Notifications and signed merchant webhook delivery |
| `services/graphql-query-service/` | **One read-only** dashboard aggregation API; no domain database |
| `contracts/` | Future reviewed REST/GraphQL/Protobuf/Kafka contracts (no runtime code yet) |
| `platform/identity/` | Keycloak realm, theme, identity Compose and validation tooling — **no actual secrets** |
| `platform/local/` | Local startup and readiness tooling |
| `platform/kubernetes/` | **Later bonus**, no manifests included |
| `ai-assistant/` | **Later optional bonus**, no implementation included |
| `tests/` | Security E2E and initial-login demo smoke harnesses |
| `docs/` | Architecture, scope, requirements, decisions, diagrams and AI instructions |

**Generation:** Each owner uses Spring Initializr for their own Java application or Vite for the SPA **inside the assigned folder** after team agreement. Do not generate a monolith, share another service's persistence entities, or ask an agent to silently scaffold the entire repo.

## Contribution workflow

Use the official repository (`https://github.com/Suthankan1/swiftdrop.git`). Create feature branches from the latest `origin/test` and target development PRs at **`test`**. Preserve `main` as the stable release baseline. Continue with one owner-reviewed slice at a time.

- [Team-run Spring Initializr and Vite setup guide](docs/development/STARTER_GENERATION_GUIDE.md)
- [Git workflow](docs/development/GIT_WORKFLOW.md)
- [Change / documentation policy](docs/development/CHANGE_POLICY.md)
- [Module boundaries](docs/architecture/DATA_OWNERSHIP.md)
- [November 1 evidence register](docs/milestones/LOGIN_ACCEPTANCE.md)

## Important provenance

Diagrams derive from the team's uploaded **Devarom November 1 Design Package**. Phase 1 changes current diagram labels to SwiftDrop without changing the v1.3 design. The latest hybrid v1.3 design supersedes older REST-only/RabbitMQ references. The academic assignment is the requirement source; architectural choices are **team design decisions**, not all prescribed by the lecturer. Original report PDFs/PNGs and generator scripts are **deliberately excluded** from this repository. Historical PRs, test records, source package names and immutable links retain their original wording.

**Administrative open item:** the assignment says 10 students; the team has 9. Confirm permission with the lecturer. Do not invent a final submission date/year or claim that test evidence already exists.
