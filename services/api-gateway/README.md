# Spring Cloud API Gateway — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Route public REST, `/graphql` and SSE to their owners; CORS/rate/edge policy, JWT prechecks.

## Ownership/boundary

Generated **Spring Cloud Gateway** app must not implement business rules, issue identities, host GraphQL field resolvers or read domain DBs. Verify JWT at owning services too. Coordinate with Platform.

## First planned slice

First milestone: route authenticated profile endpoint through gateway; deny missing token with real integration proof.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
