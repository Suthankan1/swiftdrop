# SwiftDrop — Pricing & Zones Service

## 1. Purpose & Ownership

The **Pricing & Zones Service** is an independent Spring Boot 4.1.1 business service in the SwiftDrop parcel delivery platform. It is the sole authoritative owner of:
- Geographic delivery zones and transit time expectations (`pricing_zones`)
- Immutable versioned rate cards (`pricing_rate_versions`)
- Tiered route and size category pricing matrices (`pricing_rate_rules`)
- Deterministic quote calculation algorithms

### Service Ownership Boundary
- **Module Owner:** HarithKeshan (@Harithkeshan) — GitHub Issue #93 (N10-B02)
- **Database Ownership:** Exclusively reads and writes `pricing_db` via the isolated application role `pricing_app`.
- **Boundaries & Invariants:**
  - Pricing & Zones has **zero access** to `accounts_db`, `delivery_db`, or `communications_db`.
  - No cross-service SQL joins or shared foreign keys.
  - Delivery Operations later snapshots agreed quote and fee metadata at booking time; Delivery never recalculates prices, and Pricing never mutates `delivery_db`.

---

## 2. Runtime Technology Stack

- **Language / Runtime:** Java 21 LTS
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7 / Spring Data JPA / Spring WebMVC)
- **Database:** PostgreSQL 16 (Logical DB: `pricing_db`, Role: `pricing_app`)
- **Schema Migrations:** Flyway (Additive versioned migrations under `db/migration`)
- **Build Tool:** Apache Maven 3.9+ with bundled Maven Wrapper (`./mvnw`)
- **Service Port:** `8082` (Internal / Gateway route: `http://localhost:8082`)

---

## 3. Environment Variables & Platform Dependencies

Platform-owned infrastructure dependencies (PostgreSQL, Kafka KRaft, MinIO) are provisioned via `platform/dependencies/`. Local development environment variables are supplied from `platform/dependencies/.env` (copied from `platform/dependencies/.env.example`).

| Environment Variable | Default Value | Description |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port for the Pricing service |
| `PRICING_DB_URL` | `jdbc:postgresql://127.0.0.1:5439/pricing_db` | PostgreSQL JDBC connection URL |
| `PRICING_DB_USERNAME` | `pricing_app` | Dedicated unprivileged PostgreSQL role |
| `PRICING_DB_PASSWORD` | *(from local `.env`)* | Application role database password |

> **Security Note:** Application YAML files never contain hardcoded credentials. All credentials load from environment variables at runtime.

---

## 4. Database Schema & Flyway Migrations

Migrations are strictly additive and versioned:

### `V1__create_pricing_zones_and_rates_schema.sql`
- **`pricing_zones`**:
  - `id` (UUID PK)
  - `zone_code` (VARCHAR(32) UNIQUE NOT NULL)
  - `name` (VARCHAR(128) NOT NULL)
  - `description` (VARCHAR(512) NOT NULL)
  - `standard_transit_hours` (INT NOT NULL CHECK > 0)
  - `active` (BOOLEAN NOT NULL DEFAULT true)
  - Timestamps (`created_at`, `updated_at`)
- **`pricing_rate_versions`**:
  - `id` (UUID PK)
  - `version_code` (VARCHAR(64) UNIQUE NOT NULL, e.g. `RATE-2026-V1`)
  - `currency` (VARCHAR(3) NOT NULL CHECK regex `^[A-Z]{3}$`)
  - `status` (VARCHAR(32) NOT NULL CHECK: `DRAFT`, `ACTIVE`, `SUPERSEDED`, `ARCHIVED`)
  - `effective_from` (TIMESTAMPTZ NOT NULL)
  - `effective_to` (TIMESTAMPTZ CHECK `effective_to > effective_from`)
  - **Index:** `idx_unique_active_rate_version` (Partial unique index ensuring at most one `ACTIVE` card per currency)
- **`pricing_rate_rules`**:
  - `id` (UUID PK)
  - `rate_version_id` (FK to `pricing_rate_versions.id` ON DELETE RESTRICT)
  - `pickup_zone_code` (FK to `pricing_zones.zone_code` ON DELETE RESTRICT)
  - `dropoff_zone_code` (FK to `pricing_zones.zone_code` ON DELETE RESTRICT)
  - `size_category` (CHECK: `SMALL`, `MEDIUM`, `LARGE`, `OVERSIZED`)
  - `min_weight_kg` (NUMERIC(6,2) CHECK >= 0.01)
  - `max_weight_kg` (NUMERIC(6,2) CHECK <= 30.00 AND >= min_weight_kg)
  - `base_fee_minor_units` (BIGINT CHECK >= 0)
  - `per_kg_surcharge_minor_units` (BIGINT CHECK >= 0)
  - `weight_threshold_kg` (NUMERIC(6,2) CHECK >= 0.00)
  - **Constraint:** Unique rule band per (`rate_version_id`, `pickup_zone_code`, `dropoff_zone_code`, `size_category`, `min_weight_kg`, `max_weight_kg`)

### `V2__seed_baseline_pricing_matrix.sql`
- Seeds operational zones: `ZONE_1` (Colombo Municipal District), `ZONE_2` (Western Province Suburbs), `ZONE_3` (National / Island-wide).
- Seeds active tariff card: `RATE-2026-V1` (Currency `LKR`, status `ACTIVE`).
- Seeds deterministic synthetic baseline tariff rules for all zone pairs across all size categories.

---

