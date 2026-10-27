# SwiftDrop Platform Handoff: Two-Delivery-Replica Environment (Issue #154 -> #161)

## 1. Overview and Intent

This document establishes the official platform runtime handoff from **Issue #154** (`[N10-P08] Deploy persistent Kubernetes data and readiness-scaled application workloads`, owned by `@Suthankan1`) to **Issue #161** (`[N10-WF10] Two-Delivery-replica SSE fanout`, owned by `@Vibhushan-2003`).

Issue #154 provides the deployed, healthy, persistent, and network-isolated two-replica Kubernetes environment for `delivery-service`. Issue #161 implements and verifies the application-level Kafka-to-SSE event fanout mechanism across multiple active replicas.

---

## 2. Deployed Workload Specifications

| Parameter | Measured / Verified Specification |
|:---|:---|
| **Namespace** | `swiftdrop-apps` |
| **Deployment** | `delivery-service` |
| **Replica Count** | `2` (both active and ready) |
| **Image Reference** | `ghcr.io/suthankan1/swiftdrop-delivery@sha256:0b6a0d3e451a7071e6261f95be62cb4794ec4414f084ba637651a512753a3501` |
| **HTTP Port** | `8083` (REST / Actuator / SSE) |
| **gRPC Port** | `9082` (Internal Service Interface) |
| **ClusterIP Service** | `delivery-service.swiftdrop-apps.svc.cluster.local` |
| **PodDisruptionBudget** | `delivery-budget` (`minAvailable: 1`) |
| **Rollout Strategy** | `RollingUpdate` (`maxSurge: 0`, `maxUnavailable: 1`) |
| **Graceful Shutdown** | `terminationGracePeriodSeconds: 30`, `preStop` hook `sleep 5` |
| **CPU per Replica** | Requests: `80m`, Limits: `300m` (steady-state measured: ~15m) |
| **RAM per Replica** | Requests: `192Mi`, Limits: `384Mi` (steady-state measured: ~300Mi) |

---

## 3. Active Pod Instances

As verified in `k3d-swiftdrop-local`:

```text
NAME                                READY   STATUS    IP            NODE                      RESTARTS
delivery-service-7f85df9db7-4x5nc   1/1     Running   10.42.0.166   swiftdrop-control-plane   0
delivery-service-7f85df9db7-m64gx   1/1     Running   10.42.0.165   swiftdrop-control-plane   0
```

Both replicas are balanced across incoming HTTP/gRPC requests via the Kubernetes `ClusterIP` Service:
```text
http://delivery-service.swiftdrop-apps.svc.cluster.local:8083
```

---

## 4. Messaging & Storage Interfaces Available to Delivery

### 4.1 Apache Kafka (KRaft Mode)
- **Bootstrap Servers:** `kafka.swiftdrop-messaging.svc.cluster.local:29092`
- **Cluster ID:** `4L622nShTUiAnsYh0-X41w`
- **Topics Provisioned:**
  - `parcel.lifecycle.v1` (3 partitions, replication factor 1)
  - `parcel.lifecycle.v1.dlt` (3 partitions, replication factor 1)
- **Network Policy:** Ingress permitted from `swiftdrop-apps` on TCP `29092`.

### 4.2 PostgreSQL Database
- **Database Name:** `delivery_db`
- **Host / Port:** `business-postgres.swiftdrop-data.svc.cluster.local:5432`
- **Role:** `delivery_app` (Unprivileged, strict database ownership, non-superuser)
- **Migrations Applied:** Flyway V1 through V4 verified (`parcels`, `delivery_assignments`, `outbox_events`, `proof_of_deliveries`, `idempotency_records`).
- **Connection Secret:** `delivery-db-secret` in namespace `swiftdrop-apps`.

### 4.3 MinIO S3 Object Storage (Proof of Delivery)
- **Endpoint:** `http://minio.swiftdrop-messaging.svc.cluster.local:9000`
- **Bucket:** `swiftdrop-pod` (Private, persistent)
- **Credentials:** Referenced via `swiftdrop-minio-secret` in namespace `swiftdrop-apps`.

---

## 5. Architectural Guidance for Issue #161 (SSE Fanout)

When a client opens a Server-Sent Events (SSE) stream via `GET /api/v1/parcels/{id}/live-tracking`:
1. **Connection Stickiness:** The HTTP SSE connection is established with **one specific pod replica** (e.g., `4x5nc` or `m64gx`).
2. **Standard Kafka Consumer Groups:** If each replica joins the same Kafka consumer group (e.g. `delivery-sse-group`), Kafka partitions the topic between replicas. An event for a parcel assigned to partition 1 might be consumed by replica A, while the browser SSE connection for that parcel is held by replica B. In that case, the client would never receive the update.
3. **Recommended Fanout Mechanisms for #161:**
   - **Option A (Broadcast Consumer Groups):** Each replica generates an ephemeral, unique consumer group ID on startup (e.g., `delivery-sse-fanout-${HOSTNAME}`) so that **every** Delivery replica consumes all partitions of `parcel.lifecycle.v1` and delivers updates to local SSE emitters matching the parcel ID.
   - **Option B (Dedicated In-Memory / Broker PubSub):** Forward SSE notification dispatches across instances using a dedicated broadcast channel.
4. **Resiliency:**
   - The PodDisruptionBudget guarantees that at least `1` replica remains online during node drains, maintenance, or rolling updates.
   - Rollout restart tests demonstrate zero downtime and seamless traffic handover between replicas.

---

## 6. Verification Commands for Issue #161 Owner

To test connectivity from inside the cluster:

```bash
# Verify both replicas are running and healthy
kubectl --context k3d-swiftdrop-local get pods -n swiftdrop-apps -l app.kubernetes.io/name=delivery-service -o wide

# Check Delivery Actuator health on both replicas
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-apps delivery-service-7f85df9db7-4x5nc -- wget -qO- http://127.0.0.1:8083/actuator/health
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-apps delivery-service-7f85df9db7-m64gx -- wget -qO- http://127.0.0.1:8083/actuator/health

# Verify Delivery DB connection from replica
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-apps delivery-service-7f85df9db7-4x5nc -- nc -zv business-postgres.swiftdrop-data.svc.cluster.local 5432

# Verify Kafka connection from replica
kubectl --context k3d-swiftdrop-local exec -n swiftdrop-apps delivery-service-7f85df9db7-4x5nc -- nc -zv kafka.swiftdrop-messaging.svc.cluster.local 29092
```

