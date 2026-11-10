# SwiftDrop System Audit — Backend Microservices & Distributed Architecture

**Scope:** 6 independent Spring Boot microservices, 2 shared modules, 1,743 passing automated tests.  
**Architecture Baseline:** v1.3 strict independent microservices (zero shared business DB, zero shared entity JARs).

---

## 1. Microservices Inventory & Test Execution Summary

| Service / Module | Port | Test Count | Passing | Execution Time | Build Tool | Database Engine |
|---|---|---|---|---|---|---|
| `shared/proto-contracts` | N/A | 28 | 28 (100%) | 1.8s | Maven | None (Protobuf / gRPC) |
| `shared/security-adapter`| N/A | 51 | 51 (100%) | 2.4s | Maven | None (Spring Security) |
| `services/api-gateway` | 8080 | 87 | 87 (100%) | 4.6s | Maven | None (Reactive WebFlux) |
| `services/accounts-service`| 8081 | 311 | 311 (100%) | 7.8s | Maven | PostgreSQL (`accounts_db`) |
| `services/pricing-service` | 8082 | 167 | 167 (100%) | 3.9s | Maven | PostgreSQL (`pricing_db`) |
| `services/delivery-service`| 8083 | 484 | 484 (100%) | 12.1s | Maven | PostgreSQL (`delivery_db`) |
| `services/communications-service` | 8084 | 364 | 364 (100%) | 9.3s | Maven | PostgreSQL (`communications_db`) |
| `services/graphql-query-service` | 8085 | 251 | 251 (100%) | 6.5s | Maven | None (gRPC Federation) |
| **Total** | | **1,743** | **1,743** | **48.4s** | | |

---

## 2. Service-by-Service Technical Deep Dive

### 2.1 API Gateway (`services/api-gateway`)
- **Framework:** Spring Cloud Gateway (Reactive Spring WebFlux on Netty).
- **Core Responsibilities:** Central edge ingress, Keycloak JWT signature and expiry verification via reactive filter, rate limiting, request tracing correlation (`X-Correlation-ID`), CORS header management, and path routing.
- **Audited Defect 1 (`BE-001`):** Missing Route for `/api/v1/staff/**`. The programmatic `RouteLocator` in `GatewayRoutesConfig.java` omits staff routes, resulting in HTTP 404 responses for `/api/v1/staff/me`.
- **Audited Defect 2 (`BE-002`):** GraphQL fallback URI in Java code specifies `http://localhost:8084` (Communications Service port) instead of `http://localhost:8085`.
- **Audited Defect 3 (`OPS-001`):** `application.yml` maps `/api/v1/admin/**` to `accounts-service` without declaring `/api/v1/admin/pricing/**` first, creating a potential precedence conflict with `pricing-service`.
- **Edge Security & Rate Limiting:** Enforces Redis/in-memory Token Bucket rate limiter per IP address for public tracking endpoints (10 req/min) to prevent brute-force tracking number enumeration.

---

### 2.2 Accounts & Partners Service (`services/accounts-service`)
- **Port:** 8081 | **Flyway Migrations:** 4 clean migration scripts (`V1__init_accounts.sql` through `V4__merchant_api_keys.sql`).
- **Core Responsibilities:** User profile synchronization from Keycloak webhooks, staff profile lookup, merchant enrollment and KYC status, M2M API key generation and SHA-256 hash storage.
- **Database Boundaries:** Exclusively owns table `users`, `merchants`, `staff_members`, and `merchant_api_keys`. Zero foreign keys to delivery or pricing tables.
- **Security & Authorization:** Implements Method Security (`@PreAuthorize("hasRole('ADMIN')")` for merchant review actions). Hashes API keys with salt before persistence; raw API keys are never stored.
- **Tenant Context:** Provides merchant validation endpoint used by internal services to authenticate `X-Merchant-Api-Key` headers.

---

### 2.3 Pricing & Zones Service (`services/pricing-service`)
- **Port:** 8082 | **Flyway Migrations:** 3 migration scripts.
- **Core Responsibilities:** Zone definitions, postal code boundary mapping, volumetric weight calculations, surcharge computation (fuel surcharge, emergency rush fees), and instant rate quoting.
- **Dual Protocols:** Exposes REST endpoint `POST /api/v1/pricing/quote` for external frontend calculation and gRPC service `PricingQuoteServiceGrpc` (port 9082) for internal synchronous quotes requested by `delivery-service` and `graphql-query-service`.
- **Precision:** Uses `BigDecimal` with 4 decimal places of internal precision and `RoundingMode.HALF_UP` for all financial calculations, preventing floating-point rounding errors.

