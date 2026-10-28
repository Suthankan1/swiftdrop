# N10-P14A Monitoring Query Verification

## 1. Identity and scope

| Item | Evidence |
|---|---|
| Issue | #166 — Define and verify monitoring queries and actionable failure alerts |
| QA owner | `abhi190503` |
| Branch | `qa/n10-p14a-monitoring-verification` |
| Source revision | `1324f4700a5ce7264e489ec1962744aa88d57ab1` (`test`, PR #272 merge) |
| Application metrics revision | `f195b7eae5ecaf9fe51ab51d72e4a42bc5118f9a` (PR #265 merge) |
| Audit environment | Windows/PowerShell, 2026-10-28, Kubernetes client v1.36.1, Python 3.13.5 |
| Runtime access | **BLOCKED** — no kubeconfig context; API defaulted to `localhost:8080` and refused the connection |

This report distinguishes source verification from runtime evidence. No monitoring stack was deployed, no workload was scaled, and no live failure was injected during this audit.

## 2. Executive result

All 12 rules and all six dashboards were reviewed. Four narrow query defects were corrected:

1. `KafkaConsumerFailureSpike` now selects the implemented `status="failure"` series, rather than the nonexistent `status="invalid"` series.
2. `ReleaseCandidateReadinessRegression` treats an entirely absent readiness vector as a failed gate.
3. The two overall readiness dashboard panels also render missing data as a blocked/unhealthy gate rather than an empty green-adjacent panel.
4. The gRPC latency panel calculates mean seconds from the exported timer `_sum` and `_count` series.

All 12 alert runbook fragments were corrected to headings that exist. Live acceptance remains blocked by missing cluster access and by a separate application/platform integration defect: Prometheus is configured to scrape `/actuator/prometheus`, but no application POM contains `micrometer-registry-prometheus`, and application management exposure includes only `health` or `health,info`. The expected application series therefore cannot be exported from the reviewed source configuration.

## 3. Metric-to-query compatibility matrix

`Source verified` means the producer or chart definition exists in this revision. `Observed` means returned by a live Prometheus endpoint; none were observed because no cluster/runtime endpoint was available.

| Prometheus series | Type / unit | Labels used or produced | Producer and consumers | Source verified | Observed |
|---|---|---|---|---|---|
| `probe_success` | gauge, boolean 0/1 | `job`, `instance` | Blackbox exporter; health/release dashboards; readiness alerts | PASS | BLOCKED |
| `probe_duration_seconds` | gauge, seconds | `job`, `instance` | Blackbox exporter; service-health latency panel | PASS | BLOCKED |
| `up` | gauge, boolean 0/1 | `job`, `instance` | Prometheus; scrape panel and `MonitoringTargetScrapeMissing` | PASS | BLOCKED |
| `ALERTS` | gauge, boolean 0/1 | `alertname`, `alertstate`, rule labels | Prometheus; release alert-count panels | PASS | BLOCKED |
| `swiftdrop_outbox_pending` | gauge, events | none | Delivery `OutboxMetrics`; dashboard and backlog alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_outbox_failed` | gauge, events | none | Delivery `OutboxMetrics`; dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_outbox_dead_letter` | gauge, events | none | Delivery `OutboxMetrics`; dashboard and dead-letter alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_outbox_oldest_age_seconds` | gauge, seconds | none | Delivery `OutboxMetrics`; dashboard and age alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_outbox_publish_total` | counter, events | `outcome=success|failure` | Delivery `OutboxMetrics`; publish-rate panel | PASS in code; export blocked | BLOCKED |
| `swiftdrop_webhook_deliveries` | gauge, jobs | `status` | Communications `CommunicationsMetrics`; dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_webhook_exhausted_total` | counter, events | none | Communications metrics; dashboard and exhaustion alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_webhook_attempts_total` | counter, attempts | `outcome` | Communications metrics; dashboard rate | PASS in code; export blocked | BLOCKED |
| `swiftdrop_notification_jobs` | gauge, jobs | `status` | Communications metrics; dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_notification_pending_age_seconds` | gauge, seconds | none | Communications metrics; dashboard and age alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_kafka_consumer_processed_total` | counter, records | `consumer_group`, `status=success|failure` | Communications consumers; dashboard and failure alert | PASS after selector fix; export blocked | BLOCKED |
| `swiftdrop_grpc_server_requests_total` | counter, requests | `service`, `method`, `status`, `calling_service` | Server interceptor; request-rate panel | PASS in code; export blocked | BLOCKED |
| `swiftdrop_grpc_server_errors_total` | counter, errors | `service`, `method`, `status`, `calling_service` | Server interceptor; dashboard and deadline alert | PASS in code; export blocked | BLOCKED |
| `swiftdrop_grpc_client_requests_total` | counter, requests | `service`, `method`, `status` | Client interceptor; request-rate panel | PASS in code; export blocked | BLOCKED |
| `swiftdrop_grpc_client_duration_seconds_sum` / `_count` | timer components, seconds / calls | `service`, `method`, `status` | Client interceptor; mean-latency panel | PASS after query fix; export blocked | BLOCKED |
| `swiftdrop_sse_connections_active` | gauge, connections | none | Delivery SSE manager; SSE dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_sse_connections_accepted_total` | counter, connections | none | Delivery SSE manager; SSE dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_sse_connections_closed_total` | counter, connections | none | Delivery SSE manager; SSE dashboard | PASS in code; export blocked | BLOCKED |
| `swiftdrop_sse_connections_rejected_total` | counter, connections | `reason` | Delivery SSE manager; SSE dashboard | PASS in code; export blocked | BLOCKED |
| `kube_pod_container_status_restarts_total` | counter, restarts | `namespace`, `pod`, `container` | Requires kube-state-metrics; release dashboard and crash alert | **FAIL — no provider in chart** | BLOCKED |
| `node_filesystem_avail_bytes` / `_size_bytes` | gauges, bytes | `mountpoint`, node labels | Requires node exporter; disk alert | **FAIL — no provider in chart and `/prometheus` is not established as a node mount label** | BLOCKED |

### Scrape and readiness integration findings

- `swiftdrop-apps` statically targets six services at `/actuator/prometheus`.
- All six service builds include Actuator, but none includes `micrometer-registry-prometheus`.
- Accounts, pricing, delivery, and communications expose `health,info`; gateway and GraphQL expose `health`. None exposes `prometheus`.
- NetworkPolicy source review shows an intended monitoring-to-app scrape allowance, but live enforcement was not observable.
- Blackbox probes call generic `/actuator/health` for all six Spring applications. Five configurations define readiness probes, but the configured target does not use `/actuator/health/readiness`; GraphQL does not explicitly enable health probes. The rule name and annotations therefore overstate operational-readiness coverage.
- Because the scrape targets are static, `up == 0` covers unreachable, unauthorized, and non-2xx scrape attempts while Prometheus is evaluating the target. It does not cover disappearance of an entire job/rule input; an `absent`/inventory contract is still required.

Required owner actions:

- **Thuvarahan:** approve and implement a private Prometheus registry/exposure contract for the application services; do not expose it publicly.
- **Suthankan:** confirm the intended readiness paths, add the approved metric providers or remove providerless rules/panels, and provide an accessible isolated runtime for live verification.

## 4. Alert matrix

Policy thresholds without a cited approval are recorded as **PROPOSED — OWNER APPROVAL REQUIRED**. “Static” evaluates the expression against source contracts; “Live” is actual pending/firing/resolved evidence.

| Alert | Expression; unit; threshold / `for` | Source, severity, owner | Static result and recovery | Live |
|---|---|---|---|---|
| `ServiceReadinessFailed` | `probe_success{job="swiftdrop-readiness"} == 0`; boolean; 0 / 30s | Blackbox; critical; abhi190503 | **FAIL:** generic health paths are configured, not the stated readiness paths. Recovers at 1. | BLOCKED |
| `MonitoringTargetScrapeMissing` | `up == 0`; boolean; 0 / 1m | Prometheus; warning; Suthankan1 | **FAIL:** valid for evaluated targets but cannot prove an expected job/series has disappeared. Recovers at 1. | BLOCKED |
| `OutboxRelayOldestAgeExceeded` | age gauge; `>60s` / 2m | Delivery; critical; abhi190503 | PASS query semantics; export integration blocked. Recovery below/equal 60s. Threshold PROPOSED. | BLOCKED |
| `OutboxRelayBacklogAccumulation` | count gauge; `>100` / 3m | Delivery; warning; abhi190503 | PASS query semantics; export integration blocked. Recovery below/equal 100. Threshold PROPOSED. | BLOCKED |
| `OutboxDeadLetterEventsAccumulated` | count gauge; `>0` / 2m | Delivery; critical; abhi190503 | PASS query semantics; export integration blocked. Recovery at 0. Threshold PROPOSED. | BLOCKED |
| `WebhookTerminalExhaustionSpike` | `rate(...[5m]) > 5`; events/s; 5/s / 2m | Communications; warning; abhi190503 | **BLOCKED policy:** expression means over 1,500 events per five minutes, not five events per five minutes. Recommend `increase(...[5m]) > 5` only if that is approved intent. | BLOCKED |
| `NotificationPendingBacklogAgeExceeded` | age gauge; `>120s` / 3m | Communications; critical; abhi190503 | PASS query semantics; export integration blocked. Recovery below/equal 120s. Threshold PROPOSED. | BLOCKED |
| `KafkaConsumerFailureSpike` | `rate(...{status="failure"}[5m]) > 0`; failures/s; any / 2m | Communications; warning; abhi190503 | PASS after selector and annotations were aligned with actual consumer instrumentation. Recovery at zero rate. Threshold PROPOSED. | BLOCKED |
| `GrpcServerDeadlineExceededSpike` | server error `rate` over 5m; `>2/s` / 2m | gRPC server; warning; abhi190503 | PASS syntax/type/labels, but client-observed deadlines can be missed and 2/s is PROPOSED. Recovery below/equal 2/s. | BLOCKED |
| `ApplicationPodCrashLooping` | restart counter `rate`; `>0.05/s` / 3m | kube-state-metrics; critical; Suthankan1 | **FAIL:** no kube-state-metrics provider. Name also implies state while expression measures restart rate. | BLOCKED |
| `MonitoringDiskSpaceLow` | available/size ratio; `<15%` / 2m | node exporter; warning; Suthankan1 | **FAIL:** no node exporter and no verified `/prometheus` mount label. | BLOCKED |
| `ReleaseCandidateReadinessRegression` | `(min(probe_success) or vector(0)) == 0`; boolean / 1m | Blackbox; critical; Harithkeshan | PASS after missing-vector fail-safe. Recovers only when the minimum is 1. Policy remains subject to #167 approval. | BLOCKED |

The critical-to-warning inhibition rule matches on `service`. `ServiceReadinessFailed` uses the full target URL as `service`, while `MonitoringTargetScrapeMissing` uses `job`; those values do not normally match, so the documented suppression relationship is not established.

## 5. Dashboard matrix

All panels retain datasource UID `swiftdrop-prometheus`; no sensitive identifiers appear in query labels. “Blocked” means the query is structurally sound but its application exposition or live provider is unavailable.

| Dashboard | Panel review | Result |
|---|---|---|
| `swiftdrop-service-health` | Overall health: `min(probe_success) or vector(0)`, boolean mapping; per-target readiness: `probe_success`, 0–1; scrape status: `up`, 0–1; latency: `probe_duration_seconds`, seconds. | PASS static after missing-data fix; live BLOCKED. Generic health/readiness mismatch remains. |
| `swiftdrop-delivery-outbox` | Pending/failed/dead-letter gauges are counts; oldest age is seconds; state trend uses the three gauges; publish throughput correctly applies `rate` to the counter with operations/s unit. | PASS static; all service data BLOCKED by exposition. |
| `swiftdrop-grpc` | Server/client request and server error panels use counter rates; deadline panel filters `status="DEADLINE_EXCEEDED"`; status panel groups by bounded labels; client latency now divides timer `_sum` rate by `_count` rate and uses seconds. | PASS static after latency fix; service data BLOCKED by exposition. |
| `swiftdrop-communications` | Pending webhooks/jobs are gauges; exhausted is explicitly a cumulative total; pending age is seconds; webhook and Kafka panels apply rates to counters. | PASS static; service data BLOCKED by exposition. |
| `swiftdrop-sse` | Active connections is a gauge; rejected total is explicitly cumulative; accepted/closed and rejected-by-reason panels correctly use counter rates with connections/s units. | PASS static; service data BLOCKED by exposition. |
| `swiftdrop-release-monitoring` | Gate now fails closed on missing readiness data; alert totals use `or vector(0)`; per-target readiness is 0–1; restart-rate panel uses operations/s. | **FAIL overall:** restart panel has no kube-state-metrics provider; other panels pass static review; live BLOCKED. |

Grafana JSON files parsed successfully. UIDs and provisioning contracts are unchanged. Actual panel rendering and no-data UI behavior were **NOT RUN**.

## 6. Synthetic failure and notification evidence

| Stage | Result | Evidence |
|---|---|---|
| Healthy baseline | NOT RUN | No Kubernetes context or Prometheus endpoint |
| Pricing scale-down | NOT RUN | Operator-owned disruptive action; intentionally not executed |
| Probe transition | BLOCKED | No live series |
| Alert pending | BLOCKED | No live rule engine |
| Alert firing | BLOCKED | No live rule engine |
| Alertmanager receipt | BLOCKED | No Alertmanager endpoint |
| Mailpit notification | BLOCKED | No Mailpit endpoint |
| Workload restoration | NOT RUN | No mutation was performed |
| Probe/alert resolution | BLOCKED | No live rule engine |

The existing `simulate-failure.sh` must not be used as acceptance evidence in its current form without operator supervision. It assumes the original replica count is one, lacks a restoration trap, records but does not assert metric/alert detection, does not validate a matching Mailpit message or alert resolution, and prints completion even when no firing alert was observed.

## 7. Static tests and tools

| Command | Actual result |
|---|---|
| `python -m unittest tests.kubernetes.test_monitoring_query_semantics -v` | PASS — 13 run, 12 passed, 1 skipped (`promtool` unavailable) |
| `python -m unittest tests.kubernetes.test_monitoring_chart -v` | FAIL before test execution — `helm` executable not installed; 0 tests run |
| `python -m pytest tests/kubernetes/test_monitoring_chart.py -q` | NOT RUN — `pytest` is not installed |
| `promtool check rules ...` | NOT RUN — `promtool` is not installed; represented by the explicit skipped test |
| `helm lint` / `helm template` | NOT RUN — Helm is not installed |
| `kubectl version --client` | PASS — client v1.36.1, Kustomize v5.8.1 |
| `kubectl config current-context` | BLOCKED — no current context |
| `kubectl get namespaces` | BLOCKED — connection refused at `localhost:8080` |
| `docker ps` | PASS as an environment check — Docker engine reachable when authorized; no running containers |

The focused tests cover alert inventory, expression/threshold contracts, required annotations, real runbook anchors, Kafka label compatibility, fail-closed readiness summaries, dashboard UID/datasource stability, gRPC timer semantics, rate units, and sensitive/high-cardinality label exclusions. They are static regression evidence, not proof of rule evaluation or alert delivery.

## 8. Security and cost

- PASS: no secrets, credentials, raw customer data, parcel IDs, event IDs, tenant IDs, or actor subjects were introduced into metrics or evidence.
- PASS: no Ingress, public binding, anonymous access, authentication bypass, SMTP route, NetworkPolicy, or Kubernetes Secret was changed.
- PASS: no deployment, restart, scaling, failure injection, or other cluster mutation was attempted.
- PASS: no paid or external monitoring resource was used; ADR-0011 remains satisfied.
- BLOCKED: live verification of private access, scrape authorization, NetworkPolicy enforcement, and notification content requires the operator runtime.

## 9. Acceptance blockers and handoff

1. Provide access to the approved `k3d-swiftdrop-local` context or an equivalent isolated operator-run checkpoint.
2. Resolve the private `/actuator/prometheus` registry/exposure contract with Thuvarahan and Suthankan, then prove all six application targets are UP.
3. Decide whether Spring targets must use `/actuator/health/readiness`; explicitly enable the chosen GraphQL readiness contract.
4. Add approved providers for Kubernetes restart and storage capacity metrics, or remove/replace the unsupported queries.
5. Approve alert thresholds, especially webhook exhaustion (5/s versus 5/5m) and gRPC deadline rate.
6. Define expected-target absence semantics beyond `up == 0`.
7. Align inhibition labels if readiness criticals are intended to suppress corresponding scrape warnings.
8. Harden and operator-run the failure script; capture timestamped target, pending, firing, Alertmanager, Mailpit, recovery, and resolved evidence.

## 10. Conclusion

**IMPLEMENTED WITH OPERATOR/APPROVAL BLOCKERS — NOT YET FULLY ACCEPTED**
