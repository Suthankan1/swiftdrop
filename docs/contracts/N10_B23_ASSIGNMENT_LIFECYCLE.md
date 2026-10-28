# SwiftDrop Delivery Operations — Courier Assignment Lifecycle Contract

**Milestone:** November 10 Baseline  
**Issue:** [Refs #125 (N10-B23)](https://github.com/Suthankan1/swiftdrop/issues/125) — Implement Approved Assignment Reassignment, Closure, and Audit Lifecycle  
**Primary Module Owner:** Harithkeshan (@Harithkeshan)  
**Assigned Independent Reviewers:** Pushmitha (@pushmitha20), Vibhushan (@Vibhushan-2003), Sinthuha (@Sinthuha-n)  
**Target Integration Branch:** `test`  
**Status:** **IMPLEMENTED AND VERIFIED AGAINST REAL POSTGRESQL & CONTRACT SUITE**

---

## 1. Executive Summary & Context

Under Architecture Baseline v1.3 and runtime business decisions [DEC-ASG-01 through DEC-ASG-07](docs/architecture/RUNTIME_DECISION_REGISTER.md), courier assignment is an internal capability of the **Delivery Operations** bounded context (`delivery_db`).

This contract establishes the formal runtime behavior, REST schemas, database invariants, and concurrency rules for:
1. **Initial Courier Assignment** (`POST /api/v1/assignments`) — established in Issue #24.
2. **Authorized Courier Reassignment** (`POST /api/v1/assignments/reassign` and `POST /api/v1/assignments/{assignmentId}/reassign`).
3. **Courier Assignment Closure / Unassignment** (`POST /api/v1/assignments/close` and `POST /api/v1/assignments/{assignmentId}/close`).
4. **Durable, Append-Only Assignment Audit** (`delivery_assignment_audits` and `GET /api/v1/assignments/parcels/{parcelId}/history`).
5. **Terminal Parcel Lifecycle Coordination** (atomically closing active assignments when parcels reach `DELIVERED` or `FAILED`).

---

## 2. Approved Assignment Lifecycle State Machine

The assignment lifecycle is decoupled from parcel status (`status_history`). The parcel remains in `CREATED` while assignments are created, reassigned, or closed:

```text
               [Unassigned Parcel (status: CREATED)]
                              │
                    assignCourier (DEC-ASG-02)
                              ▼
           ┌─────────────────────────────────────┐
           │     Active Assignment (Courier A)   │
           │           active = true             │
           └─────────────────────────────────────┘
                 │                         │
      reassignCourier               closeAssignment
                 │                         │
                 ▼                         ▼
   ┌───────────────────────────┐    ┌───────────────────────────┐
   │ Active Assignment (B)     │    │ Closed Assignment (Ended) │
   │ (Courier A ended_at set)  │    │ active = false            │
   └───────────────────────────┘    └───────────────────────────┘
                 │
      Terminal Parcel Transition
      (DELIVERED or FAILED)
                 │
                 ▼
   ┌───────────────────────────┐
   │ Terminated Assignment     │
   │ active = false            │
   │ audit: TERMINATED         │
   └───────────────────────────┘
```

### Approved Lifecycle Invariants
- **DEC-ASG-01 (Courier Eligibility):** Replacement courier must hold realm role `COURIER` and active business status verified via Accounts Service synchronous gRPC (`IF-GRPC-01`). Fails closed upon timeout or unavailability.
- **DEC-ASG-02 (Assignable Stage):** Assignment, reassignment, and manual closure are permitted **strictly while parcel is in `CREATED` state**. Parcels in `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, or `FAILED` cannot be assigned, reassigned, or manually closed.
- **DEC-ASG-03 (Single Active Assignment):** Exactly one active assignment is permitted per parcel at any given time. Enforced via PostgreSQL partial unique index:
  ```sql
  CREATE UNIQUE INDEX uq_delivery_assignments_active_parcel
  ON delivery_assignments(parcel_id)
  WHERE active = true;
  ```
- **DEC-ASG-06 (Reassignment Pre-Pickup):** Dispatchers may reassign couriers prior to pickup (e.g., breakdown, shift change). Old assignment is marked `active = false` with `ended_at = NOW()`; a new row is inserted with `active = true`.
- **DEC-ASG-07 (Separate Audit Trail):** Assignment state changes are audited in `delivery_assignment_audits`. They never generate fake entries in `status_history` and never publish `parcel.status.changed.v1` Kafka events.

---

## 3. Formally Excluded Speculative Semantics

To prevent architectural drift and maintain backward compatibility, the following proposals are **strictly excluded**:
- **No Courier Acceptance State Machines:** Speculative states (`PENDING_ACCEPTANCE`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `REASSIGNING`, `CANCELLED`) are prohibited. Direct assignment takes immediate effect.
- **No Hard Capacity Rejection:** Courier workload capacity of 5 parcels remains an advisory soft-cap (`DEC-ASG-04`). Reassignment does not reject a dispatcher request merely because a courier has 5 parcels.
- **No Automatic Reassignment Timeouts:** Automated countdown timers and self-rejections are deferred.
- **No Post-Pickup Reassignment:** Once parcel is `PICKED_UP`, reassignment is rejected.
- **No Fake Parcel Status Events:** Assignment mutations do not emit `parcel.status.changed.v1`.

---

## 4. Preconditions and Role Authorization Matrix

| Command | Allowed Roles | Parcel Preconditions | Concurrency & Validation |
|---|---|---|---|
| **Assign** | `DISPATCHER`, `ADMIN` | Status must be `CREATED`. No active assignment exists. | `expectedVersion == parcel.version`. Courier must be active & eligible. |
| **Reassign** | `DISPATCHER`, `ADMIN` | Status must be `CREATED`. Active assignment must exist. | `expectedVersion == parcel.version`. `currentAssignmentId` matches active if provided. `replacementCourierSub != currentCourierSub`. Replacement courier must be eligible. |
| **Close** | `DISPATCHER`, `ADMIN` | Status must be `CREATED`. Active assignment must exist. | `expectedVersion == parcel.version`. `assignmentId` matches active if provided. |
| **Audit Read** | `DISPATCHER`, `ADMIN` | Parcel must exist. | Caller must be authenticated dispatcher or administrator. |

---

## 5. Optimistic Concurrency and PostgreSQL Atomicity

### Parcel Aggregate Versioning
- Every assignment change (initial assignment, reassignment, manual closure) increments the `Parcel.version` aggregate root version.
- Callers must submit `expectedVersion`. If `expectedVersion != parcel.version`, the service aborts with `AssignmentConflictException` (HTTP 409 Conflict, code `ASSIGNMENT_CONFLICT`).
- If two concurrent dispatchers issue conflicting reassignments with the same `expectedVersion`, JPA version checking and the PostgreSQL unique partial index guarantee that exactly one succeeds and the other fails with HTTP 409.

### Hibernate Action Queue & Partial Index Safety
- In JPA/Hibernate, SQL INSERT operations are queued before UPDATE operations during flush.
- To prevent a transient duplicate key violation against `uq_delivery_assignments_active_parcel` (`WHERE active = true`), `DeliveryAssignmentService` explicitly calls:
  ```java
  assignmentRepository.saveAndFlush(currentAssignment); // Marks active = false in DB first
  assignmentRepository.saveAndFlush(newAssignment);     // Then inserts new active = true row
  ```

---

## 6. Durable Audit Persistence Model (`delivery_assignment_audits`)

Managed via Flyway migration `V7__create_assignment_audit_schema.sql`:

```sql
CREATE TABLE delivery_assignment_audits (
    audit_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    assignment_id UUID NOT NULL,
    courier_sub VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_sub VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    previous_courier_sub VARCHAR(64),
    previous_assignment_id UUID,
    reason TEXT,
    parcel_version BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_delivery_assignment_audits_parcel_id_occurred_at 
ON delivery_assignment_audits(parcel_id, occurred_at DESC);
```

### Audit Actions
- `ASSIGNED`: Initial dispatcher assignment.
- `REASSIGNED`: Atomic replacement of courier A with courier B.
- `CLOSED`: Manual unassignment by dispatcher.
- `TERMINATED`: Automatic closure on parcel terminal state transition (`DELIVERED` or `FAILED`).

---

## 7. Dynamic Authority Revocation on Assignment Termination

When an active assignment is ended (via reassignment, closure, or terminal transition):
1. **Immediate Revocation of Status Patching:** `StatusTransitionService` checks `hasActiveAssignment(parcelId, actorSub)`. Deactivated couriers attempting `PATCH /api/v1/parcels/{id}/status` immediately fail with `403 Forbidden` (`NOT_ASSIGNED_COURIER`).
2. **Immediate Revocation of Proof Upload:** `ProofService` checks `hasActiveAssignment(parcelId, actorSub)`. Deactivated couriers attempting `POST /api/v1/parcels/{id}/proof` fail with `403 Forbidden`.
3. **SSE Live Stream Disconnect:** `ParcelSseConnectionManager` verifies `existsByParcelIdAndCourierSubAndActiveTrue` before publishing events. revoking active assignment stops delivery of live events to the courier.

---

## 8. REST API Endpoints & Request/Response Contracts

### 8.1 Reassign Courier
- **Routes:** `POST /api/v1/assignments/reassign` or `POST /api/v1/assignments/{assignmentId}/reassign`
- **Request Body:**
  ```json
  {
    "parcelId": "550e8400-e29b-41d4-a716-446655440000",
    "replacementCourierSub": "courier-keycloak-sub-456",
    "expectedVersion": 2,
    "currentAssignmentId": "770e8400-e29b-41d4-a716-446655440000",
    "reason": "Courier vehicle breakdown"
  }
  ```
- **Success Response (`200 OK`):**
  ```json
  {
    "assignmentId": "880e8400-e29b-41d4-a716-446655440000",
    "parcelId": "550e8400-e29b-41d4-a716-446655440000",
    "courierSub": "courier-keycloak-sub-456",
    "active": true,
    "assignedAt": "2026-10-27T18:00:00Z",
    "assignedBy": "dispatcher-sub-123",
    "newParcelVersion": 3,
    "reason": "Courier vehicle breakdown"
  }
  ```

### 8.2 Close Assignment
- **Routes:** `POST /api/v1/assignments/close` or `POST /api/v1/assignments/{assignmentId}/close`
- **Request Body:**
  ```json
  {
    "parcelId": "550e8400-e29b-41d4-a716-446655440000",
    "expectedVersion": 2,
    "assignmentId": "770e8400-e29b-41d4-a716-446655440000",
    "reason": "Cancelled by dispatcher before pickup"
  }
  ```
- **Success Response (`200 OK`):**
  ```json
  {
    "assignmentId": "770e8400-e29b-41d4-a716-446655440000",
    "parcelId": "550e8400-e29b-41d4-a716-446655440000",
    "courierSub": "courier-keycloak-sub-123",
    "active": false,
    "assignedAt": "2026-10-27T17:00:00Z",
    "assignedBy": "dispatcher-sub-123",
    "newParcelVersion": 3,
    "endedAt": "2026-10-27T18:00:00Z",
    "reason": "Cancelled by dispatcher before pickup"
  }
  ```

### 8.3 Get Assignment Audit History
- **Routes:** `GET /api/v1/assignments/parcels/{parcelId}/history` or `GET /api/v1/assignments/{parcelId}/history`
- **Success Response (`200 OK`):**
  ```json
  [
    {
      "auditId": "110e8400-e29b-41d4-a716-446655440000",
      "parcelId": "550e8400-e29b-41d4-a716-446655440000",
      "assignmentId": "880e8400-e29b-41d4-a716-446655440000",
      "courierSub": "courier-keycloak-sub-456",
      "action": "REASSIGNED",
      "actorSub": "dispatcher-sub-123",
      "actorRole": "DISPATCHER",
      "previousCourierSub": "courier-keycloak-sub-123",
      "previousAssignmentId": "770e8400-e29b-41d4-a716-446655440000",
      "reason": "Courier vehicle breakdown",
      "parcelVersion": 3,
      "occurredAt": "2026-10-27T18:00:00Z"
    },
    {
      "auditId": "100e8400-e29b-41d4-a716-446655440000",
      "parcelId": "550e8400-e29b-41d4-a716-446655440000",
      "assignmentId": "770e8400-e29b-41d4-a716-446655440000",
      "courierSub": "courier-keycloak-sub-123",
      "action": "ASSIGNED",
      "actorSub": "dispatcher-sub-123",
      "actorRole": "DISPATCHER",
      "previousCourierSub": null,
      "previousAssignmentId": null,
      "reason": "Initial assignment",
      "parcelVersion": 2,
      "occurredAt": "2026-10-27T17:00:00Z"
    }
  ]
  ```

---

## 9. Error and Problem Code Matrix

All error responses conform to RFC 7807 (`application/problem+json`):

| HTTP Status | Error Code (`code`) | Trigger Condition | Recommended UI Action |
|---|---|---|---|
| **`400 Bad Request`** | `VALIDATION_ERROR` | Missing required payload field (e.g., `parcelId`, `replacementCourierSub`, `expectedVersion`). | Prompt user to complete mandatory fields. |
| **`401 Unauthorized`** | `UNAUTHORIZED` | Missing or invalid Bearer JWT. | Re-authenticate through Keycloak OIDC. |
| **`403 Forbidden`** | `FORBIDDEN` | Caller lacks `DISPATCHER` or `ADMIN` role. | Display authorization denial banner. |
| **`404 Not Found`** | `PARCEL_NOT_FOUND` | `parcelId` does not exist in `delivery_db`. | Refresh parcel list. |
| **`409 Conflict`** | `ASSIGNMENT_CONFLICT` | `expectedVersion` mismatch, no active assignment, assignment ID mismatch, or concurrent race. | Re-fetch parcel detail, update expectedVersion, and prompt dispatcher to confirm. |
| **`422 Unprocessable Entity`** | `INVALID_PARCEL_STATE` | Parcel is not in `CREATED` status (e.g. already picked up or failed). | Disable reassignment/closure buttons for in-transit/terminal parcels. |
| **`422 Unprocessable Entity`** | `INELIGIBLE_COURIER` | Replacement courier is suspended, inactive, or not approved in Accounts. | Show courier rejection reason; prompt dispatcher to choose an eligible courier. |

---

## 10. Dispatcher UI Integration Handoff Notes (Issue #25)

For the Dispatcher Web UI team working on Issue #25:
1. **Reassignment Flow:**
   - Display current active courier on the parcel detail view.
   - When Dispatcher clicks "Reassign Courier", provide dropdown of eligible couriers filtered by active status.
   - Send `POST /api/v1/assignments/reassign` with `parcelId`, `replacementCourierSub`, `expectedVersion`, and optional `reason`.
   - On `409 Conflict`, reload parcel details to display whether another dispatcher reassigned the parcel or if the parcel version advanced.
2. **Unassign / Close Flow:**
   - If a courier cannot make pickup and no immediate replacement is available, Dispatcher clicks "Unassign".
   - Send `POST /api/v1/assignments/close` with `parcelId`, `expectedVersion`, and reason.
   - The parcel returns to unassigned state in the Dispatcher pool while remaining in `CREATED`.
3. **Audit History Component:**
   - Call `GET /api/v1/assignments/parcels/{parcelId}/history` to render the chronological timeline of courier assignments, reassignments, closures, and reasons.
   - This history is distinct from parcel transit scan history (`status_history`).
