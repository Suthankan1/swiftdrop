# SwiftDrop Communications Service — Foundation & Durable Job Schema

**Milestone:** November 10 Baseline (Architecture Baseline v1.3)
**Umbrella Issue:** [Refs #33 (LATER-12)](https://github.com/Suthankan1/swiftdrop/issues/33) — Own Webhook Delivery/Retry Engine Through Decomposed Communications Slices
**Foundation Issue:** [Refs #96 (N10-B16)](https://github.com/Suthankan1/swiftdrop/issues/96) — Create Communications app and independently owned durable job schema
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

### IMPLEMENTED in Issue #32 (LATER-14):
- [x] Kafka consumer `ParcelLifecycleKafkaConsumer` listening to topic `parcel.lifecycle.v1` under consumer group `communications.notifications.v1`.
- [x] Strict manual offset acknowledgement (`AckMode.MANUAL_IMMEDIATE`) executed **only after** successful PostgreSQL transaction commit.
- [x] Domain event validator and parser `ParcelLifecycleEventParser` enforcing `contracts/events/parcel.status.changed.v1.schema.json`:
  - Envelope validation: `eventId` (UUID), `eventType` (`parcel.status.changed.v1`), `schemaVersion` (`^1\.[0-9]+\.[0-9]+$`), `aggregateId` equals `parcelId`, `aggregateType` (`PARCEL`), `aggregateVersion` (>= 1), `producer` (`delivery-operations`).
  - Kafka partition key alignment: record key matches parcel UUID.
  - Payload validation: `trackingNumber` pattern (`^SWD-[0-9]{8}-[A-Z0-9]{4,8}$`), valid lifecycle statuses, mandatory failure reason code when status is `FAILED`, null reason for non-failed statuses.
- [x] Delivery provenance and recipient authorization integration (`DeliveryRecipientAuthorizationClient` / `IF-GRPC-11`):
  - Authorizes event against parcel aggregate, verifying non-stale provenance and resolving authoritative `recipientSub`.
  - Fails closed on unauthorized events, stale events, or Delivery service timeouts (`DeliveryAuthorizationException`).
- [x] Accounts permitted contact resolution integration (`AccountsRecipientContactClient` / `IF-GRPC-03`):
  - Queries permitted contact handles for channel `EMAIL` and purpose `PARCEL_STATUS_UPDATE`.
  - Fails closed if recipient is not found (`RecipientNotFoundException`) or ineligible for email notifications (`NoEligibleNotificationTargetException`).
- [x] Privacy-preserving durable job scheduling:
  - Stores opaque destination reference `destination_ref = "sub:" + recipientSub` in `notifications` table.
  - Zero raw email addresses or phone numbers stored in Communications PostgreSQL or Kafka events.
  - Schedules job in `PENDING` state with `template = "PARCEL_" + newStatus` and `attempts = 0`.
- [x] Atomic transactional boundary (`DurableJobPersistenceService.persistInboxAndNotification`):
  - Commits both `consumed_events` inbox receipt and `notifications` job in a single PostgreSQL transaction.
  - Fixed exception mapping: distinguishes composite PK uniqueness collision on `consumed_events` (`DuplicateEventException`) from `NotificationJob` database constraint failures (`DataIntegrityViolationException`).
  - If a job constraint fails, transaction rolls back atomically without misclassifying as duplicate.
- [x] Durable deduplication:
  - If re-delivered or concurrent duplicate event is detected, state is verified and offset is safely acknowledged without duplicate job insertion.
- [x] Bounded notification adapter boundary (`NotificationDispatchAdapter`, `LoggingNotificationDispatchAdapter`):
  - Logs notification queueing safely without PII; retains job in `PENDING` status for future worker execution.
- [x] Automated unit and integration tests:
  - Unit tests for parser contract validation (`ParcelLifecycleEventParserTest`).
  - Unit tests for scheduling, authorization, and contact boundaries (`NotificationSchedulingServiceTest`).
  - Unit tests for manual Kafka offset commit ordering (`ParcelLifecycleKafkaConsumerTest`).
  - Unit tests for database exception mapping regression (`DurableJobPersistenceServiceTest`).
  - Real Kafka and PostgreSQL integration tests (`CommunicationsKafkaConsumerIT`) with `@EmbeddedKafka` and `@Testcontainers(disabledWithoutDocker = true)`.

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

### IMPLEMENTED in Issue #131 (N10-B18):
- [x] Dedicated Kafka consumer `WebhookLifecycleKafkaConsumer` listening to topic `parcel.lifecycle.v1` under independent consumer group `communications.webhooks.v1`.
- [x] Independent consumer infrastructure: dedicated `webhookConsumerFactory` and `webhookKafkaListenerContainerFactory` configured with `read_committed` isolation, manual offset commit (`AckMode.MANUAL_IMMEDIATE`), and bounded error handling (`DefaultErrorHandler`).
- [x] Strict manual offset acknowledgement: Kafka partition offsets are committed **strictly after** the PostgreSQL inbox receipt and durable webhook delivery jobs have committed.
- [x] Webhook delivery job materialization service `WebhookJobMaterializationService`:
  - Envelope parsing & validation via `ParcelLifecycleEventParser` enforcing `contracts/events/parcel.status.changed.v1.schema.json`.
  - Direct retail parcel bypass: parcels with `merchantId == null` record inbox receipt in `consumed_events` and advance offset with zero webhook jobs.
  - Merchant tenant subscription selection: queries `webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)`.
  - Event filter validation: checks `allowedEventTypes` via `WebhookEventFilterValidator` against `parcel.status.changed.v1`.
  - Stale-version & reordering guard: checks `findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc`. Events with `state_version <= current_version` are recognized as stale and skipped from job creation while committing inbox receipt to prevent redelivery loops.
  - Cryptographic payload integrity: computes deterministic SHA-256 digest (`signed_payload_digest`) stored with delivery job.
  - Materializes durable delivery jobs in `webhook_deliveries` with initial state `PENDING`, `attempt_count = 0`, `next_attempt_at = now`.
- [x] Dual-layer durable idempotency:
  - Table 1 `consumed_events`: PK `(consumer_name, event_id)` with `consumer_name = 'communications.webhooks.v1'`.
  - Table 4 `webhook_deliveries`: `uq_webhook_deliveries_subscription_event UNIQUE (subscription_id, event_id)`.
  - Atomic persistence via `DurableJobPersistenceService.persistInboxAndWebhookDeliveries`.
  - Graceful concurrent duplicate detection via `DuplicateEventException`.
- [x] Zero customer PII stored in `consumed_events` or `webhook_deliveries`.
- [x] Automated test coverage:
  - 11 unit tests in `WebhookJobMaterializationServiceTest` covering single/multiple subscriptions, retail parcels, disabled subscriptions, stale versions, duplicate events, concurrent collisions, and zero PII.
  - 5 unit tests in `WebhookLifecycleKafkaConsumerTest` verifying commit ordering, parser failure backoff, and exception handling.
  - 7 integration tests in `WebhookKafkaConsumerIT` with `@EmbeddedKafka` and `@Testcontainers(disabledWithoutDocker = true)`.

### Webhook Worker Handoff Boundary:
- Merchant webhook processing is completely decoupled from customer notification processing.
- The webhook consumer group `communications.webhooks.v1` consumes `parcel.lifecycle.v1` independently of `communications.notifications.v1`.
- The composite PK `(consumer_name, event_id)` in `consumed_events` ensures both consumer groups maintain independent inbox receipts for the same domain event without key collisions.
- Materialized jobs are left in `status = PENDING`, `attempt_count = 0`, `next_attempt_at = now` for discovery by the downstream delivery engine (`idx_webhook_deliveries_pending`).
- Outbound HTTP dispatch, HMAC signing headers, and backoff retries are owned by downstream Issue #33 / #142 / #151.

### IMPLEMENTED in Issue #142 (N10-B20):
- [x] Cryptographic HMAC-SHA256 Webhook Request Signing (`WebhookSigner`, `DefaultWebhookSigner`):
  - Signature header `X-SwiftDrop-Signature: t={timestamp},v1={hex_digest}` matching RFC standard practices.
  - Signed payload string: `timestamp + "." + payload_json` using UTF-8 encoding.
  - Secret management: decrypts stored AES-256-GCM secret (`enc:v1:...`) via `WebhookSigningSecretService.decryptSecret`.
  - Constant-time signature verification (`MessageDigest.isEqual`) with configurable replay tolerance window (default 300 seconds).
  - Strict input validation rejecting null/blank payloads, secrets, or malformed header formats.
- [x] Safe Outbound HTTPS Client (`WebhookHttpClient`, `DefaultWebhookHttpClient`):
  - Strict HTTPS enforcement with valid TLS certificates and hostname validation.
  - DNS resolution and SSRF defense: delegates destination validation to `WebhookDestinationValidator` before initiating connection.
  - Redirects strictly prohibited (`Redirect.NEVER`) to prevent SSRF filtering bypass and protocol downgrade to HTTP.
  - Configurable timeouts (`connectTimeoutMs = 3000`, `requestTimeoutMs = 5000`) via `WebhookDispatchProperties`.
  - Standard headers: `Content-Type: application/json; charset=utf-8`, `X-SwiftDrop-Signature`, `User-Agent: SwiftDrop-Webhook/1.0`, and `x-swiftdrop-correlation-id`.
  - Wall-clock response latency tracking in milliseconds.
- [x] Webhook Response & Exception Classification (`WebhookResponseClassifier`, `WebhookOutcomeCategory`, `WebhookResponseClassification`):
  - 2xx (`SUCCESS`): Successful delivery, terminal `status = SUCCESS`, non-retryable.
  - 3xx (`REDIRECT`): Security-relevant redirection rejected, classified as non-retryable `FAILED`.
  - 4xx (`CLIENT_ERROR`, except 408/429): Merchant endpoint client error (400, 401, 403, 404, 410, 422), classified as non-retryable `FAILED` to prevent futile retries.
  - 429 (`RATE_LIMITED`): Retryable with honor for `Retry-After` header (numeric seconds or RFC 1123 HTTP date).
  - 408 & Timeouts (`TIMEOUT`): `HttpConnectTimeoutException`, `HttpTimeoutException`, or socket timeouts classified as retryable `TIMEOUT` with null `httpStatusCode`.
  - 5xx (`SERVER_ERROR`): Server errors (500, 502, 503, 504) classified as retryable with exponential backoff.
  - Security failures (`SECURITY_FAILURE`): `SSLException`, `InvalidWebhookDestinationException` classified as non-retryable `FAILED`.
  - Error sanitization: strips control characters, masks secrets and bearer tokens, truncates diagnostic message to 500 characters.
- [x] Durable Delivery Execution & Attempt Persistence (`WebhookDeliveryExecutionService`):
  - Architectural separation: outbound HTTP network calls are executed **strictly outside** database transactions to avoid holding connection pool resources.
  - Atomic persistence boundary: attempt recording in `webhook_attempts` and job status update in `webhook_deliveries` commit together in a single PostgreSQL transaction.
  - Multi-attempt audit preservation: subsequent attempts append new rows to `webhook_attempts` without overwriting prior attempt history.
  - Retry scheduling: calculates exponential backoff (`baseBackoffSeconds * 2^(attempt-1)`) capped at `maxBackoffSeconds` (300s); sets `next_attempt_at` when retryable.
  - Attempt exhaustion: when `attemptCount >= maxAttempts` (5), transitions delivery status to terminal `EXHAUSTED`.
  - Concurrency & crash recovery: optimistic state transition to `PROCESSING` prevents concurrent worker duplicate executions; `recoverStuckProcessingJobs` recovers stalled attempts after lease expiration.
- [x] Automated Test Coverage:
  - 10 unit tests in `WebhookSignerTest` (HMAC computation, header formatting, tamper detection, replay window, timing resistance).
  - 14 unit tests in `WebhookResponseClassifierTest` (2xx, 3xx, 4xx, 429 Retry-After parsing, 408/5xx, timeouts, SSLException, sanitization).
  - 3 unit tests in `DefaultWebhookHttpClientTest` (destination validation, required headers, timeout propagation).
  - 13 unit tests in `WebhookDeliveryExecutionServiceTest` (success, backoff retry, rate limiting, terminal failures, exhaustion, multi-attempt audit, crash recovery).
  - PostgreSQL schema and attempt persistence integration tests in `CommunicationsPersistencePostgresIT`.

### IMPLEMENTED in Issue #151 (N10-B21):
- [x] Durable Webhook Polling Scheduler (`WebhookDeliveryScheduler`):
  - Scheduled background bean configured with `@Scheduled(fixedDelayString = "${swiftdrop.webhooks.scheduler.poll-interval-ms:5000}")` discovering due deliveries (`status == PENDING` and `next_attempt_at <= now`).
  - Non-blocking batch processing: queries due jobs with pagination (`Pageable.ofSize(batchSize)`), ordered by `nextAttemptAt ASC` to ensure oldest eligible deliveries execute first.
  - Safe error containment: individual job failures do not abort scheduler batch loop or stop future scheduled executions.
- [x] Atomic Leasing & Multi-Worker Concurrency Defense:
  - Atomic database lease method `claimDeliveryWithLease` (`WebhookDeliveryRepository`) using atomic JPQL `UPDATE WebhookDelivery d SET d.status = 'PROCESSING', d.updatedAt = :now WHERE d.deliveryId = :id AND (d.status = 'PENDING' OR (d.status = 'PROCESSING' AND d.updatedAt <= :leaseCutoff))`.
  - Operates under PostgreSQL row locking: when two or more concurrent workers poll the same due delivery, exactly one worker updates the row (update count = 1), while other workers receive 0 and safely skip execution without duplicate HTTP requests.
- [x] Exponential Backoff Engine with Jitter & Ceilings:
  - Configurable backoff parameters via `WebhookDispatchProperties` (`initialBackoffSeconds = 5`, `backoffMultiplier = 2.0`, `maxBackoffSeconds = 300`).
  - Standard delay sequence: attempt 1 = 5s, attempt 2 = 10s, attempt 3 = 20s, attempt 4 = 40s, attempt 5 = 80s, capped at 300s ceiling.
  - Optional full jitter support (`jitterEnabled = false`, `jitterFactor = 0.2` for +/- 20%) calculating backoff in range $[(1 - f) \times d, \min(d_{\max}, (1 + f) \times d)]$.
  - Rate-limit override: HTTP 429 `Retry-After` header values (integer seconds or RFC 1123 HTTP dates) strictly override exponential backoff calculations.
- [x] Retry Exhaustion & Terminal State Protection:
  - When `attemptCount >= maxAttempts` (default 5), transitions delivery status to terminal `EXHAUSTED`.
  - Non-retryable outcomes (client errors 4xx except 408/429, redirects 3xx, SSRF/TLS security rejections, and disabled subscriptions) transition immediately to terminal `FAILED`.
  - Terminal statuses (`SUCCESS`, `FAILED`, `EXHAUSTED`) are guarded against re-execution (`isTerminal()` check in `executeDelivery`).
  - Partial index `idx_webhook_deliveries_pending` (`WHERE status IN ('PENDING', 'PROCESSING')`) automatically excludes terminal deliveries from scheduler query plans.
- [x] Crash Recovery & Stalled Delivery Reclamation:
  - Scheduled recovery job `@Scheduled(fixedDelayString = "${swiftdrop.webhooks.scheduler.recovery-interval-ms:60000}")` (`recoverStalledDeliveries`) reclaims deliveries orphaned in `PROCESSING` status beyond `leaseTimeoutSeconds` (default 300s).
  - Resets stalled jobs back to `PENDING` with `next_attempt_at = now`, preserving at-least-once delivery semantics across worker crashes and network partitions.
- [x] Process Restart Durability:
  - All scheduling state (`next_attempt_at`, `status`, `attempt_count`, `updated_at`) is persisted in PostgreSQL; scheduled retries survive pod restarts, deployments, and scheduler reinitialization.
- [x] Configuration & Testability:
  - Dedicated configuration properties `WebhookSchedulerProperties` (`swiftdrop.webhooks.scheduler.*`): `enabled`, `pollIntervalMs`, `recoveryIntervalMs`, `leaseTimeoutSeconds`, `batchSize`.
  - Conditional scheduler activation via `WebhookSchedulerConfiguration` (`@ConditionalOnProperty(name = "swiftdrop.webhooks.scheduler.enabled", matchIfMissing = true)`).
  - Injectable `Clock` bean in `WebhookDispatchConfiguration` (`@ConditionalOnMissingBean(Clock.class)`) allowing deterministic time simulation in tests.
- [x] Automated Test Coverage:
  - 14 comprehensive unit tests in `WebhookDeliverySchedulerTest` covering due polling, two-worker lease race conditions, disabled scheduler, future job exclusion, error handling, stalled job recovery, deterministic backoff sequences, jitter bounds, disabled subscriptions, terminal state guards, retry budget exhaustion, and restart recovery.
  - 2 unit tests in `WebhookSchedulerPropertiesTest`.
  - Real PostgreSQL concurrency integration test in `CommunicationsPersistencePostgresIT.testConcurrentWorkerLeaseAndExhaustionLifecycle`.
  - Total communications-service test count increased to 254 passing tests.

### IMPLEMENTED in Issue #155 (N10-B22):
- [x] Webhook Egress SSRF Hardening & DNS Pinning:
  - Exact send-time destination validation enforcing HTTPS-only, no embedded credentials, valid hostname, and valid port.
  - Comprehensive rejection of local/loopback (127.0.0.0/8, 0.0.0.0, ::1), RFC 1918 private IPv4, link-local (169.254.0.0/16, fe80::/10), multicast, any-local, and internal suffixes (.local, .internal, .localhost).
  - Cloud metadata rejection: AWS IMDSv4 (169.254.169.254), AWS IMDSv2 IPv6 (fd00:ec2::254), AWS task metadata (169.254.170.2), and Alibaba metadata (100.100.100.200).
  - IPv4-mapped IPv6 bypass defense: unpacks mapped IPv4 addresses and executes full security checks.
  - DNS Rebinding / TOCTOU defense: `resolveAndCheckAddresses` checks all resolved A/AAAA records (mixed safe/unsafe answers fail closed).
  - Outbound socket pinning: `PinnedSSLSocketFactory` connects outbound TCP socket strictly to the pre-validated `pinnedAddress` while maintaining TLS SNI and standard HTTPS certificate hostname verification.
  - Redirect protection: redirects strictly disabled (`followRedirects = NEVER` / `setInstanceFollowRedirects(false)`).
  - Memory & timeout bounds: bounded connect/request timeouts, bounded 64KB response body read (`maxResponseBytes`), and zero logging of secrets, raw PII, or auth headers.
- [x] HMAC Secret Rotation:
  - Added REST endpoint `POST /api/v1/merchants/webhooks/{subscriptionId}/rotate-secret` with `Cache-Control: no-store`.
  - Generates new secret, encrypts with AES-256-GCM, and returns plaintext secret once.
  - Preserves event identity, payload digest, and queued deliveries.
- [x] Authorized Failed-Job Replay:
  - Added REST endpoint `POST /api/v1/merchants/webhooks/{subscriptionId}/deliveries/{deliveryId}/replay` accepting `ReplayWebhookDeliveryRequest(reason)`.
  - Requires verified JWT with merchant ownership or operator/admin (`ADMIN`, `DISPATCHER`) authority.
  - Strict terminal state guard: only `FAILED` or `EXHAUSTED` jobs can be replayed; non-terminal jobs (`SUCCESS`, `PENDING`, `PROCESSING`) rejected with 409 Conflict.
  - Flyway migration `V2__create_webhook_replays_table.sql` creating durable audit table `webhook_replays` recording `replay_id`, `delivery_id`, `subscription_id`, `merchant_id`, `actor_sub`, `actor_role`, `reason`, `previous_status`, `replayed_at`.
  - Resets job status to `PENDING` with `next_attempt_at = now`, resets `attempt_count = 0` while preserving complete historical attempt lineage in `webhook_attempts`.
- [x] Automated Test Coverage:
  - 14 additional unit tests in `WebhookDestinationValidatorTest` (DNS rebinding, mixed safe/unsafe answers, IPv4-mapped IPv6 bypass, cloud metadata IPv4/IPv6, CGNAT, ValidatedWebhookDestination).
  - 2 additional unit tests in `DefaultWebhookHttpClientTest` (PinnedSSLSocketFactory SNI/HTTPS endpoint identification, socket pinning dispatch).
  - 4 additional unit tests in `WebhookSubscriptionControllerTest` (rotate-secret endpoint, replay endpoint, blank reason rejection, non-terminal 409 conflict).
  - 4 additional unit tests in `WebhookSubscriptionServiceTest` (replay EXHAUSTED delivery, replay FAILED delivery, SUCCESS status 409 rejection, cross-tenant 403 rejection, admin operator authorization).
  - V2 Flyway migration verification in `CommunicationsFlywayMigrationTest.verifyV2FlywayMigrationScript`.
  - Entity lifecycle test for `WebhookReplayAudit` in `CommunicationsEntityTest`.
  - Data ownership verification for `webhook_replays` in `CommunicationsDataOwnershipTest`.
  - Real PostgreSQL replay and audit constraint integration test in `CommunicationsPersistencePostgresIT.testWebhookDeliveryReplayAndAuditPersistence`.
  - Total test count increased from 254 to 280 passing tests.

### IMPLEMENTED in Issue #33 (LATER-12): End-to-End Webhook Delivery & Retry Pipeline Ownership

Issue #33 establishes unified end-to-end technical ownership over the SwiftDrop webhook delivery and retry engine, integrating all five decomposed Communications slices into a single coherent, secure, observable, and recoverable pipeline:

#### 1. Decomposed Slice Dependency & Traceability Matrix

| Slice Issue | Phase | Primary Owner | Scope & Responsibility | Status & Merged PR |
|:---|:---|:---|:---|:---|
| **#116 (N10-B17)** | Pre-Nov1 | `@thuvarahan-t` | Tenant-bound webhook subscription REST configuration (`IF-REST-11`, `IF-REST-12`), AES-256-GCM secret storage, destination validation, event filtering. | **Merged (#233)** |
| **#131 (N10-B18)** | Pre-Nov1 | `@EricPraveen` | Dedicated Kafka consumer group (`communications.webhooks.v1`), at-least-once offset commit, monotonic state version check, SHA-256 payload digest, durable job materialization in `webhook_deliveries`. | **Merged (#241)** |
| **#142 (N10-B20)** | Nov1-Nov10 | `@EricPraveen` | Outbound HMAC-SHA256 signature generation (`X-SwiftDrop-Signature`), network dispatch outside database transactions, attempt persistence in `webhook_attempts`, response outcome classification. | **Merged (#243)** |
| **#151 (N10-B21)** | Nov1-Nov10 | `@EricPraveen` | Periodic `@Scheduled` polling of due jobs, atomic PostgreSQL row leasing (`claimDeliveryWithLease`), exponential backoff engine, jitter factor, 5-attempt retry budget ceiling, terminal `EXHAUSTED` status, crash recovery. | **Merged (#245)** |
| **#155 (N10-B22)** | Nov1-Nov10 | `@Vibhushan-2003` | Send-time SSRF egress defense, DNS rebinding prevention, TLS socket pinning (`PinnedSSLSocketFactory`), HMAC key rotation (`POST .../rotate-secret`), authorized failed-job replay with audit lineage in `webhook_replays`. | **Merged (#249)** |
| **#33 (LATER-12)** | Nov1-Nov10 | `@EricPraveen` | **Umbrella Engine Ownership**: End-to-end pipeline coordination, cross-slice lifecycle invariants, end-to-end automated integration suite (`WebhookDeliveryPipelineIntegrationTest`), delivery contracts, and operational recovery runbook. | **Active Ownership** |

#### 2. End-to-End Execution Path & State Lifecycle

```text
[Kafka: parcel.lifecycle.v1]
         |
         v (1. Consume & Parse Envelope)
[WebhookLifecycleKafkaConsumer]
         |
         v (2. Tenant/Filter/Version Check)
[WebhookJobMaterializationService] ---> [consumed_events] (Atomic Tx)
         |                              [webhook_deliveries (PENDING)]
         v (3. Commit Kafka Offset strictly after DB Tx)
[Kafka Acknowledgment]
         |
         v (4. Periodic Batch Poll @ 5s)
[WebhookDeliveryScheduler]
         |
         v (5. Atomic Database Lease: PENDING -> PROCESSING)
[WebhookDeliveryRepository.claimDeliveryWithLease]
         |
         +---> (Lease Lost: Concurrent Worker Collision) -> Skip safely
         |
         +---> (Lease Won: Exclusive Execution Right)
                     |
                     v (6. Egress Validation & Signing)
               [WebhookDestinationValidator] (SSRF, HTTPS, DNS pinning)
               [WebhookSigningSecretService] (AES-256-GCM decrypt)
               [DefaultWebhookSigner]        (HMAC-SHA256 signature)
                     |
                     v (7. Outbound Network Dispatch strictly OUTSIDE DB Tx)
               [DefaultWebhookHttpClient]    (PinnedSSLSocketFactory, timeouts, redirects NEVER)
                     |
                     v (8. Response & Exception Classification)
               [WebhookResponseClassifier]
                     |
                     +---> 2xx: SUCCESS (Terminal)
                     |          `-> status = SUCCESS, nextAttemptAt = null
                     |
                     +---> 3xx, 4xx (except 408/429), Security: Non-Retryable Failure
                     |          `-> status = FAILED, nextAttemptAt = null
                     |
                     +---> 408, 429, 5xx, Transport Timeout: Retryable Transient Failure
                                |
                                +---> attemptCount < 5: Schedule Retry
                                |          `-> status = PENDING, nextAttemptAt = now + backoff
                                |
                                `---> attemptCount >= 5: Retry Budget Exhausted
                                           `-> status = EXHAUSTED (Terminal), nextAttemptAt = null
                     |
                     v (9. Atomic Attempt History & Status Commit)
               [WebhookAttemptRepository]  -> Insert attempt (status, latency, errorSummary)
               [WebhookDeliveryRepository] -> Update delivery (status, attemptCount, nextAttemptAt)
                     |
                     v (10. Operator Unblock / Replay)
               [POST .../deliveries/{id}/replay]
                     |
                     +-> Pre-condition: status in ('FAILED', 'EXHAUSTED')
                     +-> Record durable audit in [webhook_replays]
                     `-> Reset delivery: status = PENDING, attemptCount = 0, nextAttemptAt = now
```

#### 3. Core Engine Invariants & Delivery Guarantees

1. **At-Least-Once Delivery with Durable Idempotency:**
   - Kafka partition offsets advance **only after** PostgreSQL transaction committing `consumed_events` and `webhook_deliveries` succeeds.
   - Dual-layer uniqueness (`consumed_events (consumer_name, event_id)` and `webhook_deliveries (subscription_id, event_id)`) guarantees duplicate events from Kafka redelivery or broker failovers do not generate duplicate delivery jobs.
   - External recipients may observe multiple deliveries due to network partition after receiver acknowledgement; receivers verify `eventId` and `X-SwiftDrop-Signature` for safe deduplication. Exactly-once delivery across distributed networks is impossible and not claimed.
2. **Transaction Separation:**
   - Outbound HTTP network requests are executed **strictly outside** database transactions, preventing slow merchant endpoints from starving HikariCP connection pools.
   - Status updates and attempt recordings commit within an isolated `REQUIRES_NEW` transaction.
3. **Atomic Multi-Worker Leasing:**
   - Worker discovery uses PostgreSQL row locking via atomic JPQL `UPDATE ... WHERE status = 'PENDING' OR (status = 'PROCESSING' AND updatedAt <= leaseCutoff)`.
   - Update count 1 grants exclusive dispatch rights; update count 0 causes concurrent workers to skip candidate deliveries without duplicate dispatches.
4. **Crash Recovery & Stalled Reclamation:**
   - Stalled worker deliveries left in `PROCESSING` status beyond `leaseTimeoutSeconds` (default 300s) are automatically reclaimed back to `PENDING` with `nextAttemptAt = now` by `recoverStalledDeliveries()`.
5. **Deterministic Exponential Backoff & Retry Ceilings:**
   - Delay sequence: $\text{delay} = \min(\text{maxBackoff}, \text{baseBackoff} \times 2^{\text{attempt} - 1})$ (5s, 10s, 20s, 40s, 80s; capped at 300s).
   - Rate limit compliance: `Retry-After` headers (numeric seconds or RFC 1123 HTTP dates) strictly override exponential backoff up to `maxBackoffSeconds`.
   - Optional jitter factor (default 0.2) diffuses thundering herds across bulk webhook redeliveries.
   - Exhaustion threshold: strictly terminates at `maxAttempts` (default 5) with status `EXHAUSTED`.
6. **Egress Security & Credential Protection:**
   - Send-time SSRF validation blocks private RFC 1918, loopback, link-local, cloud metadata, multicast, and internal domains.
   - `PinnedSSLSocketFactory` binds TCP connection to pre-validated IP while preserving TLS SNI and certificate verification.
   - Signing secrets encrypted with AES-256-GCM at rest; never exposed via GET endpoints, attempts, or application logs.
   - HTTP redirects strictly rejected (`Redirect.NEVER`) to prevent SSRF bypass and downgrade attacks.
7. **Authorized Failed-Job Replay:**
   - Only terminally failed (`FAILED` or `EXHAUSTED`) jobs can be replayed; non-terminal jobs reject replay with `409 Conflict`.
   - Replay records durable audit in `webhook_replays` (`replay_id`, `delivery_id`, `actor_sub`, `actor_role`, `reason`, `previous_status`, `replayed_at`).
   - Resets job to `PENDING`, `attemptCount = 0`, `nextAttemptAt = now` while preserving historical attempt records in `webhook_attempts`.

#### 4. Automated End-to-End Test Evidence

- `WebhookDeliveryPipelineIntegrationTest`:
  - `testCompleteHappyPathPipeline`: Proves end-to-end event ingestion, job materialization, scheduler leasing, outbound HMAC dispatch, 200 OK classification, and terminal SUCCESS transition.
  - `testMultiWorkerConcurrencyDefense`: Proves concurrent workers racing for the same candidate delivery safely skip execution without duplicate dispatches.
  - `testRetryLifecycleWithExponentialBackoffAndExhaustion`: Proves transport timeout classification, backoff scheduling, Retry-After header overrides, exponential delays, and terminal EXHAUSTED transition at attempt 5.
  - `testAuthorizedReplayAndRedelivery`: Proves cross-tenant replay denial, authorized replay audit recording in `webhook_replays`, reset to PENDING, and redelivery to SUCCESS.
  - `testNonRetryableFailuresImmediatelyTransitionToFailed`: Proves SSRF security violations, 400 Bad Request, and 302 Redirect transition immediately to FAILED.
  - `testCrashRecoveryForStalledProcessingJobs`: Proves worker crash recovery for jobs orphaned in PROCESSING status past lease timeout.
  - `testSecurityInvariantsZeroSecretsInAttempts`: Proves zero secret tokens, passwords, or auth credentials leak into attempt error summaries.
- Service-wide automated test suite: **287 passing tests** with zero failures across unit, MockMvc, gRPC, and PostgreSQL integration suites.

### Issue #117 (N10-B19) Implementation: Bounded Notification Retry, Poison Handling, and Inspected Replay

Implemented in `feat/n10-b19-notification-retry-poison-replay` (PR targeting `test`):
- **First Real Email Transport (`EmailNotificationTransport`):** Connects to the platform SMTP test sink (`mailpit:1025`) using `JavaMailSender`. Zero customer PII or plaintext emails exposed in application logs or stored in `communications_db`. Contacts are resolved dynamically via Accounts gRPC (`resolveContact`).
- **PostgreSQL-Backed Notification Worker Engine (`NotificationDeliveryScheduler`, `NotificationExecutionService`):**
  - Multi-worker concurrency lease acquisition (`claimJobWithLease` on `notifications` table) using row locking and lease expiration timestamps.
  - Bounded exponential backoff with full jitter calculation and integer overflow protection.
  - Automated stalled job recovery (`recoverStalledNotifications`) returning abandoned `PROCESSING` jobs past lease expiration back to `PENDING`.
  - Durable attempt history recording in `notification_attempts` capturing sanitized error codes, categories, and dispatch latency.
- **Poison-Event Quarantine (`PoisonEventQuarantineService`):**
  - Isolates malformed or schema-violating Kafka messages in `poison_event_quarantine` with cryptographic SHA-256 payload digests and safe classifications.
  - Offsets are acknowledged strictly AFTER durable quarantine persistence, preventing consumer partition blocking.
  - Idempotent quarantine across broker redeliveries.
- **Audited Operator Replay (`NotificationReplayService`, `NotificationOperatorController`):**
  - Restricted to `ROLE_ADMIN` and `ROLE_DISPATCHER` with non-blank replay reason.
  - Dual re-validation of recipient eligibility with Accounts and event authorization with Delivery Operations prior to resetting jobs back to `PENDING`.
  - Immutable audit history recorded in `notification_replays`.
- **Bounded Data Retention (`NotificationRetentionService`):**
  - Purges terminal notification jobs (`SENT`, `FAILED`, `EXHAUSTED`) and processed inbox events (`consumed_events`) past the configured cutoff (default 30 days).
  - Strictly preserves active `PENDING` and `PROCESSING` jobs to protect in-flight deliveries and deduplication horizons.

### EXPLICITLY NOT IMPLEMENTED (Belongs to Future Issues):
- ❌ External production third-party SMTP / SMS gateway delivery engines (e.g. Mailgun, Twilio, AWS SES).
- ❌ Shared API Gateway or Keycloak configuration changes.
- ❌ Cross-service SQL joins or direct database access to Accounts or Delivery databases.

