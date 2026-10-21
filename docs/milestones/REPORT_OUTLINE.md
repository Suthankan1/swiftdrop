# Suggested November 1 report outline (submit file outside this Git repo)

1. **Title/summary** — SwiftDrop, nine-member team and approval pending; milestone and source citation.
2. **Requirements & actors** — assignment scope; use cases figures 01–04.
3. **Context/architecture** — figures 05–06; four owned services, gateway, GraphQL Query API; justify REST vs GraphQL vs internal gRPC vs Kafka events vs SSE; document service DB ownership.
4. **Workflows** — figures 07–09 and sequence figures 10–13. Google login through Keycloak; failure paths and security checks.
5. **Data/lifecycle** — three logical ERDs figures 14–16; parcel state figure 17; account/merchant approval and proof policies.
6. **Integration reliability/privacy** — figure 18; outbox/at-least-once/idempotency, consumer groups, retry/DLT, SSE reconnect, no PII in broker data; GraphQL limits and JWT ownership.
7. **SOLID/patterns** — repository, Strategy for pricing, ports/adapters, pub-sub and transaction outbox; trade-offs (Kafka more complex than RabbitMQ, gRPC deadlines, client token XSS risk).
8. **Initial codebase and actual demo evidence** — current generated project screenshots/logs safely redacted, real local + Google login (if configured), JWT/role/ownership negative test commands/results; don't fabricate.
9. **Milestones/boundaries** — implemented vs next stages; Kubernetes and AI bonuses explicitly future unless actually deployed.
10. **Team contribution and references** — use actual PR evidence, diagrams source and assignment traceability; clarify 9 vs 10 lecturers' rule.

Do not paste whole lecture PDFs or report PDFs into Git; use external submission mechanism. Use editable original figures under `docs/diagrams/editable/` and SVG views under `docs/diagrams/rendered/`.
