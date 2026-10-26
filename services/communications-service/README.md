# SwiftDrop Communications Service — Foundation & Durable Job Schema

**Milestone:** November 10 Baseline (Architecture Baseline v1.3)
**Issue:** [Refs #96 (N10-B16)](https://github.com/Suthankan1/swiftdrop/issues/96) — Create Communications app and independently owned durable job schema
**Primary Owner:** Thuvarahan (@thuvarahan-t) — Communications / Backend Lead
**Functional Reviewers:**
- `@Sinthuha-n` — Accounts / persistence & data ownership steward
- `@Suthankan1` — Platform / architecture & integration coordinator
**Runtime Baseline:** Java 21 LTS · Spring Boot 4.1.1 · Maven · Port 8084 · Logical Database `communications_db`

---

## 1. Executive Summary & Purpose

The **Communications Service** is an independently deployable Spring Boot microservice responsible for asynchronous delivery side-effects in SwiftDrop:
- Ingesting domain events via Kafka KRaft consumer groups (`parcel.lifecycle.v1`).
- Enforcing durable inbox deduplication per consumer group to prevent duplicate side-effects.
- Persisting and executing durable notification jobs (`EMAIL`, `SMS_MOCK`).
- Managing merchant webhook subscriptions and durable delivery jobs with HMAC-SHA256 signing and exponential backoff retry tracking.

Issue #96 delivers the **Spring Boot application foundation, PostgreSQL Flyway schema, repository interfaces, and atomic transaction boundary** required to support the upcoming Kafka consumer and notification processing flow in **Issue #32**.

---

## 2. Mandatory Data Ownership & Architectural Boundaries

Per SwiftDrop Architecture Baseline v1.3 and [Figure 16 Logical ERD](../../docs/reviews/NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.md):

### Communications OWNS:
- Relational schema and persistence in dedicated logical database `communications_db`.
- Durable consumed-event inbox receipts (`consumed_events`).
- Customer notification jobs (`notifications`).
- Merchant webhook endpoint subscriptions (`webhook_subscriptions`).
- Webhook delivery jobs (`webhook_deliveries`).
- Webhook execution attempt history (`webhook_attempts`).
- Retry scheduling metadata (`next_attempt_at`, `status`, `attempt_count`).

### Communications DOES NOT OWN (Strict Boundary Enforcement):
- ❌ Parcel lifecycle states, state transitions, courier assignments, or delivery status audit history (owned strictly by **Delivery Operations**).
- ❌ Merchant organizations, approvals, or business tenant identities (owned strictly by **Accounts & Partners**).
- ❌ User credentials, passwords, or Keycloak identity representations (owned strictly by **Platform / Keycloak**).
- ❌ Shipping rate calculation, weight tiers, or zone pricing (owned strictly by **Pricing & Zones**).
- ❌ Cross-service SQL joins or foreign keys: all external references (`merchant_id`, `parcel_id`, `event_id`, `recipient_sub`) are stored strictly as opaque scalar values (UUIDs or Strings).

---

## 3. Database Schema & Flyway Migration (`V1__create_communications_schema.sql`)

The Communications schema is version-controlled via Flyway under `src/main/resources/db/migration/`:

```text
+--------------------------------------------------------------------------------------------------+
|                                  COMMUNICATIONS_DB SCHEMA                                        |
|                                                                                                  |
|  [CONSUMED_EVENTS]                                                                               |
|  PK (consumer_name, event_id)                                                                    |
|  - event_type, version, parcel_id, merchant_id, received_at, processed_at, scheduled_at          |
|                                                                                                  |
|  [NOTIFICATIONS]                                                                                 |
|  PK notification_id (UUID)                                                                       |
|  UK (event_id, recipient_sub, channel)                                                           |
|  - recipient_sub, parcel_id, channel (EMAIL/SMS_MOCK), destination_ref, template,                |
|    status (PENDING/PROCESSING/SENT/FAILED/EXHAUSTED), attempts, next_attempt_at                  |
|                                                                                                  |
|  [WEBHOOK_SUBSCRIPTIONS]                                                                         |
|  PK subscription_id (UUID)                                                                       |
|  - merchant_id (EXT), https_url (CHECK ^https://), allowed_event_types, secret_ref, active       |
|       |                                                                                          |
|       | 1 : N (local FK)                                                                         |
|       v                                                                                          |
|  [WEBHOOK_DELIVERIES]                                                                            |
|  PK delivery_id (UUID)                                                                           |
|  UK (subscription_id, event_id)                                                                  |
|  - event_id, parcel_id, merchant_id, state_version, signed_payload_digest, status, attempt_count  |
|       |                                                                                          |
|       | 1 : N (local FK CASCADE)                                                                 |
|       v                                                                                          |
|  [WEBHOOK_ATTEMPTS]                                                                              |
|  PK attempt_id (UUID)                                                                            |
|  - delivery_id (FK), attempted_at, http_status_code, response_time_ms, error_summary             |
+--------------------------------------------------------------------------------------------------+
```

### Table Specifications & Invariants:
1. **`consumed_events`:**
   - Composite Primary Key: `(consumer_name, event_id)`.
   - Invariant: Different consumer groups (e.g. `communications.webhooks.v1` and `communications.notifications.v1`) process the same domain event independently without key collisions.
2. **`notifications`:**
   - Unique Constraint: `(event_id, recipient_sub, channel)`.
   - Check Constraints: `channel IN ('EMAIL', 'SMS_MOCK')`, `status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'EXHAUSTED')`.
   - Partial Index: `idx_notifications_pending` on `(next_attempt_at, status)` WHERE `status IN ('PENDING', 'PROCESSING')`.
3. **`webhook_subscriptions`:**
   - Check Constraint: `https_url ~* '^https://'` enforcing HTTPS-only callbacks (defense against SSRF and plaintext transmission).
   - `secret_ref` references securely stored signing material (never plaintext in database).
4. **`webhook_deliveries`:**
   - Unique Constraint: `(subscription_id, event_id)`. Guarantees exactly one delivery job per subscription for a given lifecycle event.
   - Local FK: `subscription_id REFERENCES webhook_subscriptions(subscription_id) ON DELETE RESTRICT`.
   - Partial Index: `idx_webhook_deliveries_pending` on `(next_attempt_at, status)`.
5. **`webhook_attempts`:**
   - Audit trail capturing `http_status_code`, `response_time_ms`, and `error_summary`.
   - Invariant: Third-party response bodies and authorization headers are **never stored** to prevent database pollution or token leakage.

---

## 4. Transaction Boundary Service (`DurableJobPersistenceService`)

The central transaction boundary for Communications is provided by `DurableJobPersistenceService` (`com.swiftdrop.communicationsservice.transaction`):

```java
@Service
public class DurableJobPersistenceService {

    @Transactional
    public InboxAndNotificationResult persistInboxAndNotification(ConsumedEvent event, NotificationJob job);

    @Transactional
    public InboxAndWebhookDeliveriesResult persistInboxAndWebhookDeliveries(ConsumedEvent event, List<WebhookDelivery> deliveries);

    @Transactional(readOnly = true)
    public boolean isEventConsumed(String consumerName, UUID eventId);

    @Transactional
    public void markEventProcessed(String consumerName, UUID eventId);
}
```

### Future Kafka Consumer Contract (Handoff to Issue #32):
When the Kafka consumer is implemented under **Issue #32**, it MUST follow this strict commit ordering:
1. Consumer receives event `parcel.status.changed.v1` from Kafka KRaft topic `parcel.lifecycle.v1`.
2. Consumer invokes `durableJobPersistenceService.persistInboxAndNotification(event, job)`.
3. Spring executes the method within a PostgreSQL `@Transactional` boundary:
   - Persists the inbox receipt in `consumed_events`.
   - Persists the durable job in `notifications`.
   - If the event was already consumed by this consumer group, throws `DuplicateEventException` without inserting duplicate jobs.
   - If an error occurs during job creation, the transaction rolls back atomically (neither inbox receipt nor job commits).
4. **Only upon successful database commit** does the Kafka consumer acknowledge and commit its partition offset.
5. If the pod crashes before offset commit, the re-delivered event is recognized as a duplicate by the inbox table and safely discarded without duplicate external side-effects.

---

## 5. Configuration & Startup Validation

The application requires environment-driven datasource configuration:

```properties
server.port=8084
spring.application.name=communications-service
spring.datasource.url=${COMMUNICATIONS_DB_URL}
spring.datasource.username=${COMMUNICATIONS_DB_USERNAME}
spring.datasource.password=${COMMUNICATIONS_DB_PASSWORD}
spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
```

### Fail-Closed Behavior (Section 11 Requirement):
- **Missing Configuration:** If `COMMUNICATIONS_DB_URL` is unset, the application fails startup immediately with a clear configuration exception rather than falling back to an in-memory database.
- **Unreachable Database:** If PostgreSQL is offline or credentials are invalid, HikariCP connection timeout triggers startup failure.
- **Actuator Health:** `GET http://localhost:8084/actuator/health` returns `200 UP` when the database connection is healthy, and `503 DOWN` if connectivity fails. Details and credentials are never exposed in health responses.

---

## 6. Local Development & Testing

### 6.1 Compiling and Running Unit Tests
From repository root:
```bash
# Build and run all unit, entity, migration, and negative configuration tests
mvn -f services/communications-service/pom.xml test
```
Or using the Maven Wrapper inside the service directory:
```bash
cd services/communications-service
./mvnw test
```

### 6.2 Running Real PostgreSQL Integration Tests (`*IT.java`)
Integration tests require a running Docker daemon to launch the Testcontainers PostgreSQL container:
```bash
# Runs surefire unit tests and failsafe PostgreSQL integration tests
mvn -f services/communications-service/pom.xml verify
```
`CommunicationsPersistencePostgresIT.java` executes on real PostgreSQL 16 Alpine:
- Clean Flyway V1 migration execution and table validation.
- Durable inbox event persistence and deduplication.
- Independent consumer groups processing the same event ID.
- Notification job uniqueness and check constraint validation.
- Webhook subscription, delivery job, and attempt history persistence.
- Atomic transaction rollback proving that neither inbox nor job is committed on failure.
- Restart durability proving records survive context reload against persistent PostgreSQL.

---

## 7. Status and Scope Boundaries

### IMPLEMENTED in Issue #96:
- [x] Spring Boot 4.1.1 application entry point and package architecture (`com.swiftdrop.communicationsservice`).
- [x] Dedicated Maven build with pinned Java 21 runtime and Flyway / PostgreSQL dependencies.
- [x] Flyway migration `V1__create_communications_schema.sql` defining all 5 Communications tables, constraints, and indexes.
- [x] JPA entities (`ConsumedEvent`, `NotificationJob`, `WebhookSubscription`, `WebhookDelivery`, `WebhookAttempt`).
- [x] Spring Data JPA repositories with query methods.
- [x] Atomic transaction boundary service (`DurableJobPersistenceService`).
- [x] Fail-closed datasource configuration and Actuator health check.
- [x] 19 automated unit and slice tests passing with zero failures.
- [x] Testcontainers integration test harness for real PostgreSQL verification.

### IMPLEMENTED in Issue #110 (N10-B06):
- [x] Type-safe gRPC client adapter `AccountsRecipientContactClient` (`GrpcAccountsRecipientContactClient`) implementing `IF-GRPC-03`.
- [x] Channel-scoped permitted contact projection `PermittedRecipientContact` (`recipientSub`, `email`, `phoneNumber`, `fullName`, `isEligible`, `emailPermitted`, `smsPermitted`, `status`, `rejectionReason`).
- [x] Purpose validation and channel scoping (`EMAIL`, `SMS`, `SMS_MOCK`, `ANY`).
- [x] Bounded timeout enforcement (default 2000ms deadline) with fallback exception hierarchy (`AccountsServiceUnavailableException`, `AccountsSecurityException`, `AccountsContactException`).
- [x] Strict data isolation: zero Communications SQL against Accounts or Keycloak databases; zero PII logged in Communications client.
- [x] 8 automated unit/integration tests with in-process gRPC test server (`AccountsRecipientContactClientTest`).

### IMPLEMENTED in Issue #111 (N10-B07):
- [x] Type-safe gRPC client adapter `DeliveryRecipientAuthorizationClient` (`GrpcDeliveryRecipientAuthorizationClient`) implementing `IF-GRPC-11`.
- [x] Delivery event authorization domain record `DeliveryEventAuthorization` (`isAuthorized`, `parcelId`, `eventId`, `recipientSub`, `merchantId`, `trackingNumber`, `currentStatus`, `eventType`, `eventVersion`, `currentVersion`, `isTerminal`, `isStale`, `authorizationReason`).
- [x] Strict caller authentication (`SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE`) and forbidden actor delegation (`DelegationRequirement.FORBIDDEN`).
- [x] Bounded timeout enforcement (default 2000ms deadline) and structured exception hierarchy (`DeliveryServiceUnavailableException` [retryable], `DeliverySecurityException`, `DeliveryAuthorizationException`).
- [x] Strict data isolation: zero Communications SQL against Delivery database; zero PII in responses or client logs.
- [x] 10 automated unit/integration tests with in-process gRPC test server (`DeliveryRecipientAuthorizationClientTest`).

### IMPLEMENTED in Issue #116 (N10-B17):
- [x] Authoritative REST API controller `WebhookSubscriptionController` implementing `IF-REST-11` and `IF-REST-12` matching `contracts/rest/openapi-communications.yaml` (v1.0.0):
  - `POST /api/v1/merchants/webhooks` — Registers HTTPS webhook destination, generates signing secret, returns 201 Created with `Cache-Control: no-store`.
  - `GET /api/v1/merchants/webhooks` — Lists caller's webhook endpoints, omitting signing secret material.
  - `GET /api/v1/merchants/webhooks/{subscriptionId}` — Retrieves subscription configuration with strict tenant ownership check.
  - `DELETE /api/v1/merchants/webhooks/{subscriptionId}` — Soft-deactivates subscription (`active = false`), preserving audit history and delivery foreign keys.
  - `GET /api/v1/merchants/webhooks/{subscriptionId}/deliveries` — Inspects delivery logs and execution attempts, omitting third-party bodies.
- [x] Type-safe gRPC client adapter `AccountsMerchantEligibilityClient` (`GrpcAccountsMerchantEligibilityClient`) verifying merchant lifecycle status against Accounts Service (`IF-GRPC-01`, `CheckAccountEligibility`). Enforces `APPROVED` state and fails closed for `PENDING`, `SUSPENDED`, or `REJECTED` accounts.
- [x] Server-side HTTPS and SSRF defense (`WebhookDestinationValidator`) enforcing `DEC-COM-01`:
  - Enforces TLS 1.2+ HTTPS transport.
  - Rejects credentials in URLs, localhost, `.local`/`.internal`/`.localhost` suffixes.
  - Rejects RFC 1918 private IPv4, loopback, link-local, cloud instance metadata (`169.254.169.254`), IPv6 loopback/link-local/unique-local, and IPv4-mapped IPv6 addresses.
  - Performs DNS resolution verification with fail-closed error handling.
- [x] Domain event filter validator (`WebhookEventFilterValidator`): restricts subscriptions strictly to approved event `parcel.status.changed.v1`.
- [x] Cryptographic HMAC signing secret management (`WebhookSigningSecretService`):
  - 256-bit cryptographically secure entropy (`swd_whsec_...`).
  - Authenticated encryption at rest using AES-256-GCM (`enc:v1:<iv>:<ciphertext>`), never storing plaintext in database or logs.
  - One-time presentation in creation response; never exposed via GET, list, or error responses.
- [x] Spring Security Resource Server customization (`CommunicationsSecurityConfig`):
  - Strict default-deny policy.
  - Requires `ROLE_MERCHANT` for all merchant webhook management routes.
- [x] RFC 7807 ProblemDetail exception handler (`WebhookProblemHandler`) with structured, non-leaking error responses.
- [x] Documented formal contract extension proposal for subscription mutation (`PATCH`) and secret rotation (`POST .../rotate-secret`) in `docs/reviews/MERCHANT_WEBHOOK_UPDATE_ROTATION_PROPOSAL.md`.
- [x] 118 automated tests passing across unit, MockMvc, Spring Security, gRPC, and PostgreSQL integration suites with 100% pass rate.
- [x] Verified full compatibility with merged Merchant UI (`#21`, `src/features/merchant/api/webhooks.test.ts`).

### EXPLICITLY NOT IMPLEMENTED (Belongs to Future Issues):
- ❌ Webhook outbound HTTP worker, HMAC dispatch execution, and attempt recording (belongs to **Issue #142**).
- ❌ Durable webhook retry scheduler and exhaustion handling (belongs to **Issue #151**).
- ❌ External email or SMS transmission engines.
- ❌ Shared API Gateway or Keycloak configuration changes.
