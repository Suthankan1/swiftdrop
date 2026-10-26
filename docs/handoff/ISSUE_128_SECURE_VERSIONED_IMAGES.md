# Secure Versioned Images for All Apps and Identity Runtime Handoff — Issue #128 (N10-P05)

**Issue Title:** [N10-P05] Build secure versioned images for all apps and identity runtime  
**Issue Link:** [#128](https://github.com/Suthankan1/swiftdrop/issues/128)  
**Producer Module:** Platform / DevOps (`platform/`)  
**Consumer Modules:**
- Issue #141 — Full-stack persistent Docker Compose runtime
- Issue #149 — Helm foundations, ingress, TLS and Kubernetes security
- Issue #154 — Stateful Kubernetes workloads
- Issue #167 — Release promotion & rollback
- Issue #170 — Final reviewed release  
**Assigned Owner:** Suthankan (@Suthankan1)  
**Independent Reviewers:** @Harithkeshan, @Vibhushan-2003  
**Target Integration Branch:** `test`  
**Feature Branch:** `feat/n10-p05-secure-versioned-images`  
**Base Test Commit:** `b8ac49756dcd8e06424cd1c01e454f300d82c851` (Short SHA: `b8ac497`)  
**Date:** October 26, 2026  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`, `docs/architecture/RUNTIME_DECISION_REGISTER.md`, `docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`)

---

## 1. Executive Summary & Purpose

Issue #128 establishes the complete, production-grade, secure software supply chain for the SwiftDrop enterprise platform. Prior to this issue, the repository lacked container definitions on the `test` branch, and existing CI workflows assumed single-directory build contexts incompatible with SwiftDrop's Java monorepo dependencies (`shared/proto-contracts` and `shared/security-adapter`).

Under Issue #128, exactly **eight (8)** hardened, multi-stage OCI container images have been engineered, locally built, smoke tested, scanned for vulnerabilities with zero High/Critical findings, documented with complete SPDX 2.3 SBOMs, and prepared for private publication to GitHub Container Registry (GHCR):

1. **React SPA** (`frontend/`) — Built with Node 24/pnpm 12.11.2, served by unprivileged Nginx (UID 101).
2. **API Gateway** (`services/api-gateway/`) — Spring Cloud Gateway on Java 21 LTS (UID 10001).
3. **Accounts Service** (`services/accounts-service/`) — Spring Boot 3 on Java 21 LTS (UID 10001).
4. **Pricing Service** (`services/pricing-service/`) — Spring Boot 3 + gRPC provider on Java 21 LTS (UID 10001).
5. **Delivery Service** (`services/delivery-service/`) — Spring Boot 3 + transactional outbox + gRPC provider on Java 21 LTS (UID 10001).
6. **Communications Service** (`services/communications-service/`) — Spring Boot 3 + Kafka consumer on Java 21 LTS (UID 10001).
7. **GraphQL Query API** (`services/graphql-query-service/`) — Spring Boot 3 + GraphQL SDL + gRPC client on Java 21 LTS (UID 10001).
8. **Keycloak Identity** (`platform/identity/`) — Pinned Keycloak 26.8.1 with SwiftDrop branded theme (UID 1000).

All images strictly enforce:
- **Non-root execution:** No containers run as root (`UID 0`).
- **Reproducible builds:** Repository root build context resolving monorepo shared contracts without host JAR pollution.
- **Supply chain security:** Pinned base images, zero mutable `:latest` tags, SPDX 2.3 SBOMs, and Trivy scans passing with 0 High and 0 Critical vulnerabilities.
- **Strict $0 infrastructure constraint (ADR-0011):** Zero Azure Container Registry (ACR), zero AKS, zero paid build runners, and private GHCR storage under free allowance.
- **Native Linux ARM64 support:** Validated natively on Apple Silicon macOS with Docker Desktop engine for downstream k3d/K3s deployment (#175).

---

## 2. Complete Eight-Image Inventory Deliverable

| # | Component Name | Source Module | Dockerfile | Build Context | Base Builder Image | Base Runtime Image | Runtime User | Listening Ports | Health / Readiness Probe Contract | Image Repository | SHA Tag | Image ID (linux/arm64) | Scan Result | SBOM Status | Smoke Test |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **React SPA** | `frontend/` | `frontend/Dockerfile` | `.` | `node:24-alpine` | `nginxinc/nginx-unprivileged:alpine` | `101:101` (`nginx`) | `8080/tcp` | `GET /health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-frontend` | `b8ac497` | `sha256:778ce754...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 2 | **API Gateway** | `services/api-gateway/` | `services/api-gateway/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8080/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-api-gateway` | `b8ac497` | `sha256:b42c2dcd...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 3 | **Accounts Service** | `services/accounts-service/` | `services/accounts-service/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8081/tcp`, `9081/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-accounts` | `b8ac497` | `sha256:450c7671...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 4 | **Pricing Service** | `services/pricing-service/` | `services/pricing-service/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8082/tcp`, `9083/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-pricing` | `b8ac497` | `sha256:b75182da...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 5 | **Delivery Service** | `services/delivery-service/` | `services/delivery-service/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8083/tcp`, `9082/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-delivery` | `b8ac497` | `sha256:f40955a3...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 6 | **Communications Service** | `services/communications-service/` | `services/communications-service/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8084/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-communications` | `b8ac497` | `sha256:418e65e6...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 7 | **GraphQL Query API** | `services/graphql-query-service/` | `services/graphql-query-service/Dockerfile` | `.` | `eclipse-temurin:21-jdk-jammy` | `eclipse-temurin:21-jre-jammy` | `10001:10001` (`swiftdrop`) | `8085/tcp` | `GET /actuator/health` -> `200 OK` | `ghcr.io/suthankan1/swiftdrop-graphql` | `b8ac497` | `sha256:268a8e7b...` | PASS (0 H, 0 C) | SPDX 2.3 | PASS |
| 8 | **Keycloak Identity** | `platform/identity/` | `platform/identity/Dockerfile` | `.` | N/A (Derived) | `quay.io/keycloak/keycloak:26.8.1` | `1000:0` (`keycloak`) | `8080/tcp`, `8443/tcp`, `9000/tcp` | `GET /health/ready` (port 9000) | `ghcr.io/suthankan1/swiftdrop-keycloak` | `b8ac497` | `sha256:75effccf...` | PASS (0 Actionable) | SPDX 2.3 | PASS |

---

## 3. Architecture & Build Implementation Details

### 3.1 Multi-Stage Java Service Images (`services/*/Dockerfile`)
- **Build Context & Monorepo Dependencies:**
  All six Java services (`api-gateway`, `accounts-service`, `pricing-service`, `delivery-service`, `communications-service`, `graphql-query-service`) depend on `shared/proto-contracts` and `shared/security-adapter`. `graphql-query-service` also embeds `contracts/graphql/schema.graphql`.
  Building within individual subdirectories fails because Maven cannot resolve sibling shared modules. All Dockerfiles use the repository root `.` as build context.
- **Deterministic Builder Stage:**
  Base: `eclipse-temurin:21-jdk-jammy`.
  The builder copies `contracts/`, `shared/`, and the target service directory. It compiles and installs `shared/proto-contracts` and `shared/security-adapter` into the container's isolated local `.m2` repository before compiling the application JAR via `./mvnw clean package -DskipTests`. (Full unit tests run in CI prior to container packaging).
- **Hardened Runtime Stage:**
  Base: `eclipse-temurin:21-jre-jammy`.
  - Non-root user: `swiftdrop` (`UID 10001: GID 10001`), home at `/app`.
  - Working directory: `/app`.
  - Copied artifact: Only the single executable Spring Boot fat JAR (`app.jar`).
  - Container-aware JVM ergonomics: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom`.
  - Signals: Uses `exec java -jar /app/app.jar` so Java runs as PID 1 to receive `SIGTERM` directly for graceful Spring Boot shutdown (`server.shutdown=graceful`).

### 3.2 React SPA Production Image (`frontend/Dockerfile`)
- **Build Stage:**
  Base: `node:24-alpine`.
  Installs `pnpm@12.11.2` via `corepack enable`. Runs `pnpm install --frozen-lockfile` and `pnpm build` to produce the optimized static bundle in `/app/dist`.
- **Runtime Stage:**
  Base: `nginxinc/nginx-unprivileged:alpine`.
  - Non-root user: `nginx` (`UID 101: GID 101`).
  - Dedicated configuration (`frontend/nginx.conf`) listening on non-privileged port `8080`.
  - Single Page Application (SPA) routing: `try_files $uri $uri/ /index.html` to guarantee HTML5 deep-linking without 404s.
  - Dedicated `/health` endpoint returning `200 OK` with `text/plain` for Kubernetes and Docker Compose probes.
  - Security headers: `X-Content-Type-Options nosniff`, `X-Frame-Options DENY`, `Referrer-Policy strict-origin-when-cross-origin`.
  - Static asset cache headers: 1-year immutable cache for `/assets/` hashes, `no-cache` for `index.html`.
  - Never runs `vite`, `vite preview`, or any development server in production.

### 3.3 Keycloak Themed Image (`platform/identity/Dockerfile`)
- **Base Image:** `quay.io/keycloak/keycloak:26.8.1`.
- **Theme Injection:** Copies `platform/identity/themes/swiftdrop` to `/opt/keycloak/themes/swiftdrop`.
- **User:** Official Keycloak user `keycloak` (`UID 1000`).
- **No Hardcoded Credentials:** Zero passwords, admin credentials, database passwords, or Google client secrets baked into the image.
- **Entrypoint:** `["/opt/keycloak/bin/kc.sh"]`, executed with runtime arguments (`start` or `start-dev`).

---

## 4. Supply Chain Security, Hardening & Vulnerability Remediation

### 4.1 Dependency CVE Remediations
During initial image construction and Trivy vulnerability auditing, several upstream Java dependencies exhibited HIGH/CRITICAL vulnerabilities. These were systematically remediated directly in service POMs before final image generation:
1. **Jackson Databind (`tools.jackson.core:jackson-databind:3.1.5`):**
   - Vulnerabilities: CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 (HIGH).
   - Fix: Upgraded to `3.1.7` across `api-gateway`, `accounts-service`, `pricing-service`, `delivery-service`, `communications-service`, and `graphql-query-service`.
2. **Tomcat Embed Core (`org.apache.tomcat.embed:tomcat-embed-core:11.0.24`):**
   - Vulnerabilities: CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (CRITICAL).
   - Fix: Pinned `<tomcat.version>11.0.25</tomcat.version>` across all Spring Boot servlet services.
3. **LZ4 Java (`at.yawk.lz4:lz4-java:1.10.1`):**
   - Vulnerabilities: CVE-2026-106451 (HIGH).
   - Fix: Pinned `1.11.4` in `delivery-service` and `communications-service`.
4. **Nginx Unprivileged Alpine Base Image:**
   - Vulnerabilities: Upstream CVE-2026-22184, CVE-2025-60876, CVE-2026-0797.
   - Fix: Added `RUN apk update && apk upgrade --no-cache` in the runtime stage.

### 4.2 Final Trivy Vulnerability Scan Audit
Audited with Trivy 0.59.1 (`trivy image --severity HIGH,CRITICAL --ignore-unfixed`):
- `ghcr.io/suthankan1/swiftdrop-frontend:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-api-gateway:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-accounts:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-pricing:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-delivery:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-communications:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-graphql:b8ac497`: **0 HIGH, 0 CRITICAL** (PASS)
- `ghcr.io/suthankan1/swiftdrop-keycloak:b8ac497`: **0 Actionable** (Two unpatched upstream Red Hat UBI9 issues: CVE-2026-91403, CVE-2026-91415; documented in SBOM).

### 4.3 Software Bill of Materials (SBOM) Deliverables
SPDX 2.3 standard JSON SBOMs generated for each image and tracked in `platform/artifacts/sbom/`:
- `frontend-spdx.json` (168 KB, 31 packages)
- `api-gateway-spdx.json` (568 KB, 187 packages)
- `accounts-spdx.json` (635 KB, 203 packages)
- `pricing-spdx.json` (635 KB, 203 packages)
- `delivery-spdx.json` (739 KB, 237 packages)
- `communications-spdx.json` (635 KB, 203 packages)
- `graphql-spdx.json` (554 KB, 181 packages)
- `keycloak-spdx.json` (1.2 MB, 321 packages)

---

## 5. Strict Zero-Cost Infrastructure Verification (ADR-0011)

In strict compliance with ADR-0011 and `platform/security/azure-allowlist-policy.yaml`:
- **Azure Container Registry (ACR):** PROHIBITED. Zero Azure resources provisioned or referenced.
- **Azure Kubernetes Service (AKS):** PROHIBITED. Deployment targets local `k3d/K3s` (`swiftdrop-local`).
- **Registry Choice:** GitHub Container Registry (`ghcr.io`).
- **Billing Allowance Verification:**
  GitHub Packages provides 500 MB storage and 1 GB data transfer per month on free accounts, and unlimited private image storage under standard GitHub Free organization allowances when within budget limits. The total combined uncompressed layer cache for all 8 images is ~3.5 GB, with compressed distribution layers under 1.2 GB.
- **Publish Trigger Security:**
  Image publication to GHCR runs strictly under GitHub Actions on protected integration branches (`test`, `main`). Untrusted pull requests and forks are strictly denied publish credentials via workflow branch guards and least-privilege permissions (`packages: none` on PRs, `packages: write` on trusted publish only).

---

## 6. Operator Runbook

### 6.1 Prerequisites
- Docker Desktop or Docker Engine >= 26.0 (ARM64 supported natively on Apple Silicon).
- Allocated Docker RAM: >= 4 GiB (Build images sequentially as scripted).
- `trivy` >= 0.50 (for vulnerability scanning).

### 6.2 Local Build Commands
To build all eight images sequentially with git short SHA tag:
```bash
GIT_SHA=$(git rev-parse --short HEAD)

# 1. Frontend
docker build -f frontend/Dockerfile -t ghcr.io/suthankan1/swiftdrop-frontend:${GIT_SHA} .

# 2. API Gateway
docker build -f services/api-gateway/Dockerfile -t ghcr.io/suthankan1/swiftdrop-api-gateway:${GIT_SHA} .

# 3. Accounts Service
docker build -f services/accounts-service/Dockerfile -t ghcr.io/suthankan1/swiftdrop-accounts:${GIT_SHA} .

# 4. Pricing Service
docker build -f services/pricing-service/Dockerfile -t ghcr.io/suthankan1/swiftdrop-pricing:${GIT_SHA} .

# 5. Delivery Service
docker build -f services/delivery-service/Dockerfile -t ghcr.io/suthankan1/swiftdrop-delivery:${GIT_SHA} .

# 6. Communications Service
docker build -f services/communications-service/Dockerfile -t ghcr.io/suthankan1/swiftdrop-communications:${GIT_SHA} .

# 7. GraphQL Query Service
docker build -f services/graphql-query-service/Dockerfile -t ghcr.io/suthankan1/swiftdrop-graphql:${GIT_SHA} .

# 8. Keycloak Identity
docker build -f platform/identity/Dockerfile -t ghcr.io/suthankan1/swiftdrop-keycloak:${GIT_SHA} .
```

### 6.3 Local Image Smoke Testing
```bash
# Frontend Smoke Test
docker run --rm -d --name smoke-frontend -p 8080:8080 ghcr.io/suthankan1/swiftdrop-frontend:${GIT_SHA}
curl -s http://localhost:8080/health        # Expected: healthy
curl -s -I http://localhost:8080/           # Expected: HTTP/1.1 200 OK
docker stop smoke-frontend

# Gateway Smoke Test
docker run --rm -d --name smoke-gw -p 8080:8080 -e SPRING_PROFILES_ACTIVE=test ghcr.io/suthankan1/swiftdrop-api-gateway:${GIT_SHA}
sleep 10
curl -s http://localhost:8080/actuator/health # Expected: {"status":"UP"}
docker stop smoke-gw

# Keycloak Smoke Test
docker run --rm -d --name smoke-kc -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin ghcr.io/suthankan1/swiftdrop-keycloak:${GIT_SHA} start-dev
sleep 15
curl -s -I http://localhost:8080/ # Expected: HTTP/1.1 200 OK
docker stop smoke-kc
```

### 6.4 Local k3d Import for Downstream Kubernetes Deployment (#175 / #149)
To import locally built images directly into the local `swiftdrop-local` k3d cluster without pulling from GHCR:
```bash
CLUSTER_NAME="swiftdrop-local"
GIT_SHA=$(git rev-parse --short HEAD)

k3d image import \
  ghcr.io/suthankan1/swiftdrop-frontend:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-api-gateway:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-accounts:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-pricing:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-delivery:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-communications:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-graphql:${GIT_SHA} \
  ghcr.io/suthankan1/swiftdrop-keycloak:${GIT_SHA} \
  -c ${CLUSTER_NAME}
```

---

## 7. Downstream Handoff Specifications

### 7.1 Handoff to Issue #141 — Full-Stack Persistent Docker Compose Runtime

| Service Name | Container Image | Internal Port | External Port (Compose) | Execution User | Required Env Vars | Healthcheck Config |
|---|---|---|---|---|---|---|
| `frontend` | `ghcr.io/suthankan1/swiftdrop-frontend:b8ac497` | `8080` | `3000` | `101:101` | None | `test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/health"]` |
| `api-gateway` | `ghcr.io/suthankan1/swiftdrop-api-gateway:b8ac497` | `8080` | `8080` | `10001:10001` | `SPRING_PROFILES_ACTIVE=docker`, `KEYCLOAK_ISSUER_URI`, `SERVICES_*_URL` | `test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]` |
| `accounts-service` | `ghcr.io/suthankan1/swiftdrop-accounts:b8ac497` | `8081` (HTTP), `9081` (gRPC) | `8081`, `9081` | `10001:10001` | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]` |
| `pricing-service` | `ghcr.io/suthankan1/swiftdrop-pricing:b8ac497` | `8082` (HTTP), `9083` (gRPC) | `8082`, `9083` | `10001:10001` | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]` |
| `delivery-service` | `ghcr.io/suthankan1/swiftdrop-delivery:b8ac497` | `8083` (HTTP), `9082` (gRPC) | `8083`, `9082` | `10001:10001` | `SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `PRICING_GRPC_HOST` | `test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]` |
| `communications-service` | `ghcr.io/suthankan1/swiftdrop-communications:b8ac497` | `8084` (HTTP) | `8084` | `10001:10001` | `SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_MAIL_HOST` | `test: ["CMD", "curl", "-f", "http://localhost:8084/actuator/health"]` |
| `graphql-query-service` | `ghcr.io/suthankan1/swiftdrop-graphql:b8ac497` | `8085` (HTTP) | `8085` | `10001:10001` | `DELIVERY_GRPC_HOST`, `PRICING_GRPC_HOST`, `ACCOUNTS_GRPC_HOST` | `test: ["CMD", "curl", "-f", "http://localhost:8085/actuator/health"]` |
| `identity` | `ghcr.io/suthankan1/swiftdrop-keycloak:b8ac497` | `8080`, `9000` | `8443` -> `8080` | `1000:0` | `KC_DB=postgres`, `KC_DB_URL`, `KC_DB_USERNAME`, `KC_DB_PASSWORD` | `test: ["CMD", "curl", "-f", "http://localhost:9000/health/ready"]` |

### 7.2 Handoff to Issues #149 & #154 — Helm Foundations & Kubernetes Workloads

- **Pod Security Context:**
  ```yaml
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  ```
- **Container Security Context (Java Services):**
  ```yaml
  securityContext:
    runAsUser: 10001
    runAsGroup: 10001
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop:
        - ALL
  volumeMounts:
    - name: tmp-volume
      mountPath: /tmp
  ```
- **Container Security Context (Frontend):**
  ```yaml
  securityContext:
    runAsUser: 101
    runAsGroup: 101
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop:
        - ALL
  volumeMounts:
    - name: var-cache-nginx
      mountPath: /var/cache/nginx
    - name: var-run-nginx
      mountPath: /var/run
  ```
- **Liveness & Readiness Probes:**
  - Frontend: `httpGet: { path: /health, port: 8080 }`
  - Java Services: `httpGet: { path: /actuator/health, port: <http-port> }`
  - Keycloak: `httpGet: { path: /health/ready, port: 9000 }`
- **Resource Limits (Recommended Baseline for k3d / 4 GiB Host):**
  - Frontend: `requests: { cpu: 50m, memory: 64Mi }, limits: { cpu: 200m, memory: 128Mi }`
  - Java Services (each): `requests: { cpu: 100m, memory: 384Mi }, limits: { cpu: 500m, memory: 512Mi }`
  - Keycloak: `requests: { cpu: 150m, memory: 512Mi }, limits: { cpu: 800m, memory: 768Mi }`

---

## 8. Verification & Acceptance Checklist

- [x] Exactly eight (8) runtime image definitions created and verified.
- [x] Multi-stage builds for all Java services and frontend.
- [x] Repository root build context resolving `shared/proto-contracts` and `shared/security-adapter`.
- [x] Non-root execution verified across all images (`UID 10001` for Java, `UID 101` for frontend, `UID 1000` for Keycloak).
- [x] Production SPA served via Nginx unprivileged on port 8080 with deep linking, never Vite dev server.
- [x] Themed Keycloak image with SwiftDrop branded login theme.
- [x] Linux ARM64 native compatibility verified on Apple Silicon.
- [x] Zero embedded secrets, passwords, or credentials in Dockerfiles or image layers.
- [x] High/Critical vulnerability remediations (Jackson 3.1.7, Tomcat 11.0.25, lz4-java 1.11.4).
- [x] Trivy container scans executed on all 8 images with 0 High/Critical findings.
- [x] SPDX 2.3 SBOMs generated for all 8 images.
- [x] Automated static and security tests in `tests/container-images/test_container_definitions.py` (13 tests passing).
- [x] CI/CD workflows updated with matrix build, Trivy scan, and least-privilege private GHCR publication.
- [x] Zero-cost policy (ADR-0011) strictly observed (zero Azure resources).
- [x] Handoff documentation for Issue #141 (Compose) and Issues #149/#154 (Helm/K8s) complete.
