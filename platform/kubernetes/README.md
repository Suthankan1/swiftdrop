# SwiftDrop — Kubernetes Platform Architecture & Deployment Foundation

This directory houses the Kubernetes deployment architecture and cluster orchestration tooling for the SwiftDrop project under ADR-0011 (Strict $0 Cost Policy).

---

## Current Architecture & Implementation Status

| Component | Status | Governing Issue | Details |
|---|---|---|---|
| **Local k3d/K3s Cluster Foundation** | **IMPLEMENTED & VERIFIED** | **#175 (P0)** | Pinned K3s `v1.31.2`, loopback-only API (`:16443`), loopback ingress (`:18080`, `:18443`), host-mounted `local-path` storage, and verified NetworkPolicy enforcement. |
| **Cluster Lifecycle Scripts** | **IMPLEMENTED** | **#175 (P0)** | Non-destructive `create.sh`, `start.sh`, `stop.sh`, `status.sh`, `verify.sh`, `capacity.sh`, and `storage-preflight.sh`. |
| **Application Helm Charts & Ingress TLS** | **IMPLEMENTED & VERIFIED** | **#149 (P07)** | Helm chart covering all 8 pinned images, embedded Traefik ingress, local TLS boundary (`swiftdrop.local`), least-privilege RBAC, external secrets, and verified NetworkPolicies. |
| **Persistent Workloads & Databases** | PLANNED / IN PROGRESS | #154 (P08) | Stateful PostgreSQL databases, Kafka KRaft, and MinIO object storage. |
| **Public Cloud (Azure AKS / VMs)** | **STRICTLY PROHIBITED** | **ADR-0011 / #172** | Zero billable Azure resources; all workloads target measured local hardware. |

---

## Directory Layout

- `k3d/`: Declarative k3d cluster specification (`cluster.yaml`), core namespaces (`namespaces.yaml`), lifecycle scripts, and the complete [Local Cluster Operator Runbook](k3d/README.md).
- `helm/`: SwiftDrop application Helm chart (`swiftdrop`), local TLS provisioner, automated security verification, and the [Helm Operator Runbook](helm/README.md).
- `tests/`: Automated unit and policy tests verifying declarative configuration, script safety, and persistent storage contracts in [tests/kubernetes/](../../tests/kubernetes/README.md).

---

## Quick Start (Local Operator)

To start the dedicated SwiftDrop local Kubernetes cluster:
```bash
# 1. Bootstrap cluster and apply management namespaces
./platform/kubernetes/k3d/create.sh

# 2. Verify cluster readiness, storage, and NetworkPolicy enforcement
./platform/kubernetes/k3d/verify.sh

# 3. Check cluster status and resource consumption
./platform/kubernetes/k3d/status.sh
./platform/kubernetes/k3d/capacity.sh
```

For complete operational details, storage persistence guarantees, and cross-task handoffs, see the [k3d Operator Runbook](k3d/README.md).
