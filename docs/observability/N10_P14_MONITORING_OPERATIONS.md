# SwiftDrop Issue #160 — Protected Monitoring Dashboards and Actionable Release Alerts

**Owner:** `@Suthankan1` (Platform / Kubernetes / Security)  
**Base Branch:** `test`  
**Feature Branch:** `feat/n10-p14-protected-monitoring-alerts`  
**PR Target:** `test`  
**Collaborators & Reviewers:**  
- `@thuvarahan-t` — Application observability, metrics tags, and health/readiness (#158)  
- `@abhi190503` — P14A monitoring queries, thresholds, and independent alert verification (#166)  
- `@Harithkeshan` — Admin/operational integration and cross-service review  
- `@Vibhushan-2003` — Delivery operations and event boundaries  
- `@EricPraveen` — Communications and webhook delivery reliability  

---

## 1. Executive Summary & Architectural Scope

GitHub Issue #160 delivers a secure, locally hosted, resource-bounded monitoring, log aggregation, dashboarding, and alerting platform for SwiftDrop. In strict accordance with **ADR-0011 (Strict $0 Cloud Hosting Gate)** and repository instructions in `AGENTS.md`, the entire observability infrastructure operates locally on host hardware within the dedicated k3d Kubernetes cluster (`swiftdrop-local`).

```
                              ┌────────────────────────────────────────────────────────┐
                              │                   SWIFTDROP CLUSTER                    │
                              │                                                        │
┌────────────────────────┐    │  ┌───────────────────────┐   ┌──────────────────────┐  │
│   Operator Host        │    │  │     swiftdrop-apps    │   │ swiftdrop-monitoring │  │
│  (Private Localhost)   │    │  │                       │   │                      │  │
│                        │    │  │ • accounts-service    │   │ • Prometheus Server  │  │
│ • Grafana (3000)       │◄───┼──┼───────────────────────┼───┤   (TSDB, Rules)      │  │
│   [Auth Required]      │    │  │ • pricing-service     │   │                      │  │
│ • Prometheus (9090)    │◄───┼──┼───────────────────────┼───┤ • Alertmanager       │  │
│ • Alertmanager (9093)  │◄───┼──┼───────────────────────┼───┤   (Inhibition/Route) │  │
│                        │    │  │ • delivery-service    │   │                      │  │
│ (Via kubectl           │    │  │   (Outbox, Metrics)   │   │ • Blackbox Exporter  │  │
│  port-forward ONLY)    │    │  │                       │   │   (HTTP Health)      │  │
└────────────────────────┘    │  │ • communications      │   │                      │  │
                              │  │   (Webhooks, Inbox)   │   │ • Grafana (Dashbrds) │  │
                              │  │                       │   │                      │  │
                              │  │ • api-gateway         │   │ • Loki (Log Engine)  │  │
                              │  │                       │   │                      │  │
                              │  │ • graphql-query       │   │ • Promtail DaemonSet │  │
                              │  └───────────┬───────────┘   │   (Log Redaction)    │  │
                              │              │               └──────────┬───────────┘  │
                              │              │                          │              │
                              │              └──────────────────────────┘              │
                              │               Private In-Cluster Scrape                │
                              │               (NetworkPolicy Authorized)               │
                              │                                                        │
                              │  ┌──────────────────────────────────────────────────┐  │
                              │  │ swiftdrop-messaging: Mailpit SMTP (Port 1025)    │  │
                              │  └──────────────────────────────────────────────────┘  │
                              └────────────────────────────────────────────────────────┘
```

### Core Platform Invariants
1. **Strict $0 Cost Guarantee (ADR-0011)**: Zero Azure Monitor, Log Analytics, Application Insights, Azure Managed Grafana/Prometheus, paid paging/SMS, or cloud managed storage. All telemetry collection, alerting, and log aggregation execute locally.
2. **Zero Public Ingress**: Monitoring components are never exposed via Traefik or public Ingress. Every service is configured with `ClusterIP` only. Operator access requires authenticated `kubectl port-forward` to `127.0.0.1`.
3. **Least Privilege & Workload Security**: All deployments run as non-root (`runAsNonRoot: true`, user `65534` or `472`), drop all Linux capabilities (`capabilities.drop: [ALL]`), enforce `seccompProfile: RuntimeDefault`, and operate under dedicated ServiceAccounts with automount tokens disabled.
4. **Bounded Storage & Retention**: Prometheus TSDB retention is strictly capped at `24h` and `1500MB`. Grafana SQLite storage is bounded to `1Gi`. Loki chunk retention is capped at `24h` with a `1Gi` PVC.
5. **Private Secret Management**: Grafana administrator credentials are bound exclusively from an external Kubernetes Secret (`grafana-admin-secret`). Anonymous access is explicitly disabled (`GF_AUTH_ANONYMOUS_ENABLED=false`).
6. **Data Redaction**: Promtail pipelines scrub Bearer tokens, passwords, authorization headers, and secrets before log ingestion into Loki.

---

## 2. Component Architecture & Helm Manifests

The monitoring platform is packaged as a standalone, modular Helm chart at:
`platform/kubernetes/helm/swiftdrop-monitoring/`

### 2.1 Component Matrix

| Component | Image Reference | Pod Resource Requests | Pod Resource Limits | Storage (PVC) |
|---|---|---|---|---|
| **Prometheus Server** | `prom/prometheus:v3.13.3` | CPU: `40m`, RAM: `96Mi` | CPU: `200m`, RAM: `192Mi` | `2Gi` (`local-path`) |
| **Blackbox Exporter** | `prom/blackbox-exporter:v0.25.0` | CPU: `10m`, RAM: `24Mi` | CPU: `50m`, RAM: `48Mi` | N/A (Stateless) |
| **Alertmanager** | `prom/alertmanager:v0.27.0` | CPU: `15m`, RAM: `32Mi` | CPU: `80m`, RAM: `64Mi` | `500Mi` (`local-path`) |
| **Grafana** | `grafana/grafana:11.2.2` | CPU: `30m`, RAM: `64Mi` | CPU: `150m`, RAM: `128Mi` | `1Gi` (`local-path`) |
| **Loki** | `grafana/loki:3.1.1` | CPU: `20m`, RAM: `48Mi` | CPU: `100m`, RAM: `96Mi` | `1Gi` (`local-path`) |
| **Promtail** | `grafana/promtail:3.1.1` | CPU: `15m`, RAM: `24Mi` | CPU: `80m`, RAM: `48Mi` | Host mounts (read-only) |

### 2.2 Helm Chart Layout
```
platform/kubernetes/helm/swiftdrop-monitoring/
├── Chart.yaml
├── values.yaml                     # Full profile with pinned immutable OCI digests
├── values-local.yaml               # Local dev profile optimized for 4 GiB Docker RAM
├── files/
│   ├── alerts/
│   │   └── swiftdrop-alerts.yaml  # 12 validated Prometheus alert rules
│   └── dashboards/
│       ├── swiftdrop-service-health.json
│       ├── swiftdrop-delivery-outbox.json
│       ├── swiftdrop-grpc.json
│       ├── swiftdrop-communications.json
│       ├── swiftdrop-sse.json
│       └── swiftdrop-release-monitoring.json
└── templates/
    ├── _helpers.tpl
    ├── alertmanager-config.yaml
    ├── alertmanager-deployment.yaml
    ├── alertmanager-service.yaml
    ├── alertmanager-storage.yaml
    ├── blackbox-exporter.yaml
    ├── grafana-dashboards.yaml
    ├── grafana-deployment.yaml
    ├── grafana-provisioning.yaml
    ├── grafana-service.yaml
    ├── grafana-storage.yaml
    ├── logging-components.yaml
    ├── networkpolicies.yaml
    ├── prometheus-config.yaml
    ├── prometheus-deployment.yaml
    ├── prometheus-service.yaml
    ├── prometheus-storage.yaml
    └── serviceaccounts.yaml
```

---

## 3. Network Isolation & Security Enforcement

### 3.1 NetworkPolicy Architecture
Isolation is enforced across all monitoring workloads via six strict NetworkPolicies in `templates/networkpolicies.yaml`:
1. `default-deny-all-monitoring`: Default-deny on all Ingress and Egress for pods in `swiftdrop-monitoring`.
2. `allow-dns-egress-monitoring`: Permits UDP/TCP port 53 egress strictly to `kube-system/kube-dns`.
3. `allow-prometheus-traffic`:
   - Ingress: Port 9090 from Grafana pod and host port-forwarding proxies (`kube-system`).
   - Egress: Self scrape (9090), Blackbox Exporter (9115), Alertmanager (9093), and microservice actuator ports (8080-8085) in `swiftdrop-apps`.
4. `allow-blackbox-traffic`:
   - Ingress: Port 9115 strictly from Prometheus.
   - Egress: HTTP health endpoints (8080-8085) in `swiftdrop-apps` and Keycloak (9000) in `swiftdrop-identity`.
5. `allow-alertmanager-traffic`:
   - Ingress: Port 9093 from Prometheus and host port-forwarding.
   - Egress: SMTP port 1025 strictly to Mailpit in `swiftdrop-messaging`.
6. `allow-grafana-traffic`:
   - Ingress: Port 3000 strictly from host port-forwarding proxies.
   - Egress: Prometheus (9090) and Loki (3100).
7. `allow-monitoring-scrapes` (in `swiftdrop-apps`):
   - Cross-namespace policy allowing inbound TCP 8080-8085 strictly from pods with label `app.kubernetes.io/name in (prometheus, blackbox-exporter)` residing in namespace `swiftdrop-monitoring`.

### 3.2 Access Verification & Negative Test
- **Anonymous Access Rejection**: Grafana is deployed with `GF_AUTH_ANONYMOUS_ENABLED=false`. Any unauthenticated request to `/api/users` or `/api/dashboards` returns `HTTP 401 Unauthorized`.
- **Zero Public Routing**: The command `kubectl get ingress -n swiftdrop-monitoring` returns zero items.

---

## 4. Operational Dashboards

Grafana automatically provisions six operational dashboards via ConfigMaps:

1. **Service Health & Readiness (`swiftdrop-service-health.json`)**:
   - Live Blackbox HTTP probe success matrix across all core services (`accounts`, `pricing`, `delivery`, `communications`, `gateway`, `graphql`).
   - Pod readiness status and container restart rates.
   - HTTP probe response latencies ($p_{50}, p_{95}, p_{99}$).

2. **Delivery & Outbox Operations (`swiftdrop-delivery-outbox.json`)**:
   - Pending outbox records count (`swiftdrop_outbox_pending`).
   - Dead letter events count (`swiftdrop_outbox_dead_letter`).
   - Oldest pending outbox record age in seconds (`swiftdrop_outbox_oldest_age_seconds`).
   - Outbox relay publishing rate and failure rate.

3. **Internal gRPC Operations (`swiftdrop-grpc.json`)**:
   - Inbound and outbound gRPC call rate by service and method.
   - gRPC server request duration quantiles ($p_{95}, p_{99}$).
   - gRPC error rates segmented by status code (`DEADLINE_EXCEEDED`, `UNAVAILABLE`, `PERMISSION_DENIED`).

4. **Communications & Webhooks Engine (`swiftdrop-communications.json`)**:
   - Webhook delivery attempts rate vs exhausted failures (`swiftdrop_webhook_exhausted_total`).
   - Pending notification jobs backlog and oldest job age (`swiftdrop_notification_pending_age_seconds`).
   - Kafka consumer processed count and inbox dead-letter events.

5. **Live Parcel Tracking SSE (`swiftdrop-sse.json`)**:
   - Active client SSE connections count (`swiftdrop_sse_connections_active`).
   - Connection rejection rate (`swiftdrop_sse_connections_rejected_total`).
   - Event broadcast throughput.

6. **Release Candidate Gate (`swiftdrop-release-monitoring.json`)**:
   - Candidate readiness stability gauge.
   - Error budget consumption during deployment canary windows.
   - Outbox backlog slope and Kafka lag during rollout.

---

## 5. Alert Rules & Runbook Reference

Prometheus evaluates 12 reviewed alert rules defined in `files/alerts/swiftdrop-alerts.yaml`:

### 5.1 ServiceReadinessFailed
- **Severity:** `critical`
- **Condition:** `probe_success == 0` for 30 seconds.
- **Description:** A core SwiftDrop microservice failed its Blackbox readiness health probe.
- **Operator Runbook:**
  1. Inspect pod events: `kubectl describe pod -l app.kubernetes.io/name=<service> -n swiftdrop-apps`
  2. Inspect container logs: `kubectl logs -l app.kubernetes.io/name=<service> -n swiftdrop-apps --tail=100`
  3. Verify database connectivity: Ensure PostgreSQL instance in `swiftdrop-data` is reachable.

### 5.2 MonitoringTargetScrapeMissing
- **Severity:** `warning`
- **Condition:** `up == 0` for 1 minute.
- **Description:** Prometheus failed to scrape metrics from an active target.
- **Operator Runbook:** Check pod network policies and Actuator port exposure.

### 5.3 OutboxRelayOldestAgeExceeded
- **Severity:** `critical`
- **Condition:** `swiftdrop_outbox_oldest_age_seconds > 120` for 2 minutes.
- **Description:** Delivery outbox relay backlog contains uncommitted events older than 120 seconds.
- **Operator Runbook:**
  1. Check Kafka broker availability in `swiftdrop-messaging`.
  2. Inspect `delivery-service` outbox relay thread logs for transaction rollback or serialization errors.

### 5.4 OutboxRelayBacklogAccumulation
- **Severity:** `warning`
- **Condition:** `swiftdrop_outbox_pending > 50` for 3 minutes.
- **Description:** Pending outbox events are accumulating faster than the relay can publish.
- **Operator Runbook:** Review database I/O performance and Kafka producer batching.

### 5.5 OutboxDeadLetterEventsAccumulated
- **Severity:** `critical`
- **Condition:** `swiftdrop_outbox_dead_letter > 0` for 2 minutes.
- **Description:** Poison pill outbox events encountered that cannot be published.
- **Operator Runbook:** Query `delivery_db.outbox_events` where `status = 'DEAD_LETTER'` and investigate payload schema corruption.

### 5.6 WebhookTerminalExhaustionSpike
- **Severity:** `warning`
- **Condition:** `rate(swiftdrop_webhook_exhausted_total[5m]) > 0` for 2 minutes.
- **Description:** Merchant webhooks have exhausted maximum retry attempts.
- **Operator Runbook:** Check merchant receiver URL status and inspect HMAC signing configuration.

### 5.7 NotificationPendingBacklogAgeExceeded
- **Severity:** `critical`
- **Condition:** `swiftdrop_notification_pending_age_seconds > 180` for 3 minutes.
- **Description:** Communications notification jobs are stalled in pending state.
- **Operator Runbook:** Verify Mailpit/SMTP sink connectivity and worker thread pool saturation.

### 5.8 KafkaConsumerFailureSpike
- **Severity:** `warning`
- **Condition:** `rate(swiftdrop_kafka_consumer_processed_total{status="failure"}[5m]) > 0.08` for 2 minutes.
- **Description:** Kafka consumer error rate in `communications-service` exceeds threshold.
- **Operator Runbook:** Check deserialization errors and database lock contention in `consumed_events`.

### 5.9 GrpcServerDeadlineExceededSpike
- **Severity:** `warning`
- **Condition:** `rate(swiftdrop_grpc_server_errors{code="DEADLINE_EXCEEDED"}[5m]) > 0.05` for 2 minutes.
- **Description:** Internal synchronous gRPC calls between services are timing out.
- **Operator Runbook:** Identify slow downstream database queries in target microservice.

### 5.10 ApplicationPodCrashLooping
- **Severity:** `critical`
- **Condition:** `increase(kube_pod_container_status_restarts_total[5m]) > 3` for 3 minutes.
- **Description:** Microservice pod is in CrashLoopBackOff.
- **Operator Runbook:** Inspect OOMKilled status and previous container logs: `kubectl logs -p`.

### 5.11 MonitoringDiskSpaceLow
- **Severity:** `warning`
- **Condition:** Storage volume utilization exceeds 85% for 2 minutes.
- **Description:** Prometheus TSDB or Grafana PVC is nearing capacity.
- **Operator Runbook:** Check retention parameters and clean historical crash dumps.

### 5.12 ReleaseCandidateReadinessRegression
- **Severity:** `critical`
- **Condition:** Staging release readiness probe failing during release window.
- **Description:** Automated release gate detected readiness regression on candidate pod.
- **Operator Runbook:** Halt staging promotion and initiate automated rollback per #167 runbook.

---

## 6. Alertmanager Routing & Local Notification Sink

Alertmanager is configured to dispatch notifications locally to the in-cluster Mailpit SMTP service:
- **SMTP Smarthost:** `mailpit.swiftdrop-messaging.svc.cluster.local:1025`
- **From Address:** `alerts@swiftdrop.local`
- **To Address:** `operator@swiftdrop.local`
- **Inhibition Rule:** When a `critical` alert fires for a given service (e.g., `ServiceReadinessFailed`), corresponding `warning` alerts (e.g., `MonitoringTargetScrapeMissing`) for that same service are suppressed to prevent alert storms.

---

## 7. Operator Scripts & Workflows

Automated operator scripts are located at `platform/kubernetes/monitoring/`:

| Script | Purpose | Execution |
|---|---|---|
| `install-local.sh` | Namespace creation, secret injection, helm lint, upgrade/install, rollout wait | `bash platform/kubernetes/monitoring/install-local.sh` |
| `verify-local.sh` | Pod status, zero ingress check, Prometheus scrape targets, Blackbox probes, anonymous rejection | `bash platform/kubernetes/monitoring/verify-local.sh` |
| `status.sh` | Live overview of monitoring pods, PVC usage, and firing Prometheus alerts | `bash platform/kubernetes/monitoring/status.sh` |
| `simulate-failure.sh` | P14A acceptance test: injects synthetic outage, verifies alert, checks Mailpit, restores | `bash platform/kubernetes/monitoring/simulate-failure.sh` |
| `uninstall-local.sh` | Safely uninstalls monitoring release while preserving persistent PVC data | `bash platform/kubernetes/monitoring/uninstall-local.sh` |

### 7.1 Operator Access Instructions (Port-Forwarding)
To access monitoring dashboards from the local workstation:
```bash
# 1. Grafana Dashboards (Access at http://127.0.0.1:3000)
kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-grafana 3000:3000

# 2. Prometheus Query Browser (Access at http://127.0.0.1:9090)
kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-prometheus 9090:9090

# 3. Alertmanager Status & Silences (Access at http://127.0.0.1:9093)
kubectl port-forward -n swiftdrop-monitoring svc/swiftdrop-alertmanager 9093:9093

# 4. Mailpit Local Email Sink (Access at http://127.0.0.1:8025)
kubectl port-forward -n swiftdrop-messaging svc/mailpit 8025:8025
```

---

## 8. Synthetic Failure Injection & P14A Handoff

The synthetic failure injection script `simulate-failure.sh` provides deterministic validation for Issue #166 (P14A):
1. Scales `pricing-service` to 0 replicas: `kubectl scale deployment/pricing-service --replicas=0 -n swiftdrop-apps`
2. Blackbox probe `probe_success{instance="http://pricing-service.swiftdrop-apps.svc.cluster.local:8081/actuator/health"}` transitions from 1 to 0.
3. Prometheus alert rule `ServiceReadinessFailed` transitions to `PENDING` then `FIRING` within 30 seconds.
4. Alertmanager routes email notification to Mailpit.
5. Operator inspects notification in Mailpit UI at `http://127.0.0.1:8025`.
6. Restoration: Scales `pricing-service` back to 1 replica. Probe returns to 1, alert resolves, and resolved notification is received.

---

## 9. Verification & Test Evidence

All 57 Kubernetes and platform automated tests pass:
```bash
pytest tests/kubernetes/
============================== 57 passed in 1.41s ==============================
```

Test coverage includes:
- `test_monitoring_chart.py`: 13 automated tests verifying metadata, least-privilege ServiceAccounts, ClusterIP enforcement, zero Ingress, non-root security contexts, bounded TSDB retention, alert rule integrity, Mailpit SMTP routing, anonymous access rejection, Promtail secret redaction, and strict $0 policy compliance.
- `test_helm_chart_security.py`: 12 automated tests for application chart security.
- `test_cluster_config.py` & `test_cluster_safety.py`: Cluster isolation and local safety tests.
- `test_persistent_workloads.py` & `test_storage_contract.py`: Bounded PVC contracts.

