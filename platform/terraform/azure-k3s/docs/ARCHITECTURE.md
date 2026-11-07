# SwiftDrop — Azure K3s Architecture & System Topology Specification

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Target Architecture:** Single-Node Azure Linux VM hosting K3s & Embedded Traefik Ingress  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Authoritative Sizing Reference:** [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../../../../docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md)  

---

## 1. Architectural Philosophy & Scope

SwiftDrop's cloud preparation architecture adopts a **single-node self-managed K3s cluster** on an Azure Linux Virtual Machine. 

### Why Azure VM K3s Instead of Managed AKS?
1. **Architectural Parity:** The local release target (frozen in ADR-0011) runs on K3s/k3d. Running native K3s on an Azure VM preserves identical container runtime semantics, StorageClass behavior (`local-path`), Ingress controller (`Traefik`), and Flannel CNI networking.
2. **Cost Avoidance:** Managed Azure Kubernetes Service (AKS) incurs fixed control-plane costs unless using the Free tier, but even Free tier AKS requires multi-node node pools (system pool + user pool), managed load balancers ($18+/month), and managed disks per PVC, rapidly exceeding academic budgets ($100+). A single VM hosting K3s consolidates compute, ingress, and persistence into one bounded billable envelope.
3. **No Managed Dependency Creep:** Avoids vendor lock-in to Azure Database for PostgreSQL Flexible Server, Azure Event Hubs (Kafka), or Azure Blob Storage. SwiftDrop retains per-service PostgreSQL ownership, Kafka KRaft, and MinIO in-cluster.

```
+---------------------------------------------------------------------------------------------------+
| AZURE PUBLIC CLOUD BOUNDARY                                                                       |
|                                                                                                   |
|  [ Azure Public IP: Static Standard SKU ] <--- Internet HTTPS (:443)                             |
|                           |                                                                       |
|  [ Network Security Group: nsg-swiftdrop-demo ]                                                   |
|    - Allow 443 Inbound from 0.0.0.0/0                                                             |
|    - Allow 22 Inbound ONLY from Approved Operator CIDRs                                            |
|    - Deny All Other Inbound (Ports 6443, 5432, 29092, 9000, 3000, 8080-8085)                     |
|                           |                                                                       |
|  [ Azure Virtual Network: 10.10.0.0/16 ] --- [ Subnet: 10.10.1.0/24 ]                             |
|                           |                                                                       |
|  [ Azure Linux VM: Ubuntu 24.04 LTS, Standard_B4ms (4 vCPU, 16 GiB RAM) ]                         |
|    - OS Disk: 30 GB StandardSSD_LRS (/dev/sda)                                                    |
|    - Data Disk: 32 GB StandardSSD_LRS (/dev/sdc mounted at /mnt/swiftdrop-data)                   |
|                           |                                                                       |
|  +------------------------v--------------------------------------------------------------------+  |
|  | K3s SINGLE-NODE ENGINE (v1.31.2+k3s1)                                                       |  |
|  |   - Flannel CNI (Pod CIDR 10.42.0.0/16)                                                     |  |
|  |   - Embedded Traefik Ingress Controller (:443, :80 host binding)                            |  |
|  |   - Local-Path Storage Provisioner -> /mnt/swiftdrop-data/k3s-storage                       |  |
|  |                                                                                             |  |
|  |   [ Namespace: swiftdrop-apps ]                                                             |  |
|  |     - Frontend SPA (Nginx unprivileged, port 8080)                                          |  |
|  |     - API Gateway (Spring Cloud Gateway, port 8080)                                         |  |
|  |     - Accounts & Partners (Spring Boot, HTTP 8081, gRPC 9081)                               |  |
|  |     - Pricing & Zones (Spring Boot, HTTP 8082, gRPC 9083)                                   |  |
|  |     - Delivery Operations (Spring Boot, HTTP 8083, gRPC 9082, 2 replicas)                  |  |
|  |     - Communications (Spring Boot, HTTP 8084)                                               |  |
|  |     - GraphQL Query API (Spring Boot, HTTP 8085)                                            |  |
|  |                                                                                             |  |
|  |   [ Namespace: swiftdrop-identity ]                                                         |  |
|  |     - Keycloak IdP (Quarkus, HTTP 8080, Mgmt 9000)                                          |  |
|  |                                                                                             |  |
|  |   [ Namespace: swiftdrop-data ]                                                             |  |
|  |     - Accounts PostgreSQL (StatefulSet, 2Gi PVC on Data Disk)                               |  |
|  |     - Business PostgreSQL (StatefulSet, 3Gi PVC on Data Disk)                               |  |
|  |     - Keycloak PostgreSQL (StatefulSet, 2Gi PVC on Data Disk)                               |  |
|  |                                                                                             |  |
|  |   [ Namespace: swiftdrop-messaging ]                                                        |  |
|  |     - Apache Kafka KRaft (StatefulSet, 3Gi PVC on Data Disk)                                |  |
|  |     - MinIO Object Storage (StatefulSet, 2Gi PVC on Data Disk)                               |  |
|  |     - Mailpit SMTP Receiver (Deployment, HTTP 8025, SMTP 1025)                               |  |
|  |                                                                                             |  |
|  |   [ Namespace: swiftdrop-monitoring ]                                                       |  |
|  |     - Prometheus (StatefulSet, 2Gi PVC on Data Disk)                                        |  |
|  |     - Grafana Dashboards (Deployment, 1Gi PVC on Data Disk)                                 |  |
|  |     - Alertmanager (Deployment, 500Mi PVC on Data Disk)                                     |  |
|  |     - Loki & Promtail (Private Log Aggregation)                                             |  |
|  +---------------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------------+
```

