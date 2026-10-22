# SwiftDrop — Business Microservices Local Dependencies Stack

- **Milestone:** November 10, 2026 — Full SwiftDrop Release
- **Issue:** [Refs #91 (N10-P13)](https://github.com/Suthankan1/swiftdrop/issues/91) — Local Kafka, Owned Databases, and Private Object Storage
- **Module / Owner:** Platform / @Suthankan1
- **Target Integration Branch:** `test`

---

## 1. Overview & Architecture

This directory provides the reproducible, isolated, and non-destructive local container stack powering the dependencies required by SwiftDrop's business microservices:
- **Pricing & Zones Service** (HarithKeshan / Pushmitha)
- **Delivery Operations Service** (Vibhushan / Abhinaya / Sinthuha)
- **Communications Service** (Thuvarahan / Eric / Suthankan1)

```text
Host Developer Workstation (macOS / Linux / WSL)
  │
  ├─► 127.0.0.1:9092 ──► Apache Kafka KRaft (Single Node, Persistent Log)
  │                      Topics: parcel.lifecycle.v1, parcel.lifecycle.v1.dlt
  │
  ├─► 127.0.0.1:5439 ──► Business PostgreSQL 16 (Isolated Roles & Logical DBs)
  │                      ├── pricing_db        (owner: pricing_app)
  │                      ├── delivery_db       (owner: delivery_app)
  │                      └── communications_db (owner: communications_app)
  │
  └─► 127.0.0.1:9000 ──► Private S3 Object Store (MinIO Engine)
      127.0.0.1:9001     Bucket: swiftdrop-pod (Proof of Delivery, 100% Private)
```

> [!IMPORTANT]
> **Coexistence & Data Safety:** This dependency stack is completely decoupled from the November 1 identity stack (`swiftdrop-keycloak`, `swiftdrop-mailpit`, `swiftdrop-accounts-postgres`). It operates on distinct host ports and dedicated Docker named volumes. Existing databases, Keycloak users, and credentials are preserved without modification.

---

## 2. Port & Network Allocation

All externally exposed ports are strictly bound to loopback (`127.0.0.1`) to prevent accidental LAN/WAN exposure:

| Service | Host Port (Loopback) | Container Port | Container Name | Network Endpoint (Internal) |
|---|---|---|---|---|
| **Apache Kafka** | `127.0.0.1:9092` | `29092` (internal) / `9092` (external) | `swiftdrop-kafka` | `kafka:29092` |
| **Business PostgreSQL** | `127.0.0.1:5439` | `5432` | `swiftdrop-business-postgres` | `business-postgres:5432` |
| **MinIO S3 API** | `127.0.0.1:9000` | `9000` | `swiftdrop-minio` | `http://minio:9000` |
| **MinIO Web Console** | `127.0.0.1:9001` | `9001` | `swiftdrop-minio` | `http://minio:9001` |

---

## 3. PostgreSQL Logical Databases & Role Isolation

In accordance with [Architecture Baseline v1.3](../architecture/BASELINE.md) and [Data Ownership](../architecture/DATA_OWNERSHIP.md), each business service possesses sole ownership over its logical database. Cross-service database access is strictly prohibited at the PostgreSQL authorization layer:

| Database | Owning Service | Application Role | Access Confinement |
|---|---|---|---|
| **`pricing_db`** | Pricing & Zones | `pricing_app` | `pricing_db` only. Connection to `delivery_db` or `communications_db` returns `FATAL: permission denied`. |
| **`delivery_db`** | Delivery Operations | `delivery_app` | `delivery_db` only. Connection to `pricing_db` or `communications_db` returns `FATAL: permission denied`. |
| **`communications_db`** | Communications | `communications_app` | `communications_db` only. Connection to `pricing_db` or `delivery_db` returns `FATAL: permission denied`. |

### Security Invariants
1. **Unprivileged Roles:** Application roles are provisioned with `NOSUPERUSER NOCREATEDB NOREPLICATION`.
2. **Revocation of Public Access:** `REVOKE ALL ON DATABASE ... FROM PUBLIC` ensures no role can access peer databases.
3. **No Cross-Service Foreign Keys:** Entity references across domains are opaque identifiers resolved via gRPC APIs.

---

## 4. Kafka Event Contracts & Topic Conventions

Review the frozen integration contracts in [Runtime Decision Register](../architecture/RUNTIME_DECISION_REGISTER.md) and [Kafka Contracts](../contracts/KAFKA.md):

| Setting | Approved Value / Convention | Classification |
|---|---|---|
| **Primary Lifecycle Topic** | `parcel.lifecycle.v1` | **APPROVED** (Baseline v1.3, DEC-EVT-01) |
| **Domain Event Type** | `parcel.status.changed.v1` | **APPROVED** (Schema v1.0.0, DEC-EVT-01) |
| **Partition Key** | `parcelId` (UUID string) | **APPROVED** (DEC-EVT-02, strict FIFO per parcel) |
| **Dead-Letter Topic (DLT)** | `parcel.lifecycle.v1.dlt` | **PROPOSED CONVENTION** (Local dev DLT target) |
| **Partition Count** | `3` partitions | **PROPOSED CONVENTION** (Dev default, single-node broker) |
| **Replication Factor** | `1` | **PROPOSED CONVENTION** (Dev default, single-node broker) |

### Approved Consumer Group Conventions
- **Communications Notifications:** `swiftdrop-communications-notifications`
- **Communications Webhooks:** `swiftdrop-communications-webhooks`
- **Delivery SSE Live Tracking:** `swiftdrop-delivery-tracking` (or `tracking.<replica-id>` for multi-pod fanout per DEC-SSE-02)

---

## 5. Private S3 Object Storage (Proof of Delivery)

Delivery Operations captures proof-of-delivery photos and recipient signatures in private object storage:
- **Bucket Name:** `swiftdrop-pod`
- **Privacy Standard:** Strictly private. Anonymous reads (`GET`), anonymous uploads (`PUT`), and anonymous bucket listing (`GET /`) return `HTTP 403 Forbidden` (`AccessDenied`).
- **Access Pattern:** Only authorized backend microservices (Delivery Operations) hold S3 credentials. The browser SPA never receives S3 admin keys or public object URLs.

---

## 6. Local Tooling & Lifecycle Runbook

All helper scripts are located in `platform/dependencies/`:

### 6.1 Start Dependencies
```bash
./platform/dependencies/start-dependencies.sh
```
What it does:
1. Verifies loopback port availability on `9092`, `5439`, `9000`, `9001`.
2. Boots `swiftdrop-kafka`, `swiftdrop-business-postgres`, and `swiftdrop-minio` via Docker Compose.
3. Waits for health checks on all three dependencies.
4. Idempotently provisions `parcel.lifecycle.v1` and `parcel.lifecycle.v1.dlt` via `swiftdrop-kafka-init`.
5. Idempotently provisions the private `swiftdrop-pod` bucket via `init-storage.py`.

### 6.2 Check Dependency Readiness
```bash
./platform/dependencies/check-dependencies.sh
```
Runs 14 automated health, topic existence, and role isolation probes.

### 6.3 Execute Full Smoke & Persistence Test Suite
```bash
./platform/dependencies/smoke-dependencies.sh
```
Executes the comprehensive automated verification suite:
- Event schema validation against `parcel.status.changed.v1.schema.json`.
- Kafka publish & consume smoke with partition key `parcelId` and consumer group offsets.
- Kafka restart and topic/offset persistence verification.
- PostgreSQL role connectivity and cross-database access denial.
- PostgreSQL container restart and data persistence verification.
- MinIO anonymous access rejection (HTTP 403 Forbidden).
- S3 object upload, download, and SHA256 checksum matching.
- MinIO restart and object persistence verification.
- Verification that existing Accounts DB and Keycloak resources remain intact.

### 6.4 Stop Dependencies (Preserving Named Volumes)
```bash
./platform/dependencies/stop-dependencies.sh
```
Gracefully stops containers without removing Docker named volumes.

---

## 7. Service Owner Handoff Reference

When developing individual business services, configure the following environment variables:

### Pricing & Zones Service
```bash
export PRICING_DB_URL=jdbc:postgresql://localhost:5439/pricing_db
export PRICING_DB_USERNAME=pricing_app
export PRICING_DB_PASSWORD=pricing_dev_password
```

### Delivery Operations Service
```bash
export DELIVERY_DB_URL=jdbc:postgresql://localhost:5439/delivery_db
export DELIVERY_DB_USERNAME=delivery_app
export DELIVERY_DB_PASSWORD=delivery_dev_password

export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TOPIC_PARCEL_LIFECYCLE=parcel.lifecycle.v1

export S3_ENDPOINT=http://localhost:9000
export S3_BUCKET=swiftdrop-pod
export S3_REGION=us-east-1
export S3_ACCESS_KEY=swiftdrop_storage_admin
export S3_SECRET_KEY=minio_dev_password
```

### Communications Service
```bash
export COMMUNICATIONS_DB_URL=jdbc:postgresql://localhost:5439/communications_db
export COMMUNICATIONS_DB_USERNAME=communications_app
export COMMUNICATIONS_DB_PASSWORD=communications_dev_password

export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TOPIC_PARCEL_LIFECYCLE=parcel.lifecycle.v1
export KAFKA_CONSUMER_GROUP_NOTIFICATIONS=swiftdrop-communications-notifications
export KAFKA_CONSUMER_GROUP_WEBHOOKS=swiftdrop-communications-webhooks
```
