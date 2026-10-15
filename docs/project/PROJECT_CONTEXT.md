# Devarom — compact project context for contributors and AI agents

**Product:** Parcel Delivery & Courier Management System. **Brand:** Devarom (formerly called SwiftDrop in early drafts). **Course:** Enterprise Application Development group assignment. **Actual team:** nine members; assignment prints ten, lecturer clarification is pending. **Milestone:** November 1 — design report (use cases, architecture, flowcharts, justifications) and an initial codebase **with demonstrable logins**. The supplied assignment did not specify this deadline; it came from the group.

## Actors and main journeys

- **Customer:** local/Google sign-up/login, request delivery quote using addresses/size/weight, book parcel, check progress/history, receive receipt, public redacted number tracking.
- **Merchant:** register (admin approval required), submit delivery orders via scoped API key or possibly OAuth client credentials, manage dashboard and webhook subscriptions.
- **Courier:** see *own* daily assignments, update parcel state with notes/recipient/photo proof, record failed delivery reason.
- **Dispatcher:** view unassigned parcels, assign eligible courier, monitor workload; **Admin:** merchant approvals, users, zones, pricing, reports.
- **System:** JWT/role and ownership enforcement, Google OIDC login, async status processing, merchant webhook retry, live status updates, protected personal data, Dockerized runtime, tested integrations.

## Architecture v1.3 in one paragraph

React+TS+Vite SPA → Spring Cloud Gateway: **REST for commands/simple reads, read-only GraphQL for multi-service dashboards, SSE for parcel updates**. Keycloak brokers Google with OIDC Authorization Code + PKCE; Keycloak JWT is validated at gateway **and** owner service. Four independent Spring Boot business services: Accounts & Partners, Pricing & Zones, Delivery Operations and Communications. One separate GraphQL Query API aggregates authorized internal **gRPC** reads; Delivery also uses gRPC to Accounts/Pricing for synchronous eligibility/quote checks. Delivery PostgreSQL transaction commits parcel/history/outbox; relay publishes versioned **Kafka** records; Communications independently consumes events and schedules durable notifications/signed webhooks/retries. Delivery provides SSE. Each business service owns its **own logical PostgreSQL database**. Bonus Kubernetes/Helm and AI Delivery Assistant are later targets.

## Not in this starter

No implementation, packages, Maven/Gradle files, React files, Docker Compose or Dockerfile, Kubernetes YAML, `.proto`, Kafka topic provisioning or generated credentials. Empty module folders have only explanatory READMEs. **Do not treat report/diagram presence as passing software.**

## Design constraints

No monolith. No cross-service DB access. No GraphQL mutations or GraphQL querying DBs. No JWT in query string/localStorage; browser OIDC public client with in-memory access tokens. No realtime GPS claims; SSE is for parcel status. No automatic staff role grant on signup. No committing secrets.

## Project entry points

- [Architecture](../architecture/BASELINE.md)
- [Requirements](../requirements/ASSIGNMENT_SCOPE.md)
- [Ownership](../team/OWNERSHIP.md)
- [November 1 acceptance](../milestones/LOGIN_ACCEPTANCE.md)
- [Open questions](../architecture/OPEN_DECISIONS.md)
