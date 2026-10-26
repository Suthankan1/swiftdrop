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
- Authorized official PDF delivery receipt generation and streaming.

Issue #126 establishes the **Spring Boot 4.1.1 application foundation, dedicated PostgreSQL Flyway schema, aggregate entities, and bounded feature package contracts** that provide the architectural scaffolding for upcoming delivery feature implementations:
- **Issue #20:** Delivery Booking & Canonical Parcel Provider (Sinthuha)
- **Issue #23:** Guarded Parcel Status/History and Failure Slice (Vibhushan)
- **Issue #24:** Single-Active-Courier Assignment Command (HarithKeshan)
- **Issue #29:** Courier Proof Capture & Delivery Metadata (Abhinaya)
- **Issue #31:** Delivery Outbox & Bounded Kafka Relay (Vibhushan)
- **Issue #94:** Composite Dashboard Read Projections & gRPC Provider (Vibhushan)
- **LATER-09:** Bounded Authorized PDF Delivery Receipt Backend Adapter (Sinthuha)

---

## 2. Mandatory Data Ownership & Architectural Boundaries

Per SwiftDrop Architecture Baseline v1.3, [DATA_OWNERSHIP.md](../../docs/architecture/DATA_OWNERSHIP.md), and [Figure 14 Logical ERD](../../docs/architecture/ISSUE_12_DELIVERY_DESIGN_REVIEW.md):

