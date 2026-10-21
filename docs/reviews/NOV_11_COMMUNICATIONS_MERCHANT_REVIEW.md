# Communications Domain Architecture Review — ERD, Merchant Integration & Reliability Rationale

**Milestone:** November 1, 2026 — Design Report & Working Logins Checkpoint  
**Task:** Issue #10 (`[NOV-11] Review Communications ERD, merchant integration and reliability rationale`)  
**Lead Architectural Reviewer:** Thuvarahan (@thuvarahan-t), Merchant Lead  
**Required Cross-Team Reviewers:**  
- **Vibhushan (@Vibhushan-2003)** — Courier Lead & Security QA Lead (Delivery events, outbox publication, status sequence 12 & SSE fan-out)  
- **Suthankan1 (@Suthankan1)** — Platform Lead (Kafka KRaft event contract, webhook worker/engine bounded slice & security boundaries)  
- **Eric (@EricPraveen)** — Merchant Lead Collaborator (Merchant registration/order contract, use cases 02 & order sequence 11)  
- **HarithKeshan (@Harithkeshan)** — Dispatcher/Admin Lead & Academic Report Assembly Lead (Issue #19)  
**Target Integration Branch:** `test`  
**Feature Branch:** `docs/nov11-communications-merchant-review`  
**Status:** **AUTHOR ARCHITECTURAL REVIEW COMPLETE — PENDING TEAM SIGNOFF**

---

## 1. Scope, Purpose & Working Boundaries

This document provides a formal, evidence-based architectural, database, integration, and reliability review of the **Communications Service** and its integration with the **Merchant bounded context** for the SwiftDrop enterprise parcel delivery platform under Architecture Baseline v1.3.

### 1.1 Review-Only Deliverable Scope
- **Design Review Only:** This task is strictly an **architectural, entity-relationship, event contract, and reliability rationale review** for the November 1 Academic Design Report (Issue #19).
- **Zero Generated Application Code:** Per repository policy (`AGENTS.md`), no microservice application code, Spring Boot starters, controllers, JPA entities, Flyway migrations, Kafka producer/consumer implementations, retry workers, or webhook dispatchers are created in this task.
- **Honest Status Separation:** The operational login vertical slice (React SPA, API Gateway, Keycloak OIDC, and Accounts business profiles) has been implemented and independently verified under Issues #1, #2, #3, #5, #6, #8, #36, #37, and #18. Communications persistence (`communications_db`), Kafka event relays, transactional outboxes, signed webhook dispatch engines, and SSE streams represent **PLANNED TARGET ARCHITECTURE** and must be reported as such in the academic report.

### 1.2 Responsibilities & Team Boundaries
- **Merchant Team (Eric & Thuvarahan):** Owns merchant onboarding/approval flow, merchant order API, merchant dashboard, and webhook configuration contracts/UX boundaries.
- **Platform Extra Slice (Suthankan1):** The team allocation (`docs/team/OWNERSHIP.md`) defines a small post-Nov 1 bounded slice for Suthankan1: *Webhook delivery + retry worker/engine within Communications*, while the Merchant pair owns the subscription UI, event filter contracts, and delivery inspection UX.
- **Communications Service Owner:** Communications is an independent microservice whose service database and side-effect jobs are owned separately from parcel state and merchant account records.

---

## 2. Documents & Diagram Sources Reviewed

The architectural review was conducted against current repository contracts, diagrams, and architecture baselines:

1. **Architecture & Governance Baselines:**
   - [`AGENTS.md`](../../AGENTS.md) (Mandatory working discipline, documentation guard, non-negotiable v1.3 constraints)
   - [`docs/architecture/BASELINE.md`](../architecture/BASELINE.md) (SwiftDrop v1.3 architecture baseline)
   - [`docs/architecture/DATA_OWNERSHIP.md`](../architecture/DATA_OWNERSHIP.md) (Logical data ownership and schema boundaries)
   - [`docs/architecture/PROTOCOL_MATRIX.md`](../architecture/PROTOCOL_MATRIX.md) (Cross-service transport protocols: REST, GraphQL, gRPC, Kafka, SSE)
   - [`docs/architecture/OPEN_DECISIONS.md`](../architecture/OPEN_DECISIONS.md) (Open decisions register)
   - [`docs/architecture/ISSUE_12_DELIVERY_DESIGN_REVIEW.md`](../architecture/ISSUE_12_DELIVERY_DESIGN_REVIEW.md) (Delivery ERD, lifecycle, outbox, and SSE review by Vibhushan)
2. **Team Ownership & Milestone Artifacts:**
   - [`docs/team/OWNERSHIP.md`](../team/OWNERSHIP.md) (Nine-member team allocation and cross-module boundaries)
   - [`docs/team/MODULE_HANDOFF.md`](../team/MODULE_HANDOFF.md) (Module handoff protocol)
   - [`docs/milestones/NOV_01.md`](../milestones/NOV_01.md) (November 1 milestone scope)
   - [`docs/milestones/NOV_01_READINESS_TRACKER.md`](../milestones/NOV_01_READINESS_TRACKER.md) (Working login verification tracker)
   - [`docs/project/STATUS.md`](../project/STATUS.md) (Current verified project status)
3. **Integration & Event Contracts:**
   - [`docs/contracts/KAFKA.md`](../contracts/KAFKA.md) (Kafka domain events: `parcel.lifecycle.v1`, partition key, idempotency, envelope)
   - [`docs/contracts/REST.md`](../contracts/REST.md) (Proposed external REST endpoints)
   - [`docs/contracts/LOGIN_CONTRACT.md`](../contracts/LOGIN_CONTRACT.md) (Frozen authentication and token verification matrix)
4. **Primary Diagrams Reviewed:**
   - **Figure 16:** Communications Logical ERD (`docs/diagrams/editable/16_communications_erd.drawio`, `docs/diagrams/rendered/16_communications_erd.svg`)
   - **Figure 18:** Kafka Reliability & SSE Fan-Out Topology (`docs/diagrams/editable/18_kafka_sse_topology.drawio`, `docs/diagrams/rendered/18_kafka_sse_topology.svg`)
   - **Figure 11:** Merchant Order API Sequence (`docs/diagrams/editable/11_merchant_order_sequence.drawio`, `docs/diagrams/rendered/11_merchant_order_sequence.svg`)
   - **Figure 12:** Courier Status & Webhook Sequence (`docs/diagrams/editable/12_courier_status_sequence.drawio`, `docs/diagrams/rendered/12_courier_status_sequence.svg`)
   - **Figure 02:** Merchant Use Cases (`docs/diagrams/editable/02_merchant_use_cases.drawio`, `docs/diagrams/rendered/02_merchant_use_cases.svg`)
   - **Figures 05 & 06:** System Context & Logical Container Architecture (`docs/diagrams/rendered/05_system_context.svg`, `docs/diagrams/rendered/06_logical_architecture.svg`)

---

## 3. Communications Domain Service & Data Boundaries

Per the SwiftDrop v1.3 architecture baseline, **Communications** is an independently deployable Spring Boot business service with an exclusive logical PostgreSQL database (`communications_db`).

```text
+--------------------------------------------------------------------------------------------------+
|                                    COMMUNICATIONS SERVICE DOMAIN                                 |
|                                                                                                  |
|  [CONSUMED_EVENTS / INBOX] (Deduplication boundary per consumer group)                           |
|     |                                                                                            |
|     +-- Independent Consumer Group 1: communications.webhooks.v1                                 |
|     |     |-- creates --> [WEBHOOK_DELIVERIES] (UK: subscription_id, event_id)                   |
|     |     |                 |-- child (optional) --> [WEBHOOK_ATTEMPTS]                          |
|     |     +-- targets --> [WEBHOOK_SUBSCRIPTIONS] (EXT merchant_id; secret_ref)                  |
|     |                                                                                            |
|     +-- Independent Consumer Group 2: communications.notifications.v1                            |
|           |-- creates --> [NOTIFICATIONS] (UK: event_id, recipient_sub, channel)                 |
+--------------------------------------------------------------------------------------------------+
          |                                      |                                    |
          | EXT merchant_id (UUID)               | EXT parcel_id (UUID)               | EXT recipient_sub (String)
          v                                      v                                    v
     [Accounts Service]                 [Delivery Service]                   [Keycloak / Accounts]
     (Merchant Tenancy/Status)          (Authoritative Parcel State)         (User Contact Details)
```

### 3.1 Strict Invariant: Communications Does NOT Own Parcel State
1. **Authoritative State Isolation:** Delivery Operations is the **sole source of truth** for parcel lifecycle states, state transitions, courier assignments, and tracking audit history. Communications **never updates parcel fields, does not store a shadow copy of parcel state, and does not perform parcel state transitions**.
2. **Side-Effect Scope:** Communications is exclusively responsible for **asynchronous delivery side-effects**:
   - Ingesting domain events via Kafka consumer groups.
   - Enforcing durable inbox de-duplication to prevent duplicate downstream side-effects.
   - Persisting asynchronous delivery jobs (`WEBHOOK_DELIVERIES`, `NOTIFICATIONS`).
   - Signing and dispatching HTTPS POST webhooks with bounded backoff retries.
   - Dispatching email/mock-SMS notifications to resolved recipient contact handles.
3. **No Cross-Service SQL Joins:** Communications owns its own schema, migrations, connection pool, and credentials. There are **zero cross-database foreign keys** linking `communications_db` to `delivery_db`, `accounts_db`, or `pricing_db`.
4. **Opaque External References (`EXT`):**
   - `merchant_id`: Logical UUID scalar identifying the subscribing tenant in Accounts.
   - `parcel_id`: Logical UUID scalar identifying the parcel aggregate in Delivery.
   - `event_id`: Logical UUID scalar identifying the unique domain event published by Delivery.
   - `recipient_sub`: Keycloak subject string identifying the notification recipient.

---

## 4. Figure 16: Communications Logical ERD Deep Review

Figure 16 models the service-owned relational schema for durable event receipt, webhook subscription management, webhook delivery execution, and notification dispatch.

### 4.1 Detailed Entity Assessment

| Entity | Primary Key | Key Attributes | Foreign Key & External References | Architectural Assessment & Invariant Enforcement |
|---|---|---|---|---|
| **`WEBHOOK_SUBSCRIPTIONS`** | `subscription_id : UUID` | `https_url : VARCHAR`<br>`allowed_event_types : VARCHAR[] / JSONB`<br>`secret_ref : VARCHAR`<br>`active : BOOLEAN`<br>`created_at : TIMESTAMP` | `EXT merchant_id : UUID` (Accounts tenant reference) | **Tenant Webhook Registration:** Owned by Communications, managed by Merchant pair via authenticated API. URL must enforce HTTPS. `secret_ref` references securely stored HMAC signing secret (never plaintext in logs or open database fields). `active` flag allows merchants to disable endpoints without deleting configuration. |
| **`WEBHOOK_DELIVERIES`** | `delivery_id : UUID` | `state_version : INT`<br>`signed_payload_digest : VARCHAR(64)`<br>`status : VARCHAR`<br>`attempt_count : INT`<br>`next_attempt_at : TIMESTAMP` | `FK subscription_id -> WEBHOOK_SUBSCRIPTIONS`<br>`EXT event_id : UUID`<br>`EXT parcel_id : UUID`<br>`EXT merchant_id : UUID` | **Durable Webhook Job:** Represents a specific webhook delivery execution. Status transitions: `PENDING` -> `PROCESSING` -> `SUCCESS` / `FAILED` / `EXHAUSTED`. `state_version` prevents stale overwrites on the merchant endpoint. Enforces **`UK (subscription_id, event_id)`** to prevent duplicate job creation upon Kafka message re-delivery. |
| **`CONSUMED_EVENTS / INBOX`** | `PK (consumer_name, event_id)` | `event_type : VARCHAR`<br>`version : INT`<br>`received_at : TIMESTAMP`<br>`processed_at : TIMESTAMP`<br>`scheduled_at : TIMESTAMP` | `EXT parcel_id : UUID`<br>`EXT merchant_id : UUID` | **Transactional Inbox:** Enforces durable de-duplication independently per consumer group (`consumer_name = 'communications.webhooks.v1'` or `'communications.notifications.v1'`). Prevents dual processing of replayed or duplicate Kafka messages. |
| **`NOTIFICATIONS`** | `notification_id : UUID` | `channel : VARCHAR`<br>`destination_ref : VARCHAR`<br>`template : VARCHAR`<br>`status : VARCHAR`<br>`attempts : INT`<br>`next_attempt_at : TIMESTAMP` | `EXT event_id : UUID`<br>`EXT recipient_sub : VARCHAR`<br>`EXT parcel_id : UUID` | **Customer/Recipient Notifications:** Stores email/SMS notification jobs. Channels: `EMAIL`, `SMS_MOCK`. Destination handle (`destination_ref`) resolved via authenticated Accounts API, not stored in event payload. Enforces **`UK (event_id, recipient_sub, channel)`** to guarantee exactly one notification dispatch job per event-channel-recipient tuple. |
| **`WEBHOOK_ATTEMPTS`** *(Optional Child Table)* | `attempt_id : UUID` | `attempted_at : TIMESTAMP`<br>`http_status_code : INT`<br>`response_time_ms : INT`<br>`error_summary : VARCHAR(255)` | `FK delivery_id -> WEBHOOK_DELIVERIES` | **Audit History:** Captures execution history across retry attempts. Stores HTTP status codes and execution latencies. Prohibits saving full third-party response bodies to prevent sensitive data leakage or payload injection. |

### 4.2 Entity Relationship Cardinality & Constraints
1. **Subscription to Deliveries (`1 : 0..*`):** A single merchant webhook subscription can have zero or many historical delivery records. Deliveries reference the parent subscription via a local foreign key (`subscription_id`).
2. **Inbox Decoupling from Kafka:** As noted in Figure 16: *"INBOX is independent per consumer group. Logical event IDs join job records by lookup; no FK to Kafka or Delivery database."* Kafka offsets are managed by Kafka broker commit protocols, while application de-duplication is anchored in PostgreSQL ACID transactions.
3. **PostgreSQL Partial Indexes Recommended for Worker Execution:**
   - To support high-throughput polling by the webhook retry worker without table scans, the database schema must index pending executions:
     ```sql
     CREATE INDEX idx_webhook_deliveries_pending 
     ON webhook_deliveries (next_attempt_at, status) 
     WHERE status IN ('PENDING', 'PROCESSING');
     ```
   - Similarly for customer notifications:
     ```sql
     CREATE INDEX idx_notifications_pending 
     ON notifications (next_attempt_at, status) 
     WHERE status = 'PENDING';
     ```

---

## 5. Merchant-to-Communications Integration Architecture

The Merchant bounded context interacts with the Communications Service across four distinct architectural seams:

```text
+---------------------------------------------------------------------------------------------------------+
|                                  MERCHANT INTEGRATION LIFECYCLE                                         |
|                                                                                                         |
|  1. Configuration Seam (REST via Gateway):                                                              |
|     Merchant UI / System ---> Gateway (JWT auth) ---> Communications API (Manage Webhook Subscriptions)|
|                                                                                                         |
|  2. Order Creation Seam (REST via Gateway):                                                             |
|     Merchant System ---> Gateway (API key) ---> Delivery Service (Atomic parcel + outbox row)          |
|                                                                                                         |
|  3. Status Update Seam (Async Event Stream):                                                            |
|     Delivery Outbox ---> Kafka KRaft (parcel.lifecycle.v1) ---> Communications Webhook Consumer         |
|                                                                                                         |
|  4. Webhook Dispatch Seam (Signed HTTPS):                                                               |
|     Communications Webhook Worker ---> HTTPS POST + HMAC-SHA256 ---> External Merchant Webhook Receiver |
+---------------------------------------------------------------------------------------------------------+
```

### 5.1 Merchant Use Cases Alignment (Figure 02)
Figure 02 specifies eight merchant use cases. The Communications integration directly implements or supports four of these:
- **`Manage webhook subscription`:** Merchant user registers target HTTPS URL, secret token, and subscribed event types via Merchant Portal SPA.
- **`Receive signed status webhooks`:** External merchant system receives signed HTTPS callbacks for parcel lifecycle transitions (`PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED`).
- **`Inspect webhook delivery outcome`:** Merchant user reviews delivery statuses, attempt counts, response latency, and failure codes to diagnose endpoint downtime.
- **`Create delivery order via secured API`:** Synchronous order placement (Figure 11); initiates the parcel lifecycle which subsequently triggers event publishing.

### 5.2 Provider and Consumer Responsibilities Matrix

| Interaction | Originating Service (Provider) | Consuming Service (Consumer) | Transport Protocol | Minimal Payload / Contract Context | Failure & Security Guarantees |
|---|---|---|---|---|---|
| **Webhook Subscription Config** | Merchant Client / Gateway | Communications Service | **REST over HTTPS** (`POST /api/v1/merchants/webhooks`) | `https_url`, `allowed_event_types`, `secret_ref` | Authenticated Keycloak JWT with `MERCHANT` role. Communications enforces tenant matching (`merchant_id` matches caller). |
| **Merchant Order Booking** | Merchant System / Gateway | Delivery Operations | **REST over HTTPS** (`POST /api/v1/merchant/parcels`) | API Key digest, `Idempotency-Key`, parcel dimensions, pickup/dropoff addresses | Accounts verifies key digest and approved status via internal gRPC (`ValidateMerchantKeyDigest`). Commits outbox event atomically. |
| **Domain Event Relay** | Delivery Outbox Relay | Communications Service | **Kafka KRaft** (Topic: `parcel.lifecycle.v1`, Key: `parcelId`) | Envelope: `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `occurredAt`, `correlationId`, `oldStatus`, `newStatus` | At-least-once durable publishing with broker acks (`acks=all`). Consumers deduplicate by `(consumer_name, event_id)`. Zero PII in payload. |
| **Signed Webhook Dispatch** | Communications Webhook Worker | External Merchant Receiver | **HTTPS POST** (Signed Webhook) | Event payload, `eventId`, `aggregateId`, `aggregateVersion`, `timestamp`, `status` | Bounded retries with exponential backoff and jitter. Payload signed via `X-SwiftDrop-Signature` HMAC-SHA256. |
| **Delivery History Inspection** | Merchant Portal / Gateway | Communications Service | **REST over HTTPS** (`GET /api/v1/merchants/webhooks/{id}/deliveries`) | Paged delivery records, timestamps, status, attempt counts, response codes | Strict tenant confinement. Merchant A cannot access Merchant B's delivery history. ProblemDetail on access violations. |

### 5.3 Sequence Diagram Cross-Review (Figures 11 & 12)
1. **Merchant Order API Sequence (Figure 11):**
   - Confirms that Communications is **not** in the synchronous order creation transaction.
   - When a merchant books an order, Delivery Operations validates credentials via Accounts gRPC, snapshots pricing via Pricing gRPC, and commits `PARCELS`, `STATUS_HISTORY`, and `OUTBOX_EVENTS` in a single ACID transaction.
   - Decoupling guarantee: Communications downtime or email/SMS latency has zero impact on merchant order ingestion throughput or response latency.
2. **Courier Status & Webhook Sequence (Figure 12):**
   - Courier submits status patch (`PATCH /api/v1/parcels/{id}/status`) to Delivery Operations via Gateway.
   - Delivery validates assigned courier and monotonic state version, commits state, and writes to `OUTBOX_EVENTS`.
   - Courier receives immediate `HTTP 200 OK` without waiting for downstream webhook or notification delivery.
   - Asynchronous outbox relay reads committed events and publishes to Kafka `parcel.lifecycle.v1`.
   - Communications consumes the event into `CONSUMED_EVENTS` and schedules `WEBHOOK_DELIVERIES`.
   - Webhook worker dispatches HMAC-signed payload to external merchant; merchant receiver verifies signature and de-duplicates by `eventId`.

---

## 6. Reliability Rationale & Messaging Topology

Figure 18 details the asynchronous event topology, durable inbox processing, and real-time streaming architecture.

```text
[ Delivery Operations ]
       |
       | 1. Local ACID Transaction (delivery_db)
       |    - UPDATE parcels SET current_status = '...', version = version + 1;
       |    - INSERT INTO status_history (...);
       |    - INSERT INTO outbox_events (topic='parcel.lifecycle.v1', payload=JSON, published_at=NULL);
       |    - COMMIT;
       v
[ Outbox Poller / Relay Worker ]
       |
       | 2. Read pending rows (published_at IS NULL ORDER BY created_at ASC)
       | 3. Publish to Kafka (acks=all, key=parcelId)
       v
[ Apache Kafka KRaft ] (Topic: parcel.lifecycle.v1 | Key: parcelId)
       |
       +---> [ Communications: notifications.v1 ]
       |        - Atomically record CONSUMED_EVENTS + NOTIFICATIONS job
       |        - Commit Kafka offset
       |        - Worker executes Email/SMS adapter with bounded retry
       |
       +---> [ Communications: webhooks.v1 ]
       |        - Atomically record CONSUMED_EVENTS + WEBHOOK_DELIVERIES job
       |        - Commit Kafka offset
       |        - Worker dispatches signed HTTPS POST with HMAC-SHA256 & backoff
       |
       +---> [ Delivery SSE Fan-Out Pods ]
                - Delivery Replica A (Group: tracking.<replica-pod-a>) -> All Partitions -> Authorized SSE Clients
                - Delivery Replica B (Group: tracking.<replica-pod-b>) -> All Partitions -> Authorized SSE Clients
```

### 6.1 Justification for Asynchronous Kafka KRaft
- **Runtime Decoupling:** Downstream consumers (Communications notification workers, webhook dispatchers, SSE relays) operate independently. Outages, high consumer lag, or slow third-party email/SMS gateways do not block transactional order processing in Delivery Operations.
- **Handling Transient Unavailability:** If Communications Service is rebooting or database migrations are running, Kafka retains published events durably on disk across all partitions. Once Communications resumes, it consumes unprocessed messages without data loss.
- **Trade-Off Acknowledgment:** Asynchronous event messaging introduces operational complexity: consumer lag monitoring, partition sizing, poison pill handling, dead-letter topics, and eventual consistency lag between courier status patch and merchant callback receipt.

### 6.2 At-Least-Once Delivery & Deduplication Invariants
- **Kafka Guarantees:** Kafka KRaft provides **durable at-least-once delivery**, NOT universal exactly-once processing across distributed boundaries.
- **Sources of Duplication:**
  1. *Outbox Relay Retries:* If the outbox publisher pushes a message to Kafka, Kafka commits it, but the network drops before the acknowledgement reaches the relay, the relay retries, creating duplicate events in Kafka.
  2. *Kafka Consumer Rebalance:* If a consumer pod restarts or rebalances before committing its consumer group offset, re-assigned partitions re-deliver recent messages.
  3. *Webhook Network Retries:* If Communications dispatches an HTTP POST to a merchant endpoint, the merchant processes the webhook, but the HTTP 200 response drops in transit, Communications retries.
- **Deduplication Invariants Enforced:**
  1. *Communications Inbox Table:* `CONSUMED_EVENTS` has composite primary key `(consumer_name, event_id)`. Re-delivered Kafka messages for the same consumer group are recognized as duplicates and ignored.
  2. *Job Uniqueness Constraints:* `WEBHOOK_DELIVERIES` enforces `UK (subscription_id, event_id)`; `NOTIFICATIONS` enforces `UK (event_id, recipient_sub, channel)`.
  3. *No Exactly-Once External Side Effects:* The architecture **explicitly acknowledges that external HTTP side-effects cannot be guaranteed exactly-once**. The merchant webhook receiver **MUST** implement idempotency by storing processed `eventId` values and rejecting duplicates.

### 6.3 Message Ordering & Monotonic State Versioning
- **Per-Parcel Ordering Guarantee:** The outbox publisher specifies `key = parcelId`. Kafka guarantees strict FIFO ordering **within each individual partition**. Because all lifecycle events for a given parcel are hashed to the same partition, a consumer processes parcel events in chronological sequence (`CREATED` -> `PICKED_UP` -> `IN_TRANSIT` -> `OUT_FOR_DELIVERY` -> `DELIVERED`).
- **No Global Ordering:** Kafka does not provide global ordering across distinct parcels in different partitions, which is both unnecessary and anti-scalable for delivery operations.
- **Stale Overwrite Protection:** Every event envelope carries `aggregateVersion` (or `state_version`). Downstream consumers and merchant webhook receivers compare the incoming `state_version` against their locally recorded version; events with `state_version <= current_version` are safely discarded as stale.

### 6.4 Webhook Delivery Engine: HMAC-SHA256, Retries & Dead-Letter Handling
1. **HTTPS Enforcement:** Communications strictly rejects non-HTTPS webhook URLs at subscription time (`http://` rejected; TLS 1.2+ required).
2. **Cryptographic Payload Signing:**
   - Webhook payloads are signed using HMAC-SHA256.
   - Header specification: `X-SwiftDrop-Signature: t=1773000000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08`.
   - The signed data is constructed as: `t + "." + payload_json`.
   - The merchant receiver verifies the signature using their shared secret and rejects webhooks where `abs(current_time - t) > 300 seconds` to protect against replay attacks.
3. **Retry Strategy & Backoff:**
   - **Transient Failures (Retryable):** HTTP 500, 502, 503, 504, connect timeouts, socket read timeouts.
   - **Non-Retryable Failures (Fail Fast):** HTTP 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 410 Gone. These indicate endpoint misconfiguration and fail immediately without wasting compute or network resources.
   - **Backoff Algorithm:** Exponential backoff with jitter:
     $$\text{delay} = \min(\text{max\_backoff}, \text{base\_delay} \times 2^{\text{attempt}} + \text{jitter})$$
4. **Dead-Letter Handling:** After reaching the retry ceiling (e.g., 5 attempts), the webhook delivery transitions to `EXHAUSTED`. An alert or failure metric is incremented for operator and merchant visibility.

### 6.5 SSE Real-Time Fan-Out Topology & Authoritative REST Recovery (Figure 18)
1. **Multi-Replica Scaling Invariant:**
   - In Kafka, consumer instances belonging to the *same* consumer group partition partitions among themselves.
   - If multiple Delivery tracking replicas shared a single consumer group, each replica would consume only a subset of partitions. A browser client connected via SSE to Replica B would **never** receive events processed by Replica A!
   - **Topology Solution (Figure 18):** Each Delivery SSE-serving replica registers an **independent, unique Kafka consumer group** (e.g., `tracking.pod-replica-a`, `tracking.pod-replica-b`). Every replica receives 100% of the topic partitions, ensuring connected browser clients receive live updates regardless of which pod holds their HTTP connection.
2. **Authoritative REST Recovery on Reconnect:**
   - SSE connections over the public internet are inherently subject to disconnects (mobile handovers, Wi-Fi blips).
   - Rather than attempting complex message replay through Kafka offsets for ephemeral browser clients, the React tracking client follows a **canonical REST recovery pattern**:
     - When the SSE connection drops, the browser automatically executes `GET /api/v1/tracking/{trackingNumber}` to fetch authoritative parcel state from PostgreSQL.
     - Upon establishing a fresh SSE stream, it compares incoming `state_version` and drops any older events.

---

## 7. Security, Tenant Isolation & Privacy Boundaries

### 7.1 Merchant Tenant Isolation
- **Accounts Service Tenant Enforcement:** Issue #8 delivered `MerchantTenantAuthorizationProvider` in Accounts Service, which strictly isolates merchant records:
  - Caller Keycloak `sub` is securely mapped to a single `merchant_id`.
  - Merchant A cannot inspect, update, or approve Merchant B's account (`TenantAccessDeniedException` -> RFC 7807 403 Forbidden).
  - Unapproved merchants (`PENDING`, `SUSPENDED`, `REJECTED`) fail closed when attempting operational actions (`MerchantNotApprovedException` -> 403 Forbidden).
- **Communications Tenant Confinement:**
  - Webhook subscription endpoints (`/api/v1/merchants/webhooks/**`) require valid Keycloak bearer tokens.
  - The Communications Service extracts the authenticated caller's tenant identifier and strictly scopes all database queries (`WHERE merchant_id = :authenticatedMerchantId`).
  - Cross-tenant subscription tampering or viewing another merchant's delivery outcomes is strictly prevented.
- **Edge Header Stripping:** Verified in Issue #18: API Gateway strips client-injected identity headers (`X-Merchant-Id`, `X-User-Id`, `X-Roles`, `X-Keycloak-Sub`) to prevent identity spoofing.

### 7.2 Data Minimization & PII Protection in Events
- **No Raw Contact Details in Events:** The Kafka event payload on `parcel.lifecycle.v1` is strictly sanitized per `docs/contracts/KAFKA.md`. It contains:
  ```json
  {
    "eventId": "b3e0c0a8-6f1d-4b8a-9a9d-5f3e2d1c0b9a",
    "eventType": "parcel.status.changed.v1",
    "aggregateId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "aggregateVersion": 3,
    "occurredAt": "2026-10-21T18:30:00Z",
    "correlationId": "c83b1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
    "oldStatus": "PICKED_UP",
    "newStatus": "IN_TRANSIT"
  }
  ```
- **Zero Customer PII:** The payload does **not** contain recipient names, full street addresses, customer phone numbers, proof images, or Keycloak passwords.
- **Permitted Contact Resolution:** When the Communications Service notification worker dispatches an email, it resolves the authorized recipient contact handle via authenticated synchronous gRPC calls to Accounts Service using service-to-service credentials.

### 7.3 Sensitive Secret Protection & Attempt Logging
- **Hashed Secrets:** Webhook shared secrets are stored as secure hashes or managed in an encrypted vault; plaintext secrets are never written to application logs.
- **Sanitized Attempt History:** The `WEBHOOK_ATTEMPTS` table records only operational metadata (`http_status_code`, `response_time_ms`, `error_summary`). Arbitrary HTTP response bodies returned by third-party merchant servers are **not** persisted to prevent cross-site payload injection, database pollution, or accidental storage of sensitive merchant data.

---

## 8. Register of Open Architectural Decisions

The following open decisions must be formally approved by the team leads before implementing the Communications microservice:

| Decision ID | Summary of Open Question | Architectural Options & Trade-Offs | Responsible Owners | Required Sign-Off |
|---|---|---|---|---|
| **`DEC-COM-01`** | **Webhook Retry Schedule & Ceiling** | **Option A:** Fixed schedule (5 attempts: 1m, 5m, 15m, 1h, 4h).<br>**Option B:** Configurable exponential backoff with randomized jitter up to 24h ceiling. | Merchant Pair + Platform | Suthankan1 (@Suthankan1) & Eric (@EricPraveen) |
| **`DEC-COM-02`** | **Webhook Signature Standard & Secret Rotation** | **Option A:** Single active secret per subscription using `X-SwiftDrop-Signature` HMAC-SHA256.<br>**Option B:** Dual secret window (`v1_secret`, `v2_secret`) allowing zero-downtime key rotation. | Merchant Pair + Platform | Suthankan1 (@Suthankan1) |
| **`DEC-COM-03`** | **Webhook Event Subscription Granularity** | **Option A:** Topic-level coarse filter (e.g., all `parcel.status.changed.v1`).<br>**Option B:** Fine-grained event & status filter (e.g., only `DELIVERED` and `FAILED` transitions). | Merchant Pair | Eric (@EricPraveen) & Thuvarahan (@thuvarahan-t) |
| **`DEC-COM-04`** | **Dead-Letter Queue & Failed Job Retention** | **Option A:** Retain exhausted webhook deliveries in PostgreSQL indefinitely for manual replay.<br>**Option B:** Move to dedicated Kafka DLQ (`parcel.webhooks.dlq`) with automated 30-day archival purge. | Platform Lead + Merchant Lead | Suthankan1 (@Suthankan1) |

---

## 9. Report-Ready Diagram Captions & Architectural Rationale

For integration into the November 1 Academic Design Report (Issue #19, led by HarithKeshan), the following formal academic captions and justifications are approved:

### Figure 16: Communications Service Logical Database Schema (ERD)
> **Caption (Figure 16):** *Logical Entity-Relationship Diagram for the Communications Service database (`communications_db`). The schema adheres strictly to microservice data ownership principles, containing exclusively service-owned tables (`WEBHOOK_SUBSCRIPTIONS`, `WEBHOOK_DELIVERIES`, `CONSUMED_EVENTS`, `NOTIFICATIONS`, and child `WEBHOOK_ATTEMPTS`). External references to merchant organizations, parcels, and user subjects are preserved as opaque scalar identifiers (`EXT`), eliminating distributed database foreign keys. Uniqueness constraints on `(subscription_id, event_id)` and `(consumer_name, event_id)` enforce durable inbox de-duplication, preventing duplicate webhook dispatches or notification jobs upon Kafka message re-delivery.*

### Figure 18: Kafka Reliability, Outbox Pattern & SSE Fan-Out Topology
> **Caption (Figure 18):** *Asynchronous event-driven messaging and real-time streaming topology for SwiftDrop. Delivery Operations guarantees reliable publication via the Transactional Outbox pattern, committing parcel updates and outbox events in a single PostgreSQL ACID transaction. An asynchronous relay publishes ordered events to Apache Kafka (`parcel.lifecycle.v1`) partitioned by `parcelId`. Communications Service consumer groups maintain independent durable inboxes for notifications and signed webhooks. Horizontally scaled Delivery SSE tracking pods register unique consumer groups per replica (`tracking.<pod-id>`) to consume all partitions and stream real-time events to connected browser clients, supported by authoritative REST status recovery on reconnection.*

### Figure 11: Merchant Order Creation Sequence Diagram
> **Caption (Figure 11):** *UML sequence diagram illustrating synchronous merchant API order creation. The merchant system submits a secure REST request carrying an API key digest and idempotency key through the API Gateway to Delivery Operations. Delivery validates merchant approval and tenant tenancy via synchronous gRPC to Accounts Service, and validates rate snapshotting via Pricing Service. The parcel aggregate, initial status history, and outbox event are committed in a single local database transaction, returning HTTP 201 Created with tracking credentials while fully decoupling order ingestion from downstream notification processing.*

### Figure 12: Courier Status Transition & Webhook Notification Sequence
> **Caption (Figure 12):** *UML sequence diagram depicting the asynchronous status update and webhook dispatch pipeline. An assigned courier updates delivery state via Gateway to Delivery Operations, which commits the new status and writes a `parcel.status.changed.v1` record to the transactional outbox before returning HTTP 200 OK. The outbox relay streams the event to Kafka KRaft. The Communications webhook consumer group ingests the event into its transactional inbox, schedules a webhook delivery job, and dispatches an HMAC-SHA256 signed HTTPS callback to the merchant endpoint with automated retry handling upon transient network failures.*

### Figure 02: Merchant Bounded Context UML Use Case Diagram
> **Caption (Figure 02):** *UML use case diagram defining merchant actor interactions within SwiftDrop. The merchant user authenticates via Keycloak OIDC, submits an onboarding application, views approval status, manages webhook endpoint subscriptions, and inspects webhook delivery audit logs through the web portal. The external merchant system interfaces directly with the secured order creation API and receives signed HTTPS status webhooks for automated enterprise order fulfillment.*

---

## 10. Acceptance Criteria Checklist & Traceability Matrix

| Issue #10 Acceptance Criterion | Review Finding & Architecture Traceability | Verification Status |
|---|---|---|
| **1. Communications ERD & narrative retain its own inbox/jobs/subscriptions/attempts DB and do not own parcel transitions.** | Figure 16 models `WEBHOOK_SUBSCRIPTIONS`, `WEBHOOK_DELIVERIES`, `CONSUMED_EVENTS`, and `NOTIFICATIONS` in an exclusive `communications_db`. Delivery Operations retains sole ownership of parcel state and transitions. Zero cross-service SQL or foreign keys. | **VERIFIED PASS** (Section 3 & 4) |
| **2. Kafka facts are at-least-once with event-ID dedupe and per-parcel ordering; no claim of exactly-once external side effects.** | Documented in Section 6.2 & 6.3: Kafka KRaft delivers at-least-once semantics. Inbox de-duplication enforced via `PK (consumer_name, event_id)` and `UK (subscription_id, event_id)`. Partition key `parcelId` guarantees strict per-parcel ordering. Universal exactly-once external side-effects explicitly rejected; merchant receiver deduplication required. | **VERIFIED PASS** (Section 6) |
| **3. Webhook contract explains HMAC, event/aggregate version, HTTPS, bounded timeout/retry/failure inspection; exact retry limits remain decisions until approved.** | Documented in Section 6.4: Webhook signing specified via `X-SwiftDrop-Signature` HMAC-SHA256 with timestamp window `t`. Mandatory HTTPS transport. Bounded exponential backoff with jitter. Status inspection via `WEBHOOK_DELIVERIES` and `WEBHOOK_ATTEMPTS`. Parameterized retry limits recorded as open decision `DEC-COM-01`. | **VERIFIED PASS** (Section 6.4 & 8) |
| **4. SSE fanout and REST recovery rationale is correct; one consumer group cannot be misrepresented as broadcast to every replica.** | Documented in Section 6.5 & Figure 18: Explained that a shared consumer group would load-balance partitions across pods and starve clients. Figure 18 requires unique consumer group per Delivery tracking replica (`tracking.<pod-id>`). Browser reconnect recovery relies on authoritative REST `GET /api/v1/tracking/{number}`. | **VERIFIED PASS** (Section 6.5) |
| **5. Figures/text are reviewed and clearly marked target design; no runtime Kafka/webhook completion claim appears in the Nov 1 report.** | Figures 02, 11, 12, 16, and 18 reviewed and confirmed consistent. Labeled unambiguously as **target design / planned architecture**. Distinction between implemented working login vertical slice and planned messaging/webhook architecture explicitly maintained. | **VERIFIED PASS** (Section 1.1, 9 & 11) |

---

## 11. Cross-Team Peer Review & Sign-Off Handoff

**Architectural Review Completed By:**  
Thuvarahan (@thuvarahan-t)  
Merchant Lead, SwiftDrop Group Project  
Date: 2026-10-21  

**Formal Review Requests Submitted To:**
- **Vibhushan (@Vibhushan-2003)** — For Delivery outbox publication, status sequence 12, and SSE replica consumer group topology.
- **Suthankan1 (@Suthankan1)** — For Kafka KRaft contracts, HMAC security standard, and Communications webhook retry engine bounded slice.
- **Eric (@EricPraveen)** — For Merchant onboarding, order API sequence 11, and webhook subscription configuration UX.
- **HarithKeshan (@Harithkeshan)** — For Academic Report (Issue #19) caption and diagram integration.
