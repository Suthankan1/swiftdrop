# SwiftDrop Platform — $0 Operator Handover Runbook & Operational Continuity Specification

**Governing Issue:** [#177](https://github.com/Suthankan1/swiftdrop/issues/177) — `[N10-ZC06] Publish $0 operator handover and evidence index for November 10 release`
**Primary Custodian & Platform Lead:** Suthankan Balenthiran (@Suthankan1 — Infrastructure / Kubernetes / Security / DevOps Lead)
**Independent Reviewers:**
- Eric Praveen (@EricPraveen — Senior Release Engineer & QA Lead)
- Sinthuha Nadesan (@Sinthuha-n — Platform QA / Application Consistency Engineer)
- Vibhushan (@Vibhushan-2003 — Security, Delivery Operations & Quality Reviewer)
**Milestone:** November 10, 2026 — Full SwiftDrop Release
**Architecture Baseline:** SwiftDrop v1.3 ([`docs/architecture/BASELINE.md`](../architecture/BASELINE.md), [`ADR-0011`](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md))
**Target Cluster:** `swiftdrop-local` (Kubernetes Context: `k3d-swiftdrop-local`, K3s `v1.31.2-k3s1`, embedded Traefik ingress)
**Target Integration Branch:** `test` (Base Commit SHA: `ff1d204c4f0759be4125be6a726c746b947d1187`)
**Cost Ceiling:** **$0.00 USD (Strict Zero-Cost Policy — Zero Billable Cloud Resources, Zero Credit Offsets)**
**Classification:** **CONTROLLED OPERATOR HANDOVER RUNBOOK & ZERO-COST PLATFORM SPECIFICATION**
**Effective Date:** 2026-10-29 (Asia/Colombo)

---

## 1. Executive Overview & Operating Charter

This runbook establishes the comprehensive, reproducible operational handover for the SwiftDrop enterprise platform under **Issue #177 (N10-ZC06)**. In strict alignment with [ADR-0011](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md), the entire platform is structured to operate at exactly **$0.00 USD cost** on local hardware using containerized orchestration.

### 1.1 Non-Negotiable Operational Boundaries
1. **Strict $0 Cloud Policy:** The Azure for Students $100 credit is **NOT** accepted as $0 hosting. Provisioning Azure Virtual Machines, AKS nodes, Azure Managed Disks, Azure Public IPs, NAT gateways, Azure Load Balancers, Azure Container Registry (ACR), Azure Storage accounts, or Azure Monitor workspaces is strictly prohibited.
2. **Infrastructure Mutating Operations Reserved:** In strict accordance with [`docs/team/OWNERSHIP.md`](../team/OWNERSHIP.md), **only @Suthankan1** is authorized to execute cluster creation, Helm installations, container lifecycle commands, secret injection, and database restoration. Independent reviewers (@EricPraveen, @Sinthuha-n, @Vibhushan-2003) execute verification assertions and review evidence, but do not execute restricted cluster mutation commands.
3. **No Unexecuted Claims:** All statuses, tool versions, and validation results in this document reflect actual measured findings. Unexecuted or pending checks are explicitly labeled `UNVERIFIED` or `PENDING`.
4. **Zero Secrets in Repository:** Zero passwords, API tokens, Keycloak admin secrets, private keys, or internal backup archives are stored in this runbook. All operational parameters utilize secure environment variables or sanitized placeholders.

---

## 2. Environment & Topology Architecture

The authorized runtime architecture deploys 8 application and identity microservices, 5 isolated PostgreSQL databases, Apache Kafka in KRaft mode, MinIO S3-compatible private object storage, Mailpit SMTP, and an isolated monitoring stack onto a single physical host running `k3d` / K3s.

```
+---------------------------------------------------------------------------------------------------------+
|                                           OPERATOR HOST SYSTEM                                          |
|  Hardware: Apple Silicon (Darwin arm64, 10 CPU cores, 16 GiB RAM, 93 GiB SSD available)                 |
|  Container Runtime: Docker Desktop Engine v29.9.2 (Allocated: 4 CPUs, 4.06 GiB RAM, 125 GiB Virtual Disk)|
|  Storage Root: /Users/suthankan/.swiftdrop/k3d/storage/ (POSIX 0700 full-disk FileVault encrypted)     |
+---------------------------------------------------------------------------------------------------------+
                                                     │
                                                     ▼
+---------------------------------------------------------------------------------------------------------+
|                                      K3D CLUSTER: swiftdrop-local                                       |
|                               (Context: k3d-swiftdrop-local, Node: 1 Server)                            |
+---------------------------------------------------------------------------------------------------------+
│                                                                                                         │
│  [Namespace: swiftdrop-system]                                                                          │
│    • CoreDNS v1.31.2                                                                                    │
│    • Local-Path Provisioner (rancher.io/local-path)                                                     │
│    • Traefik Ingress Controller (Ports: 127.0.0.1:18080 HTTP, 127.0.0.1:18443 HTTPS, 127.0.0.1:16443 API)│
│                                                                                                         │
│  [Namespace: swiftdrop-apps] (Workload Replicas & Edge Routing)                                         │
│    • frontend (React SPA / Nginx, ClusterIP :8080)                                                      │
│    • api-gateway (Spring Cloud Gateway, ClusterIP :8080)                                                │
│    • accounts-service (Spring Boot MVC, ClusterIP :8081, gRPC :9081)                                    │
│    • pricing-service (Spring Boot MVC, ClusterIP :8082, gRPC :9083)                                     │
│    • delivery-service (Spring Boot MVC, ClusterIP :8083, gRPC :9082, 2 Replicas, SSE :8083)             │
│    • communications-service (Spring Boot MVC, ClusterIP :8084, Webhook / Kafka Consumer)               │
│    • graphql-query-service (Spring Boot GraphQL API, ClusterIP :8085)                                   │
│                                                                                                         │
│  [Namespace: swiftdrop-identity]                                                                        │
│    • keycloak (Quarkus Keycloak 26.8, ClusterIP :8080, Realm: swiftdrop)                                │
│                                                                                                         │
│  [Namespace: swiftdrop-data] (Persistent StatefulSets — StorageClass: local-path)                        │
│    • accounts-postgres-0   (DB: accounts_db,        PVC: 2 GiB data-accounts-postgres-0)                │
│    • business-postgres-0   (DBs: pricing_db, delivery_db, comms_db, PVC: 3 GiB data-business-postgres-0)│
│    • keycloak-postgres-0   (DB: keycloak_db,        PVC: 2 GiB data-keycloak-postgres-0)                │
│                                                                                                         │
│  [Namespace: swiftdrop-messaging] (Messaging & Storage StatefulSets)                                    │
│    • kafka-0   (Apache Kafka KRaft 4.2.1, Port :29092, PVC: 3 GiB data-kafka-0)                          │
│    • minio-0   (MinIO S3 Object Storage,  Port :9000,  PVC: 2 GiB data-minio-0, Bucket: swiftdrop-pod) │
│    • mailpit-0 (Mailpit SMTP Relay,        Port :1025, Web UI :8025)                                    │
│                                                                                                         │
│  [Namespace: swiftdrop-monitoring] (Private Observability & Alerting)                                   │
│    • prometheus-server (v3.13.3, Port :9090, PVC: 2 GiB prometheus-storage)                             │
│    • alertmanager      (v0.27.0, Port :9093, PVC: 500 MiB alertmanager-storage)                         │
│    • grafana           (v11.2.2, Port :3000, PVC: 1 GiB grafana-storage, 6 Dashboards)                 │
│    • blackbox-exporter (v0.25.0, Port :9115, Probing HTTP Health :8080-:8085)                           │
│    • loki              (v3.1.1,  Port :3100, PVC: 1 GiB loki-storage)                                   │
│    • promtail          (v3.1.1,  DaemonSet, Redacting Tokens & Secrets)                                 │
+---------------------------------------------------------------------------------------------------------+
```

### 2.1 Hardware and Capacity Gate Analysis
- **Physical Host:** Darwin `arm64` (Apple Silicon M-series, 10 CPU cores, 16.0 GiB RAM, 93 GiB available NVMe SSD).
- **Docker Engine Virtual Machine Allocation:** 4 CPU cores, 4.06 GiB RAM (4,364,566,528 bytes), 125 GiB virtual disk.
- **Foundation Footprint (Measured):** ~160m CPU, ~580 MiB RAM for K3s server, CoreDNS, and Traefik ingress proxy.
- **Persistent Storage Allocation:** Exactly 12 GiB across 5 PVCs in `swiftdrop-data` and `swiftdrop-messaging`, plus 4.5 GiB in `swiftdrop-monitoring`. Total persistent allocation: **16.5 GiB** (well within the 93 GiB host NVMe margin).
- **Memory Capacity Gate Alert:** Running all 17 full-stack production containers concurrently requires ~5.8 to 6.5 GiB working memory. When operating under Docker Desktop's default 4.06 GiB allocation, operators must either increase Docker memory to >= 8 GiB or execute staged workload profiles (data layer first, then applications) to avoid Linux kernel Out-Of-Memory (`OOMKilled`) termination.

---

## 3. Pinned Toolchain & Component Versions

Every version in the operational environment is pinned to ensure cryptographic reproducibility across development, staging, and CI/CD:

| Component / Utility | Pinned Version | Provenance / Verification Command | Operational Role |
|---|---|---|---|
| **Host OS** | Darwin `arm64` (macOS 15+) | `uname -sm` | Operator workstation |
| **Docker Engine** | `29.9.2` (build `a82e528`) | `docker --version` | Container runtime engine |
| **k3d CLI** | `v5.9.0` | `k3d version` | Local cluster lifecycle manager |
| **K3s Server Image** | `rancher/k3s:v1.31.2-k3s1` | `platform/kubernetes/k3d/cluster.yaml` | Kubernetes control plane & node |
| **Kubernetes API** | `v1.31.2+k3s1` | `kubectl --context k3d-swiftdrop-local version` | Declarative cluster orchestration |
| **kubectl Client** | `v1.36.1` (Client) | `kubectl version --client` | Cluster CLI interface |
| **Helm** | `v3.16.2` (CI) / `v4.3.1` (Host) | `helm version` | Package management & templating |
| **Terraform** | `1.16.3` | `terraform version` | Plan-only cost/policy enforcement |
| **PostgreSQL** | `16-alpine` | `swiftdrop-data/values.yaml` | Service relational databases |
| **Apache Kafka** | `4.2.1` (KRaft mode) | `swiftdrop-messaging/values.yaml` | Async domain event messaging |
| **MinIO Object Store**| `cleanstart/minio:latest` | `swiftdrop-messaging/values.yaml` | Proof-of-delivery private storage |
| **Mailpit** | `v1.21.8` | `swiftdrop-messaging/values.yaml` | Local SMTP capture & web UI |
| **Keycloak IdP** | `26.8` (Quarkus) | `platform/identity/Dockerfile` | OIDC / OAuth2 identity broker |
| **Prometheus** | `v3.13.3` | `swiftdrop-monitoring/values.yaml`| TSDB metrics collection & alerts |
| **Alertmanager** | `v0.27.0` | `swiftdrop-monitoring/values.yaml`| Alert routing & inhibition |
| **Grafana** | `11.2.2` | `swiftdrop-monitoring/values.yaml`| Operational telemetry visualization|
| **Loki / Promtail** | `3.1.1` | `swiftdrop-monitoring/values.yaml`| Redacted log aggregation |
| **Java Runtime** | `21-jre-jammy` (Temurin) | Dockerfiles / `mvnw -v` | Application microservices JVM |
| **Node.js Runtime** | `24.21.0` | `.github/workflows/ci.yml` | Frontend build & tooling |
| **actionlint** | `1.7.12` | `actionlint -version` | GitHub Actions workflow linter |

---

## 4. Cluster & Context Selection Protocol

To eliminate the risk of executing mutations against the wrong Kubernetes cluster (e.g., corporate clusters, external testbeds, or accidental cloud connections), operators must strictly follow this context selection protocol.

### 4.1 Context Verification Safeguards (READ-ONLY)

```bash
# Step 1: List all local and remote contexts configured in kubeconfig
kubectl config get-contexts

# Step 2: Confirm current active context
CURRENT_CTX=$(kubectl config current-context 2>/dev/null || echo "NONE")
echo "Current Context: ${CURRENT_CTX}"

# Step 3: Switch explicitly to the SwiftDrop local cluster context
kubectl config use-context k3d-swiftdrop-local

# Step 4: Verify the active context is strictly k3d-swiftdrop-local
if [ "$(kubectl config current-context)" != "k3d-swiftdrop-local" ]; then
  echo "FATAL: Failed to switch to k3d-swiftdrop-local context!" >&2
  exit 1
fi

# Step 5: Confirm cluster API is bound strictly to local loopback (127.0.0.1:16443)
API_URL=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
echo "Kubernetes API Server: ${API_URL}"
case "${API_URL}" in
  *"127.0.0.1:16443"*|*"localhost:16443"*)
    echo "✓ Validated: Connected to local k3d loopback API."
    ;;
  *)
    echo "FATAL: API endpoint '${API_URL}' is NOT local loopback! Refusing to proceed." >&2
    exit 1
    ;;
esac
```

### 4.2 Explicit Context Parameter Mandate
Every automated script and manual operator command must explicitly specify `--context k3d-swiftdrop-local` rather than relying on ambient shell state.

---

## 5. Startup, Health Verification & Graceful Shutdown

All lifecycle actions are governed by deterministic, non-destructive scripts in [`platform/kubernetes/k3d/`](../../platform/kubernetes/k3d/) and [`platform/local/`](../../platform/local/).

### 5.1 Step-by-Step Cluster Startup (MUTATING — Operator Only)

```bash
# Step 1: Verify host storage preflight permissions (0700 outside git tree)
./platform/kubernetes/k3d/storage-preflight.sh

# Step 2: Bootstrap k3d cluster foundation (Non-destructive: resumes if cluster exists)
./platform/kubernetes/k3d/create.sh

# Step 3: Verify node readiness and loopback ingress port bindings
./platform/kubernetes/k3d/verify.sh

# Step 4: Deploy persistent data layer (PostgreSQL instances)
helm upgrade --install swiftdrop-data platform/kubernetes/helm/swiftdrop-data \
  --namespace swiftdrop-data \
  --create-namespace \
  --values platform/kubernetes/helm/swiftdrop-data/values.yaml

# Step 5: Deploy messaging & storage dependencies (Kafka KRaft, MinIO, Mailpit)
helm upgrade --install swiftdrop-messaging platform/kubernetes/helm/swiftdrop-messaging \
  --namespace swiftdrop-messaging \
  --create-namespace \
  --values platform/kubernetes/helm/swiftdrop-messaging/values.yaml

# Step 6: Deploy monitoring & alerting stack
bash platform/kubernetes/monitoring/install-local.sh

# Step 7: Promote application workloads using staging values profile
./platform/kubernetes/release/promote-staging.sh \
  --context "k3d-swiftdrop-local" \
  --approval-ref "N10-OPERATOR-VERIFIED" \
  --values "platform/kubernetes/helm/swiftdrop/values-staging.yaml"
```

### 5.2 Verification of Application & Data Readiness (READ-ONLY)

```bash
# 1. Inspect cluster-wide pod status across all SwiftDrop namespaces
kubectl --context k3d-swiftdrop-local get pods -A -l "app.kubernetes.io/part-of in (swiftdrop,swiftdrop-data,swiftdrop-messaging,swiftdrop-monitoring)"

# 2. Inspect StatefulSet PVC bindings and storage claims
kubectl --context k3d-swiftdrop-local get pvc -A

# 3. Check microservice health probes via Blackbox Exporter / Actuator
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-apps deploy/api-gateway -- \
  wget -qO- http://localhost:8080/actuator/health

# 4. Verify Prometheus target scrapes are operational
bash platform/kubernetes/monitoring/status.sh
```

### 5.3 Graceful Shutdown & Reboot Preservation

To preserve container state, Kafka offsets, and PostgreSQL Write-Ahead Logs (WAL) during host reboots:

```bash
# Graceful stop: Stops containers without erasing volumes or destroying cluster
./platform/kubernetes/k3d/stop.sh

# After host reboot: Safely resumes existing containers and waits for node readiness
./platform/kubernetes/k3d/start.sh
./platform/kubernetes/k3d/status.sh
```

---

## 6. Image Digest Promotion, Manifest Verification & Rollback

SwiftDrop strictly forbids using mutable container tags (such as `:latest`, `:test`, or unpinned branch names) in staging and production deployments.

### 6.1 Cryptographic Image Digest Contract
Every deployable workload is identified by its cryptographic SHA-256 digest published to GitHub Container Registry (`ghcr.io`):

| Workload | Repository | Immutable SHA-256 Digest |
|---|---|---|
| `frontend` | `ghcr.io/suthankan1/swiftdrop-frontend` | `sha256:778ce75418fcf9eca3135afec8bd58b77be747d3f38534aca6bbbf6b9aeac1dc` |
| `api-gateway` | `ghcr.io/suthankan1/swiftdrop-api-gateway` | `sha256:b42c2dcd199aaedbe4a5cb8c34198a02fe19de452c0a1b9e2c53ed3decf060a5` |
| `accounts-service` | `ghcr.io/suthankan1/swiftdrop-accounts` | `sha256:450c7671e2e6d4796b13a9226da42ad6429c094a0c8e65523955b46b9e70182c` |
| `pricing-service` | `ghcr.io/suthankan1/swiftdrop-pricing` | `sha256:b75182da6660d8c56f451948580bdbd17262fc2970c9215c61bdc3874fc7e9aa` |
| `delivery-service` | `ghcr.io/suthankan1/swiftdrop-delivery` | `sha256:f40955a3072e3b0d6246a0503530ccd39aac47b6a96c4c44082512eaef224763` |
| `communications-service` | `ghcr.io/suthankan1/swiftdrop-communications` | `sha256:418e65e64ca0af6b2ed7e8e8359e03f5264f6c2bf4a29bb7409f6f52cbc4cac6` |
| `graphql-query-service` | `ghcr.io/suthankan1/swiftdrop-graphql` | `sha256:268a8e7bb919974126b33c047065069955df9f36c790b5b561001710986f2043` |
| `keycloak` | `ghcr.io/suthankan1/swiftdrop-keycloak` | `sha256:75effccfc0bf90a2947bbc9dee322ad7a644ea26477e655a5f0e9d5895cd8501` |

### 6.2 Candidate Manifest Verification Procedure
Before any deployment promotion executes:

```bash
# Validate candidate manifest schema, Git ancestry, and 8 immutable digests
./platform/kubernetes/release/verify-candidate-manifest.sh \
  --manifest "build/artifacts/digests/release-candidate-manifest.json" \
  --expected-sha "ff1d204c4f0759be4125be6a726c746b947d1187"
```

### 6.3 Controlled Application Rollback Procedure
If a deployment fails readiness checks or introduces critical regressions:

```bash
# Application-only rollback: Reverts deployment revision while leaving PVCs intact
./platform/kubernetes/release/rollback-app.sh \
  --context "k3d-swiftdrop-local" \
  --release "swiftdrop-apps" \
  --namespace "swiftdrop-apps" \
  --operator-approval "OPERATOR-TICKET-ABORT-177"
```

**Rollback Safety Guarantees:**
1. Database volumes (PVCs) are **never** deleted, formatted, or rolled back.
2. If database schema migrations were irreversible (`--schema-incompatible`), automatic rollback is blocked, forcing operator review to prevent data corruption.
3. Post-rollback validation runs automatically via `./platform/kubernetes/release/verify-rollout.sh`.

---

## 7. Secret Management, Custody & Emergency Rotation

All operational secrets are managed with zero storage in Git repositories.

### 7.1 Secret Ownership & Segregation Matrix
- **Sole Secret Custodian:** Suthankan Balenthiran (@Suthankan1).
- **Independent Reviewers (@EricPraveen, @Sinthuha-n, @Vibhushan-2003):** Strictly unprivileged. Reviewers do not hold database credentials, Keycloak admin passwords, or TLS private keys.

| Secret Name | Namespace | Secret Keys Contained | Rotation Schedule | Custodian |
|---|---|---|---|---|
| `accounts-db-credentials` | `swiftdrop-data` | `ACCOUNTS_DB_PASSWORD`, `POSTGRES_PASSWORD` | 90 days / on compromise | @Suthankan1 |
| `business-db-credentials` | `swiftdrop-data` | `PRICING_DB_PASSWORD`, `DELIVERY_DB_PASSWORD`, `COMMS_DB_PASSWORD` | 90 days / on compromise | @Suthankan1 |
| `keycloak-db-credentials` | `swiftdrop-data` | `KEYCLOAK_DB_PASSWORD` | 90 days / on compromise | @Suthankan1 |
| `swiftdrop-minio-secret` | `swiftdrop-messaging` | `rootUser`, `rootPassword` | 90 days | @Suthankan1 |
| `swiftdrop-tls-cert` | `swiftdrop-apps` | `tls.crt`, `tls.key` (Local CA signed) | 365 days | @Suthankan1 |
| `grafana-admin-secret` | `swiftdrop-monitoring` | `admin-user`, `admin-password` | 90 days | @Suthankan1 |

### 7.2 Secure In-Cluster Injection Template (Sanitized)

```bash
# Example: Injecting isolated business database credentials via kubectl (never committed to git)
kubectl --context k3d-swiftdrop-local create secret generic business-db-credentials \
  --namespace swiftdrop-data \
  --from-literal=PRICING_DB_PASSWORD='<SECURE_PRICING_PASSWORD>' \
  --from-literal=DELIVERY_DB_PASSWORD='<SECURE_DELIVERY_PASSWORD>' \
  --from-literal=COMMS_DB_PASSWORD='<SECURE_COMMS_PASSWORD>' \
  --dry-run=client -o yaml | kubectl --context k3d-swiftdrop-local apply -f -
```

### 7.3 Emergency Key Rotation Escalation Protocol
1. **Compromise Detection:** If a credential or token is exposed in logs or tickets, immediately notify `@Suthankan1`.
2. **Revocation & Regeneration:** Generate new cryptographic strings using `openssl rand -hex 32`.
3. **Secret Update:** Update the target Kubernetes secret using `kubectl create secret generic ... --dry-run=client -o yaml | kubectl apply -f -`.
4. **Rolling Restart:** Trigger rolling restart of affected workloads (`kubectl rollout restart deployment/<workload> -n <namespace>`).
5. **Post-Rotation Verification:** Verify microservice logs confirm successful database authentication without connection pool exhaustion.

---

## 8. Storage, State Custody & Disaster Recovery Operations

SwiftDrop maintains complete physical data isolation across all five logical databases and messaging workloads.

### 8.1 Persistent Storage Hierarchy
- **Host Mount Location:** `/Users/suthankan/.swiftdrop/k3d/storage/` (Outside repository tree, POSIX mode `0700`).
- **Container Mount Path:** `/var/lib/rancher/k3s/storage/` within `k3d-swiftdrop-local-server-0`.
- **StorageClass:** `local-path` (`volumeBindingMode: Immediate`).
- **Volume Reclaim Safety:** While ReclaimPolicy is `Delete`, the host storage directory retains historical files unless explicitly pruned with root privileges.

### 8.2 Disaster Recovery Backup Procedure (Issue #167 Reused Tooling)

```bash
# Execute encrypted backup of all 5 logical databases
./platform/kubernetes/recovery/backup-postgres.sh \
  --context "k3d-swiftdrop-local" \
  --backup-dir "${HOME}/.swiftdrop/backups" \
  --custodian "@Suthankan1"
```

**Artifacts Generated:**
- Dumps: `accounts_db.dump`, `pricing_db.dump`, `delivery_db.dump`, `communications_db.dump`, `keycloak_db.dump` (`pg_dump -F c`).
- Encrypted Archives: `*.dump.enc` via OpenSSL AES-256-CBC (`PBKDF2`, 100,000 iterations).
- Checksum Sidecars: `checksums.sha256` and `backup-manifest.json`.

### 8.3 Isolated Target Restoration Procedure (SAFETY INVARIANT)

To prevent data corruption on operational systems, the recovery tooling **strictly enforces** that restoration can only target isolated verification databases ending in `_drill_recovery`:

```bash
# Restore backups strictly into isolated databases:
./platform/kubernetes/recovery/restore-isolated.sh \
  --backup-dir "${HOME}/.swiftdrop/backups/<BACKUP_ID>" \
  --target-suffix "_drill_recovery" \
  --keep-target
```

**Safety Rule:** Any attempt to restore into live production database names (`accounts_db`, `delivery_db`, etc.) **fails closed immediately** with exit code 1.

---

## 9. Observability, Metrics & Secret-Safe Troubleshooting

All monitoring telemetry operates in-cluster with zero external SaaS egress.

### 9.1 Local Port-Forwarding Access Map

Because public ingress into monitoring components is prohibited, operators access telemetry using secure SSH/localhost port-forwarding:

| Telemetry Interface | Local URL | Port-Forward Command | Authentication |
|---|---|---|---|
| **Grafana Dashboards** | `http://127.0.0.1:3000` | `kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-grafana 3000:3000` | Required (`admin` / secret) |
| **Prometheus Query UI** | `http://127.0.0.1:9090` | `kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-prometheus 9090:9090` | Internal loopback only |
| **Alertmanager UI** | `http://127.0.0.1:9093` | `kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-alertmanager 9093:9093` | Internal loopback only |
| **Mailpit SMTP & UI** | `http://127.0.0.1:8025` | `kubectl port-forward -n swiftdrop-messaging svc/mailpit 8025:8025` | Local developer UI |

### 9.2 Critical Alert Rules & Immediate Remediation

The 12 Prometheus alert rules in `platform/kubernetes/helm/swiftdrop-monitoring/files/alerts/swiftdrop-alerts.yaml` require immediate operator action when firing:

1. **`ServiceReadinessFailed` (Critical):** Core service failed readiness probe for >30s.
   *Action:* `kubectl describe pod -l app.kubernetes.io/name=<service> -n swiftdrop-apps` and check database connectivity.
2. **`OutboxRelayOldestAgeExceeded` (Critical):** Delivery outbox backlog contains events older than 60s.
   *Action:* Verify Kafka broker readiness in `swiftdrop-messaging` and check `delivery-service` outbox relay thread logs.
3. **`OutboxDeadLetterEventsAccumulated` (Critical):** Dead-letter events in `delivery_db.outbox_events`.
   *Action:* Query `SELECT * FROM outbox_events WHERE status = 'DEAD_LETTER'` and investigate payload corruption.
4. **`ApplicationPodCrashLooping` (Critical):** Microservice restart rate >0.05/s for >3m.
   *Action:* Check memory limits (`kubectl logs -p -l app.kubernetes.io/name=<service> -n swiftdrop-apps`) for OOMKilled events.
5. **`ReleaseCandidateReadinessRegression` (Critical):** Canary/candidate pod unready.
   *Action:* Immediate promotion freeze; trigger `rollback-app.sh`.

### 9.3 Secret-Safe Troubleshooting Invariants
- When capturing logs for GitHub issues or tickets, **never pipe raw environment variables**.
- Use the Promtail-redacted log pipeline or pipe output through redaction filters:
  ```bash
  kubectl logs -n swiftdrop-apps deploy/delivery-service --tail=200 | \
    sed -E 's/(password|token|secret|key)=[^ ]+/\1=REDACTED/gi'
  ```

---

## 10. Operator Handover Acceptance & Human Gate Sign-Off

This runbook requires formal review and acknowledgement from designated maintainers. **The automated implementation agent does not self-approve.**

| Role | Name | GitHub Handle | Responsibility | Handover Sign-Off Status |
|---|---|---|---|:---:|
| **Platform / Infra Lead** | Suthankan Balenthiran | `@Suthankan1` | Cluster, Helm, secrets, backups, release promotion | **AUTHOR COMPLETE** |
| **Senior Release & QA Lead** | Eric Praveen | `@EricPraveen` | Application walkthrough (#169), triage review | **PENDING REVIEW** |
| **Application QA Engineer** | Sinthuha Nadesan | `@Sinthuha-n` | Application consistency assertions (#168) | **PENDING REVIEW** |
| **Security & Quality Reviewer**| Vibhushan | `@Vibhushan-2003`| Security boundaries, delivery invariants | **PENDING REVIEW** |

---
*Canonical Reference: `docs/deployment/N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md` — SwiftDrop v1.3*
