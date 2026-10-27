# SwiftDrop Server-Sent Events (SSE) & REST Recovery Protocol

**Contract Version:** 1.0.0  
**Service:** Delivery Operations Service (`services/delivery-service`)  
**Endpoint:** `GET /api/v1/parcels/{parcelId}/events`  
**Media Type:** `text/event-stream`  
**Security:** Verified Keycloak OIDC JWT (`Authorization: Bearer <token>`)  
**Scope:** Multi-Replica Broadcast Fan-Out & REST Recovery (Issue #130 & Issue #161)

---

## 1. Architectural Philosophy: Event Stream vs. Source of Truth

The authenticated Server-Sent Events (SSE) channel is a **low-latency live notification mechanism**, **NOT** an authoritative event store.
- **Authoritative State:** PostgreSQL persistence in `delivery-service` accessed via authorized REST endpoints (`GET /api/v1/parcels/{parcelId}` and `GET /api/v1/parcels/{parcelId}/history`) is the sole canonical source of truth.
- **Live Stream:** Delivers real-time status transitions to connected, authorized viewers with zero PII.
- **Recovery Invariant:** A missed or dropped SSE live notification must never result in permanent client inconsistency. The client must reconcile its view with canonical REST state whenever an anomaly or disruption occurs.

---

## 2. Authorization & Access Lifecycle

### 2.1 Connection Authorization
1. Clients establish the SSE connection by sending an HTTP `GET` with a standard `Authorization: Bearer <keycloak_jwt>` header.
2. The server verifies the token against the Keycloak JWKS endpoint (`issuer`, `audience=swiftdrop-api`, signature, unexpired).
3. The server validates that the authenticated subject has read permissions on the requested parcel:
   - **Sender (CUSTOMER):** Caller `sub` must match `parcel.sender_sub`.
   - **Merchant (MERCHANT):** Caller `merchant_id` claim must match `parcel.merchant_id` with `merchant_status == 'APPROVED'`.
   - **Courier (COURIER):** Caller must hold an active assignment in `courier_assignments` (`active = true`).
   - **Staff (ADMIN / DISPATCHER):** Operational read scope across all parcels.
4. Any failure to authenticate yields `401 Unauthorized`. Any authorization mismatch yields `403 Forbidden`. If the parcel does not exist, `404 Not Found` is returned.

### 2.2 Token Expiry & Active Revocation
- **Bounded Lifetime:** The SSE connection duration is capped by `min(server_max_timeout, remaining_token_lifetime)`.
- **Scheduled Termination:** When the JWT token expires, the server closes the SSE stream gracefully (`SseEmitter.complete()`).
- **Dynamic Deauthorization (Couriers):** Before delivering any status event to an active courier session, the server re-checks that the courier is still actively assigned to the parcel. If the parcel has been unassigned or reassigned to another courier, the stream is closed immediately and the event is dropped.

---

## 3. Wire Format & Event Deduplication

### 3.1 Event Format
Events are transmitted over `text/event-stream` using the following wire format:

```http
:keepalive

id: 7c9e6679-7425-40de-944b-e07fc1f90ae7
event: parcel.status.changed
data: {"eventId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","parcelId":"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d","trackingNumber":"SWD-20261027-A1B2","aggregateVersion":3,"oldStatus":"PICKED_UP","newStatus":"IN_TRANSIT","reason":null,"occurredAt":"2026-10-27T09:15:30Z"}
```

- **Heartbeats:** Sent every 15 seconds as an SSE comment (`:keepalive\n\n`) to prevent proxy and gateway read timeouts.
- **Zero PII Guarantee:** Payloads contain strictly non-sensitive fields (`eventId`, `parcelId`, `trackingNumber`, `aggregateVersion`, `oldStatus`, `newStatus`, `reason`, `occurredAt`).

### 3.2 Monotonic Aggregate Version Sequencing
- Each parcel aggregate maintains a monotonically increasing `version` in PostgreSQL.
- Both the server session and client track `lastDeliveredVersion`.
- **Deduplication Rule:** If an incoming event has `aggregateVersion <= lastDeliveredVersion`, it is discarded as duplicate or stale.
- **Gap Detection Rule:** If an incoming event has `aggregateVersion > lastDeliveredVersion + 1`, a version gap has occurred (missed events). The client must trigger immediate REST recovery.

---

## 4. REST Recovery Protocol for Clients

Every consumer client (e.g., Customer UI, Courier App, Dispatcher Dashboard) must implement the canonical recovery flow.

```
       +---------------------------------------------+
       | Client Starts or Reconnects                 |
       +---------------------------------------------+
                              |
                              v
       +---------------------------------------------+
       | Step 1: Fetch Authoritative REST Snapshot    |
       | GET /api/v1/parcels/{parcelId}              |
       | Record snapshotVersion = response.version   |
       +---------------------------------------------+
                              |
                              v
       +---------------------------------------------+
       | Step 2: Establish SSE Stream                |
       | GET /api/v1/parcels/{parcelId}/events       |
       | Authorization: Bearer <fresh_token>         |
       +---------------------------------------------+
                              |
                              v
       +---------------------------------------------+
       | Step 3: Process Live Events                 |
       | - If event.version <= snapshotVersion: DROP |
       | - If event.version == lastVersion + 1: APPLY|
       | - If event.version > lastVersion + 1: GAP!  |
       +---------------------------------------------+
            |                                    |
            | Stream Interrupted                 | Version Gap Detected
            v                                    v
       +---------------------------------------------+
       | Step 4: Reconnect with Exponential Backoff  |
       | Backoff = min(maxWait, base * 2^attempt)    |
       | + random jitter (0..1000ms)                 |
       +---------------------------------------------+
```

### 4.1 Trigger Conditions for REST Snapshot
A client **MUST** trigger a canonical REST read (`GET /api/v1/parcels/{parcelId}`) under any of the following conditions:
1. **Initial Mount / Page Load:** Always load the current parcel state and history via REST before or concurrent with establishing the SSE stream.
2. **Connection Loss / Network Interruption:** When the SSE stream drops or closes.
3. **Version Gap Detected:** When `event.aggregateVersion > lastSeenVersion + 1`.
4. **Token Expiry Reconnect:** When the connection terminates due to JWT expiration, obtain a refreshed Keycloak token and resynchronize via REST before reconnecting SSE.
5. **Server Error / 5xx:** When the server returns `503 Service Unavailable` or connection reset during restarts.

### 4.2 Reconnection Algorithm with Jitter
To prevent thundering herds on `delivery-service` during service restarts or network recoveries, clients must reconnect using **bounded exponential backoff with full jitter**:

```javascript
/**
 * Canonical SwiftDrop SSE Reconnection Configuration
 */
const RECONNECT_CONFIG = {
  initialDelayMs: 1000,    // 1 second base
  maxDelayMs: 30000,       // 30 seconds ceiling
  backoffMultiplier: 2.0,  // Exponential factor
  maxRetries: 10
};

function calculateBackoffWithJitter(attempt) {
  const exponential = Math.min(
    RECONNECT_CONFIG.maxDelayMs,
    RECONNECT_CONFIG.initialDelayMs * Math.pow(RECONNECT_CONFIG.backoffMultiplier, attempt)
  );
  // Full jitter between 0 and exponential delay
  return Math.floor(Math.random() * exponential);
}
```

---

## 5. Concurrency Limits & Throttling (RFC 9457)

To prevent resource exhaustion on the single delivery replica, connection thresholds are strictly enforced:
- **Global Limit:** Maximum 100 active SSE streams per replica.
- **Per-User Limit:** Maximum 10 concurrent streams per user subject (`sub`).
- **Per-Parcel Limit:** Maximum 5 concurrent streams per parcel aggregate.

When any threshold is breached, the server rejects the connection with **HTTP 429 Too Many Requests**:

```json
{
  "type": "https://swiftdrop.com/errors/SSE_LIMIT_EXCEEDED",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Per-user SSE connection limit reached (max: 10)",
  "instance": "/api/v1/parcels/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d/events",
  "code": "SSE_LIMIT_EXCEEDED",
  "limitType": "USER",
  "maxAllowed": 10,
  "timestamp": "2026-10-27T09:16:00Z"
}
```

Clients receiving HTTP 429 must not immediately retry; they should fall back to graceful polling via REST at an interval not less than 30 seconds.

---

## 6. Multi-Replica Kafka-to-SSE Fan-Out Topology (Issue #161)

### 6.1 The Fan-Out Problem & Partition Starvation
In a horizontally scaled deployment where Delivery Service runs multiple replicas (e.g., Replica A and Replica B), SSE client HTTP connections are distributed across replicas by Gateway/Ingress.
- If replicas share a single Kafka consumer group (e.g. `delivery-service-sse`), Kafka assigns disjoint topic partitions to each replica (load-balancing).
- Under this misconfiguration, Replica A would consume partition 0 while Replica B consumed partition 1. A client connected to Replica B whose parcel updates are routed to partition 0 would **never receive live events** (partition starvation).
- To achieve true broadcast fan-out, **both replicas must consume 100% of topic partitions independently**.

### 6.2 Independent Consumer Group Architecture
Each active Delivery replica registers an independent, unique Kafka consumer group formatted as:
```
tracking.<stable-replica-instance-id>
```

#### Naming & Identity Invariants:
1. **Prefix:** `tracking.` (configurable via `swiftdrop.sse.kafka.consumer.group-prefix`).
2. **Stable Replica Identifier:** Resolved deterministically in the following priority order:
   - Config property: `swiftdrop.sse.kafka.consumer.replica-id`
   - Environment variables: `SWIFTDROP_SSE_REPLICA_ID`, `SSE_REPLICA_ID`, `POD_NAME`, `INSTANCE_ID`, `HOSTNAME`
   - Local hostname fallback (sanitized).
3. **No Dynamic Random IDs:** Replicas do not generate random UUIDs on restart. Reusing a stable pod or instance name (e.g., `delivery-service-0`, `delivery-service-1`, `replica-a`) ensures Kafka retains committed offsets across restarts without leaking dangling consumer groups.
4. **Collision Detection:** The application validates that simultaneous replicas do not configure duplicate replica identifiers, failing closed with a descriptive configuration error.

### 6.3 Consumer Lifecycle & Offset Policy
- **Auto Offset Reset:** Configured to `latest` (`swiftdrop.sse.kafka.consumer.auto-offset-reset = latest`).
  - New replicas or replicas recovering after prolonged downtime attach to the head of the topic.
  - Historical replay is strictly avoided. Reconnecting clients must resynchronize state via canonical REST snapshot before listening to live events.
- **Kafka Offset Retention & Scale-Down:**
  - Active replicas continuously heartbeat to maintain their consumer groups.
  - When a replica is permanently scaled down or decommissioned, the Kafka broker automatically purges its consumer group offsets after the cluster's `offsets.retention.minutes` (default 7 days).
- **Graceful Termination:** On SIGTERM / application shutdown, Spring Kafka listener containers terminate gracefully, committing offsets and leaving the group cleanly.

### 6.4 Version Deduplication & Gap Handling
- Each replica independently maintains an in-memory session registry (`ParcelSseConnectionManager`).
- For every active connection, `SseSubscriberSession` tracks `lastDeliveredVersion`:
  - **Duplicate/Stale Drop:** Events with `aggregateVersion <= lastDeliveredVersion` are dropped without emission.
  - **Gap Detection:** If `event.aggregateVersion > lastDeliveredVersion + 1`, a warning is logged (`Detected aggregate version gap`). The event is emitted, and the client triggers the canonical REST recovery protocol (`GET /api/v1/parcels/{parcelId}`).
- Re-delivery of events by Kafka does not result in duplicate SSE notifications or stale UI states.

### 6.5 Authorization Invariants Across Replicas
- **Replica-Local Routing:** Each replica routes Kafka events strictly to its locally connected, authorized sessions. No cross-replica in-memory session synchronization is required.
- **Dynamic Courier Revocation:** Before dispatching an event to an active courier SSE stream, the replica re-verifies active courier assignment (`existsByParcelIdAndCourierSubAndActiveTrue`). If unassigned or reassigned, the stream is closed immediately and the event is dropped.
- **Token Expiry Enforced:** Session lifetimes remain bounded by JWT token expiry across all replicas.
