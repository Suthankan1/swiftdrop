# SwiftDrop read-only GraphQL Query API

Issue #119 (HarithKeshan) supplies the single dashboard query runtime. Java 21,
Spring Boot 4.1.1, Spring GraphQL and Maven; namespace `com.swiftdrop.graphql`.
No database, migrations, domain repositories, mutations or subscriptions.

## Build and run

From the repository root, install shared artifacts in order (Unix: `./mvnw`):

```powershell
cd shared/proto-contracts
.\mvnw.cmd clean install
cd ../security-adapter
.\mvnw.cmd clean install
cd ../../services/graphql-query-service
.\mvnw.cmd clean test
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

The executable JAR runs with `java -jar target/graphql-query-service-0.0.1-SNAPSHOT.jar`.
HTTP port is **8085**, per `docs/contracts/LOGIN_CONTRACT.md`; optional
`GRAPHQL_SERVER_PORT` overrides it. `POST /graphql` requires a SwiftDrop bearer
JWT, including direct service access. Only health is public:
`GET /actuator/health`; details and other actuator endpoints are not exposed.
GET GraphQL, GraphiQL and schema introspection are disabled.

Platform's existing JWT environment names are consumed unchanged:
`KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWKS_URI`, `JWT_AUDIENCE` (default
`swiftdrop-api`). Local issuer/JWKS defaults use the existing Keycloak on 8090.
A Google token is not an API credential. No credentials belong in this module,
browser variables or source control. Isolated tests require no containers.

## Executable schema and compatibility

Maven copies `../../contracts/graphql/schema.graphql` into the JAR at build time;
there is no maintained second schema. Startup rejects unexpected roots or a
Mutation/Subscription type. Exposed fields:

| Query | Local role gate | Provider / resolver dependency |
|---|---|---|
| customerDashboard | CUSTOMER | Customer pair; Delivery subject-scoped dashboard reads |
| merchantDashboard | MERCHANT | Merchant pair; Accounts approval/tenant binding and Delivery reads |
| courierDashboard | COURIER | Courier pair; Accounts eligibility and Delivery assigned-only reads |
| dispatcherDashboard | DISPATCHER or ADMIN | Dispatcher pair; Accounts/Delivery queue and workload |
| adminDashboard | ADMIN | Admin pair; Accounts pending summary plus Delivery aggregates |
| deliveryStats | DISPATCHER or ADMIN | Pushmitha, #30/#113; preserve PR #213's SDL contract |

**All six production resolvers are deferred.** A permitted root returns sanitized
GraphQL `UNAVAILABLE` until its owner registers a resolver. Wrong roles receive
`FORBIDDEN` first. There are no dummy records or zero-valued statistics.
Contract exposure is not business integration evidence.

Issue #119's October 23 amendment explicitly removes `Query.publicTracking`
from the release SDL. This intentional breaking removal requires domain/Platform
review; anonymous tracking remains REST-owned (#115/#133). The old shape remains
in Git history and unused supporting types remain in the SDL as historical
evidence. No anonymous GraphQL root is exposed. The compatibility validator
reports this exact retirement as accepted under #119. Other breaking changes
remain failures; see `tests/contracts/test_graphql_compatibility.py`.

The service aligns gRPC runtime libraries on **1.83.1**. The earlier shaded
transport 1.68.1 is vulnerable to CVE-2025-55163; no scanner suppression is used.
Shared generated contracts and security adapter source remain unchanged.

## Execution limits

| Guard | Policy |
|---|---|
| Maximum depth | 5, graphql-java AST-aware instrumentation before fetchers |
| Maximum complexity | 100, including aliases/fragments and multiple roots |
| JSON request | 65,536 bytes, including unknown-length bodies; 413 above limit |
| Pagination | page defaults 0; size defaults 20; page >= 0 and size 1..50 |
| Result lists / arrays | maximum 50 items per field; oversized results fail closed |
| Scalar strings | maximum 4,096 characters per field |
| gRPC response | maximum 262,144 inbound bytes per call |
| gRPC deadline | 2,000ms, preserving a shorter existing deadline |
| Batch | maximum 50 keys per provider call; loader caching disabled |

Complexity is `1 + children` for all selected fields; scalars cost 1. Result
cardinality is bounded separately by provider pagination and the 50-item result
guard. Issue #25's policy correction removes the former fixed list multiplier:
it charged the required Dispatcher projection 709 even at page size 1. The
projection now costs 23 under the unchanged cap of 100. Alias/fragment expansion
still counts toward complexity, and oversized pages are rejected before provider
execution. This policy change requires security review in the feature PR.
graphql-java handles syntax, fragment cycles,
variables and selected operations before provider work. JSON batches are rejected.

The result guard is a final defense, never fetch-all-and-truncate pagination.
Owners must pass bounded pages into providers before retrieval. Unpaginated SDL
lists need an agreed provider cap (at most 50); do not invent pagination arguments.
Int range/count and projection validation remain provider/resolver-owner work.

## Resolver and provider handoff

1. Add one `RootQueryResolver` bean in `resolver/` for an existing approved root.
   Duplicate/unapproved registrations fail startup. `GraphqlFieldPolicy` wraps
   every registered root; never install a global default fetcher.
2. Read the verified `JwtAuthenticationToken` from
   `env.getGraphQlContext().get("actor")`. Its subject is the caller. Never trust
   subject/tenant arguments or identity headers. Use `PaginationPolicy.from(...)`;
   dispatcher pagination is already validated by the root wrapper.
3. Enforce merchant approval/tenant membership, courier assignments, operational
   zones and child projection at the owning provider. Roles alone do not grant
   resource access. No invented tenant claim or delegation scope is added here.
4. Platform supplies a rotating `ServiceTokenProvider` bean and approved TLS
   endpoints/trust. Spring properties `swiftdrop.grpc.accounts-target` and
   `swiftdrop.grpc.delivery-target` create named `accountsReadClients` and
   `deliveryReadClients` beans. Standard environment names are
   `SWIFTDROP_GRPC_ACCOUNTS_TARGET` and `SWIFTDROP_GRPC_DELIVERY_TARGET`.
   A configured target without Platform's provider fails startup. No channels or
   machine credentials are invented when targets are absent.
5. Use `client.accounts(context, stub -> ...)` or `client.delivery(context, stub -> ...)`.
   Generated stubs use the shared `SwiftDropGrpcClientInterceptor`. Calls capture
   the verified JWT explicitly, forward its signed actor token, scope/restore
   Spring and gRPC context, enforce deadlines and bound responses. Providers must
   independently verify machine identity, actor proof and resource scope. Never
   populate unproved `KEY_DELEGATED_ACTOR` values. There are no automatic retries.
6. Register `RequestBatchLoader` beans returning a NEW
   `BoundedBatchSupport.create(...)` loader for each `create(context)` call.
   `QueryContextInterceptor` creates a registry per request.
   `AccountsProfileBatch.create(client, context, scope)` uses the existing
   `GetBatchProfileSummaries` RPC and stable subject keys; approved use is
   DISPATCHER/ADMIN only. Missing keys retain null positions; foreign/duplicate
   keys fail closed. Scope must be an owner-reviewed constant. No unsupported
   Delivery/Pricing batch RPC is invented. If adding async work, use bounded
   workers, propagate cancellation/deadlines and capture this request context.
7. Throw provider failures. `SafeGraphqlExceptionResolver` returns safe codes:
   FORBIDDEN, UNAUTHENTICATED, INVALID_ARGUMENT, NOT_FOUND, DEADLINE_EXCEEDED,
   UNAVAILABLE or INTERNAL. Provider descriptions, hostnames, claims, SQL and
   traces never become error messages. Missing providers are not empty successes.
   Standard non-null propagation applies: every current root is non-null, so a
   failed root nulls `data`, even in a multi-root query. Changes to partial-success
   nullability require contract review.
8. Add endpoint tests for allowed/denied roles, tenant/resource isolation, child
   projections, bounds and provider errors, plus authenticated generated gRPC
   tests. Isolated providers prove wiring, not live business integration. Test
   decoders belong only in test configuration.

Correlation IDs accept `[A-Za-z0-9_-]{1,64}` or are regenerated; execution context
stores them, responses return `x-swiftdrop-correlation-id`, and scoped gRPC context
propagates them. Browser actor/tenant/service-token headers are rejected. Never
log tokens, credentials, contact details or proof keys.

## Integration prerequisites and review

PR #213 is a draft contract/test harness for #30. Statistics semantics and missing
aggregate RPCs are outside #119. Pricing has no approved GraphQL read RPC, so
there is no Pricing client for `ValidateQuote`. Platform owns machine identity,
TLS provisioning and Gateway `/graphql` routing/edge review. This task does not
modify or run Platform containers or claim deployed Gateway/provider integration.

Reviewers: Suthankan1 (security/Platform), Vibhushan-2003 (independent limits and
security QA), pushmitha20 (resolver integration), and affected domain owners.
Reviews are not approvals until recorded.

Tests live under `src/test/java/com/swiftdrop/graphql/`. Run `mvnw clean test`
and `mvnw clean verify`; use Surefire reports for exact results. The shared
adapter retains its negative JWT/delegation tests. From the repository root:
`python tests/contracts/validate_contracts.py --mode=graphql --check-compatibility --base-ref=origin/test`
with the repository Python contract dependencies installed.
