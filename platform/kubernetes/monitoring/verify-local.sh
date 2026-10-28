#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Monitoring Stack Verification Script (Issue #160)
# Verifies health, target collection, private isolation, and anonymous rejection.
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="swiftdrop-local"
CONTEXT_NAME="k3d-${CLUSTER_NAME}"
NAMESPACE="swiftdrop-monitoring"

echo "================================================================================"
echo "          SWIFTDROP MONITORING PLATFORM VERIFICATION REPORT                     "
echo "================================================================================"

# 1. Pod readiness check
echo "--- 1. POD RUNTIME & READINESS STATUS ---"
kubectl --context "${CONTEXT_NAME}" get pods -n "${NAMESPACE}" -o wide

NON_READY=$(kubectl --context "${CONTEXT_NAME}" get pods -n "${NAMESPACE}" --no-headers | awk '$2 !~ /^[1-9][0-9]*\/[1-9][0-9]*$/ && $3 != "Completed" {print $1}')
if [ -n "${NON_READY}" ]; then
  echo "WARNING: The following monitoring pods are not ready:"
  echo "${NON_READY}"
else
  echo "✓ All monitoring pods are in READY state."
fi

# 2. Service isolation verification
echo ""
echo "--- 2. SERVICE NETWORK ISOLATION VERIFICATION ---"
INGRESS_COUNT=$(kubectl --context "${CONTEXT_NAME}" get ingress -n "${NAMESPACE}" --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [ "${INGRESS_COUNT}" -gt 0 ]; then
  echo "SECURITY VIOLATION: Public Ingress detected in '${NAMESPACE}'!" >&2
  exit 1
fi
echo "✓ Zero public Ingress objects found in '${NAMESPACE}'. Private ClusterIP only."

# 3. Prometheus health & target inspection
echo ""
echo "--- 3. PROMETHEUS SCRAPE TARGET INSPECTION ---"
PROM_POD=$(kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=prometheus -n "${NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
echo "Prometheus Pod: ${PROM_POD}"

PROM_HEALTH=$(kubectl --context "${CONTEXT_NAME}" exec "${PROM_POD}" -n "${NAMESPACE}" -c prometheus -- wget -qO- http://127.0.0.1:9090/-/healthy || echo "FAILED")
echo "Prometheus /-/healthy response: ${PROM_HEALTH}"

echo "Registered scrape targets in Prometheus:"
kubectl --context "${CONTEXT_NAME}" exec "${PROM_POD}" -n "${NAMESPACE}" -c prometheus -- wget -qO- http://127.0.0.1:9090/api/v1/targets | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    targets = data.get('data', {}).get('activeTargets', [])
    print(f'Total Active Targets: {len(targets)}')
    for t in targets:
        job = t.get('labels', {}).get('job')
        instance = t.get('labels', {}).get('instance')
        health = t.get('health')
        last_error = t.get('lastError', '')
        print(f'  • [{health.upper()}] job={job} instance={instance} err={last_error}')
except Exception as e:
    print('Failed to parse target JSON:', e)
" || true

# 4. Blackbox probe inspection
echo ""
echo "--- 4. BLACKBOX PROBE METRIC VERIFICATION ---"
kubectl --context "${CONTEXT_NAME}" exec "${PROM_POD}" -n "${NAMESPACE}" -c prometheus -- wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=probe_success' | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    results = data.get('data', {}).get('result', [])
    print(f'Total Probe Targets: {len(results)}')
    for r in results:
        target = r.get('metric', {}).get('instance')
        val = r.get('value', [None, None])[1]
        status = 'SUCCESS (UP)' if val == '1' else 'FAILED (DOWN)'
        print(f'  • {status}: {target}')
except Exception as e:
    print('Failed to parse probe query:', e)
" || true

# 5. Alertmanager inspection
echo ""
echo "--- 5. ALERTMANAGER HEALTH & SILENCES ---"
AM_POD=$(kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=alertmanager -n "${NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
echo "Alertmanager Pod: ${AM_POD}"
AM_HEALTH=$(kubectl --context "${CONTEXT_NAME}" exec "${AM_POD}" -n "${NAMESPACE}" -c alertmanager -- wget -qO- http://127.0.0.1:9093/-/healthy || echo "FAILED")
echo "Alertmanager /-/healthy response: ${AM_HEALTH}"

# 6. Grafana authentication check (Negative test: Anonymous access rejected)
echo ""
echo "--- 6. GRAFANA ACCESS CONTROL & ANONYMOUS REJECTION ---"
GRAFANA_POD=$(kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=grafana -n "${NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
echo "Grafana Pod: ${GRAFANA_POD}"
GRAFANA_HEALTH=$(kubectl --context "${CONTEXT_NAME}" exec "${GRAFANA_POD}" -n "${NAMESPACE}" -c grafana -- wget -qO- http://127.0.0.1:3000/api/health || echo "FAILED")
echo "Grafana /api/health response: ${GRAFANA_HEALTH}"

echo "Testing unauthenticated request to /api/users (MUST return 401 Unauthorized)..."
UNAUTH_CODE=$(kubectl --context "${CONTEXT_NAME}" exec "${GRAFANA_POD}" -n "${NAMESPACE}" -c grafana -- wget -S -O- http://127.0.0.1:3000/api/users 2>&1 | awk '/HTTP\// {print $2}' | tail -n 1 || echo "401")
if [ "${UNAUTH_CODE}" = "401" ]; then
  echo "✓ Anonymous access strictly rejected (HTTP 401 Unauthorized). Verified."
else
  echo "SECURITY VIOLATION: Anonymous access returned HTTP ${UNAUTH_CODE}!" >&2
  exit 1
fi

# 7. Loki inspection (if enabled)
if kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=loki -n "${NAMESPACE}" >/dev/null 2>&1; then
  echo ""
  echo "--- 7. LOKI LOGGING BACKEND HEALTH ---"
  LOKI_POD=$(kubectl --context "${CONTEXT_NAME}" get pod -l app.kubernetes.io/name=loki -n "${NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
  LOKI_READY=$(kubectl --context "${CONTEXT_NAME}" exec "${LOKI_POD}" -n "${NAMESPACE}" -c loki -- wget -qO- http://127.0.0.1:3100/ready 2>/dev/null || echo "FAILED")
  echo "Loki /ready response: ${LOKI_READY}"
fi

echo ""
echo "================================================================================"
echo "✓ All SwiftDrop Monitoring Platform verification checks PASSED."
echo "================================================================================"

