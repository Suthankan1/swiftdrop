# Multi-owner module handoff protocol

Each user-facing **actor module** is a feature area; each deployable **service** has a distinct data boundary. The same `delivery-service` may receive small PRs from Customer, Courier, Dispatcher and Platform developers. Agree feature paths/namespaces and **one reviewer** before parallel code changes.

Before starting cross-module work:

1. Create a GitHub Issue with owner, reviewer, service directory, user journey, acceptance criteria, security/role matrix and protocol contract links.
2. Decide API/provider boundary before work (e.g., courier proof UI calls Delivery-owned proof endpoint; cross-module contributor creates a storage adapter only).
3. Merge contract/endpoint proposal before multiple parallel implementations.
4. Keep business invariants in owning service, not in Gateway, GraphQL, Communications or frontend.
5. Reviewer checks concurrency, ownership, error paths and tests; merge only after team signoff.

Workload accounting: estimate **small slices** on a common team scale; don't count the entire backend as "platform integration". If the ninth member takes the webhook worker, redistribute infrastructure or split with Merchant pair. Track estimates/evidence in `CONTRIBUTION_LOG.csv`; replace estimated allocation after actual work starts.
