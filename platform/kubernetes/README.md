# SwiftDrop — Kubernetes Platform Architecture & Deployment Foundation

This directory houses the Kubernetes deployment architecture and cluster orchestration tooling for the SwiftDrop project under ADR-0011 (Strict $0 Cost Policy).

---

## Current Architecture & Implementation Status

| Component | Status | Governing Issue | Details |
|---|---|---|---|
| **Local k3d/K3s Cluster Foundation** | **IMPLEMENTED & VERIFIED** | **#175 (P0)** | Pinned K3s `v1.31.2`, loopback-only API (`:16443`), loopback ingress (`:18080`, `:18443`), host-mounted `local-path` storage, and verified NetworkPolicy enforcement. |
| **Cluster Lifecycle Scripts** | **IMPLEMENTED** | **#175 (P0)** | Non-destructive `create.sh`, `start.sh`, `stop.sh`, `status.sh`, `verify.sh`, `capacity.sh`, and `storage-preflight.sh`. |
| **Application Helm Charts & Ingress TLS** | **IMPLEMENTED & VERIFIED** | **#149 (P07)** | Helm chart covering all 8 pinned images, embedded Traefik ingress, local TLS boundary (`swiftdrop.local`), least-privilege RBAC, external secrets, and verified NetworkPolicies. |
| **Persistent Workloads & Databases** | **IMPLEMENTED & VERIFIED** | **#154 (P08)** | Stateful PostgreSQL databases (service-owned DBs/roles), Kafka KRaft (`apache/kafka:4.2.1`), MinIO object storage (`swiftdrop-pod`), startup/readiness/liveness probes, PDBs, and 2-replica Delivery deployment for #161. |
| **Public Cloud (Azure AKS / VMs)** | **STRICTLY PROHIBITED** | **ADR-0011 / #172** | Zero billable Azure resources; all workloads target measured local hardware. |

---

## Directory Layout

- `k3d/`: Declarative k3d cluster specification (`cluster.yaml`), core namespaces (`namespaces.yaml`), lifecycle scripts, and the complete [Local Cluster Operator Runbook](k3d/README.md).
- `helm/`: SwiftDrop Helm chart ecosystem:
  - `swiftdrop`: Application workloads, Ingress, PDBs, probes, NetworkPolicies, and 2-replica Delivery deployment.
  - `swiftdrop-data`: Stateful PostgreSQL workloads (`accounts-postgres`, `business-postgres`, `keycloak-postgres`) with persistent volume claims and unprivileged role isolation.
  - `swiftdrop-messaging`: Stateful Apache Kafka (KRaft mode) and MinIO object storage with persistent storage.
  - `scripts/`: Dev secret provisioners (`create-dev-secrets.sh`), local TLS certificate generator, and security verifiers.
- `tests/`: Automated unit, template, and policy tests verifying declarative configuration, script safety, and persistent storage contracts in [tests/kubernetes/](../../tests/kubernetes/README.md).

---

## Deployment Sequence (Local Operator)

To deploy the persistent local Kubernetes runtime from scratch:

```bash
# 1. Bootstrap cluster and apply management namespaces
./platform/kubernetes/k3d/create.sh

# 2. Generate local TLS secrets and developer secrets
./platform/kubernetes/helm/scripts/generate-tls-secret.sh
./platform/kubernetes/helm/scripts/create-dev-secrets.sh

# 3. Deploy persistent data tier (PostgreSQL databases)
helm upgrade --install swiftdrop-data platform/kubernetes/helm/swiftdrop-data \
  -n swiftdrop-data -f platform/kubernetes/helm/swiftdrop-data/values-local.yaml

# 4. Deploy persistent messaging tier (Kafka KRaft, MinIO, Mailpit)
helm upgrade --install swiftdrop-messaging platform/kubernetes/helm/swiftdrop-messaging \
  -n swiftdrop-messaging -f platform/kubernetes/helm/swiftdrop-messaging/values-local.yaml

# 5. Deploy application workloads and identity (Keycloak, 6 Java services, React SPA)
helm upgrade --install swiftdrop-apps platform/kubernetes/helm/swiftdrop \
  -n swiftdrop-apps -f platform/kubernetes/helm/swiftdrop/values-local.yaml

# 6. Verify cluster health and resource consumption
./platform/kubernetes/k3d/status.sh
./platform/kubernetes/k3d/capacity.sh
```

---

## Handoff Runbooks & Drills

- [Issue #161 Handoff: Two-Delivery-Replica Environment](../../docs/handoff/ISSUE_154_TO_161_DELIVERY_REPLICAS_HANDOFF.md)
- [Issue #167 Handoff: Disaster Recovery & Rollout Durability](../../docs/handoff/ISSUE_154_TO_167_RECOVERY_HANDOFF.md)
