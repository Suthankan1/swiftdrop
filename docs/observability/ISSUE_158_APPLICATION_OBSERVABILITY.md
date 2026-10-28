# SwiftDrop Issue #158 — Application Correlation, Service Metrics, Health/Readiness and Failure Visibility

**Owner:** `@thuvarahan-t`  
**Base Branch:** `test`  
**Feature Branch:** `feat/n10-qa06-observability-metrics`  
**Reviewers:**  
- `@Harithkeshan` — Admin/operational integration and cross-service review.  
- `@Vibhushan-2003` — Delivery integration, event boundaries, and independent verification.  
- `@EricPraveen` — Independent domain acceptance and webhook integration verification.  
- `@Suthankan1` — Platform security, monitoring, health/readiness and deployment handoff.  

---

## 1. Executive Summary & Architecture Overview

Issue #158 delivers secure, bounded, production-grade application observability across the canonical SwiftDrop business flow:
$$\text{Merchant/Customer Booking} \longrightarrow \text{Delivery Status} \longrightarrow \text{PostgreSQL Outbox} \longrightarrow \text{Kafka} \longrightarrow \text{Communications Inbox} \longrightarrow \text{Webhook/Notification Jobs} \longrightarrow \text{Delivery Attempts \& Terminal Visibility}$$

### Non-Negotiable Architecture Constraints Preserved
1. **Zero New Microservices**: Observability is implemented within existing Spring Boot services via Micrometer, Spring Boot Actuator, shared gRPC security interceptors, and structured MDC logging.
2. **Platform Separation of Concerns**: Application code owns metric definitions, bounded cardinality safeguards, health/readiness groupings, and safe error visibility. Platform infrastructure (`@Suthankan1`) owns Prometheus/Grafana deployment, Helm releases, cluster alerting, and secret provisioning.
3. **Strict Privacy & Redaction**: Zero credentials, authorization tokens, bearer JWTs, recipient phone numbers, street addresses, proof URLs, or webhook HMAC signing secrets are ever leaked into logs, metrics tags, or failure inspection payloads.
4. **Bounded Metric Cardinality**: No high-cardinality values (`correlationId`, `merchantId`, `parcelId`, `quoteId`, `eventId`, `deliveryId`, `userId`, or arbitrary URLs) are ever used as Micrometer tag keys or values.
5. **Contract Compatibility**: No frozen REST contracts, Protobuf field numbers, or Kafka event schemas (`parcel.status.changed.v1`) were broken or mutated.

---

## 2. End-to-End Correlation Propagation Architecture

### 2.1 Canonical Policy & Sanitization Boundary
- **Format Policy**: Canonical correlation identifiers must match `^[a-zA-Z0-9_-]{1,64}$`.
- **Normalization**: Any missing, blank, malformed, oversized (>64 chars), CRLF-injected, or control-character-containing header is rejected and safely replaced with a server-generated standard UUIDv4.
- **Gateway Trust Boundary (`api-gateway`)**:
  - `StripUntrustedHeadersGatewayFilter` strips untrusted internal headers (including `x-swiftdrop-correlation-id`) coming from external clients.
  - `CorrelationHeaderGatewayFilter` (ordered at `HIGHEST_PRECEDENCE + 1`) inspects `X-Correlation-ID` and `X-Request-ID`. If valid, it normalizes and forwards `X-Correlation-ID` downstream to internal microservices and echoes it back on the HTTP response. If invalid or absent, a fresh UUIDv4 is generated.
- **Spring MVC (`shared/security-adapter`)**:
  - `CorrelationIdFilter` intercepts incoming HTTP Servlet requests, extracts and validates `X-Correlation-ID`, binds it to `CorrelationContext` and SLF4J MDC (`correlationId`), sets the response header, and deterministically clears MDC in a `finally` block to prevent thread-pool leakage.
- **Internal gRPC (`shared/security-adapter`)**:
  - `SwiftDropGrpcClientInterceptor` propagates `CorrelationContext.get()` into gRPC metadata key `x-swiftdrop-correlation-id`.
  - `SwiftDropGrpcServerSecurityInterceptor` extracts and sanitizes the gRPC metadata, wrapping the entire gRPC listener execution in `CorrelationContext.runWith(correlationId, ...)`.
- **Delivery Booking & Outbox Relay (`delivery-service`)**:
  - `BookingService` reads `CorrelationContext.get().orElse(key.toString())` to correlate the booking transaction.
  - Idempotent booking replays return original cached results without altering existing event correlation.
  - `StatusTransitionService` passes the correlation ID into `OutboxEventFactory.createParcelStatusChangedEvent(...)`.
  - `KafkaOutboxRelay` extracts `correlationId` from the outbox event payload and binds it to `CorrelationContext.executeWith(...)` during Kafka publication.
