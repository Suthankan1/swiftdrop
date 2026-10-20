# SwiftDrop — compact project context for contributors and AI agents

**Company and product:** SwiftDrop — Parcel Delivery & Courier Management System. The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on **2026-10-20** by the project owner. Legacy runtime identifiers remain unchanged in Phase 1; see the [migration proposal](../branding/PHASE_2_MIGRATION.md). **Course:** Enterprise Application Development group assignment. **Actual team:** nine members; assignment prints ten, lecturer clarification is pending. **Milestone:** November 1 — design report (use cases, architecture, flowcharts, justifications) and an initial codebase **with demonstrable logins**. The supplied assignment did not specify this deadline; it came from the group.

## Actors and main journeys

- **Customer:** local/Google sign-up/login, request delivery quote using addresses/size/weight, book parcel, check progress/history, receive receipt, public redacted number tracking.
- **Merchant:** register (admin approval required), submit delivery orders via scoped API key or possibly OAuth client credentials, manage dashboard and webhook subscriptions.
- **Courier:** see *own* daily assignments, update parcel state with notes/recipient/photo proof, record failed delivery reason.
- **Dispatcher:** view unassigned parcels, assign eligible courier, monitor workload; **Admin:** merchant approvals, users, zones, pricing, reports.
- **System:** JWT/role and ownership enforcement, Google OIDC login, async status processing, merchant webhook retry, live status updates, protected personal data, Dockerized runtime, tested integrations.

## Architecture v1.3 in one paragraph

React+TS+Vite SPA → Spring Cloud Gateway: **REST for commands/simple reads, read-only GraphQL for multi-service dashboards, SSE for parcel updates**. Keycloak brokers Google with OIDC Authorization Code + PKCE; Keycloak JWT is validated at gateway **and** owner service. Four independent Spring Boot business services: Accounts & Partners, Pricing & Zones, Delivery Operations and Communications. One separate GraphQL Query API aggregates authorized internal **gRPC** reads; Delivery also uses gRPC to Accounts/Pricing for synchronous eligibility/quote checks. Delivery PostgreSQL transaction commits parcel/history/outbox; relay publishes versioned **Kafka** records; Communications independently consumes events and schedules durable notifications/signed webhooks/retries. Delivery provides SSE. Each business service owns its **own logical PostgreSQL database**. Bonus Kubernetes/Helm and AI Delivery Assistant are later targets.

## Actual implementation and deferred targets

The React/Keycloak/Gateway/Accounts initial-login slice and local verification tooling now exist; inspect [STATUS.md](STATUS.md) for dated results and review gaps. Delivery, Pricing, Communications, GraphQL, gRPC, Kafka, SSE, Kubernetes and the AI assistant remain deferred design targets. Preserve the placeholder READMEs in those modules. **Do not treat report/diagram presence as passing software.**

## Design constraints

No monolith. No cross-service DB access. No GraphQL mutations or GraphQL querying DBs. No JWT in query string/localStorage; browser OIDC public client with in-memory access tokens. No realtime GPS claims; SSE is for parcel status. No automatic staff role grant on signup. No committing secrets.

## Project entry points

- [Architecture](../architecture/BASELINE.md)
- [Requirements](../requirements/ASSIGNMENT_SCOPE.md)
- [Ownership](../team/OWNERSHIP.md)
- [November 1 acceptance](../milestones/LOGIN_ACCEPTANCE.md)
- [Open questions](../architecture/OPEN_DECISIONS.md)
