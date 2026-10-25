# SwiftDrop — Local k3d/K3s Cluster Operator Runbook

This directory provides the authoritative declarative configuration, lifecycle tooling, and storage interfaces for the dedicated SwiftDrop local Kubernetes cluster (`swiftdrop-local`), establishing the deployment foundation for Helm charts (Issue #149) and persistent workloads (Issue #154) under ADR-0011 (Strict $0 Cost Policy).

---

## 1. Pinned Architecture & Toolchain Specifications

| Component | Pinned Version / Setting | Architecture / Origin | Security Boundary |
|---|---|---|---|
| **k3d CLI** | `v5.9.0` | macOS arm64 (Apple Silicon) | Local container orchestrator |
| **K3s Server Image** | `rancher/k3s:v1.31.2-k3s1` | arm64 (Docker Hub pinned) | Embedded Flannel CNI & kube-router |
| **Kubernetes API** | `v1.31.2+k3s1` | Loopback `127.0.0.1:16443` | Non-routable to LAN / Public internet |
| **HTTP Ingress Port** | `127.0.0.1:18080` | Loopback `127.0.0.1:18080 -> :80` | Non-conflicting with Compose :8080 |
| **HTTPS Ingress Port** | `127.0.0.1:18443` | Loopback `127.0.0.1:18443 -> :443` | Non-conflicting with Compose :8443 |
| **Ingress Controller** | Traefik (embedded K3s) | Cluster internal | Local routing only; no public tunnel |
| **Storage Provisioner** | `rancher.io/local-path` | StorageClass `local-path` (default) | Node mount to host `${HOME}/.swiftdrop/k3d/storage` |
| **Cluster Identity** | `swiftdrop-local` | Context `k3d-swiftdrop-local` | Isolated Docker network `k3d-swiftdrop-local` |

---

## 2. Host Prerequisites & Capacity Budget

### Measured Host Baseline (Suthankan's Development Machine)
- **Host Hardware:** Apple Silicon (Darwin arm64, 10 CPU cores, 16.0 GiB physical RAM, 93 GiB available disk).
- **Docker Desktop Allocation:** 4 CPUs, 4.06 GiB RAM (4,364,566,528 bytes), Overlayfs on 125 GiB virtual disk (102 GiB available).
- **Measured Foundation Footprint:** ~160m CPU, ~630 MiB RAM total container usage (K3s server + serverlb + tools).

### Full-Stack Resource Budget & Capacity Gate
| Component Group | Estimated CPU | Estimated RAM | Storage Budget | Gate Status |
|---|---|---|---|---|
| **K3s Foundation & System Pods** | 160m (measured) | 580 MiB (measured) | ~1.5 GB | **VERIFIED (Active)** |
| **8 Microservices & Identity** | ~2,000m | ~3,136 MiB | Ephemeral | Pending #128 / #154 |
| **5 PostgreSQL Databases** | ~500m | ~640 MiB | ~10.0 GB | Pending #154 |
| **Kafka Broker & MinIO Storage** | ~350m | ~768 MiB | ~10.0 GB | Pending #154 |
| **2 Delivery Replicas (SSE)** | ~500m | ~768 MiB | Ephemeral | Pending #154 / #161 |
| **Full-Stack Concurrency Total** | **~3,510m (~3.5 CPU)** | **~5.8 - 6.5 GiB RAM** | **~25.0 GB** | **BLOCKED on 4.06 GiB Docker** |

> **CAPACITY VERDICT:** The cluster foundation itself comfortably fits within Docker Desktop's 4.06 GiB allocation. However, running all 17 full-stack production containers concurrently requires ~6.0 GiB RAM. Operators must either allocate >= 8 GiB RAM in Docker Desktop settings or run bounded demo profiles (staged microservice execution) during the November 10 milestone evaluation.

---

## 3. Operator Lifecycle Commands

All scripts enforce `set -euo pipefail` and non-destructive execution:

### A. Preflight & Bootstrap Cluster
```bash
./platform/kubernetes/k3d/create.sh
```
- Validates Docker daemon, tools, and loopback port availability (`:16443`, `:18080`, `:18443`).
- Validates storage directory permissions (`0700`, outside repository tree).
- Refuses collisions; if `swiftdrop-local` exists, safely resumes without re-creating.
- Applies core management namespaces (`swiftdrop-system`, `swiftdrop-apps`, `swiftdrop-data`, `swiftdrop-identity`, `swiftdrop-messaging`).

### B. Check Cluster Status
```bash
./platform/kubernetes/k3d/status.sh
```
Displays lifecycle state, allocatable resources, storage class, ingress bindings, and system pods without leaking secrets.

### C. Verify Readiness, Storage, & Network Isolation
```bash
./platform/kubernetes/k3d/verify.sh
```
Runs end-to-end acceptance checks:
1. Kubernetes node and CoreDNS readiness.
2. Loopback-only port verification (fails if bound to `0.0.0.0`).
3. Synthetic PVC write/read test and confirmation of host directory persistence.
4. Active NetworkPolicy ingress enforcement (default deny and scoped allow verification via embedded kube-router).

### D. Measure Capacity & Resource Footprint
```bash
./platform/kubernetes/k3d/capacity.sh
```
Outputs live host CPU/RAM, Docker VM allocation, Kubernetes allocatable metrics, and full-stack capacity gate evaluation.

### E. Safely Stop the Cluster
```bash
./platform/kubernetes/k3d/stop.sh
```
Stops cluster containers (`k3d-swiftdrop-local-*`) without erasing persistent data, volumes, or Docker Compose dependencies.

### F. Resume Stopped Cluster
```bash
./platform/kubernetes/k3d/start.sh
```
Restarts existing cluster containers and waits for node and CoreDNS readiness.

---

## 4. Persistent Storage Contract & Data Ownership

K3s uses the `local-path` provisioner mounted to `${HOME}/.swiftdrop/k3d/storage` on the operator host.

### Service-Owned Data Isolation (Issue #154)
Each persistent stateful dependency is assigned its own discrete logical database/volume:
- **Accounts PostgreSQL:** `accounts_db` (owned exclusively by Accounts service).
- **Pricing PostgreSQL:** `pricing_db` (owned exclusively by Pricing service).
- **Delivery PostgreSQL:** `delivery_db` (owned exclusively by Delivery service).
- **Communications PostgreSQL:** `communications_db` (owned exclusively by Communications service).
- **Keycloak PostgreSQL:** `keycloak_db` (owned by Identity / Platform).
- **Kafka KRaft:** `kafka-broker` volume (owned by Platform messaging).
- **MinIO Object Storage:** `minio-data` volume (owned by Platform private S3).

### Persistence Lifecycle Guarantees
- **Pod Restart:** Data survives (PersistentVolumeClaim retains host directory).
- **Cluster Stop/Start:** Data survives (Host volume remains mounted to server node).
- **Cluster Recreation:** Data preserved on host directory `${HOME}/.swiftdrop/k3d/storage/`; re-creating the cluster re-mounts the same host directory.
- **Host Failure:** Single physical failure domain; requires independent external backup drills (Issues #154 / #167).

---

## 5. Explicit Kube Context Usage

To prevent unintended modifications to other clusters or cloud environments, all scripts and kubectl commands explicitly qualify the context:
```bash
kubectl --context k3d-swiftdrop-local get nodes
kubectl --context k3d-swiftdrop-local get pods -A
```
The configuration explicitly sets `switchCurrentContext: false`, leaving your default kubeconfig context unchanged.

---

## 6. Deliberate Cleanup Procedure (Destructive Operator Action Only)

Cluster deletion is deliberately excluded from all normal lifecycle scripts to prevent accidental data loss. If a developer explicitly chooses to tear down the cluster:

> [!CAUTION]
> The following command deletes the Kubernetes control plane and node containers.
> Host storage in `${HOME}/.swiftdrop/k3d/storage` is preserved unless manually deleted.
> Never run `docker volume prune` or `docker system prune` as this will destroy unrelated project volumes.

```bash
# Deliberate teardown of the local cluster:
k3d cluster delete swiftdrop-local

# Optional manual purge of local persistent data:
# rm -rf ~/.swiftdrop/k3d/storage/*
```

---

## 7. Downstream Cross-Task Handoffs

- **Issue #128 (Secure Images):** Local cluster is Darwin `arm64`; requires linux/arm64 multi-arch or native arm64 container images imported via `k3d image import -c swiftdrop-local <image>:<tag>` or pulled from GHCR.
- **Issue #149 (Helm & Ingress Security):** Target context `k3d-swiftdrop-local`; namespaces prepared; ingress points to `127.0.0.1:18080` (HTTP) and `127.0.0.1:18443` (HTTPS); NetworkPolicy enforcement verified.
- **Issue #154 (Persistent Workloads):** StorageClass `local-path` verified; PVC persistence across restart verified; capacity budget dictates bounded demo profile or >= 8 GiB Docker RAM.
- **Issue #176 (HTTPS & Public Access):** Cluster is strictly bound to loopback `127.0.0.1`; public access requires deliberate local proxy or domain certificate integration.
- **Issue #177 (Operator Handover):** Runbook verified with reproducible scripts and measured resource baselines.