---

## 2. Failure Domains & Risk Assessment

This architecture is explicitly optimized for a **bounded academic evaluation and live demonstration environment**, NOT a high-availability multi-region enterprise cluster.

| Failure Domain | Severity | Consequence | Mitigation / Recovery Procedure |
|---|---|---|---|
| **Host VM Failure** | CRITICAL | Entire cluster stops; applications and DBs offline. | Managed disk retains state independently; VM can be restarted or re-attached in minutes. |
| **Data Disk Failure** | HIGH | Local-path PVCs corrupted. | Regular scheduled snapshots before deployment and test runs; pg_dump logical exports. |
| **K3s Control Plane Failure** | HIGH | Workload rescheduling disabled; pod routing degraded. | Systemd automatically restarts `k3s.service`; state stored in SQLite on data disk. |
| **Memory Pressure / OOM** | MEDIUM | Linux kernel OOM-killer terminates high-memory pod. | VM sized at 16 GiB RAM; JVM options enforce `-XX:+ExitOnOutOfMemoryError` and heap limits. |
| **Public IP Address Change** | LOW | DNS record desynchronization. | Static Standard Public IP allocated; IP remains fixed across VM stops/starts. |

---

## 3. Network Topology & Traffic Routing

### 3.1 IP Address Allocation
- **Azure Virtual Network (`vnet-swiftdrop-demo`):** `10.10.0.0/16`
- **Azure Subnet (`snet-swiftdrop-k3s`):** `10.10.1.0/24`
- **K3s Pod Network (Flannel VXLAN):** `10.42.0.0/16`
- **K3s Service Network (ClusterIPs):** `10.43.0.0/16`

### 3.2 External Ingress Path
1. External user accesses `https://demo.swiftdrop.net/`.
2. Public DNS resolves to Azure Public IP.
3. NSG permits TCP 443 inbound; all other non-SSH ports dropped.
4. K3s embedded Traefik terminates TLS on host port 443 using secret `swiftdrop-tls-cert`.
5. Traefik routes traffic internally via Kubernetes Ingress rules:
   - `/` -> `frontend-service.swiftdrop-apps:8080`
   - `/api/**` -> `api-gateway.swiftdrop-apps:8080`
   - `/graphql` -> `graphql-query-service.swiftdrop-apps:8085`
   - `/realms/**` -> `keycloak.swiftdrop-identity:8080`
   - `/admin/**` -> Denied by Traefik Ingress rule (`exposeAdmin: false`)

### 3.3 Internal gRPC & Database Traffic
All internal communications (Spring Cloud Gateway to business services, GraphQL to Accounts/Delivery gRPC, Spring Boot to PostgreSQL, Delivery to Kafka and MinIO) remain strictly private on the internal Kubernetes overlay network (`10.42.0.0/16` / `10.43.0.0/16`). No internal port is mapped or routed through Azure networking.

---

## 4. Hardware Sizing Rationale

To reliably host the 18 pods comprising the SwiftDrop ecosystem:

| Tier | Workloads | Requested Memory | Peak Working Set |
|---|---|---|---|
| **Identity & Gateway** | Keycloak, API Gateway | 640 MiB | 1.25 GiB |
| **Business Microservices** | Accounts, Pricing, Delivery (x2), Comms, GraphQL | 1.5 GiB | 3.0 GiB |
| **Frontend** | React SPA (Nginx) | 64 MiB | 128 MiB |
| **Data Layer** | 3x PostgreSQL (Accounts, Business, Keycloak) | 384 MiB | 768 MiB |
| **Messaging & Storage** | Kafka KRaft, MinIO, Mailpit | 544 MiB | 1.3 GiB |
| **Monitoring** | Prometheus, Grafana, Alertmanager, Loki, Promtail | 350 MiB | 750 MiB |
| **Host System & K3s** | Linux kernel, K3s control plane, Traefik, Flannel | 1.0 GiB | 2.0 GiB |
| **TOTALS** | **18 Pods + Host OS** | **~4.5 GiB** | **~9.2 GiB** |

### Evaluated SKU Comparison
- `Standard_B2ms` (2 vCPU, 8 GiB RAM): Insufficient headroom during simultaneous Java service startup; high risk of OOM thrashing.
- `Standard_B4ms` (4 vCPU, 16 GiB RAM): **RECOMMENDED EVALUATION TARGET**. Provides 16 GiB RAM and 4 vCPU burstable capacity at ~$0.166/hour.
- `Standard_D4s_v5` (4 vCPU, 16 GiB RAM): High-performance non-burstable target for extended load evaluation (~$0.192/hour).
