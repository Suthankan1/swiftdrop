# SwiftDrop Delivery Operations Service — Foundation & Bounded Context

**Milestone:** November 10 Baseline (Architecture Baseline v1.3)<br/>
**Issue:** [Refs #126 (N10-B24)](https://github.com/Suthankan1/swiftdrop/issues/126) — Create Delivery app, owned migrations and bounded feature package contracts<br/>
**Primary Owner:** Sinthuha (@Sinthuha-n) — Delivery / Backend Lead<br/>
**Functional Reviewers:**
- `@Vibhushan-2003` — Delivery domain & courier status transition steward (Issue #23)
- `@Harithkeshan` — Dispatcher courier assignment steward (Issue #24)
- `@Suthankan1` — Platform / architecture & integration coordinator<br/>
**Runtime Baseline:** Java 21 LTS · Spring Boot 4.1.1 · Maven · Port 8083 · Logical Database `delivery_db`

---

## 1. Executive Summary & Purpose

The **Delivery Operations Service** is the authoritative Spring Boot microservice responsible for the core logistics lifecycle in SwiftDrop:
- Canonical parcel booking, aggregate consistency, and immutable tracking number issuance.
- Append-only chronological status transition audit log (`status_history`).
- Single-active-courier assignment coordination and courier capacity enforcement.
- Proof-of-delivery metadata reference validation (private object store object keys).
- Atomic Transactional Outbox publication (`outbox_events` -> Kafka KRaft topic `parcel.lifecycle.v1`).
- One-way live parcel status updates via Server-Sent Events (SSE).

Issue #126 establishes the **Spring Boot 4.1.1 application foundation, dedicated PostgreSQL Flyway schema, aggregate entities, and bounded feature package contracts** that provide the architectural scaffolding for upcoming delivery feature implementations:
- **Issue #20:** Delivery Booking & Canonical Parcel Provider (Sinthuha)
- **Issue #23:** Guarded Parcel Status/History and Failure Slice (Vibhushan)
- **Issue #24:** Single-Active-Courier Assignment Command (HarithKeshan)
- **Issue #29:** Courier Proof Capture & Delivery Metadata (Abhinaya)
- **Issue #31:** Delivery Outbox & Bounded Kafka Relay (Vibhushan)
- **Issue #94:** Composite Dashboard Read Projections & gRPC Provider (Vibhushan)

---

## 2. Mandatory Data Ownership & Architectural Boundaries

Per SwiftDrop Architecture Baseline v1.3, [DATA_OWNERSHIP.md](../../docs/architecture/DATA_OWNERSHIP.md), and [Figure 14 Logical ERD](../../docs/architecture/ISSUE_12_DELIVERY_DESIGN_REVIEW.md):

### Delivery OWNS:
- Relational schema and persistence in dedicated logical database `delivery_db` (application role `delivery_app`).
- Canonical parcel aggregate state (`parcels`).
- Append-only chronological status audit history (`status_history`).
- Request de-duplication lease and completion records (`idempotency_records`).
- Future feature persistence: `delivery_assignments` (Issue #24), `delivery_proofs` (Issue #29), and `outbox_events` (Issue #31).

### Delivery DOES NOT OWN (Strict Boundary Enforcement):
- ❌ User identity, passwords, credentials, or Keycloak identity representations (owned strictly by **Platform / Keycloak**). External subjects (`sender_sub`, `courier_sub`, `actor_sub`) are stored strictly as opaque scalar Strings.
- ❌ Merchant organization approval, status, or API key lifecycle (owned strictly by **Accounts & Partners**). Merchant identity is stored as an opaque scalar UUID (`merchant_id`).
- ❌ Rate calculation rules, zone tariffs, or fee computation (owned strictly by **Pricing & Zones**). When a parcel is booked, Delivery snapshots the accepted fee (`amount_minor_units`, `currency`, `quote_id`, `rate_version`) and never recalculates accepted fees.
- ❌ Notification jobs, email/SMS dispatch, or merchant webhook retries (owned strictly by **Communications**). Delivery publishes events to Kafka via its transactional outbox; Communications ingests and processes side-effects independently.
- ❌ Cross-service SQL joins or shared entity libraries: all cross-service boundaries use synchronous gRPC wire contracts (`shared/proto-contracts`) or asynchronous Kafka domain events.

---

## 3. Database Schema & Flyway Migration (`V1__create_delivery_schema.sql`)

The Delivery schema is version-controlled via Flyway under `src/main/resources/db/migration/`:

```text
+--------------------------------------------------------------------------------------------------+
|                                      DELIVERY_DB SCHEMA                                          |
|                                                                                                  |
|  [PARCELS] (Aggregate Root)                                                                      |
|  PK parcel_id (UUID)                                                                             |
|  UK tracking_number (VARCHAR 32)                                                                  |
|  - sender_sub (VARCHAR 64), merchant_id (UUID, nullable), quote_id (UUID, nullable),              |
|    rate_version (VARCHAR 32), amount_minor_units (BIGINT), currency (CHAR 3 DEFAULT 'LKR'),      |
|    weight_kg (NUMERIC 8,3), status (VARCHAR 20 DEFAULT 'CREATED'),                               |
|    version (BIGINT DEFAULT 1 - Optimistic Lock), created_at, updated_at                          |
|    CHECK (status IN ('CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','FAILED'))|
|       |                                                                                          |
|       | 1 : N (local FK RESTRICT)                                                                |
|       v                                                                                          |
|  [STATUS_HISTORY]                                                                                |
|  PK history_id (UUID)                                                                            |
|  FK parcel_id REFERENCES parcels(parcel_id)                                                      |
|  - previous_status, new_status (CHECK valid statuses), reason (VARCHAR 64), note (TEXT),          |
|    proof_id (UUID, nullable), actor_sub (VARCHAR 64), resulting_version (BIGINT), occurred_at    |
|  INDEX (parcel_id, resulting_version)                                                            |
|                                                                                                  |
|  [IDEMPOTENCY_RECORDS]                                                                           |
|  PK record_id (UUID)                                                                             |
|  UK (principal_or_tenant, idempotency_key)                                                       |
|  - request_digest (VARCHAR 64), status (IN_PROGRESS/COMPLETED/FAILED), stored_result (JSONB),    |
|    created_at, expires_at                                                                        |
|  INDEX (expires_at)                                                                              |
+--------------------------------------------------------------------------------------------------+
```

### Table Specifications & Invariants:
1. **`parcels`:**
   - Primary Key: `parcel_id UUID`.
   - Unique Tracking Number: `tracking_number VARCHAR(32) NOT NULL UNIQUE`.
   - Optimistic Concurrency: `version BIGINT NOT NULL DEFAULT 1` incremented on every state transition.
   - Status Check Constraint: `status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')`.
   - Opaque External References: `sender_sub VARCHAR(64)`, `merchant_id UUID`, `quote_id UUID`.
2. **`status_history`:**
   - Foreign Key: `parcel_id REFERENCES parcels(parcel_id) ON DELETE RESTRICT`.
   - Chronological Version Index: `idx_status_history_parcel_version` on `(parcel_id, resulting_version)`.
   - Audit trail capturing `previous_status`, `new_status`, `reason`, `note`, `proof_id`, `actor_sub`, and `occurred_at`.
3. **`idempotency_records`:**
   - Composite Unique Constraint: `(principal_or_tenant, idempotency_key)`.
   - Check Constraint: `status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')`.
   - Expiration Index: `idx_idempotency_expires_at` on `(expires_at)`.
4. **Future Migrations Reserved for Feature Issues:**
   - `V2__create_delivery_outbox_events.sql` is reserved strictly for **Issue #31** (Transactional Outbox).
   - Assignment tables (`delivery_assignments`) are reserved strictly for **Issue #24** (Courier Assignment).
   - Proof metadata tables (`delivery_proofs`) are reserved strictly for **Issue #29** (Proof Metadata).

---

## 4. Bounded Feature Package Contracts & Ownership

To prevent competing service scaffolds and ensure clean feature package ownership across team members, Delivery Operations organizes packages by architectural responsibility:

| Package | Responsibility | Primary Feature Owner | Linked Issue | Key Contracts / Classes |
|---|---|---|---|---|
| `com.swiftdrop.deliveryservice` | Bootstrap, Spring configuration & Actuator | Sinthuha / Platform | #126 | `DeliveryServiceApplication` |
| `com.swiftdrop.deliveryservice.core` | Aggregate root entity, enum, repository | Sinthuha | #126 / #20 | `Parcel`, `ParcelStatus`, `ParcelRepository`, `ParcelNotFoundException` |
| `com.swiftdrop.deliveryservice.status` | State machine guards, history audit, optimistic locking | Vibhushan | #126 / #23 | `StatusHistory`, `StatusHistoryRepository`, `ParcelLifecycleTransitionContract`, `InvalidTransitionException`, `VersionConflictException` |
| `com.swiftdrop.deliveryservice.idempotency` | Mutation de-duplication lease & completion | Sinthuha / Vibhushan | #126 / #20 | `IdempotencyRecord`, `IdempotencyStatus`, `IdempotencyRecordRepository`, `IdempotencyServiceContract` |
| `com.swiftdrop.deliveryservice.booking` | Booking intake boundary & quote snapshotting | Sinthuha | #20 | `BookingContract`, `BookParcelCommand`, `BookingResult` |
| `com.swiftdrop.deliveryservice.assignment` | Courier active assignment & workload check | HarithKeshan | #24 | `CourierAssignmentContract` |
| `com.swiftdrop.deliveryservice.proof` | Proof-of-delivery metadata validation | Abhinaya | #29 | `ProofMetadataContract` |
| `com.swiftdrop.deliveryservice.query` | Read projections for internal gRPC & GraphQL | Vibhushan | #94 | `ParcelProjection`, `ParcelQueryService` |

---

## 5. Configuration & Startup Validation

The application requires environment-driven datasource configuration:

```properties
server.port=8083
spring.application.name=delivery-service
spring.datasource.url=${DELIVERY_DB_URL}
spring.datasource.username=${DELIVERY_DB_USERNAME}
spring.datasource.password=${DELIVERY_DB_PASSWORD}
spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
```

### Fail-Closed Behavior:
- **Missing Configuration:** If `DELIVERY_DB_URL` is unset, the application fails startup immediately with a clear configuration exception rather than falling back to an in-memory database.
- **Unreachable Database:** If PostgreSQL is offline or credentials are invalid, HikariCP connection timeout triggers startup failure.
- **Actuator Health:** `GET http://localhost:8083/actuator/health` returns `200 UP` when the database connection is healthy, and `503 DOWN` if connectivity fails. Details and credentials are never exposed in unauthenticated health responses.

---

## 6. Local Development & Testing

### 6.1 Compiling and Running Unit Tests
From repository root:
```bash
# Build and run all unit, entity, migration, lifecycle, and negative configuration tests
mvn -f services/delivery-service/pom.xml test
```
Or using the Maven Wrapper inside the service directory:
```bash
cd services/delivery-service
./mvnw test
```

On Windows PowerShell:
```powershell
cd services\delivery-service
.\mvnw.cmd test
```

### 6.2 Running Real PostgreSQL Integration Tests (`*IT.java`)
Integration tests require a running Docker daemon to launch the Testcontainers PostgreSQL container:
```bash
mvn -f services/delivery-service/pom.xml verify
```
`DeliveryPersistencePostgresIT.java` executes on real PostgreSQL 16 Alpine:
- Clean Flyway V1 migration execution and schema validation.
- Atomically persisting parcel aggregate root and status history within a single transaction.
- Unique tracking number constraint enforcement.
- Idempotency record uniqueness enforcement per `(principal_or_tenant, idempotency_key)`.
- Parcel status check constraint enforcement against invalid state strings.

---

## 7. Status and Scope Boundaries

### IMPLEMENTED in Issue #126:
- [x] Spring Boot 4.1.1 application entry point and package architecture (`com.swiftdrop.deliveryservice`).
- [x] Dedicated Maven build with pinned Java 21 LTS runtime and Flyway / PostgreSQL / Protobuf contract dependencies.
- [x] Flyway migration `V1__create_delivery_schema.sql` defining `parcels`, `status_history`, and `idempotency_records` with constraints and indexes.
- [x] JPA entities (`Parcel`, `StatusHistory`, `IdempotencyRecord`).
- [x] Spring Data JPA repositories with query methods.
- [x] Bounded feature package contracts: `booking` (#20), `status` (#23), `assignment` (#24), `proof` (#29), `query` (#94), `idempotency`, and `core`.
- [x] Fail-closed datasource configuration and Actuator health check on port 8083.
- [x] 18 automated unit and slice tests passing with zero failures.
- [x] Testcontainers integration test harness for real PostgreSQL verification.

### EXPLICITLY NOT IMPLEMENTED (Belongs to Future Issues):
- ❌ Delivery booking business service or quote validation logic (belongs to **Issue #20**).
- ❌ Courier status transition REST endpoints and state mutation service (belongs to **Issue #23**).
- ❌ Single-active courier assignment command and tables (belongs to **Issue #24**).
- ❌ Proof-of-delivery photo upload adapter or MinIO S3 client (belongs to **Issue #29**).
- ❌ Outbox table and Kafka background publisher relay (belongs to **Issue #31**).
- ❌ Server-Sent Events (SSE) tracking stream handler (belongs to **Issue #26**).
- ❌ gRPC server stubs or private gRPC service implementation (belongs to **Issue #94** / **Issue #100**).
---

## 8. LATER-02 Quote-to-Booking Foundation (Issue #20)

**Owner:** Sinthuha (@Sinthuha-n) (Customer booking). Java 21 / Spring Boot 4.1.1.

**Production quote-to-booking is blocked and disabled.** There is no booking controller, no registered `BookingService` bean, and no production implementation of `BookingQuoteProvider` or `BookingAuthorization`. Only health is permitted by the foundation security chain (`BookingFoundationSecurity`). Do not add a permissive adapter to make the service accept bookings.

Read [LATER-02 contract/audit/handoff](../../docs/contracts/LATER_02_QUOTE_BOOKING.md) for evidence, examples, compatibility conflicts, data ownership and required Pricing decisions. Existing Pricing ValidateQuote fields and public OpenAPI contracts are preserved.

### Implemented Independently:
- Existing booking request fields with Jakarta validation and authenticated `CUSTOMER` actor extraction (`BookingActor`).
- Required adapter seams for quote authorization (`BookingAuthorization`) and existing Pricing validation results (`BookingQuoteProvider`).
- Internal booking result `ParcelBooking` (not the incomplete public `ParcelDetailResponse`).
- PostgreSQL transaction spanning parcel, initial history, outbox, and stored idempotent response.
- Concurrency-safe local key replay and one booking per quote, immutable fee/provenance, safe structured failures.
- Initial `CREATED` / version 1 only. No assignments, notifications, Kafka relay, live tracking, merchant orders, or frontend changes.

