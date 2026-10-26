#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Full-Stack Runtime Status Inspector (Issue #141)
# Inspects container lifecycle, memory/CPU usage, and active health probes.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/platform/compose"
COMPOSE_FILE="${COMPOSE_DIR}/compose.yaml"
ENV_FILE="${COMPOSE_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Runtime Status & Health Audit"
echo "=============================================================================="

if [ -f "${ENV_FILE}" ]; then
  docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" ps -a
else
  docker compose -f "${COMPOSE_FILE}" ps -a
fi

echo ""
echo "------------------------------------------------------------------------------"
echo " Container Memory & Resource Consumption (Host Ceiling: ~4.06 GiB)"
echo "------------------------------------------------------------------------------"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}" \
  $(docker compose -f "${COMPOSE_FILE}" ps -q 2>/dev/null) 2>/dev/null || echo "No running containers found."

echo ""
echo "------------------------------------------------------------------------------"
echo " Endpoint Health Probes Audit"
echo "------------------------------------------------------------------------------"
probe_http() {
  local name="$1"
  local url="$2"
  local expected="${3:-200}"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
  if [ "$status" = "$expected" ]; then
    printf "  %-32s : [PASS] HTTP %s (%s)\n" "$name" "$status" "$url"
  else
    printf "  %-32s : [FAIL] HTTP %s (expected %s) (%s)\n" "$name" "$status" "$expected" "$url"
  fi
}

probe_http "API Gateway Health" "http://127.0.0.1:8080/actuator/health" "200"
probe_http "React SPA Health" "http://127.0.0.1:3000/health" "200"
probe_http "Keycloak OIDC Discovery" "http://127.0.0.1:8090/realms/swiftdrop/.well-known/openid-configuration" "200"
probe_http "Mailpit Readyz Probe" "http://127.0.0.1:8025/api/v1/info" "200"
probe_http "MinIO Live Probe" "http://127.0.0.1:9000/minio/health/live" "200"

echo ""
echo "------------------------------------------------------------------------------"
echo " Logical Database & Infrastructure State"
echo "------------------------------------------------------------------------------"
probe_db() {
  local name="$1"
  local container="$2"
  local user="$3"
  local db="$4"
  if docker exec "$container" pg_isready -U "$user" -d "$db" >/dev/null 2>&1; then
    printf "  %-32s : [PASS] Database ready (%s on %s)\n" "$name" "$db" "$container"
  else
    printf "  %-32s : [FAIL] Database unreachable (%s on %s)\n" "$name" "$db" "$container"
  fi
}

probe_db "Accounts Database" "swiftdrop-accounts-postgres" "accounts_app" "accounts_db"
probe_db "Keycloak Database" "swiftdrop-keycloak-postgres" "keycloak" "keycloak_db"
probe_db "Pricing Database" "swiftdrop-business-postgres" "pricing_app" "pricing_db"
probe_db "Delivery Database" "swiftdrop-business-postgres" "delivery_app" "delivery_db"
probe_db "Communications Database" "swiftdrop-business-postgres" "communications_app" "communications_db"

if docker exec swiftdrop-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
  printf "  %-32s : [PASS] Broker responsive (PLAINTEXT://kafka:29092)\n" "Kafka KRaft Broker"
else
  printf "  %-32s : [FAIL] Broker unresponsive\n" "Kafka KRaft Broker"
fi

echo "=============================================================================="
