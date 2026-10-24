# Architectural & Provider Contract Review: Delivery Transactional Outbox and Bounded Kafka Relay (Issue #31 / LATER-13)

**Task:** Issue #31 (`[LATER-13] Implement Delivery transactional outbox and bounded Kafka relay`)  
**Lead Owner / Contributor:** Vibhushan (@Vibhushan-2003) — Courier Lead & Delivery Outbox Specialist  
**Cross-Team Stakeholders & Reviewers:**  
- **Sinthuha (@Sinthuha-n)** — Delivery Transaction & Booking Ownership (Issue #20 Customer Booking Lead)  
- **Thuvarahan (@thuvarahan-t)** — Kafka Event Consumer Contract (Issue #32 Communications Inbox Lead)  
- **Suthankan1 (@Suthankan1)** — Platform Lead (Kafka provisioning, orchestration & integration review)  
- **HarithKeshan (@Harithkeshan)** — Dispatcher/Admin Lead (Assignment & transition boundary reviewer)  
- **Abhinaya (@abhi190503)** — Courier Role & Proof Policy Reviewer  

**Target Integration Branch:** `test`  
**Preparation Branch:** `docs/issue-31-outbox-contract-preparation`  
**Contract Baseline Reference:**  
- `contracts/events/parcel.status.changed.v1.schema.json` (v1.0.0, PR #101 / Issue #88 frozen baseline)  
- `docs/contracts/KAFKA.md` (Kafka domain events proposed v1 contract)  
- `docs/contracts/SERVICE_INTERFACE_MATRIX.md` (`IF-EVT-01`)  
- `docs/architecture/RUNTIME_DECISION_REGISTER.md` (Sections 3 & 6)  
- `docs/architecture/DATA_OWNERSHIP.md`  

**Status:** **INDEPENDENT CONTRACT & ARCHITECTURAL PREPARATION COMPLETE — AWAITING UPSTREAM ISSUE #23 DOMAIN PROVIDER**

---

## 1. Executive Summary & Purpose

This document establishes the authoritative, independent architectural design, contract review, and database migration specification for **Issue #31: Delivery Transactional Outbox and Bounded Kafka Relay**.

Per SwiftDrop repository discipline (`AGENTS.md`) and the master implementation instructions for Issue #31:
1. **Dependency Gate Verification:** When an upstream domain provider (specifically **Issue #23: Guarded parcel status/history and failure slice**) is not yet implemented in executable code, an engineer **must NOT** invent competing domain aggregates, fake parcel mutation shortcuts, or speculative state machine implementations inside Issue #31.
2. **Strict Stop Condition:** Full integrated implementation is stopped. The engineer must deliver independent, high-value preparation artifacts: contract validation, schema invariant proofs, outbox persistence models, relay concurrency algorithms, and precise handoff specifications.
3. **Branch Hygiene:** Work is committed to the preparation branch `docs/issue-31-outbox-contract-preparation` targeting `test`, without claiming that integrated delivery or Issue #31 is finished.

---

## 2. Preimplementation Dependency Gate Evaluation

A rigorous audit of the current GitHub issue states, merged pull requests, and the active codebase on `origin/test` was conducted:

| Issue / Prerequisite | Title & Scope | GitHub State | Actual Executable Code Evidence | Gate Outcome |
|---|---|---|---|---|
| **#19** | Nov 1 Report & Login Baseline | **CLOSED** | Merged in PR #87 & #89; login and role landings verified | **PASS** |
| **#88 (N10-P01)** | Runtime Contract Freeze | **CLOSED** | Contracts merged in PR #101 (`parcel.status.changed.v1.schema.json`); runtime decisions recorded | **PASS (CONTRACT FROZEN)** |
| **#91 (N10-P13)** | Kafka & Business DB Prerequisites | **CLOSED** | Provisioning scripts merged in PR #104 (`docker-compose.dependencies.yml`, `init-kafka.sh`, `01-init-business-databases.sh`). Broker runtime not running locally (Docker engine stopped). | **PASS (INFRA SPECIFIED)** |
| **#23 (LATER-06)** | Guarded Parcel Status/History Provider | **OPEN** | Assigned to Vibhushan (@Vibhushan-2003); scheduled Oct 28 – Nov 2, 2026. **Zero code in `services/delivery-service/`** (only `README.md` placeholder exists). No `Parcel` entity, repository, migrations, or state transition service. | **BLOCKED (UPSTREAM PROVIDER UNAVAILABLE)** |
| **#20 (LATER-05)** | Delivery Booking Foundation | **OPEN** | Assigned to Sinthuha (@Sinthuha-n); scheduled Oct 27 – Oct 30, 2026. Unstarted. | **INFORMATIONAL (INDEPENDENT)** |
| **#24 (LATER-08)** | Single Active Assignment Command | **OPEN** | Assigned to HarithKeshan (@Harithkeshan); scheduled Nov 1 – Nov 2, 2026. Unstarted. | **INFORMATIONAL (INDEPENDENT)** |
| **#32 (LATER-14)** | Communications Consumer & Inbox | **OPEN** | Assigned to Thuvarahan (@thuvarahan-t); scheduled Nov 3 – Nov 5, 2026. Downstream consumer of Issue #31 outbox. | **DOWNSTREAM CONSUMER** |

### Dependency Gate Conclusion
**GATE STATUS: PARTIALLY READY / BLOCKED FOR INTEGRATED IMPLEMENTATION.**
- Contract artifacts, schema definitions, and platform infrastructure specifications are **READY**.
- Upstream domain provider **Issue #23** is **NOT READY**.
- In accordance with Section 4 of the Implementation Prompt, **full integrated coding is paused**, and independent contract, persistence, relay, and test preparation is completed.

---

## 3. Authoritative Kafka Event Contract Specification

The outbox relay publishes domain events conforming strictly to the frozen schema in `contracts/events/parcel.status.changed.v1.schema.json` (`IF-EVT-01`).

### 3.1 Pinned Metadata & Topic Configuration
- **Kafka Topic:** `parcel.lifecycle.v1`
- **Dead Letter Topic (DLT):** `parcel.lifecycle.v1.dlt`
- **Partitions:** 3 (provisioned in `init-kafka.sh`)
- **Replication Factor:** 1 (local dev) / 3 (production cluster)
- **Producer Service:** `delivery-operations` (`services/delivery-service`)
- **Consumer Group(s):**
  - `communications-notification-group` (Communications Service email/SMS worker)
  - `communications-webhook-group` (Communications Service webhook dispatcher)
  - `delivery-tracking-sse-<podId>` (Delivery horizontal SSE broadcasting instances)

### 3.2 Partition Key Contract & FIFO Guarantee
- **Partition Key:** Strictly the parcel UUID (`payload.parcelId` / `aggregateId`).
- **Ordering Guarantee:** Strict per-parcel FIFO order across all transitions of a single parcel (`CREATED` -> `PICKED_UP` -> `IN_TRANSIT` -> `OUT_FOR_DELIVERY` -> `DELIVERED`).
- **Global Ordering:** No total ordering across different parcels is provided or claimed; Kafka partitioning guarantees order only within the same partition key.

### 3.3 Event Envelope Structure
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "eventType": "parcel.status.changed.v1",
  "schemaVersion": "1.0.0",
  "aggregateId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "aggregateType": "PARCEL",
  "aggregateVersion": 2,
  "occurredAt": "2026-10-24T18:00:00Z",
  "correlationId": "corr-req-98765",
  "producer": "delivery-operations",
  "payload": {
    "parcelId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
    "trackingNumber": "SWD-20261023-A1B2",
    "oldStatus": "CREATED",
    "newStatus": "PICKED_UP",
    "reason": null,
    "merchantId": "8b3f1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
    "courierSub": "3c9a1f24-5d6e-4a8b-9c1d-7e8f0a2b4c6d",
    "cityOrZone": "Colombo 03"
  }
}
```

### 3.4 Strict Privacy & Zero-PII Policy (`DEC-EVT-03`)
In accordance with `docs/architecture/RUNTIME_DECISION_REGISTER.md` and repository privacy rules:
- **Zero Customer Contact Data:** No recipient phone numbers (`recipientPhone`), customer emails (`recipientEmail`), or plain text names are permitted in event payloads.
- **Zero Street Addresses:** No house numbers, street names, or detailed destination coordinates are published. Only coarse geographic hub identifiers (`cityOrZone`, e.g., `"Colombo 03"`) are permitted for public tracking UI display.
- **Zero Credentials:** No Bearer tokens, API keys, basic auth strings, or pre-signed S3 proof URLs are permitted.
- **Contact Resolution Boundary:** Downstream notification workers resolve customer email and phone by calling Accounts Service gRPC endpoint `ResolveRecipientContact` (`IF-GRPC-03`) using internal service JWTs.
- **Schema Enforcement:** Both envelope and payload define `"additionalProperties": false`. Any unexpected property injection causes immediate validation failure.

---

## 4. Delivery-Owned Outbox Database Schema & Migration Plan

The outbox table resides exclusively within `delivery_db`, owned by the `delivery_app` role. No other service has read or write access to this database.

### 4.1 Flyway Migration Specification
- **Target File:** `services/delivery-service/src/main/resources/db/migration/V2__create_delivery_outbox_events.sql`  
  *(Note: V1 will establish the canonical `parcels` and `status_history` tables under Issue #23 / #20)*

```sql
-- ==============================================================================
-- SwiftDrop Delivery Operations: Transactional Outbox Persistence (Issue #31)
-- Database: delivery_db
-- Owner: delivery_app
-- ==============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'PARCEL',
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    acknowledged_at TIMESTAMPTZ,
    topic VARCHAR(128) NOT NULL DEFAULT 'parcel.lifecycle.v1',
    partition_key VARCHAR(64) NOT NULL,
    CONSTRAINT chk_outbox_status CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')
    )
);

-- Partial index for high-throughput, bounded pending event polling
CREATE INDEX IF NOT EXISTS idx_outbox_pending_poll 
ON outbox_events (status, next_attempt_at, created_at) 
WHERE status IN ('PENDING', 'FAILED');

-- Index for aggregate audit and causal version lookup
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_version 
ON outbox_events (aggregate_id, aggregate_version);
```

### 4.2 State Machine for Outbox Records
- **`PENDING`:** Event successfully committed inside the business transaction; eligible for relay polling.
- **`IN_FLIGHT`:** Event claimed by a relay worker with an active lease; prevents duplicate pickup by concurrent relay threads.
- **`PUBLISHED`:** Broker acknowledgement received (`RecordMetadata` confirmed); terminal success state. Kept for audit / retention window (30 days per `DEC-RET-01`).
- **`FAILED`:** Transient broker or network error encountered; `attempt_count` incremented, `next_attempt_at` backed off with exponential jitter.
- **`DEAD_LETTER`:** Exceeded maximum retry ceiling (10 attempts) or unrecoverable payload serialization error; requires operator inspection.

---

## 5. Atomic Business Transaction Integration Architecture

The central acceptance criterion of Issue #31 is:
> **Parcel state, status history, and matching outbox event MUST be written in ONE LOCAL DATABASE TRANSACTION.**

### 5.1 Integration Boundary with Issue #23
When Issue #23 is implemented, the entry point for parcel status transitions is the `ParcelStateTransitionService`. The transactional boundary is structured as follows:

```java
@Service
public class ParcelStateTransitionService {

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;

    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Parcel transitionStatus(UUID parcelId, ParcelStatus targetStatus, String reason, String actorSub) {
        // 1. Load Aggregate & Enforce Optimistic Concurrency Guard
        Parcel parcel = parcelRepository.findByIdWithLock(parcelId)
            .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        ParcelStatus currentStatus = parcel.getStatus();
        
        // Validate lifecycle transition rules per Section 3 of Runtime Decisions
        LifecycleGuards.assertValidTransition(currentStatus, targetStatus, reason);

        // 2. Mutate Canonical State & Increment Aggregate Version
        parcel.transitionTo(targetStatus);
        parcelRepository.save(parcel);

        // 3. Persist Append-Only Status History Record
        StatusHistory history = new StatusHistory(
            UUID.randomUUID(),
            parcel.getId(),
            currentStatus,
            targetStatus,
            reason,
            actorSub,
            Instant.now()
        );
        statusHistoryRepository.save(history);

        // 4. Construct & Persist Matching Outbox Event (Atomic in same DB transaction)
        OutboxEvent outboxEvent = outboxEventFactory.createParcelStatusChangedEvent(
            parcel,
            currentStatus,
            targetStatus,
            reason,
            actorSub
        );
        outboxEventRepository.save(outboxEvent);

        // 5. Commit Transaction
        // ZERO Kafka network calls are made here. If the DB fails, all three roll back.
        // If the DB commits, all three records are guaranteed durable.
        return parcel;
    }
}
```

### 5.2 Transactional Invariants
1. **No Distributed Transactions (XA):** Two-Phase Commit across PostgreSQL and Kafka is explicitly avoided. Reliability is achieved via the local database Transactional Outbox pattern.
2. **Zero In-Transaction Kafka I/O:** Calling `KafkaTemplate.send()` inside the `@Transactional` method is strictly prohibited. Network latency or Kafka broker disconnects must never block or roll back the local database commit.
3. **Idempotency Protection:** Idempotent replays of the same command (e.g., repeating an already applied status) do not increment aggregate version and do not generate duplicate outbox events.

---

## 6. Bounded Asynchronous Kafka Relay Engine Design

The background relay is a Delivery-owned scheduled component executing bounded polling cycles.

### 6.1 Relay Polling & Concurrency Algorithm
To support multi-instance Delivery deployments without duplicate claims or deadlocks, the relay uses PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`:

```sql
SELECT event_id, aggregate_id, aggregate_version, event_type, schema_version, payload, topic, partition_key, attempt_count
FROM outbox_events
WHERE status IN ('PENDING', 'FAILED')
  AND next_attempt_at <= NOW()
ORDER BY created_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

### 6.2 Short-Lived Claim Leases
1. A database transaction claims a batch of up to `batchSize = 50` events.
2. The worker marks them as `status = 'IN_FLIGHT'` with a lease timestamp (`last_attempt_at = NOW()`, `next_attempt_at = NOW() + INTERVAL '30 SECONDS'`).
3. The claim transaction commits immediately, releasing row locks in PostgreSQL. Network I/O to Kafka occurs **after** the claim transaction has released database locks.
4. If a relay worker process crashes mid-flight, the lease expires after 30 seconds, automatically returning the event to eligible polling.

### 6.3 Producer Reliability & Acknowledgement Handling
- **Spring Kafka Configuration:**
  - `acks = "all"` (all in-sync replicas must acknowledge receipt)
  - `enable.idempotence = true` (prevents producer-side duplicate writes on TCP retry)
  - `retries = 3`
  - `max.in.flight.requests.per.connection = 1` (strict FIFO preservation)
  - `delivery.timeout.ms = 10000`

- **Asynchronous Completion Callback:**
  ```java
  CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
      event.getTopic(),
      event.getPartitionKey(),
      event.getPayloadJson()
  );

  future.whenComplete((result, ex) -> {
      if (ex == null) {
          // Broker Acknowledged: Update outbox to PUBLISHED
          outboxService.markPublished(event.getEventId(), Instant.now());
      } else {
          // Broker Error: Apply exponential backoff with jitter
          outboxService.handlePublishFailure(event.getEventId(), ex.getMessage(), event.getAttemptCount());
      }
  });
  ```

### 6.4 Backoff & Poison-Message Handling Policy
- **Backoff Algorithm:** Exponential backoff with jitter:  
  `backoffSeconds = min(300, 2^(attemptCount) + randomJitter(1..5))`
- **Poison Message Ceiling:** If `attempt_count >= 10`, the event transitions to `DEAD_LETTER`.
- **Observable Logging:** Failure events log sanitized error messages (excluding payloads) and increment Prometheus metric `delivery_outbox_dead_letter_total`.

---

## 7. Delivery Semantics & Downstream Deduplication Contract

| Semantic Dimension | Delivery Platform Guarantee | Architectural Implementation |
|---|---|---|
| **Publishing Guarantee** | **At-Least-Once** | Guaranteed via durable outbox rows and broker retries. Exactly-once publishing across crash windows is impossible without distributed locks; duplicate events may be published if relay crashes after broker ack but before DB ack. |
| **Downstream Deduplication** | **Consumer-Side Idempotent Inbox** | Consumers (Communications Service Issue #32) enforce deduplication using unique constraint `PRIMARY KEY (consumer_name, event_id)` in their `inbox_events` table. |
| **Ordering Guarantee** | **Strict FIFO Per Parcel** | Partition key is `parcelId`. All events for a parcel map to the same Kafka partition. |
| **Staleness Rejection** | **Aggregate Version Guard** | Consumers reject events where `event.aggregateVersion <= currentKnownVersion`, preventing out-of-order state overwrites. |

---

## 8. Automated Test Suite & Verification Evidence

An automated, machine-readable contract validation test suite has been implemented in:
`tests/contracts/test_outbox_contract.py`

### 8.1 Test Results Summary
Executed on branch `docs/issue-31-outbox-contract-preparation`:
```
================================================================================
      SWIFTDROP ISSUE #31: DELIVERY OUTBOX EVENT CONTRACT TEST SUITE         
================================================================================

[TEST GROUP 1] Approved Lifecycle Transition Payloads
  [PASS] Initial CREATED transition (oldStatus=null, newStatus=CREATED)
  [PASS] Transition CREATED -> PICKED_UP (version=2)
  [PASS] Transition PICKED_UP -> IN_TRANSIT (version=3)
  [PASS] Transition IN_TRANSIT -> OUT_FOR_DELIVERY (version=4)
  [PASS] Terminal transition OUT_FOR_DELIVERY -> DELIVERED (version=5)
  [PASS] Terminal transition OUT_FOR_DELIVERY -> FAILED (reason=RECIPIENT_UNAVAILABLE)
  [PASS] Pre-pickup cancellation CREATED -> FAILED (reason=CANCELLED_BY_SENDER)

[TEST GROUP 2] Privacy & Zero Customer PII Invariant (Negative Tests)
  [PASS] Reject customer phone number in payload
  [PASS] Reject customer email address in payload
  [PASS] Reject street-level address in payload
  [PASS] Reject security bearer token in payload
  [PASS] Reject unapproved root envelope fields (additionalProperties: false)

[TEST GROUP 3] Envelope Invariants & Partition Key Contract
  [PASS] Reject event with missing aggregateVersion
  [PASS] Reject non-positive aggregateVersion (minimum: 1)
  [PASS] Reject conflicting eventType (must match const parcel.status.changed.v1)
  [PASS] Partition key alignment: aggregateId strictly equals payload.parcelId (UUID)

[TEST GROUP 4] Outbox Relational Database Schema Verification
  [PASS] Outbox Flyway DDL specifications verified: UUID PK, JSONB payload, and bounded partial polling index present.

================================================================================
SUMMARY: 17 PASSED, 0 FAILED
================================================================================
```

In addition, global contract validation passed across all modes:
```
python tests/contracts/validate_contracts.py --mode all
[GATE 1] OpenAPI 3.0.3 valid across 5 specifications.
[GATE 2] GraphQL SDL valid (7 Query fields). Read-only invariant enforced.
[GATE 3] Kafka Domain Events valid (5 negative tests rejected as expected).
[GATE 4] Protobuf gate inspected (specification frozen in README.md).
RESULT: ALL CONTRACT VALIDATIONS PASSED.
```

---

## 9. Blockers & Provider Handoff Record for Issue #31

Per SwiftDrop repository discipline, the exact state of Issue #31 is recorded:

1. **Active Blocker:** Upstream provider **Issue #23 (`[LATER-06] Implement guarded parcel status/history and failure slice`)** is currently in **OPEN / To Do** status, scheduled from October 28 to November 2, 2026.
2. **Missing Provider Artifacts:**
   - `services/delivery-service` Spring Boot project setup (currently contains only placeholder `README.md`)
   - `Parcel` JPA entity and aggregate root with `@Version` optimistic locking
   - `ParcelRepository` and `StatusHistoryRepository`
   - `ParcelStateTransitionService` defining the transactional status update method
   - Flyway migration `V1` establishing `parcels` and `status_history` tables
3. **Execution Plan Upon Provider Availability:**
   - As soon as Issue #23 merges `ParcelStateTransitionService` and initial migrations into `test`, Issue #31 will branch from updated `test` onto `feature/issue-31-delivery-outbox-relay`.
   - Issue #31 will introduce `V2__create_delivery_outbox_events.sql`, `OutboxEvent` entity, `OutboxEventRepository`, integrate the 3-way atomic transaction inside `ParcelStateTransitionService`, and implement `BoundedKafkaRelayScheduler`.
   - Live integration tests against the KRaft Kafka container (`platform/dependencies/docker-compose.dependencies.yml`) will verify publication, at-least-once duplication handling, and crash recovery.

---

## 10. Peer Review & Handoff Signoff Checklist

| Reviewer | Area of Responsibility | Status |
|---|---|---|
| **Sinthuha (@Sinthuha-n)** | Delivery Transaction & Booking Ownership (#20) | Awaiting provider delivery (#23) |
| **Thuvarahan (@thuvarahan-t)** | Kafka Event Consumer & Inbox Compatibility (#32) | Contract aligned (`parcel.status.changed.v1`) |
| **Suthankan1 (@Suthankan1)** | Platform Infrastructure & Kafka KRaft Prerequisites (#91) | Infrastructure specified in PR #104 |
| **Vibhushan (@Vibhushan-2003)** | Issue #31 & #23 Delivery Lead | Preparation complete; ready to implement upon #23 |