### Delivery OWNS:
- Relational schema and persistence in dedicated logical database `delivery_db` (application role `delivery_app`).
- Canonical parcel aggregate state (`parcels`).
- Append-only chronological status audit history (`status_history`).
- Request de-duplication lease and completion records (`idempotency_records`).
- Future feature persistence: `delivery_assignments` (Issue #24), `delivery_proofs` (Issue #29), and `outbox_events` (Issue #31).
- Official bounded PDF delivery receipt generation and streaming adapter.

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
| `com.swiftdrop.deliveryservice.proof.storage` | Private proof-byte storage port and S3-compatible adapter | Abhinaya | #28 | `ProofObjectStorage`, `S3ProofObjectStorage` |
| `com.swiftdrop.deliveryservice.query` | Read projections for internal gRPC & GraphQL | Vibhushan | #94 | `ParcelProjection`, `ParcelQueryService` |
| `com.swiftdrop.deliveryservice.receipt` | Bounded Authorized PDF Delivery Receipt adapter | Sinthuha | LATER-09 | `PdfReceiptAdapter`, `ReceiptController`, `ReceiptService` |

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
# Build and run all unit, entity, migration, lifecycle, receipt, and negative configuration tests
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
- [x] Automated unit and slice tests passing with zero failures.
- [x] Testcontainers integration test harness for real PostgreSQL verification.

### EXPLICITLY NOT IMPLEMENTED (Belongs to Future Issues):
- ❌ Delivery booking business service or quote validation logic (belongs to **Issue #20**).
- ❌ Courier status transition REST endpoints and state mutation service (belongs to **Issue #23**).
- ❌ Single-active courier assignment command and tables (belongs to **Issue #24**).
- ❌ Proof metadata persistence and authorized upload/retrieval endpoints (belong to **Issue #95**); the independent private S3-compatible byte adapter is implemented separately under **Issue #28**.
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

---

## 9. Bounded Authorized PDF Delivery Receipt Backend Adapter (LATER-09)

**Owner:** Sinthuha (@Sinthuha-n) — Delivery Operations.

### Official Receipt Endpoint

Conforms strictly to `contracts/rest/openapi-delivery.yaml`:

- **Path:** `GET /api/v1/parcels/{parcelId}/receipt`
- **Method:** `GET`
- **Security:** Bearer JWT (Keycloak OIDC)
- **Content-Type:** `application/pdf`
- **Headers:**
  - `Content-Disposition: attachment; filename="receipt-SWD-YYYYMMDD-XXXX.pdf"`
  - `Cache-Control: no-store, no-cache, must-revalidate, max-age=0`
  - `Pragma: no-cache`
  - `X-Content-Type-Options: nosniff`

### Security & Object-Level Authorization (BOLA/IDOR Defense)

Receipts contain sensitive commercial and personal delivery data and are strictly access-controlled:
1. **Parcel Creator:** Requester `sub` must match `createdBySub`.
2. **Merchant Tenant:** Requester holding `MERCHANT` role must have matching `merchant_id` claim.
3. **Recipient:** Requester matching verified `phone_number` claim.
4. **Staff Oversight:** Requesters holding `ADMIN` or `DISPATCHER` roles.
5. **Cross-access denial:** Requesters without verified ownership receive `403 Forbidden` RFC 7807 ProblemDetail.
6. **Unauthenticated denial:** Missing or invalid tokens receive `401 Unauthorized` RFC 7807 ProblemDetail.
7. **Eligibility gate:** Non-completed parcels (not `DELIVERED`) or unconfirmed payments fail closed with `404 Not Found` ("Receipt not available or parcel not completed").

### Resource Bounds & Safety

PDF generation is deterministic, in-process, and strictly bounded using Apache PDFBox 3.0.5:
- **Max text length:** 2,000 characters per field.
- **Max financial line items:** 50 items.
- **Max document pages:** 5 pages (receipts format to 1 page).
- **Max output byte size:** 1,048,576 bytes (1 MB).
- **Zero SSRF:** Zero external network calls, zero external fonts, zero external template engines. Uses built-in Standard 14 PDF Helvetica fonts.
- **Zero float errors:** Minor-unit integer arithmetic preserved with exact decimal string formatting.

---

## 10. Private Proof Storage Adapter (Issue #28)

**Owner:** Abhinaya (`@abhi190503`) — Delivery Operations / Courier backend.

`ProofObjectStorage` is an internal, controller-free port for storing and retrieving private proof bytes. The
`S3ProofObjectStorage` implementation targets the Platform-owned private S3-compatible MinIO bucket and always uses
the one configured bucket. It generates opaque `proofs/v1/{uuid-v4}` references on the server, uses a conditional
create (`If-None-Match: *`) to prevent overwrite, rejects non-canonical references, streams exact declared lengths,
and maps provider failures to sanitized typed exceptions.

The adapter is disabled unless `SWIFTDROP_STORAGE_ENABLED=true`. When enabled, Delivery requires backend-only
injection of `S3_ENDPOINT`, `SWIFTDROP_STORAGE_BUCKET`, `SWIFTDROP_STORAGE_REGION`,
`SWIFTDROP_STORAGE_ACCESS_KEY`, and `SWIFTDROP_STORAGE_SECRET_KEY`. Optional call deadlines use
`SWIFTDROP_STORAGE_API_CALL_TIMEOUT` and `SWIFTDROP_STORAGE_API_CALL_ATTEMPT_TIMEOUT`. Never place these credentials
in frontend configuration or logs.

The adapter deliberately does not accept filenames, return public/presigned URLs, authorize users, persist proof
metadata, validate evidence-policy media types, choose upload-size limits, or implement retention/deletion. Issue #95
must authorize the caller before resolving an opaque reference and owns metadata/endpoint integration. Issue #124
owns approved retention and cleanup policy; deletion is not exposed until that contract is reviewed.

Run focused tests from `services/delivery-service`:

```powershell
.\mvnw.cmd -Dtest="ProofStorageSecurityTest,S3ProofObjectStorageTest,ProofStorageConfigurationTest" test
```

Real MinIO verification requires the approved local dependency stack and uniquely generated synthetic objects. It is
not represented as passing unless that stack is actually available during verification. The opt-in
`S3ProofObjectStorageMinioIT` requires `SWIFTDROP_MINIO_IT_ENABLED=true` plus the backend-only `S3_ENDPOINT`,
`SWIFTDROP_STORAGE_BUCKET`, `SWIFTDROP_STORAGE_REGION`, `SWIFTDROP_STORAGE_ACCESS_KEY`, and
`SWIFTDROP_STORAGE_SECRET_KEY` settings. It refuses non-loopback endpoints and buckets other than the approved
`swiftdrop-pod`, uses only random synthetic content, verifies anonymous denial and conditional non-overwrite behavior,
and deletes only the exact object it created. If the approved provider or application credentials are absent, the real
provider test is reported as skipped rather than passed.

---

## 11. Delivery Event-to-Recipient Authorization gRPC Service (Issue #111)

**Owner:** Sinthuha (`@Sinthuha-n`) — Delivery Operations Lead.

`DeliveryInternalServiceImpl` implements the authoritative server-side gRPC endpoints for `swiftdrop.delivery.v1.DeliveryInternalService`:
1. `GetParcelInternal`: Returns parcel projections for the GraphQL Query API (`IF-GRPC-05`).
2. `GetCourierWorkload`: Returns active workload count for couriers.
3. `AuthorizeEventRecipient`: Exposes secure, bounded, read-only event-to-recipient authorization for Communications (`IF-GRPC-11`, #111).

### Security & Invariant Rules
- **Machine Caller Enforcement:** Strictly verifies machine caller identity (`COMMUNICATIONS_SERVICE` via internal service JWT). Calls from unauthorized services or unauthenticated callers fail closed (`PERMISSION_DENIED` / `UNAUTHENTICATED`).
- **Forbidden Human Delegation:** Rejects requests with human actor tokens (`DelegationRequirement.FORBIDDEN`) to prevent spoofing on background notification jobs.
- **Authoritative Provenance:** Validates `event_id` against `outbox_events` and asserts that `event.aggregate_id == request.parcel_id`. Mismatched or forged pairings fail closed (`is_authorized = false`, `reason = EVENT_PARCEL_MISMATCH`).
- **Tenant Isolation:** Enforces merchant boundary when `merchant_id` is supplied (`MERCHANT_TENANT_MISMATCH`).
- **Recipient Association:** Authorizes only the legitimate parcel customer (`sender_sub`) or the currently active courier assigned to the parcel (`courier_sub`). Third parties and unassociated users are rejected (`RECIPIENT_NOT_ASSOCIATED`).
- **Staleness & Terminal Policies:** Evaluates state versions (`current_version` vs `event_version`). Superseded non-terminal events require `allow_stale = true` (`STALE_EVENT`). Superseded terminal events strictly reject outdated notifications unless explicit terminal override applies (`STALE_TERMINAL_EVENT`).
- **Zero PII Exposure:** Returns strictly minimal delivery metadata (`tracking_number`, `current_status`, `is_authorized`, `authorization_reason`). Plain recipient email, phone, street address, or raw payloads are never emitted.

### Verification
Execute focused test suite:
```bash
mvn test -Dtest=DeliveryInternalServiceGrpcTest -f services/delivery-service/pom.xml
```