- **Kafka Consumers & Durable Inbox (`communications-service`)**:
  - `ParcelLifecycleKafkaConsumer` and `WebhookLifecycleKafkaConsumer` extract `event.correlationId()` from the verified event envelope and wrap listener execution in `CorrelationContext.runWith(...)`.
  - Inbox persistence stores `correlation_id` in `consumed_events` via Flyway migration `V3__add_correlation_id_tracking.sql`.
  - Durable job materialization records `correlation_id` in `notifications` and `webhook_deliveries`.
  - `WebhookDeliveryExecutionService` executes outbound HTTP attempts wrapped in `CorrelationContext.executeWith(delivery.getCorrelationId(), ...)`.

---

## 3. Metric Catalog & Cardinality Safeguards

All metrics use bounded cardinality enums and low-cardinality labels. Sampling queries are cached with 1000ms minimum intervals to eliminate database query storms during Prometheus scrapes.

| Component | Metric Name | Type | Unit | Description | Allowed Tags | Cardinality Controls |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Shared gRPC** | `swiftdrop.grpc.client.duration` | Timer | seconds | Latency of internal outbound gRPC calls | `service`, `method` | Method names are normalized canonical IDs. |
| **Shared gRPC** | `swiftdrop.grpc.client.requests` | Counter | calls | Total completed outbound gRPC calls | `service`, `method` | Pre-registered per client stub. |
| **Shared gRPC** | `swiftdrop.grpc.client.errors` | Counter | calls | Outbound gRPC call errors | `service`, `method`, `status` | Status is bounded gRPC status code (`DEADLINE_EXCEEDED`, `UNAVAILABLE`, etc.). |
| **Shared gRPC** | `swiftdrop.grpc.server.duration` | Timer | seconds | Server execution latency of gRPC handlers | `service`, `method` | Bounded service and method names. |
| **Shared gRPC** | `swiftdrop.grpc.server.requests` | Counter | calls | Total inbound gRPC requests received | `service`, `method` | Low cardinality. |
| **Shared gRPC** | `swiftdrop.grpc.server.errors` | Counter | calls | Inbound gRPC request failures | `service`, `method`, `status` | Status code only. No tenant or actor tags. |
| **Delivery** | `swiftdrop.outbox.pending` | Gauge | events | Current backlog of PENDING outbox events | (none) | Derived from cached `COUNT(*)` status filter. |
| **Delivery** | `swiftdrop.outbox.failed` | Gauge | events | Current count of FAILED (retryable) outbox events | (none) | Cached query sampling. |
| **Delivery** | `swiftdrop.outbox.dead_letter` | Gauge | events | Current count of DEAD_LETTER outbox events | (none) | Cached query sampling. |
| **Delivery** | `swiftdrop.outbox.oldest.age.seconds` | Gauge | seconds | Age of oldest uncompleted outbox event | (none) | Calculated via `MIN(created_at)` with injected `Clock`. 0 if backlog is empty. |
| **Delivery** | `swiftdrop.outbox.publish.total` | Counter | events | Outbox relay publication attempts | `outcome` | Bounded: `success`, `failure`. |
| **Delivery** | `swiftdrop.sse.connections.active` | Gauge | connections | Current open SSE subscriber streams | (none) | Single-replica session tracker count. |
| **Delivery** | `swiftdrop.sse.connections.accepted.total` | Counter | connections | Cumulative accepted SSE connections | (none) | Monotonic counter. |
| **Delivery** | `swiftdrop.sse.connections.rejected.total` | Counter | connections | Cumulative rejected SSE connections | `reason` | Bounded: `user_limit`, `parcel_limit`, `global_limit`. |
| **Delivery** | `swiftdrop.sse.connections.closed.total` | Counter | connections | Cumulative closed SSE connections | (none) | Decrements active gauge, increments closed counter. |
| **Communications** | `swiftdrop.webhook.deliveries` | Gauge | jobs | Current count of durable webhook delivery jobs | `status` | Bounded: `pending`, `processing`, `success`, `failed`, `exhausted`. |
| **Communications** | `swiftdrop.webhook.attempts.total` | Counter | attempts | Cumulative outbound webhook HTTP attempts | `outcome` | Bounded: `success`, `rate_limited`, `server_error`, `client_error`, `timeout`, `network_error`. |
| **Communications** | `swiftdrop.webhook.attempt.duration` | Timer | seconds | Outbound webhook HTTP call latency | (none) | Summary and percentile latency. |
| **Communications** | `swiftdrop.webhook.retry.scheduled.total`| Counter | retries | Cumulative webhook retries scheduled | (none) | Counted once upon scheduling next attempt. |
| **Communications** | `swiftdrop.webhook.exhausted.total` | Counter | jobs | Cumulative webhooks transitioning to EXHAUSTED | (none) | Counted once on terminal exhaustion. |
| **Communications** | `swiftdrop.webhook.stuck.recovered.total` | Counter | jobs | Cumulative stuck PROCESSING webhooks recovered | (none) | Recovered during scheduler polling. |
| **Communications** | `swiftdrop.notification.jobs` | Gauge | jobs | Current count of durable notification jobs | `status` | Bounded: `pending`, `sent`, `failed`, `exhausted`. |
| **Communications** | `swiftdrop.notification.pending.age.seconds` | Gauge | seconds | Age of oldest uncompleted pending notification job | (none) | `MIN(created_at)` via injected `Clock`. 0 if empty. |
| **Communications** | `swiftdrop.notification.materialized.total` | Counter | jobs | Cumulative notification jobs created | `channel`, `status` | `channel=email`, `status=persisted`. |
| **Communications** | `swiftdrop.kafka.consumer.processed.total` | Counter | records | Cumulative Kafka records consumed and committed | `consumer_group`, `status` | `consumer_group` (bounded), `status` (`success`, `duplicate`, `invalid`). |
| **Communications** | `swiftdrop.kafka.inbox.events` | Gauge | records | Total inbox records persisted | `consumer_group` | Bounded consumer group name. |

