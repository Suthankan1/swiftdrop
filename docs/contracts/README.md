# Contract-first collaboration (no implementation files yet)

This folder describes review and compatibility requirements; **no real `.proto`, OpenAPI, GraphQL schema, Kafka JSON schema or generated clients have been created in this starter**. When teams implement the appropriate slice, agree contract PRs first and then place versioned machine-readable files in the repository `contracts/` tree.

| Contract | Guidance | Future machine-readable location |
|---|---|---|
| Login & Auth (Nov 1) | [Login & Auth Contract](LOGIN_CONTRACT.md) | Identity integration, OIDC PKCE, JWT validation, port registry, and minimal Gateway-Accounts route |
| External REST | [REST](REST.md) | `contracts/rest/` OpenAPI and HTTP error conventions |
| Read-only GraphQL | [GraphQL](GRAPHQL.md) | `contracts/graphql/` schema files |
| Internal gRPC | [gRPC](GRPC.md) | `contracts/grpc/` versioned `.proto` files |
| Async business events | [Kafka](KAFKA.md) | `contracts/events/` event schemas/example payloads (synthetic only) |

**Change protocol:** PR opened by owner → consumer reviewer signs off → schema validation/backward compatibility → implementation + tests → update diagrams/docs. Never allow separately generated callers and servers to guess the same field names. Proposed URI/topic examples are **not already frozen**.
