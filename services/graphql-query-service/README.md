# Read-only GraphQL Query API — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

One aggregation service for role dashboards, backed by private authorized gRPC reads.

## Ownership/boundary

No business DB, GraphQL mutation/subscription or direct SQL. Dispatcher pair owns most reporting UX; Platform owns only the small `deliveryStats` resolver by agreement. Query complexity, batching and tenant field checks.

## First planned slice

Future milestone: one authorized typed dashboard query after providers exist.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