---

## 4. Health, Readiness & Endpoint Security

### 4.1 Liveness vs. Readiness Separation
- **Liveness Probe (`/actuator/health/liveness`)**:
  - Answers: *Is the Java process alive and uncorrupted?*
  - Uses Spring Boot `livenessState`. Transient database or broker connectivity drops do not mark liveness `DOWN` (preventing catastrophic container crash-looping).
- **Readiness Probe (`/actuator/health/readiness`)**:
  - Answers: *Can this service accept and execute operational workloads?*
  - `delivery-service`: Includes `readinessState,db`. If PostgreSQL is unavailable, readiness is marked `DOWN`.
  - `communications-service`: Includes `readinessState,db`. If PostgreSQL is unavailable, readiness is marked `DOWN`.
  - `accounts-service`: Includes `readinessState,db`.
  - `pricing-service`: Includes `readinessState,db`.
  - `api-gateway`: Includes `readinessState`.

### 4.2 Least-Privilege Management Exposure
- Exposed endpoints over HTTP are restricted to `health,info`:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info
    endpoint:
      health:
        show-details: when_authorized
        probes:
          enabled: true
        group:
          liveness:
            include: livenessState
          readiness:
            include: readinessState,db
  ```
- No public exposure of `/actuator/metrics` or `/actuator/prometheus` without Platform-approved authentication and dedicated management port.
- No database credentials, connection strings, Kafka broker addresses, or Keycloak client secrets are exposed in health responses.

---

## 5. Terminal Failure Visibility Architecture

For operational failure inspection, `TerminalFailureVisibilityService` in `communications-service` provides a tenant-isolated diagnostic inquiry path:
- **Tenant Isolation**: Only the authentic owning `merchantId` can query terminal failure evidence for their deliveries. Cross-merchant requests return `Optional.empty()` (404/Forbidden).
- **Correlation & Traceability**: Each failure response contains:
  - `deliveryId`: Unique delivery identifier.
  - `eventId`: Originating domain event UUID.
  - `correlationId`: Logical trace identifier linking back to customer/merchant booking.
  - `status`: Terminal status (`FAILED` or `EXHAUSTED`).
  - `attemptCount`: Total attempts made before failure.
  - `httpStatusCode`: Last observed HTTP status (e.g. `500`, `504`).
  - `sanitizedErrorReason`: Standardized categorization (e.g., `HTTP 500: Internal Server Error`, `PROVIDER_TIMEOUT`).
- **Zero Leakage**: Raw response bodies from external merchant endpoints, internal authentication tokens, recipient phone numbers, and webhook HMAC secrets are omitted.

---

## 6. Sanitized Operational Correlation Trace Example

```text
2026-10-28T02:00:00.100Z [api-gateway] [reactor-http-epoll-1] INFO c.s.g.f.CorrelationHeaderGatewayFilter [correlationId=550e8400-e29b-41d4-a716-446655440000] Ingress request received [method=POST, path=/api/v1/merchant/parcels, normalizedCorrelationId=550e8400-e29b-41d4-a716-446655440000]
2026-10-28T02:00:00.150Z [delivery-service] [http-nio-8083-exec-1] INFO c.s.d.b.BookingService [correlationId=550e8400-e29b-41d4-a716-446655440000] Parcel booking accepted [parcelId=1e3f4c35-f2ea-4554-88f7-f83d723e7b44, trackingNumber=SWD-20261028-A1B2C3D4, outcome=committed]
2026-10-28T02:00:00.180Z [delivery-service] [scheduling-1] INFO c.s.d.o.KafkaOutboxRelay [correlationId=550e8400-e29b-41d4-a716-446655440000] Outbox event published [eventId=22222222-2222-2222-2222-222222222222, eventType=parcel.status.changed.v1, outcome=published]
2026-10-28T02:00:00.220Z [communications-service] [kafka-listener-1] INFO c.s.c.c.WebhookLifecycleKafkaConsumer [correlationId=550e8400-e29b-41d4-a716-446655440000] Consumed Kafka lifecycle event [eventId=22222222-2222-2222-2222-222222222222, inboxStatus=persisted]
2026-10-28T02:00:00.300Z [communications-service] [webhook-dispatch-1] WARN c.s.c.w.d.WebhookDeliveryExecutionService [correlationId=550e8400-e29b-41d4-a716-446655440000] Webhook attempt failed [deliveryId=33333333-3333-3333-3333-333333333333, attempt=1, statusCode=500, category=SERVER_ERROR, outcome=retry_scheduled]
2026-10-28T02:00:10.500Z [communications-service] [webhook-dispatch-2] ERROR c.s.c.w.d.WebhookDeliveryExecutionService [correlationId=550e8400-e29b-41d4-a716-446655440000] Webhook retries exhausted [deliveryId=33333333-3333-3333-3333-333333333333, totalAttempts=3, terminalStatus=EXHAUSTED]
```

---

## 7. Platform Deployment Handoff for `@Suthankan1`

### 7.1 Prometheus Scraping Guidelines
- **Scrape Path**: `/actuator/prometheus` (when Prometheus exporter is activated by Platform).
- **Management Security**: Recommended to expose Actuator on an isolated internal management port (e.g. `management.server.port: 8091`) or enforce Kubernetes network policies permitting ingress solely from the Prometheus scraper namespace.
- **Scrape Interval**: Recommended `15s` or `30s`. Application gauges are internally cached for 1000ms, ensuring zero database query storms even under concurrent scraping.

### 7.2 Alert Rule Threshold Recommendations
1. **Outbox Backlog Accumulation**:
   - `swiftdrop_outbox_pending > 100` for 3m $\rightarrow$ Warning (Outbox relay sluggish).
   - `swiftdrop_outbox_oldest_age_seconds > 60` for 2m $\rightarrow$ Critical (Outbox relay stopped).
2. **Webhook Exhaustion Spike**:
   - `rate(swiftdrop_webhook_exhausted_total[5m]) > 5` $\rightarrow$ Warning (Merchant endpoint degradation).
3. **gRPC Deadline Exceeded**:
   - `rate(swiftdrop_grpc_server_errors{status="DEADLINE_EXCEEDED"}[5m]) > 2` $\rightarrow$ Warning (Downstream latency pressure).
4. **Service Readiness Drop**:
   - `probe_success{job="swiftdrop-readiness"} == 0` for 30s $\rightarrow$ Critical (Service unready, traffic shifted).

---

## 8. Issue #117 Integration Handoff Note

- **Current State**: Issue #117 (real SMTP/SMS notification transport, bounded retry, and poison-event quarantine) is currently OPEN.
- **Seam Provided**: `CommunicationsMetrics.recordNotificationMaterialized(channel, status)` and `swiftdrop.notification.jobs` (gauges) are active and verified.
- **Future Integration for #117**:
  - When the real email worker is implemented in #117, it should invoke `recordNotificationMaterialized("email", outcome)` or register a timer `swiftdrop.notification.dispatch.duration` using the same bounded outcome convention.
  - The existing `NotificationSchedulingService` and `NotificationJobRepository` already preserve `correlationId` in the `notifications` table, ensuring complete trace continuity when Issue #117 merges.

---

## 9. Verification & Automated Test Evidence Summary

| Module | Test Classes Executed | Tests Run | Failures | Errors | Result |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `shared/security-adapter` | `CorrelationContextTest`, `GrpcDeadlineAndCorrelationTest` | 51 | 0 | 0 | **PASSED** |
| `services/api-gateway` | `CorrelationHeaderGatewayFilterTest` + full suite | 82 | 0 | 0 | **PASSED** |
| `services/accounts-service` | Full test suite | 311 | 0 | 0 | **PASSED** |
| `services/pricing-service` | Full test suite | 167 | 0 | 0 | **PASSED** |
| `services/delivery-service` | `OutboxMetricsTest`, `ParcelSseMetricsTest`, `BookingCorrelationTest` + full suite | 429 | 0 | 0 | **PASSED** |
| `services/communications-service` | `CommunicationsMetricsTest`, `TerminalFailureVisibilityServiceTest`, `SyntheticEndToEndFlowTest` + full suite | 296 | 0 | 0 | **PASSED** |
| **Total Verified** | **All In-Scope Suites** | **1,336** | **0** | **0** | **100% GREEN** |