## 5. Domain Engine & Pricing Invariants

### Currency & Minor Units (DEC-PRC-01, DEC-PRC-02)
- Default currency is **`LKR`** (Sri Lankan Rupee).
- All fees are represented and computed using **integer minor units** (`long amountMinorUnits`, 1 LKR = 100 minor units).
- Java floating-point types (`float`, `double`) are **never** used for monetary values. `Math.addExact` protects against overflow.

### Physical & Volumetric Measurement Rules (DEC-PRC-04, DEC-PRC-05)
- **Weight Bounds:** Physical and chargeable weight must be between `0.01 kg` and `30.00 kg` (2 decimal places).
- **Dimension Bounds:** Linear dimensions must be integers between `1 cm` and `150 cm`.
- **Girth Limit:** Combined length and girth `L + 2*(W + H) <= 300 cm` (where L is the longest side).
- **Volumetric Divisor:** `(length * width * height) / 5000.0`, rounded to 2 decimal places (`HALF_UP`).
- **Chargeable Weight:** `max(physicalWeightKg, volumetricWeightKg)`.
- **Size Categories:**
  - `SMALL`: max dimension <= 25 cm, chargeable weight <= 1.00 kg
  - `MEDIUM`: max dimension <= 50 cm, chargeable weight <= 5.00 kg
  - `LARGE`: max dimension <= 100 cm, chargeable weight <= 15.00 kg
  - `OVERSIZED`: max dimension <= 150 cm, chargeable weight <= 30.00 kg

### Tiered Pricing Calculation Strategy
$$\text{Total Fee} = \text{Base Fee} + \max(0, \text{Chargeable Weight} - \text{Weight Threshold}) \times \text{Per-Kg Surcharge}$$
- All calculations are reproducible: identical input + identical version = identical output.

### Version Immutability Guarantees
- Published rate cards (`ACTIVE`, `SUPERSEDED`, `ARCHIVED`) are strictly immutable.
- Activating a newer rate version creates a new version record and leaves historical versions and past quote snapshots completely unaffected.

---

## 6. Build, Verification & Testing

### Running Unit Tests
```bash
cd services/pricing-service
./mvnw clean test
```
All 43 unit tests verify:
- Minor-unit arithmetic and currency boundaries
- Physical measurement bounds, girth, and volumetric weight
- Size category classification
- Rate version immutability and lifecycle transitions
- Deterministic calculation reproducibility and surcharge accuracy
- Unknown and inactive zone rejection
- Ambiguous rate rule rejection

### Running Integration Tests
```bash
cd services/pricing-service
./mvnw clean verify
```
PostgreSQL integration tests (`PricingPersistencePostgresIT`) utilize Testcontainers (`postgres:16-alpine`) with `@Testcontainers(disabledWithoutDocker = true)`.
- If Docker Desktop is active, tests verify real Flyway execution, check constraints, foreign keys, and unique indexes.
- If Docker is unavailable in the local environment, Testcontainers tests are reported as **BLOCKED / SKIPPED**, without breaking local offline compilation.

---

## 7. Downstream Integration & Handoff Boundaries

| Issue / Milestone | Owner | Integration Contract | Handoff Boundary Established in #93 |
|---|---|---|---|
| **Issue #108 (N10-B03)** Quote REST & Snapshots | HarithKeshan | `POST /api/v1/quotes`<br>`GET /api/v1/quotes/{id}`<br>`GET /api/v1/zones` | `PricingCalculationService` provides deterministic calculation and component breakdown (`baseFee`, `weightSurcharge`, `totalAmount`, `chargeableWeight`, `sizeCategory`). #108 wraps this with expiring quote persistence and REST controllers. |
| **Issue #114 (N10-B04)** Pricing gRPC Provider | Pushmitha | `PricingInternalService.ValidateQuote`<br>(`IF-GRPC-04`) | Exposes `RateVersionQueryService` to verify quote parameters against agreed snapshots and active/historical rate versions. |
| **Issue #123 (N10-WF21)** Admin Pricing CRUD | Pushmitha | Admin rate creation commands | Exposes versioned rate repositories and immutability guards (`ensureMutable()`) ensuring new rates are created as new versions rather than mutating historical tariffs. |

---

## 8. Runtime Policy Status & Decisions Register

| Decision | Topic | Status | Notes / Assumption in Baseline |
|---|---|---|---|
| `DEC-PRC-01` | Currency LKR | **APPROVED** | Baseline standard |
| `DEC-PRC-02` | Integer Minor Units | **APPROVED** | Baseline standard |
| `DEC-PRC-03` | 15-Minute Quote Expiry | *PROPOSED* | Fixed 15-minute window modeled for #108 |
| `DEC-PRC-04` | 0.01 - 30.00 kg Limits | *PROPOSED* | Modeled in `ParcelMeasurements` validation |
| `DEC-PRC-05` | 150cm Max Dim / 5000 Divisor | *PROPOSED* | Modeled in `ParcelMeasurements` & `SizeCategory` |
| `DEC-PRC-06` | ZONE_1, ZONE_2, ZONE_3 | *PROPOSED* | Seeded in `V2__seed_baseline_pricing_matrix.sql` |
| `DEC-PRC-07` | Single-Use Quote Validation | **APPROVED** | Domain snapshot readiness verified for #108/#114 |
| `DEC-PRC-08` | Delivery Snapshot Boundary | **APPROVED** | Delivery snapshots fee; Pricing never writes to `delivery_db` |
