# Assignment scope and traceability

Source: the group-provided four-page EAD courier-delivery assignment, summarized in the uploaded Devarom diagram package. **Original assignment remains authoritative if the summary conflicts.** 52 design/feature/rubric items are mirrored in [`assignment-traceability.csv`](assignment-traceability.csv), adapted to named module ownership rather than the design pack's superseded M1–M9 role proposal.

## Required final capabilities

1. Customer: secure signup/login incl. Google; size/weight + pickup/dropoff quote, booking, status history, real-time tracking, receipt; mobile-friendly public redacted tracking.
2. Merchant: pending approval, scoped credential-based order API, status-change webhooks with retry, order dashboard.
3. Courier: own assignments, guarded status changes and notes, recipient/photo proof where required, failed-reason capture.
4. Dispatcher/Admin: unassigned queue, courier assignment and workload, users/merchants/zones/pricing management, daily outcomes report.
5. Platform: JWT and RBAC/resource-owner checks, async broker pub/sub and notification consumer, webhook delivery, security/privacy, end-to-end Docker Compose runtime, documented interfaces.
6. Delivery evidence: use-case, ERD, architecture/integration and UML sequences/activities with justifications; tests with measurable coverage and negative authorization tests; reproducible demo and contribution sheet.

**Printed rubric components:** Design 20, Implementation 50, Testing 15, Demo 15. Assignment also mentions a 20-mark weightage; verify normalization with lecturer. Bonus **+5 full-stack Kubernetes deployment**, **+5 AI assistant calling real backend tools** (MCP encouraged, not mandatory). Bonus architecture drawings alone do not earn implementation bonus.

## November 1 is narrower than final project

For that checkpoint produce **the report** and **a running first login slice**. Protect the first functional delivery: React local login / Google login through Keycloak (subject to provider setup), JWT validation and role/ownership rejection, and reproducible run evidence. Delivering all parcels, Kafka, gRPC, GraphQL and Kubernetes by then is **not** assumed.

## Administrative and policy ambiguities

- Assignment says 10 people; actual group is 9. Request written lecturer approval.
- November 1 deadline was communicated by the team, not printed in the assignment.
- Clarify business rules: price currency/quote expiry, failure-before-pickup, proof policy, merchant API key vs client-credentials approach and notification destinations.
- Paths, schemas and exact service application names below are reviewed architectural proposals until owners approve the contracts.
