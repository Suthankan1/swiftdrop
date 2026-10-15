# Phased development plan — small, tested increments

**Phase 0: pre-code (now)** — GitHub repo, read-only design review, 9-person ownership signoff, contracts/open issues, scoped PR rules.

**Phase 1: by November 1** — team-generated thin React, Gateway and Accounts/Keycloak subset; local/Google login, auth-bound role navigation, JWT+ownership negative tests, diagrams/report and actual evidence. Other service scaffolds may be added later; never call plans complete.

**Phase 2: first booking vertical slice** — Pricing rate/quote, Delivery create/history, Customer booking UI, basic PostgreSQL service DB migrations, narrow gRPC verification. Commit each slice separately with tests.

**Phase 3: operations** — Merchant approval/credential API and booking; courier assignments/status/failure/proof; dispatcher assignment/workload; receipts and customer tracking history.

**Phase 4: asynchronous integrations** — Outbox + Kafka topic, robust publisher/consumer groups, notification/webhook jobs, retry/DLT, tracking SSE and reconnect. Add failure/duplicate/concurrency tests. Introduce GraphQL dashboards with typed gRPC reads once owner APIs exist.

**Phase 5: hardening/delivery** — privacy/auth edge tests, coverage measured, reproducible Docker Compose full stack, staging/demo scripts, docs. **Optional bonuses** full-stack Kubernetes/Helm with real deployment evidence and AI assistant with verified, authorized backend tool calls.

**Implementation cadence:** create one issue → contract/invariant → targeted test → minimal code → tests/integration → reviewed PR → status/handoff update. Avoid parallel undeclared service scaffolds and paid cloud resources without permission.
