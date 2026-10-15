# Communication and protocol matrix

| Caller → target | Protocol | Example purpose | Security/operational boundary |
|---|---|---|---|
| Browser → Keycloak | OIDC Code + PKCE, brokered Google | Local/Google login; obtain Keycloak access JWT | Public SPA client, no client secret, strict redirect/state/nonce |
| React → Gateway → core service | **REST** | Create booking, request quote, status update, assign courier, get receipt/public redacted tracking | JWT + owner checks at core service; rate limit public endpoints |
| Merchant system → Gateway → core service | **REST** | Create delivery order, manage webhook subscription | Merchant API-key identity/scopes or later OAuth client credentials; approved tenant bound |
| React → Gateway → GraphQL Query API | **GraphQL over HTTP** | Dashboard aggregates/status metrics | Read-only; per-field/tenant auth, depth/complexity limits, batching; no direct DB |
| GraphQL Query API → Accounts/Pricing/Delivery | **gRPC** | Aggregate authorized read models | Machine auth + scoped actor/tenant context, deadlines |
| Delivery → Accounts & Partners | **gRPC** | Courier eligibility, merchant/account approval, principal binding | Request is not an authorization bypass; fail closed |
| Delivery → Pricing & Zones | **gRPC** | Validate quote or fee-policy version | Bounded deadline; accepted quote snapshot |
| Delivery outbox → Kafka | **Kafka producer** | `parcel.status.changed.v1` | Key by parcel ID, broker ack; at-least-once publishes |
| Kafka → Communications | **Kafka consumer groups** | Durable email/mock-SMS notifications, webhook jobs | Separate group/inbox, retry, idempotency, DLT |
| Kafka → Delivery tracking relay | **Kafka** | Forward status changes to stream-serving replicas | Unique groups for multi-replica fanout; version filter |
| Delivery → browser via Gateway | **SSE** | One-way authorized live parcel status | Fetch-based client can send Authorization header; canonical REST refresh on reconnect |
| Communications → external merchant | **HTTPS webhook** | Signed status callback | HMAC, timeout/retry, event ID, version checks; no credentials in URLs |

**Rules:** Gateway is not the GraphQL resolver. GraphQL never calls business databases. Do not use synchronous gRPC for notifications/long-lived retries. Don't assume Kafka exactly-once external emails; external effects may duplicate. Do not expose direct gRPC to browsers in MVP. A simple `GET /tracking/{number}` can remain REST even when dashboards use GraphQL.
