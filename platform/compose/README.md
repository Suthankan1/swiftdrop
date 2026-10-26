# SwiftDrop — Full-Stack Persistent Docker Compose Runtime (Issue #141)

**Issue:** [#141](https://github.com/Suthankan1/swiftdrop/issues/141) · **Owner:** @Suthankan1 · **Priority:** P0  
**Target Integration Branch:** `test` · **Feature Branch:** `feat/n10-p06-full-stack-compose-runtime`  
**Independent Reviewers:** @Harithkeshan, @Vibhushan-2003  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`, `docs/architecture/RUNTIME_DECISION_REGISTER.md`)  
**Strict $0 Infrastructure Policy:** ADR-0011 (`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`)

---

## 1. Architecture Overview & Topology

The SwiftDrop Full-Stack Compose runtime delivers a reproducible, non-destructive, persistent local integration profile. It connects the **eight (8) approved application and identity images** from Issue #128 with the existing service-owned database foundations, Kafka KRaft streaming broker, private MinIO object storage, and Mailpit local email receiver.

```text
                               +------------------------------------+
                               |     Host Browser (127.0.0.1)       |
                               +-----------------+------------------+
                                                 |
                   +-----------------------------+----------------------------+
                   |                             |                            |
                   v                             v                            v
          +-----------------+           +------------------+         +------------------+
          |   React SPA     |           |   API Gateway    |         | Themed Keycloak  |
          |  (Port: 3000)   |           |   (Port: 8080)   |         |   (Port: 8090)   |
          +--------+--------+           +--------+---------+         +--------+---------+
                   |                             |                            |
===================|=============================|============================|==================
Docker Network:    |                             | (app-net: bridge)          | (JWKS fetch)
                   |                             v                            |
                   |      +-------------------------------------------+       |
                   |      |  Accounts Service   (Port: 8081 / gRPC: 9081)     |
                   |      |  Pricing Service    (Port: 8082 / gRPC: 9083)     |
                   |      |  Delivery Service   (Port: 8083 / gRPC: 9082)     |
                   |      |  Comms Service      (Port: 8084)                  |
                   |      |  GraphQL Query API  (Port: 8085)                  |
                   |      +----------------------+--------------------+       |
                   |                             |                            |
===================|=============================|============================|==================
Docker Network:                                  | (dependencies-net: bridge) |
                                                 v                            v
               +----------------------------------------------------------------------+
               |  accounts-postgres   (accounts_db       - Port 5438 - accounts_app)  |
               |  keycloak-postgres   (keycloak_db       - Port 5437 - keycloak)      |
               |  business-postgres   (pricing_db        - Port 5439 - pricing_app)   |
               |                      (delivery_db       - Port 5439 - delivery_app)  |
               |                      (communications_db - Port 5439 - comms_app)     |
               |  swiftdrop-kafka     (KRaft Broker      - Port 9092 / 29092)         |
               |  swiftdrop-minio     (Private S3        - Port 9000 / 9001)          |
               |  swiftdrop-mailpit   (Local SMTP Sink   - Port 8025 / 1025)          |
               +----------------------------------------------------------------------+
```

---

## 2. Approved Eight-Image Inventory

All images are built multi-stage, non-root, pinned to immutable Git commit SHA tags (`b8ac497`), and verified with 0 High/Critical vulnerabilities in Issue #128:

| # | Container Name | Approved Image Repository | Pinned SHA Tag | Runtime User | Listening Ports | Health Probe Contract |
|---|---|---|---|---|---|---|
| 1 | `swiftdrop-frontend` | `ghcr.io/suthankan1/swiftdrop-frontend` | `b8ac497` | `101:101` (`nginx`) | `8080/tcp` (host `3000`) | `GET /health` -> `200 OK` |
| 2 | `swiftdrop-api-gateway` | `ghcr.io/suthankan1/swiftdrop-api-gateway` | `b8ac497` | `10001:10001` (`swiftdrop`) | `8080/tcp` (host `8080`) | `GET /actuator/health` -> `200 OK` |
| 3 | `swiftdrop-accounts-service` | `ghcr.io/suthankan1/swiftdrop-accounts` | `b8ac497` | `10001:10001` (`swiftdrop`) | `8081/tcp`, `9081/tcp` | `GET /actuator/health` -> `200 OK` |
| 4 | `swiftdrop-pricing-service` | `ghcr.io/suthankan1/swiftdrop-pricing` | `b8ac497` | `10001:10001` (`swiftdrop`) | `8082/tcp`, `9083/tcp` | `GET /actuator/health` -> `200 OK` |
| 5 | `swiftdrop-delivery-service` | `ghcr.io/suthankan1/swiftdrop-delivery` | `b8ac497` | `10001:10001` (`swiftdrop`) | `8083/tcp`, `9082/tcp` | `GET /actuator/health` -> `200 OK` |
| 6 | `swiftdrop-communications-service`| `ghcr.io/suthankan1/swiftdrop-communications`| `b8ac497`| `10001:10001` (`swiftdrop`)| `8084/tcp` | `GET /actuator/health` -> `200 OK` |
| 7 | `swiftdrop-graphql-query-service` | `ghcr.io/suthankan1/swiftdrop-graphql` | `b8ac497` | `10001:10001` (`swiftdrop`) | `8085/tcp` | `GET /actuator/health` -> `200 OK` |
| 8 | `swiftdrop-keycloak` | `ghcr.io/suthankan1/swiftdrop-keycloak` | `b8ac497` | `1000:0` (`keycloak`) | `8080/tcp` (host `8090`) | `TCP socket /dev/tcp/127.0.0.1/8080` |

---

## 3. Five Logical Databases & Security Boundaries

Per `docs/architecture/DATA_OWNERSHIP.md`, every business service strictly owns its own schema, credentials, transactions, and Flyway migrations:

1. `accounts_db` on `accounts-postgres`: Owned by Accounts service. Dedicated role `accounts_app`. Flyway migrations V1–V5.
2. `pricing_db` on `business-postgres`: Owned by Pricing service. Dedicated role `pricing_app`. Flyway migrations V1–V3.
3. `delivery_db` on `business-postgres`: Owned by Delivery service. Dedicated role `delivery_app`. Flyway migrations V1–V4.
4. `communications_db` on `business-postgres`: Owned by Communications service. Dedicated role `communications_app`. Flyway migration V1.
5. `keycloak_db` on `keycloak-postgres`: Dedicated Keycloak identity database. Native schema initialized by Keycloak.

**Isolation Guarantees:**
- Cross-service SQL queries are strictly impossible (revoked connect/schema grants).
- Zero shared JPA business entities or cross-service foreign keys.
- No database superuser connection in application runtime configuration.
- Host ports (`5437`, `5438`, `5439`) bound strictly to loopback (`127.0.0.1`) for local operator inspection only.

---

## 4. Host Prerequisites & Capacity Preflight

- **Docker Engine / Desktop:** >= 26.0 (Compose v2 / v5 supported).
- **Architecture:** Linux ARM64 (Apple Silicon natively supported) and AMD64.
- **Measured Docker Allocation:** 4 CPUs, ~4.06 GiB RAM.
- **JVM Ergonomics & Memory Tuning:**
  To guarantee stability on hosts with 4.06 GiB RAM, all Java services enforce conservative container heap constraints:
  ```bash
  JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -Xms96m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -Xss512k"
  ```
  Total combined active memory across all 14 containers is ~2.8–3.2 GiB, fitting safely under the host memory ceiling without triggering OOM killer events.

---

## 5. Operator Commands

All lifecycle scripts are located in `platform/compose/` and are fully non-destructive:

### 5.1 Start Runtime
```bash
./platform/compose/start.sh
```
- Performs host capacity and prerequisite checks.
- Auto-initializes local `.env` from `.env.example` if absent.
- Checks loopback ports for collisions.
- Stages startup: (1) Databases/Kafka/MinIO -> (2) Keycloak -> (3) Java microservices, Gateway & Frontend.
- Waits for real readiness gates on all services.

### 5.2 Inspect Runtime Status
```bash
./platform/compose/status.sh
```
- Displays container status, CPU usage, memory RSS, and active health probe results.

### 5.3 Run Automated Invariant Verification
```bash
./platform/compose/verify.sh
```
- Verifies Flyway migration history across all 5 databases.
- Performs negative cross-service database access authorization tests.
- Verifies Kafka topic creation (`parcel.lifecycle.v1`, `parcel.lifecycle.v1.dlt`).
- Verifies MinIO private bucket permissions (anonymous GET returns HTTP 403).
- Verifies Keycloak OIDC issuer consistency and Spring Cloud Gateway routes.

### 5.4 Run Integration Smoke Tests
```bash
./platform/compose/smoke.sh
```
- Probes edge SPA and Gateway health endpoints.
- Tests Keycloak OIDC discovery.
- Calculates a real pricing quote through the API Gateway (`POST /api/v1/quotes`), verifying quote creation and persistence.
- Evaluates Delivery booking readiness (accurately reporting `[BLOCKED]` per Issue #20 / #114).
- Tests stop/restart persistence by inserting a durable record, restarting the database container, and verifying record survival.

### 5.5 Stop Runtime (Data Preserved)
```bash
./platform/compose/stop.sh
```
- Gracefully stops all containers without destroying persistent volumes.

---

## 6. Coexistence vs. Fresh Clone Modes

| Environment State | Behavior | Operator Action |
|---|---|---|
| **Clean Clone** | Creates all 5 volumes fresh, provisions databases, runs migrations, imports canonical `realm-swiftdrop.json` into `keycloak_db`. | Run `./platform/compose/start.sh` directly. |
| **Existing Local Dev** | Detects existing `swiftdrop-business-postgres-data`, `devarom-accounts-postgres-data`, or stopped developer containers. | Set `ACCOUNTS_DB_VOLUME=devarom-accounts-postgres-data` in `.env` if preserving legacy dev accounts; existing data is never truncated. |

---

## 7. Downstream Deployment Handoffs

### 7.1 Handoff to Issue #149 (Helm, Ingress & TLS Security)
- **Image References:** Exact eight images published to private GHCR under `:b8ac497`.
- **Port Map:**
  - Frontend: `8080/tcp` (external Ingress `443`)
  - Gateway: `8080/tcp` (external Ingress `443/api/*`)
  - Keycloak: `8080/tcp` (external Ingress `443/realms/*`)
  - Internal Java Microservices: `8081`–`8085` (private cluster IP only)
  - gRPC Endpoints: `9081` (Accounts), `9082` (Delivery), `9083` (Pricing)
- **Probes:**
  - Liveness/Readiness: `httpGet: /actuator/health` for Java, `/health` for Frontend, `/realms/swiftdrop` for Keycloak.

### 7.2 Handoff to Issue #154 (Persistent Kubernetes Workloads)
- **Stateful Workloads:**
  - 5 Logical PostgreSQL databases requiring StatefulSets / PVCs (`accounts`, `keycloak`, `business-postgres` with pricing, delivery, communications).
  - Kafka KRaft single-broker or small cluster with persistent log storage (`swiftdrop-kafka-data`).
  - MinIO private storage with dedicated PVC (`swiftdrop-minio-data`).
- **Startup Sequencing:**
  - DBs and Kafka must report `Ready` before microservices launch.
  - Keycloak must complete schema migration and OIDC discovery readiness before API Gateway starts.

---

## 8. Verification & Acceptance Summary

- [x] Documented single startup workflow (`start.sh`, `stop.sh`, `status.sh`, `verify.sh`, `smoke.sh`).
- [x] Exact eight (8) runtime images integrated.
- [x] Five (5) logically isolated PostgreSQL databases with dedicated service credentials.
- [x] Kafka KRaft single broker with provisioned lifecycle topics.
- [x] MinIO private object storage with unprivileged bucket policies.
- [x] Local capture-only Mailpit SMTP receiver.
- [x] Non-root execution across all containers.
- [x] Bounded JVM memory ergonomics operating within host 4.06 GiB RAM ceiling.
- [x] Zero secrets committed in Git (`.env` ignored, `.env.example` template).
- [x] Strict $0 local infrastructure policy preserved (ADR-0011).
- [x] Downstream handoffs to #149 and #154 documented.
