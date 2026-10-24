# Architectural & Contract Preparation: Guarded Parcel Status, History and Failure Slice (Issue #23 / LATER-06)

**Task:** Issue #23 (`[LATER-06] Implement guarded parcel status/history and failure slice`)  
**Lead Owner / Contributor:** Vibhushan (@Vibhushan-2003) — Courier Lead & Delivery Domain Specialist  
**Cross-Team Stakeholders & Reviewers:**  
- **HarithKeshan (@Harithkeshan)** — Dispatcher Assignment Boundary (Issue #24 Single-Active Assignment Lead)  
- **Sinthuha (@Sinthuha-n)** — Shared Delivery Ownership & Parcel Persistence (Issue #126 / Issue #20 Booking Lead)  
- **Abhinaya (@abhi190503)** — Courier Contract, Proof Metadata Policy & Independent QA (Issue #29 Proof Lead)  
- **Suthankan1 (@Suthankan1)** — Platform Lead (Database isolation, migration standards & integration gate)  

**Target Integration Branch:** `test`  
**Preparation Branch:** `docs/issue-23-transition-contract-preparation`  
**Contract Baseline Reference:**  
- `contracts/rest/openapi-delivery.yaml` (v1.0.0, PR #101 / Issue #88 frozen baseline; Operation `updateParcelStatus`)  
- `docs/contracts/SERVICE_INTERFACE_MATRIX.md` (`IF-REST-07`)  
- `docs/architecture/RUNTIME_DECISION_REGISTER.md` (Section 3: Lifecycle Rules)  
- `docs/architecture/DATA_OWNERSHIP.md`  
- `docs/reviews/LATER_13_DELIVERY_OUTBOX_CONTRACT_REVIEW.md` (Issue #31 Outbox Integration Handoff)  

**Status:** **STAGE A INDEPENDENT PREPARATION COMPLETE — AWAITING UPSTREAM ISSUES #126, #20, AND #24 PROVIDERS**

---

## 1. Executive Summary & Purpose

This document provides the authoritative **Stage A Independent Preparation** for **Issue #23: Guarded Parcel Status/History and First Approved Failure Slice**.

Per SwiftDrop repository discipline (`AGENTS.md`) and the master coding instructions:
1. **Mandatory Dependency Gate:** When foundational upstream deliverables—specifically **Issue #126 (Delivery application foundation)**, **Issue #20 (Booking & canonical parcel provider)**, and **Issue #24 (Single-active-courier assignment)**—are not yet merged into executable code on `origin/test`, an engineer **must NOT** invent competing `Parcel` aggregates, duplicate Spring Boot applications, or speculative assignment schemas inside Issue #23.
2. **Stage A Execution:** Full integrated code implementation is halted until prerequisite providers land. The engineer must deliver independent preparation: status transition acceptance matrices, role/actor permission matrices, failure/proof requirements, optimistic concurrency rules, atomic transaction integration boundaries, and machine-readable contract test suites.
3. **Branch Hygiene:** Independent preparation artifacts are committed to the dedicated preparation branch `docs/issue-23-transition-contract-preparation` targeting `test`, without claiming unverified completion.

---

## 2. Mandatory Dependency Gate Evaluation

An audit of GitHub issues, open pull requests, and the current codebase on `origin/test` establishes the exact status of all prerequisite dependencies:

| Prerequisite Issue | Title & Scope | GitHub State | Codebase Implementation Evidence | Gate Outcome |
|---|---|---|---|---|
| **#126 (N10-B24)** | Create Delivery App, Owned Migrations & Package Contracts | **OPEN** | Assigned to Sinthuha (@Sinthuha-n); scheduled Oct 24 – Oct 27, 2026. `services/delivery-service/` contains only `README.md`. No Spring Boot application, `pom.xml`, datasource, or Flyway migrations exist. | **BLOCKED (APP FOUNDATION ABSENT)** |
| **#20 (LATER-05)** | Delivery Booking & Canonical Parcel Provider | **OPEN** | Assigned to Sinthuha (@Sinthuha-n); scheduled Oct 27 – Oct 30, 2026. No `Parcel` JPA entity, repository, or booking persistence exists. | **BLOCKED (PARCEL AGGREGATE ABSENT)** |
| **#24 (LATER-08)** | Single-Active-Courier Assignment Command | **OPEN** | Assigned to HarithKeshan (@Harithkeshan); scheduled Nov 1 – Nov 2, 2026. No `DeliveryAssignment` entity or assignment validation provider exists. | **BLOCKED (ASSIGNMENT MODEL ABSENT)** |
| **#88 (N10-P01)** | Runtime Integration Contract Freeze | **CLOSED** | OpenAPI contracts (`contracts/rest/openapi-delivery.yaml`) and runtime registers merged in PR #101. | **PASS (CONTRACT FROZEN)** |
| **#91 (N10-P13)** | Kafka & Business Database Dependencies | **CLOSED** | Docker Compose and database initialization scripts merged in PR #104 (`delivery_db` provisioned for user `delivery_app`). | **PASS (INFRA SPECIFIED)** |
| **#29 (LATER-05)** | Courier Proof Capture & Delivery Metadata | **OPEN** | Assigned to Abhinaya (@abhi190503); scheduled Oct 30 – Nov 6, 2026. Downstream proof consumer. | **INFORMATIONAL (DOWNSTREAM)** |
| **#31 (LATER-13)** | Delivery Outbox & Bounded Kafka Relay | **OPEN** | Assigned to Vibhushan (@Vibhushan-2003); preparation PR #182 merged/reviewed. | **INFORMATIONAL (TRANSACTION HANDOFF)** |
| **#98 (N10-WF13)** | Complete Approved Remaining Lifecycle Transitions | **OPEN** | Assigned to Vibhushan (@Vibhushan-2003); scheduled Oct 29 – Nov 4, 2026. Follow-up slice after #23. | **INFORMATIONAL (FOLLOW-UP SLICE)** |

### Dependency Gate Conclusion
**GATE STATUS: STAGE A PREPARATION ONLY — STAGE B IMPLEMENTATION BLOCKED.**
- Crucial foundational providers (#126, #20, #24) are unmerged.
- In accordance with Section 1 and Section 5 of the master prompt, **no duplicate Delivery service, Parcel aggregate, or assignment table is fabricated**.
- Independent contract, transition matrix, concurrency, and automated test specifications are frozen below.

---

## 3. Status Transition Acceptance Matrix

In accordance with `contracts/rest/openapi-delivery.yaml` (Operation `updateParcelStatus`, `IF-REST-07`) and Section 3 of `docs/architecture/RUNTIME_DECISION_REGISTER.md`, the authoritative parcel lifecycle transitions for Delivery Operations are specified below:

| Current State | Target State | Authorized Actor | Preconditions & Validation Guards | Required Payload Input | Concurrency Check | Emitted Outbox Event | HTTP Response |
|---|---|---|---|---|---|---|---|
| **`CREATED`** | **`PICKED_UP`** | Assigned `COURIER` | Active courier assignment bound in `delivery_assignments`; parcel in courier physical custody | Optional `note` | Optimistic `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |
| **`CREATED`** | **`FAILED`** | `CUSTOMER` (unassigned) or `DISPATCHER` | Parcel not yet collected; abort command issued before physical pickup | Mandatory `reason`: `CANCELLED_BY_SENDER` or `PICKUP_FAILED_SENDER_ABSENT` | Optimistic `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`PICKED_UP`** | **`IN_TRANSIT`** | Assigned `COURIER` | Parcel departed origin sorting facility / transit hub | Optional transit scan `note` | Optimistic `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |
| **`PICKED_UP`** | **`FAILED`** | Assigned `COURIER` or `DISPATCHER` | Parcel damaged or lost during intake/first-mile transit | Mandatory `reason`: `PARCEL_DAMAGED` or `FORCE_MAJEURE` | Optimistic `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`IN_TRANSIT`** | **`OUT_FOR_DELIVERY`** | Assigned `COURIER` | Parcel loaded onto courier last-mile vehicle | Optional vehicle manifest `note` | Optimistic `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |
| **`IN_TRANSIT`** | **`FAILED`** | Assigned `COURIER` or `DISPATCHER` | Transit accident, vehicle breakdown, or irreversible parcel loss | Mandatory `reason`: `PARCEL_DAMAGED` or `FORCE_MAJEURE` | Optimistic `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`OUT_FOR_DELIVERY`** | **`DELIVERED`** *(Terminal)* | Assigned `COURIER` | Physical handover completed at destination address | Mandatory `proofId` (UUID matching recorded proof metadata) | Optimistic `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |
| **`OUT_FOR_DELIVERY`** | **`FAILED`** *(Terminal)* | Assigned `COURIER` | Last-mile delivery attempt unsuccessful | Mandatory `reason`: `RECIPIENT_UNAVAILABLE`, `INCORRECT_ADDRESS`, or `REFUSED_BY_RECIPIENT` | Optimistic `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |

### 3.1 Lifecycle Invariant Rules
1. **Forbidden Backward Transitions:** Any backward state change (e.g., `IN_TRANSIT -> PICKED_UP`, `DELIVERED -> OUT_FOR_DELIVERY`) is strictly rejected with `HTTP 422 Unprocessable Entity` (`ILLEGAL_STATE_TRANSITION`).
2. **Terminal State Immutability:** Both `DELIVERED` and `FAILED` are strictly terminal. No subsequent status patches are permitted. Any mutation attempt on a terminal parcel returns `HTTP 409 Conflict` (`TERMINAL_STATE_IMMUTABLE`). Redelivery workflows are deferred to later releases.
3. **Decoupling Assignment from Lifecycle:** Assigning a courier under Issue #24 does **NOT** alter the lifecycle state; the parcel remains in `CREATED` until physical pickup.

---

## 4. Courier / Dispatcher / Admin Permission Matrix

Every protected status transition command requires server-verified authentication via Bearer JWT. Client-supplied identity headers are strictly ignored.

| Actor Role | Allowed Transition Operations | Authorization & Assignment Verification | Violation Behavior |
|---|---|---|---|
| **`COURIER`** | `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED` (after pickup) | 1. Token must contain Keycloak realm role `COURIER`.<br>2. Caller's `sub` must match the active assignment in `delivery_assignments` for the parcel. | `HTTP 403 Forbidden` (`NOT_ASSIGNED_COURIER`) |
| **`DISPATCHER`** | `FAILED` (pre-pickup aborts, lost transit parcels) | Token must contain Keycloak realm role `DISPATCHER`. | `HTTP 403 Forbidden` (`ROLE_DISPATCHER_REQUIRED`) |
| **`ADMIN`** | System override for terminal failure recording | Token must contain Keycloak realm role `ADMIN`. | `HTTP 403 Forbidden` (`ROLE_ADMIN_REQUIRED`) |
| **`CUSTOMER` / `MERCHANT`** | Read parcel status (`GET`); early cancellation before courier assignment | Customer may cancel unassigned parcel in `CREATED` state. Once assigned/picked up, direct customer status mutation is rejected. | `HTTP 403 Forbidden` (`CUSTOMER_TRANSITION_FORBIDDEN`) |

---

## 5. Failure Reason & Proof Requirements

### 5.1 Mandatory Failure Reasons
When `newStatus == 'FAILED'`, the `reason` field in `UpdateStatusRequest` is mandatory. The approved reason codes are strictly restricted to:
- `RECIPIENT_UNAVAILABLE` — Recipient not present at delivery address after calling.
- `INCORRECT_ADDRESS` — Destination address does not exist or cannot be located.
- `REFUSED_BY_RECIPIENT` — Consignee refused to accept parcel or package rejected.
- `PARCEL_DAMAGED` — Parcel damaged during transit; unfit for delivery.
- `FORCE_MAJEURE` — Severe weather, natural disaster, road closure, or civil disruption.
- `CANCELLED_BY_SENDER` — Sender requested delivery termination prior to physical collection.
- `PICKUP_FAILED_SENDER_ABSENT` — Courier arrived at pickup location but sender was unavailable.

Supplying a missing or unapproved failure reason returns `HTTP 400 Bad Request` (`MISSING_OR_INVALID_FAILURE_REASON`).

### 5.2 Mandatory Proof Reference for DELIVERED
When `newStatus == 'DELIVERED'`, `proofId` (UUID) is mandatory in `UpdateStatusRequest`. It references proof-of-delivery metadata (recipient signature or photo key) captured under Issue #29. Missing `proofId` returns `HTTP 400 Bad Request` (`PROOF_METADATA_REQUIRED`).

---

## 6. Stale-Version & Concurrency Behavior

### 6.1 Optimistic Locking Invariant
Every status transition request must supply `expectedVersion` matching the current `parcels.version`:
- If `expectedVersion == parcel.version`: The update proceeds. `parcel.version` is incremented to `version + 1`, and the new version is recorded in `status_history.resulting_version`.
- If `expectedVersion != parcel.version`: The update is rejected immediately.
  - Response: `HTTP 409 Conflict` (RFC 9457 ProblemDetail, `title: "Optimistic Lock Failure"`, `detail: "The parcel has been updated concurrently. Refresh and retry."`).
  - Canonical state in `parcels` is untouched.
  - No record is appended to `status_history`.
  - Zero outbox records are created.

### 6.2 Concurrent Update Race Resolution
When two concurrent requests attempt to transition a parcel from the same `expectedVersion = N`:
- PostgreSQL row locking (`SELECT FOR UPDATE` or JPA `@Version`) guarantees that exactly **one** transaction commits.
- The competing transaction encounters an optimistic lock exception and rolls back cleanly, returning `HTTP 409 Conflict`.

---

## 7. Atomic State/History Transaction Architecture

The central architectural guarantee of Issue #23 is:
> **Parcel status mutation and append-only status history MUST commit atomically in ONE local database transaction.**

### 7.1 Database Schema DDL for `delivery_db`
Owned exclusively by application role `delivery_app`:

```sql
-- ==============================================================================
-- SwiftDrop Delivery Operations: Canonical Parcel & Status History Tables
-- Database: delivery_db
-- ==============================================================================

CREATE TABLE IF NOT EXISTS parcels (
    parcel_id UUID PRIMARY KEY,
    tracking_number VARCHAR(32) NOT NULL UNIQUE,
    sender_sub VARCHAR(64) NOT NULL,
    merchant_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_parcel_status CHECK (
        status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
    )
);

CREATE TABLE IF NOT EXISTS status_history (
    history_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(64),
    note TEXT,
    proof_id UUID,
    actor_sub VARCHAR(64) NOT NULL,
    resulting_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_status_history_parcel_version 
ON status_history(parcel_id, resulting_version);
```

### 7.2 Service-Level Transaction Boundary
```java
@Service
public class ParcelStateTransitionService {

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final AssignmentLookupService assignmentLookupService;
    // Issue #31 will inject: private final OutboxEventFactory outboxEventFactory;

    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public ParcelStatusChangeResponse updateStatus(
            UUID parcelId,
            UpdateStatusRequest request,
            String actorSub,
            Set<String> actorRoles
    ) {
        // 1. Fetch aggregate with optimistic version check
        Parcel parcel = parcelRepository.findById(parcelId)
            .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        if (!parcel.getVersion().equals(request.getExpectedVersion())) {
            throw new VersionConflictException(request.getExpectedVersion(), parcel.getVersion());
        }

        // 2. Authorize actor and verify active courier assignment
        if (actorRoles.contains("COURIER")) {
            assignmentLookupService.assertActiveAssignment(parcelId, actorSub);
        } else if (!actorRoles.contains("DISPATCHER") && !actorRoles.contains("ADMIN")) {
            throw new UnauthorizedTransitionActorException(actorSub);
        }

        // 3. Validate lifecycle state machine rules
        ParcelStatus previousStatus = parcel.getStatus();
        LifecycleGuards.validateTransition(previousStatus, request.getNewStatus(), request.getReason(), request.getProofId());

        // 4. Atomically mutate parcel state & increment version
        parcel.setStatus(request.getNewStatus());
        parcel.setVersion(parcel.getVersion() + 1);
        parcelRepository.save(parcel);

        // 5. Append immutable status history
        StatusHistory history = new StatusHistory(
            UUID.randomUUID(),
            parcel.getId(),
            previousStatus,
            request.getNewStatus(),
            request.getReason(),
            request.getNote(),
            request.getProofId(),
            actorSub,
            parcel.getVersion(),
            Instant.now()
        );
        statusHistoryRepository.save(history);

        // 6. ISSUE #31 INTEGRATION POINT:
        // OutboxEvent outboxEvent = outboxEventFactory.create(parcel, history);
        // outboxEventRepository.save(outboxEvent);

        return new ParcelStatusChangeResponse(
            parcel.getId(),
            parcel.getTrackingNumber(),
            previousStatus.name(),
            request.getNewStatus().name(),
            parcel.getVersion(),
            parcel.getVersion(),
            Instant.now()
        );
    }
}
```

---

## 8. Automated Test Suite & Acceptance Scenarios

An automated contract and invariant test suite has been implemented in:
`tests/contracts/test_delivery_status_contract.py`

### 8.1 Test Results Summary
Executed against the repository baseline:
```
================================================================================
    SWIFTDROP ISSUE #23: DELIVERY STATUS & HISTORY CONTRACT TEST SUITE       
================================================================================

[TEST GROUP 1] REST Endpoint & Operation Invariants
  [PASS] PATCH method exists on /api/v1/parcels/{parcelId}/status
  [PASS] operationId is updateParcelStatus
  [PASS] Endpoint secured by BearerAuth (COURIER role)
  [PASS] Response status code 200 documented in contract
  [PASS] Response status code 400 documented in contract
  [PASS] Response status code 401 documented in contract
  [PASS] Response status code 403 documented in contract
  [PASS] Response status code 409 documented in contract
  [PASS] Response status code 422 documented in contract

[TEST GROUP 2] UpdateStatusRequest Schema Invariants
  [PASS] 'newStatus' is mandatory in UpdateStatusRequest
  [PASS] 'expectedVersion' is mandatory for optimistic locking
  [PASS] Permitted newStatus enum matches approved lifecycle subset
  [PASS] Failure reasons match approved terminal failure codes
  [PASS] proofId is formatted as UUID for DELIVERED transitions

[TEST GROUP 3] ParcelStatusChangeResponse Schema Invariants
  [PASS] Response contains all mandatory status change and version fields

[TEST GROUP 4] State Transition Permissibility Matrix
  [PASS] Permit CREATED -> PICKED_UP (Physical courier pickup)
  [PASS] Permit PICKED_UP -> IN_TRANSIT (Hub transit)
  [PASS] Permit IN_TRANSIT -> OUT_FOR_DELIVERY (Out for delivery)
  [PASS] Permit OUT_FOR_DELIVERY -> DELIVERED (Handover completed)
  [PASS] Permit OUT_FOR_DELIVERY -> FAILED (Delivery attempt failed)
  [PASS] Forbid backward transition IN_TRANSIT -> PICKED_UP
  [PASS] Forbid terminal transition DELIVERED -> OUT_FOR_DELIVERY
  [PASS] Forbid terminal transition FAILED -> DELIVERED
  [PASS] Forbid skipping stages CREATED -> DELIVERED

[TEST GROUP 5] Database DDL Invariant Specifications for delivery_db
  [PASS] status_history table defined with foreign key to parcels
  [PASS] parcels table incorporates BIGINT version for optimistic locking
  [PASS] status_history records resulting aggregate version
  [PASS] status_history stores proof metadata reference

================================================================================
SUMMARY: 28 PASSED, 0 FAILED
================================================================================
```

---

## 9. Special Issue #31 Handoff Architecture

1. **No Kafka I/O in #23:** `ParcelStateTransitionService` does **NOT** publish directly to Kafka.
2. **Outbox Participation Point:** When Issue #31 is activated, it extends `ParcelStateTransitionService` to persist an `OutboxEvent` row inside the **SAME local database transaction**.
3. **Audit Completeness:** Status history remains the authoritative business audit log in PostgreSQL; Kafka events provide asynchronous delivery to downstream consumers (Communications Service).

---

## 10. Provider Readiness Checklist & Next Action

| Prerequisite Deliverable | Responsible Owner | Target Issue | Current State |
|---|---|---|---|
| Delivery Spring Boot foundation | Sinthuha (@Sinthuha-n) | Issue #126 | Awaiting implementation |
| Canonical `Parcel` aggregate & booking persistence | Sinthuha (@Sinthuha-n) | Issue #20 | Awaiting implementation |
| Single active courier assignment validation | HarithKeshan (@Harithkeshan) | Issue #24 | Awaiting implementation |
| Guarded status transition & atomic history | Vibhushan (@Vibhushan-2003) | Issue #23 | **Stage A Complete; Ready for Stage B upon #126/#20/#24** |
| Transactional outbox & Kafka relay | Vibhushan (@Vibhushan-2003) | Issue #31 | Stage A Complete (PR #182) |
