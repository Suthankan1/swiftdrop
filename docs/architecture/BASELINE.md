# Architecture baseline v1.3 — Devarom

**Status:** team-proposed design. Do not claim deployed/implemented until code and tests exist. Supersedes v1.2 REST/RabbitMQ references and the former name SwiftDrop.

## Logical layout

```text
Customer / Merchant / Courier / Dispatcher / Admin
                  | React + TypeScript + Vite SPA
                  | REST / GraphQL / auth-header SSE
                  v
           Spring Cloud API Gateway <---- REST ---- External merchant client
                 |      |                (scoped API key, tenant-bound)
     REST commands|      +--- /graphql --> Read-only GraphQL Query API
 / simple GET     |                            |
                 |                      gRPC aggregated reads
                 v                            v
       +---------------- Four business Spring Boot services --------------+
       | Accounts & Partners | Pricing & Zones | Delivery Operations     |
       |                          ^                 |                    |
       |                       gRPC quote   gRPC courier/merchant checks |
       | Communications   <------- Kafka parcel.lifecycle.v1 <--- outbox |
       +------------------------------------------------------------------+
           | notifications / signed webhooks            | SSE tracking
         (own async jobs)                            (REST recovery)

Keycloak <--- Google OIDC broker ; React uses Authorization Code + PKCE
Each business service => OWN PostgreSQL logical database; Delivery => private object storage
```

For accurate arrows, ERD, OAuth sequence and component boundaries, use [figure 06](../diagrams/rendered/06_logical_architecture.svg) and the [diagram index](../diagrams/README.md). Diagram 19/20 show **target** deployment only.

## Components and ownership

| Component | Owns | Does not own |
|---|---|---|
| SPA | Forms, role navigation, public tracking and dashboard display | Enforcement of API access; browser secrets |
| Gateway | Routing, CORS/edge policy, JWT gate/rate-limit policy | Business rules, user database, GraphQL field resolution |
| Accounts & Partners | Profiles keyed to Keycloak subject, merchant status, courier eligibility, scoped merchant credential metadata | Passwords, parcel SQL |
| Pricing & Zones | Zone rules, pricing logic, expiring/snapshotted quotes | Parcel data |
| Delivery Operations | Booking, assignments, lifecycle/history, proof metadata, receipt data, outbox, authorized tracking, SSE | Communications retry jobs, identity passwords |
| Communications | Kafka consumers, notifications, merchant webhook subscriptions/delivery attempts, inbox/jobs | Parcel business transitions, contact data copied into Kafka events |
| GraphQL Query API | Composite **read-only** views from authorized gRPC calls | Mutations, direct cross-service database queries |
| Keycloak | Identity, OIDC/OAuth, Google federation and token issuance | Business parcel access control in isolation |
| Kafka | Retained partitioned async facts | Canonical business state/audit database |

**Services communicate synchronously over selected gRPC requests**, never by reading one another's schema. The gateway makes REST and GraphQL available externally; GraphQL queries are resolved in their own app. **Kafka** is not a generic replacement for gRPC or REST; it delivers async event facts. A browser receives live status via **SSE** and reconciles by GET after reconnect.

## Reliability decisions

- Single transaction in Delivery persists parcel/version/history/outbox; publish asynchronously using Kafka producer confirms/acknowledgements. At-least-once possible; dedupe by event ID.
- Kafka topic proposed `parcel.lifecycle.v1`, event `parcel.status.changed.v1`, key `parcelId`; ordering per parcel partition only. Independent notification, webhook and tracking consumer groups; no sensitive PII in events.
- Communications logs durable inbox receipt **and job** transactionally before committing Kafka offset; retries/exponential backoff + inspection/DLT for failures. Signed HTTPS webhooks use stable event id and aggregate version to guard against replay/out-of-order updates.
- Initial SSE single-instance target may be non-HA; multi-replica broadcast requires **one group per SSE-serving replica** or another proven distribution mechanism, and always canonical REST refresh on reconnect.
- Remote gRPC calls have deadlines, service identity, verified caller/actor delegation, appropriate error translation and bounded retries only for safe idempotent calls.

## Security decisions

Keycloak public OIDC client: code + PKCE, registered redirect origins, state/nonce, Google broker. Short-lived access JWT in JS memory; no secrets in `VITE_*` config or localStorage. Route guards are UX only; server validates issuer, signature, audience, expiry, roles **and resource/tenant ownership**. No self-granted elevated roles or approved merchant status. API keys, if chosen, are 256-bit random, hashed at rest, scoped, revocable and never logged.

## Principles

Microservices and DB ownership; SOLID/DRY at domain boundary; ports/adapters/repository for persistence; Strategy for pricing; observer/pub-sub via Kafka; idempotency/outbox for resilience; security by default. No unnecessary Kafka-first logic for initial logins. Bonus Kubernetes/AI are **later** project stages.

## Non-goals at initial milestone

Full parcel lifecycle, live integrations, GraphQL dashboards, proof storage, Kubernetes, AI assistant, GPS routing, server-rendered Next.js, server-issued SPA cookie/BFF, Redis, Kafka MSK, gRPC-Web and GraphQL mutations. These need separate approved features or are not in scope.
