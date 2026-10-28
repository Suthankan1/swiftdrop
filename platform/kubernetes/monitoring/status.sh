#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Monitoring Platform Status & Resource Summary (Issue #160)
# Canonical Reference: docs/architecture/BASELINE.md, ADR-0011
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
NAMESPACE="swiftdrop-monitoring"

echo "================================================================================"
echo "          SWIFTDROP MONITORING PLATFORM OPERATIONAL STATUS                      "
echo "================================================================================"

# 1. Pods
echo "--- 1. MONITORING WORKLOADS & PODS ---"
kubectl --context "${CONTEXT_NAME}" get pods -n "${NAMESPACE}" -o wide

# 2. Services
echo ""
echo "--- 2. INTERNAL CLUSTERIP SERVICES ---"
kubectl --context "${CONTEXT_NAME}" get svc -n "${NAMESPACE}"

# 3. Persistent Storage
echo ""
echo "--- 3. PERSISTENT STORAGE ALLOCATION ---"
kubectl --context "${CONTEXT_NAME}" get pvc -n "${NAMESPACE}"

# 4. Resource Consumption
echo ""
echo "--- 4. CLUSTER & MONITORING CONTAINER CONSUMPTION ---"
if command -v docker >/dev/null 2>&1; then
  docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" | grep -E "k3d|NAME" || true
fi

# 5. Firing Alerts
echo ""
echo "--- 5. ACTIVE PROMETHEUS / ALERTMANAGER ALERTS ---"
PROM_POD=$(kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=prometheus -n "${NAMESPACE}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
if [ -n "${PROM_POD}" ]; then
  kubectl --context "${CONTEXT_NAME}" exec "${PROM_POD}" -n "${NAMESPACE}" -c prometheus -- wget -qO- 'http://127.0.0.1:9090/api/v1/alerts' 2>/dev/null | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    alerts = data.get('data', {}).get('alerts', [])
    firing = [a for a in alerts if a.get('state') == 'firing']
    pending = [a for a in alerts if a.get('state') == 'pending']
    print(f'Total Active Alerts: {len(alerts)} (Firing: {len(firing)}, Pending: {len(pending)})')
    for a in alerts:
        name = a.get('labels', {}).get('alertname')
        state = a.get('state').upper()
        sev = a.get('labels', {}).get('severity')
        svc = a.get('labels', {}).get('service', 'N/A')
        print(f'  • [{state}] {name} (severity={sev}, service={svc})')
except Exception as e:
    print('No alerts active or query failed:', e)
" || echo "No alert data retrieved."
else
  echo "Prometheus pod not found."
fi

echo "================================================================================"

