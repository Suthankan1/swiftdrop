# Delivery Operations Domain Architecture Review — ERD, Lifecycle & Service Invariants

**Milestone:** November 1, 2026 — Design Report & Working Logins Checkpoint  
**Task:** Issue #12 (`[NOV-15] Review Delivery ERD, lifecycle and shared-service invariants`)  
**Lead Architectural Reviewer:** Vibhushan (@Vibhushan-2003), Courier Lead & Security QA Lead  
**Required Cross-Team Reviewers:**  
- Sinthuha (@Sinthuha-n) — Customer Lead (Booking, Customer Profile & Data References)  
- Pushmitha (@pushmitha20) — Dispatcher Lead (Courier Assignment & Workload Scheduling)  
- Thuvarahan (@thuvarahan-t) — Merchant Lead (Event Boundaries & Webhook Contracts)  
- HarithKeshan (@Harithkeshan) — Dispatcher/Admin Lead & Report Assembly Lead (Issue #19)  
**Target Integration Branch:** `test`  
**Feature Branch:** `docs/issue-12-delivery-architecture-review`  
**Status:** **AUTHOR ARCHITECTURAL REVIEW COMPLETE — PENDING TEAM SIGNOFF**

---

## 1. Scope, Context & Review Boundary

This document delivers a formal, independent architectural and database design review of the **Delivery Operations** bounded context for the SwiftDrop enterprise parcel delivery system (Architecture Baseline v1.3). 

### Deliverable Context
- **Design Review Only:** This task is strictly a **documentation, database design, state machine, and concurrency analysis** deliverable for the November 1 Academic Design Report (Issue #19).
- **Zero Generated Code:** No Spring Boot application scaffolds, JPA entity classes, Flyway migration SQL scripts, Kafka broker topologies, or gRPC server stubs are created in this task.
- **Honest Status Separation:** The operational login vertical slice (React SPA, API Gateway, Keycloak OIDC, and Accounts business profiles) has been implemented and independently verified under Issues #1, #2, #3, #5, #6, #36, #37, and #18. Delivery Operations persistence, Kafka event relays, transactional outboxes, and SSE streams are **PLANNED TARGET ARCHITECTURE** and must be reported as such in the design report.

---

## 2. Documents & Diagram Sources Reviewed

The architectural review was conducted against the official repository artifacts:

1. **Architecture & Governance:**
   - [`docs/architecture/BASELINE.md`](BASELINE.md) (Architecture baseline v1.3)
   - [`docs/architecture/DATA_OWNERSHIP.md`](DATA_OWNERSHIP.md) (Service boundaries and logical entities)
   - [`docs/architecture/OPEN_DECISIONS.md`](OPEN_DECISIONS.md) (Open architectural decisions register)
   - [`docs/contracts/REST.md`](../contracts/REST.md) (Proposed external REST contract outline)
   - [`docs/team/OWNERSHIP.md`](../team/OWNERSHIP.md) (Nine-member team allocation and module handoff rules)
   - [`docs/milestones/NOV_01.md`](../milestones/NOV_01.md) (Milestone scope and deliverables)
   - [`docs/milestones/NOV13_COURIER_REVIEW.md`](../milestones/NOV13_COURIER_REVIEW.md) (Courier use cases, status flows, and proof policy review)
2. **Primary Diagrams Reviewed:**
   - **Figure 14:** Delivery Operations Logical ERD (`docs/diagrams/editable/14_delivery_erd.drawio`, `docs/diagrams/rendered/14_delivery_erd.svg`)
   - **Figure 17:** Parcel Lifecycle State Machine (`docs/diagrams/editable/17_parcel_state_machine.drawio`, `docs/diagrams/rendered/17_parcel_state_machine.svg`)
3. **Cross-Referenced Diagrams:**
   - **Figure 08:** Dispatcher Courier Assignment Activity (`docs/diagrams/rendered/08_assignment_activity.svg`)
   - **Figure 18:** Kafka Reliability & SSE Fan-Out Topology (`docs/diagrams/rendered/18_kafka_sse_topology.svg`)
   - **Figure 06:** Logical Container Architecture (`docs/diagrams/rendered/06_logical_architecture.svg`)

---

## 3. Delivery Domain Service & Data Ownership Matrix

Per the SwiftDrop v1.3 data boundary rules, **Delivery Operations** is an independently deployable Spring Boot microservice with an exclusive, dedicated PostgreSQL database (`delivery_db`).

```text
+--------------------------------------------------------------------------------------------------+
|                                    DELIVERY OPERATIONS DOMAIN                                    |
|                                                                                                  |
|  [PARCELS] (Aggregate Root)                                                                      |
|     |-- 1:0..* --> [DELIVERY_ASSIGNMENTS]   (Strictly ONE active assignment per parcel)          |
|     |-- 1:1..* --> [STATUS_HISTORY]         (Append-only chronological audit log)                |
|     |-- 1:0..* --> [DELIVERY_PROOFS]        (Opaque object storage keys; no public URLs)         |
|     |-- 1:0..* --> [OUTBOX_EVENTS]          (Atomic event record for reliable Kafka relay)       |
|     +-- 1:0..* --> [IDEMPOTENCY_RECORDS]    (Scoped by tenant/principal and idempotency key)     |
+--------------------------------------------------------------------------------------------------+
       |                                      |                                    |
       | Opaque String (Keycloak sub)         | Opaque UUID (No FK)                | Opaque UUID (No FK)
       v                                      v                                    v
  [Accounts Service]                   [Accounts Service]                   [Pricing Service]
  (Customer / Courier)                 (Approved Merchant)                  (Snapshotted Quote)
```

### Data Boundary Invariants:
1. **Exclusive Persistence Ownership:** Delivery Operations is the sole reader and writer of `delivery_db`. No other service (Accounts, Pricing, Communications, Gateway, or GraphQL) possesses database credentials or direct network access to this schema.
2. **Opaque External Identifiers (`EXT`):** References to external entities are stored as raw scalar identifiers without database-level foreign key constraints:
   - `created_by_sub` & `courier_sub`: Raw string subjects issued by Keycloak.
   - `merchant_id`: Opaque UUID string referencing an approved merchant organization in Accounts.
   - `quote_id`: Opaque UUID string referencing the accepted quote in Pricing & Zones.
3. **No Cross-Service Joins:** Delivery never joins against `accounts_db` or `pricing_db`. Information needed across boundaries is resolved synchronously via authorized gRPC calls or ingested asynchronously via Kafka.
4. **Denormalized Snapshotting:** Pricing and zone calculations belong exclusively to the Pricing & Zones service. When a parcel is booked, Delivery snapshots the accepted fee, currency, and rate version. Delivery never recalculates prices or modifies snapshotted financial terms.

---

## 4. Figure 14: Delivery Operations Logical ERD Review

Figure 14 models the relational entities required to support booking, assignment, transition audit, proof capture, and transactional outbox publication.

### 4.1 Detailed Entity Assessment

| Entity | Primary Key | Key Attributes | Foreign Key References | Architectural Assessment & Findings |
|---|---|---|---|---|
| **`PARCELS`** | `parcel_id : UUID` | `tracking_number : VARCHAR (UK)`<br>`created_by_sub : VARCHAR (EXT)`<br>`merchant_id : UUID (EXT, nullable)`<br>`quote_id : UUID (EXT)`<br>`rate_version : VARCHAR (snapshot)`<br>`pickup_address : JSONB/TEXT`<br>`dropoff_address : JSONB/TEXT`<br>`weight_kg : DECIMAL`<br>`dimensions : VARCHAR`<br>`amount : DECIMAL (snapshot)`<br>`currency : CHAR(3)`<br>`current_status : VARCHAR`<br>`version : BIGINT`<br>`created_at : TIMESTAMP` | Local Root Entity | **Aggregate Root:** Correctly acts as the consistency boundary. The `version` attribute provides optimistic locking for concurrent status updates and assignments. `current_status` denormalizes the latest state for fast indexed queries. Fee snapshotting is strictly preserved. |
| **`DELIVERY_ASSIGNMENTS`** | `assignment_id : UUID` | `courier_sub : VARCHAR (EXT)`<br>`active : BOOLEAN`<br>`assigned_at : TIMESTAMP`<br>`assigned_by : VARCHAR (EXT)` | `parcel_id -> PARCELS.parcel_id` | **Assignment Relation:** Correctly separated from the parcel entity. An unassigned parcel has zero active assignment rows. Re-assignment deactivates prior rows without data loss. Requires a partial unique index in PostgreSQL: `UNIQUE (parcel_id) WHERE (active = true)`. |
| **`STATUS_HISTORY`** | `history_id : UUID` | `status : VARCHAR`<br>`occurred_at : TIMESTAMP`<br>`state_version : INT`<br>`actor_sub : VARCHAR (EXT)`<br>`note : TEXT (optional)`<br>`failure_reason : TEXT (nullable)` | `parcel_id -> PARCELS.parcel_id` | **Append-Only Audit Log:** Immutable chronological event store. `UK (parcel_id, state_version)` enforces monotonic state versioning and prevents race conditions or duplicated transition history. |
| **`DELIVERY_PROOFS`** | `proof_id : UUID` | `recipient_name : VARCHAR (nullable)`<br>`delivery_note : TEXT (nullable)`<br>`photo_object_key : VARCHAR`<br>`uploaded_by_sub : VARCHAR (EXT)`<br>`uploaded_at : TIMESTAMP` | `parcel_id -> PARCELS.parcel_id` | **Proof Metadata:** Stores private S3/MinIO object keys (`photo_object_key`), NOT public HTTP URLs. Multiplicity `1 : 0..*` correctly supports multiple evidence artifacts (e.g., recipient signature + parcel dropoff photo). |
| **`OUTBOX_EVENTS`** | `event_id : UUID` | `type : VARCHAR`<br>`version : INT`<br>`payload : JSONB`<br>`topic : VARCHAR`<br>`created_at : TIMESTAMP`<br>`published_at : TIMESTAMP (nullable)` | `parcel_id -> PARCELS.parcel_id` | **Transactional Outbox:** Guaranteed at-least-once asynchronous domain event publication. Committed atomically in the same local database transaction as parcel mutations. Topic pinned to `parcel.lifecycle.v1`. |
| **`IDEMPOTENCY_RECORDS`** | `record_id : UUID` | `principal_or_tenant : VARCHAR`<br>`key : VARCHAR`<br>`digest : VARCHAR(64)`<br>`stored_result : JSONB`<br>`expires_at : TIMESTAMP` | `parcel_id -> PARCELS.parcel_id (nullable)` | **API De-duplication:** Enforces `UK (principal_or_tenant, key)`. Guarantees safe network retries on REST booking and courier updates without creating duplicate deliveries or transitions. |

### 4.2 Figure 14 Discrepancy & Enhancement Findings:
1. **Active Assignment Constraint Implementation:** The ERD note states `UK active assignment per parcel`. In standard relational SQL, a standard unique constraint across `(parcel_id, active)` permits only one `true` and one `false` row. The architecture report must explicitly document the required implementation: **PostgreSQL Partial Unique Index** (`CREATE UNIQUE INDEX idx_unique_active_assignment ON delivery_assignments (parcel_id) WHERE active = true;`).
2. **Actor Sub Identification:** The `STATUS_HISTORY` entity records `actor_sub`. To ensure forensic auditability across both human and automated actions, `actor_sub` must accommodate Dispatcher assignments, Courier updates, and system timeout cancellations.
3. **Sanitized Outbox Payload:** Outbox payloads in `OUTBOX_EVENTS.payload` must strictly contain public/operational event descriptors (`parcelId`, `trackingNumber`, `status`, `timestamp`) and redact sensitive customer PII (e.g., recipient phone numbers, billing details).

---

## 5. Figure 17: Parcel Lifecycle State Machine Review

Figure 17 defines the authoritative state machine enforced by Delivery Operations.

```text
       +-----------------------------------------------------------------------+
       |                                                                       |
       v                                                                       |
   (Initial)                                                                   |
       |                                                                       |
       v                                                                       |
  [ CREATED ] -------------------------------------------------------------+   |
       |                                                                   |   |
    (Pickup)                                                               |   |
       v                                                                   |   |
 [ PICKED_UP ] --------------------+                                       |   |
       |                           |                                       |   |
    (Depart)                       |                                       |   |
       v                           |                                       |   |
 [ IN_TRANSIT ] ---------------+   | (recordFailure)                       |   |
       |                       |   | [Assigned Courier + Valid Stage]      |   |
  (Last mile)                  |   | Append history + outbox atomically    |   |
       v                       v   v                                       v   v
[ OUT_FOR_DELIVERY ] -----> [ FAILED ]                             [ CANCELLED / FAILED ]
       |                       |                                   (Proposed Pre-Pickup Policy)
   (Complete)                  v                                   (Open Decision DEC-DEL-01)
 [Proof Valid]             (( Final ))
       v
 [ DELIVERED ]
       |
       v
  (( Final ))
```

### 5.1 State Machine Invariants & Validation

1. **Sequential Forward Flow:**
   - `CREATED -> PICKED_UP`: Triggered when the assigned courier collects the parcel from sender.
   - `PICKED_UP -> IN_TRANSIT`: Triggered when parcel departs local facility/hub.
   - `IN_TRANSIT -> OUT_FOR_DELIVERY`: Triggered when loaded for final destination delivery.
   - `OUT_FOR_DELIVERY -> DELIVERED`: Terminal completion. Requires valid proof metadata.
2. **Strict Terminal States:**
   - Both `DELIVERED` and `FAILED` are **terminal** pseudo-states in the MVP specification.
   - Once a parcel transitions to `DELIVERED` or `FAILED`, no further lifecycle mutations are accepted by Delivery Operations (`HTTP 409 Conflict` / `HTTP 422 Unprocessable Entity`).
3. **Decoupling of Assignment from State:**
   - Assignment of a courier by a dispatcher does **NOT** alter the parcel's lifecycle state. There is no `ASSIGNED` state. A parcel remains in `CREATED` until physical pickup occurs.
   - Re-assignment between couriers does not generate a parcel status transition; it only creates an audit record in `DELIVERY_ASSIGNMENTS`.
4. **Transition Guard Invariants:**
   Every status transition request (`PATCH /api/v1/parcels/{id}/status`) must satisfy:
   - **Identity & Role:** Caller has Keycloak role `COURIER`.
   - **Active Assignment:** Caller's Keycloak `sub` strictly matches `DELIVERY_ASSIGNMENTS.courier_sub` where `active = true`.
   - **Optimistic Concurrency:** Request payload includes `expectedVersion` matching `PARCELS.version`.
   - **Legal Transition:** Target state is a valid forward transition from `PARCELS.current_status`.
   - **Evidence Requirement:** Transition to `DELIVERED` requires proof metadata; transition to `FAILED` requires a non-blank `failure_reason`.

### 5.2 Open Architectural Decision: Pre-Pickup Failure / Cancellation (`DEC-DEL-01`)
- **Current Diagram Finding:** Figure 17 includes a dashed red line from `CREATED` to `FAILED` with note: *"Failure before pickup: proposed policy | Requires explicit lecturer / business approval"*.
- **Problem Analysis:** If a customer cancels an order before pickup, or if the merchant cannot provide the parcel, classifying the order as `FAILED` distorts operational courier performance metrics, since no delivery attempt was ever made.
- **Architectural Recommendation:** 
  - Introduce an explicit `CANCELLED` terminal state for pre-pickup customer/merchant cancellations, OR
  - Constrain `FAILED` strictly to post-pickup delivery attempts (`PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`), while unassigned/uncollected parcels transition to `CANCELLED` via customer/dispatcher command.
  - This decision remains **OPEN** pending lecturer confirmation and alignment with the Dispatcher/Admin pair.

---

## 6. Figure 08: Dispatcher Assignment & Concurrency Invariants

Figure 08 specifies the interaction between the Dispatcher SPA, GraphQL Query API, and Delivery Operations during courier dispatching.

### 6.1 Concurrency & Workload Integrity Assessment

1. **Two-Phase Read/Command Flow:**
   - **Read Phase (GraphQL):** Dispatcher queries composite unassigned queue and active courier workloads via Gateway -> GraphQL Query API -> gRPC to Delivery and Accounts.
   - **Command Phase (REST):** Dispatcher issues `POST /api/v1/assignments` with `parcelId`, `courierId`, and `expectedVersion` through Gateway directly to Delivery Operations.
2. **Concurrent Multi-Dispatcher Race Conditions:**
   - When two dispatchers simultaneously assign the same parcel to different couriers:
   - The first request acquires the database row lock on `PARCELS`, verifies `expectedVersion`, inserts a new row in `DELIVERY_ASSIGNMENTS` (`active = true`), increments `PARCELS.version`, and commits.
   - The second concurrent request fails the optimistic lock check (`expectedVersion != currentVersion`) or triggers the partial unique constraint (`idx_unique_active_assignment`).
   - The second request is rejected with `HTTP 409 Conflict`.
   - The second Dispatcher's UI catches 409, refreshes the queue, and prevents duplicate assignment.
3. **Courier Eligibility Enforcement:**
   - Delivery Operations verifies that the target courier is active and authorized before committing the assignment. This check is performed via synchronous gRPC to Accounts Service (`CheckCourierEligibility(courier_id)`).

---

## 7. Figure 18: Kafka Reliability, Outbox Pattern & SSE Fan-Out

Figure 18 details the asynchronous event topology and real-time client streaming architecture.

### 7.1 Transactional Outbox Pattern Evaluation

```text
[ Delivery Operations Service ]
       |
       | 1. Local ACID Transaction (PostgreSQL)
       |    - UPDATE parcels SET current_status = '...', version = version + 1
       |    - INSERT INTO status_history (...)
       |    - INSERT INTO outbox_events (topic='parcel.lifecycle.v1', payload=JSON, published_at=NULL)
       |    - COMMIT;
       |
       v
[ Outbox Poller / Relay Worker ]
       |
       | 2. Read unpublished rows (published_at IS NULL ORDER BY created_at ASC)
       | 3. Publish to Kafka (topic: parcel.lifecycle.v1, key: parcelId, acks: all)
       v
[ Apache Kafka Cluster ] (KRaft mode, topic: parcel.lifecycle.v1)
       |
       +---> [ Communications Service ] (Consumer Group: communications.notifications.v1)
       |        - Idempotent Inbox deduplication by event_id
       |        - Dispatches customer notification email/SMS
       |
       +---> [ Communications Service ] (Consumer Group: communications.webhooks.v1)
       |        - Idempotent Inbox deduplication by event_id
       |        - Signs payload with HMAC-SHA256 and dispatches merchant webhook
       |
       +---> [ Delivery SSE Fan-Out Pods ] (Independent consumer group per replica)
                - Group: tracking.<replica-pod-id>
                - Broadcasts sanitized status event to connected browser EventSource clients
```

### 7.2 Message Ordering & Delivery Guarantees
- **Partition Key Invariant:** All events for a specific parcel are published with `key = parcelId`. Kafka guarantees strict FIFO message ordering **within a single partition**. Thus, parcel status transitions (`CREATED` -> `PICKED_UP` -> `IN_TRANSIT` -> `OUT_FOR_DELIVERY` -> `DELIVERED`) are processed in guaranteed order by downstream consumers.
- **At-Least-Once Delivery:** Outbox relay retries on network interruption may produce duplicate Kafka records. Downstream consumers (Communications Service and SSE pods) **must** implement idempotent consumer patterns using `event_id` and `aggregate_version`.
- **SSE Multi-Replica Fan-Out Invariant:** Figure 18 identifies a critical scaling invariant: When Delivery Operations is horizontally scaled across multiple instances (Replica A, Replica B), each replica **must have a unique Kafka consumer group** (e.g., `tracking.pod-a`, `tracking.pod-b`). A single shared consumer group would load-balance events across pods, causing clients connected to Pod B to miss events consumed by Pod A.

---

## 8. Logical Data Integrity & Security Boundary Review

1. **Anti-Tampering & Security Invariants:**
   - **No Client-Supplied Identifiers:** The `created_by_sub` or `courier_sub` is extracted exclusively from the authenticated JWT principal (`SecurityContext`). Clients cannot inject forged user IDs in request bodies or query parameters.
   - **Edge Header Stripping:** Verified in Issue #18: API Gateway strips all client-supplied `X-User-Id`, `X-Keycloak-Sub`, and `X-Roles` headers prior to proxying.
2. **Private Proof Storage Architecture:**
   - Delivery proof images (e.g., recipient signature, doorstep photograph) must be stored in a **private S3/MinIO bucket** with restricted IAM credentials.
   - The database stores only the opaque object key (`DELIVERY_PROOFS.photo_object_key`).
   - Retrieval of proof is governed by Delivery Operations authorization rules: Only the sender (Customer/Merchant), assigned Courier, or Dispatcher/Admin may request a short-lived presigned URL or download the proof artifact. Public, unauthenticated proof URLs are strictly prohibited.
3. **Public Tracking Redaction:**
   - The public tracking endpoint (`GET /api/v1/tracking/{trackingNumber}`) is accessible without authentication.
   - Data projection is strictly redacted: It exposes parcel lifecycle status, timestamps, and city/zone. It **never** reveals recipient full names, street addresses, phone numbers, driver identities, or proof photographs.

---

## 9. Register of Open Architectural Decisions

The following open decisions must be formally approved by the respective human leads before implementing the Delivery Operations microservice:

| Decision ID | Summary of Open Question | Architectural Options & Trade-Offs | Responsible Owners | Required Sign-Off |
|---|---|---|---|---|
| **`DEC-DEL-01`** | **Pre-Pickup Cancellation vs Early Failure** | **Option A:** Allow `CREATED -> FAILED` with mandatory reason.<br>**Option B:** Introduce distinct `CANCELLED` state for pre-pickup aborts. | Courier + Dispatcher Pairs | HarithKeshan (@Harithkeshan) |
| **`DEC-DEL-02`** | **Proof-of-Delivery Mandatory Requirements** | **Option A:** Mandatory photo + signature for `DELIVERED`.<br>**Option B:** Flexible evidence (photo OR recipient signature OR note) configured per service level. | Courier + Dispatcher Pairs | Course Lecturer / Team Signoff |
| **`DEC-DEL-03`** | **Post-Failure Redelivery Modeling** | **Option A:** Strict MVP terminal `FAILED` (no redelivery in scope).<br>**Option B:** Introduce `DeliveryAttempt` 1:N entity allowing dispatchers to reschedule failed parcels. | Courier + Dispatcher Pairs | Suthankan1 (@Suthankan1) |
| **`DEC-DEL-04`** | **Outbox Relay Strategy** | **Option A:** Scheduled polling worker (`SELECT ... FOR UPDATE SKIP LOCKED`).<br>**Option B:** Change Data Capture (Debezium / PostgreSQL logical decoding). | Platform + Courier Lead | Suthankan1 (@Suthankan1) |

---

## 10. Report-Ready Diagram Captions & Architecture Rationale

For integration into the November 1 Academic Design Report (Issue #19, led by HarithKeshan), the following formal academic captions and justifications are approved:

### Figure 14: Delivery Operations Logical Database Schema (ERD)
> **Caption (Figure 14):** *Logical Entity-Relationship Diagram for the Delivery Operations service database (`delivery_db`). The schema adheres strictly to microservice data ownership principles, containing exclusively service-owned tables (`PARCELS`, `DELIVERY_ASSIGNMENTS`, `STATUS_HISTORY`, `DELIVERY_PROOFS`, `OUTBOX_EVENTS`, `IDEMPOTENCY_RECORDS`). External references to customer identities, merchant organizations, and pricing quotes are stored as opaque scalar identifiers (`EXT`), eliminating distributed database foreign keys. Concurrency control is enforced via optimistic locking (`version`), while assignment uniqueness is maintained through a PostgreSQL partial unique index on active assignments.*

### Figure 17: Parcel Lifecycle State Transition Model
> **Caption (Figure 17):** *Finite state machine diagram modeling the authoritative lifecycle of a SwiftDrop parcel. Transitions proceed unidirectionally from `CREATED` through `PICKED_UP`, `IN_TRANSIT`, and `OUT_FOR_DELIVERY` to the terminal `DELIVERED` state. Delivery failure transitions are supported from active delivery stages to the terminal `FAILED` state upon provision of a mandatory failure reason. Courier assignment is decoupled as an independent relational attribute rather than a lifecycle state. Transition guards enforce JWT bearer token validation, active courier assignment binding, optimistic version verification, and atomic history recording.*

### Figure 08: Dispatcher Assignment Activity and Concurrency Resolution
> **Caption (Figure 08):** *UML activity diagram illustrating the two-phase dispatching workflow. Workload aggregation and courier eligibility are read via the GraphQL Query API through backend gRPC federation. Courier assignment is dispatched as a REST command to Delivery Operations, where optimistic locking against the parcel aggregate root prevents race conditions between concurrent dispatchers. Conflicting assignments fail closed with HTTP 409 Conflict, preserving strict one-to-one active courier assignment invariants.*

### Figure 18: Reliable Event Publication and SSE Distribution Topology
> **Caption (Figure 18):** *Asynchronous event-driven integration and real-time tracking topology. Delivery Operations guarantees reliable domain event publication using the Transactional Outbox pattern, committing parcel state changes and outbox records in a single PostgreSQL ACID transaction. An asynchronous outbox relay streams events to Apache Kafka (`parcel.lifecycle.v1`) partitioned by `parcelId` for ordered processing. Communications consumers execute idempotent webhook and notification jobs, while horizontally scaled Delivery SSE pods consume from unique replica-bound consumer groups to broadcast real-time status updates to connected browser clients.*

---

## 11. Acceptance Criteria Checklist & Sign-Off Status

- [x] **Figure 14 & 17 Alignment:** Confirmed Delivery Operations as the sole authoritative source of truth; zero cross-service database foreign keys or shared schemas.
- [x] **Assignment Uniqueness & Concurrency:** Concurrency control, optimistic locking, and active assignment constraints rigorously distinguished from parcel state transitions.
- [x] **Terminal States & Failure Boundaries:** `DELIVERED` and `FAILED` verified as strictly terminal for MVP; pre-pickup failure and cancellation documented as open decision `DEC-DEL-01`.
- [x] **Outbox & Messaging Boundaries:** Transactional outbox, partition ordering, and SSE multi-replica fan-out clearly documented as planned target architecture (not claimed as running code for Nov 1).
- [x] **Report Artifacts Linked:** Report-ready captions, ownership matrices, and rationale prepared for HarithKeshan (Issue #19).
- [ ] **Cross-Team Peer Sign-Off:** Review requests submitted to Sinthuha, Pushmitha, Thuvarahan, and HarithKeshan.

**Architectural Review Completed By:**  
Vibhushan (@Vibhushan-2003)  
Courier Lead & Security QA Lead, SwiftDrop Group Project  
Date: 2026-10-21