---

### 2.4 Delivery Operations Service (`services/delivery-service`)
- **Port:** 8083 | **Flyway Migrations:** 10 migration scripts.
- **Core Responsibilities:** Heart of the delivery domain. Parcel state machine, courier dispatch assignments, proof-of-delivery ingest, delivery failure recording, transactional outbox for event publishing.
- **Parcel State Machine:**
  - Strict valid transitions:
    `CREATED` -> `ASSIGNED` -> `PICKED_UP` -> `IN_TRANSIT` -> `DELIVERED` or `FAILED`
  - Reassignment: Allows transitioning from `ASSIGNED` to `ASSIGNED` (with different courier) or `FAILED` back to `ASSIGNED`.
- **Concurrency & Optimistic Locking:**
  - The `Parcel` JPA entity includes an `@Version Long version` field. Concurrent courier assignment attempts throw `OptimisticLockingFailureException`, preventing double assignment.
- **Transactional Outbox Pattern:**
  - Domain events (`parcel.created`, `parcel.assigned`, `parcel.status_changed`, `parcel.delivered`) are written in the same database transaction as the parcel state change to the `outbox_events` table.
  - A scheduled poller (`@Scheduled(fixedDelay = 500)`) reads unpublished outbox records and publishes them to Kafka with at-least-once delivery guarantees.
- **Audited Defect (`INT-005`):** In `MerchantParcelBookingController.java`, the controller demands a `merchant_id` claim in the Keycloak JWT. Because Keycloak does not provide this claim for user sessions, authenticated merchants in the browser are denied access (HTTP 403).
- **Database Performance Finding (`DB-001`):** Column `parcels.merchant_id` lacks a database index, which will cause sequential scans during merchant order queries.

---

### 2.5 Communications Service (`services/communications-service`)
- **Port:** 8084 | **Flyway Migrations:** 5 migration scripts.
- **Core Responsibilities:** Asynchronous Kafka event consumer, customer notification dispatch (email/SMS mock adapters), and merchant webhook delivery engine.
- **Webhook Delivery Engine:**
  - Dispatches HMAC-SHA256 signed HTTP POST requests to merchant endpoints.
  - Exponential backoff retry policy (1m, 5m, 15m, 1h, 6h) with dead-letter queue (DLQ) storage in `failed_webhook_deliveries` after 5 consecutive failures.
- **Audited Security Finding (`SEC-001`):** `WebhookDestinationValidator.java` does not validate against IPv6 loopback (`::1`) or cloud link-local metadata IP (`169.254.169.254`), posing a potential SSRF risk in cloud environments.

---

### 2.6 Read-Only GraphQL Query Service (`services/graphql-query-service`)
- **Port:** 8085 | **Database:** Zero databases (strictly read-only federation service).
- **Core Responsibilities:** High-performance composite query resolver powering web dashboards (Dispatcher, Admin, Customer).
- **Federation Mechanism:** Translates incoming GraphQL queries into parallel internal gRPC calls to `delivery-service`, `pricing-service`, and `accounts-service`. Uses `CompletableFuture` for non-blocking concurrent aggregation.
- **Adherence to Rules:** Contains zero GraphQL mutations; all state changes must go through domain REST endpoints.
- **Audited Issue (`INT-006`):** The `deliveryStats` root field throws `Status.UNAVAILABLE` because its gRPC backend was deferred under ADR `LATER_11`.

---

## 3. Distributed Patterns & Reliability Analysis

### 3.1 Idempotency & Duplicate Handling
- **Kafka Consumers:** In `communications-service`, Kafka consumers check an `idempotency_keys` table before processing events. If `event_id` was already processed, the message is acknowledged and skipped, ensuring at-least-once Kafka delivery produces exactly-once side effects.
- **Parcel Booking:** The `POST /api/v1/parcels` endpoint accepts an optional `Idempotency-Key` header stored with a unique constraint in `delivery-service`.

### 3.2 Error Responses & ProblemDetail (RFC 7807)
All 6 services implement centralized `@RestControllerAdvice` emitting standardized RFC 7807 `application/problem+json` structures:
```json
{
  "type": "https://api.swiftdrop.com/errors/invalid-parcel-state",
  "title": "Invalid Parcel State Transition",
  "status": 409,
  "detail": "Parcel SWIFT-1002 cannot transition from DELIVERED to IN_TRANSIT",
  "instance": "/api/v1/parcels/SWIFT-1002/status",
  "timestamp": "2026-11-05T10:19:00Z"
}
```
