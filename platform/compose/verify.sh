#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Full-Stack Compose Automated Verification (Issue #141)
# Verifies database migrations, role boundaries, Kafka topics, MinIO access,
# Keycloak OIDC token contract, and Spring Cloud Gateway business routing.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/platform/compose"
ENV_FILE="${COMPOSE_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Automated Runtime Verification Suite"
echo "=============================================================================="

TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0

check_result() {
  local desc="$1"
  local code="$2"
  TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
  if [ "$code" -eq 0 ]; then
    printf "  [PASS] %s\n" "$desc"
    PASSED_CHECKS=$((PASSED_CHECKS + 1))
  else
    printf "  [FAIL] %s\n" "$desc"
    FAILED_CHECKS=$((FAILED_CHECKS + 1))
  fi
}

# ------------------------------------------------------------------------------
# 1. Database Schema Migrations & Flyway History
# ------------------------------------------------------------------------------
echo "[1/6] Verifying service-owned Flyway migrations & schema state..."

# Accounts Flyway (V1 - V5)
ACCOUNTS_MIGRATIONS=$(docker exec swiftdrop-accounts-postgres psql -U accounts_app -d accounts_db -t -c \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ' || echo "0")
if [ "$ACCOUNTS_MIGRATIONS" -ge 1 ]; then
  check_result "Accounts DB Flyway migrations applied ($ACCOUNTS_MIGRATIONS successful scripts)" 0
else
  check_result "Accounts DB Flyway migrations missing or uninitialized" 1
fi

# Pricing Flyway (V1 - V3)
PRICING_MIGRATIONS=$(docker exec swiftdrop-business-postgres psql -U pricing_app -d pricing_db -t -c \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ' || echo "0")
if [ "$PRICING_MIGRATIONS" -ge 1 ]; then
  check_result "Pricing DB Flyway migrations applied ($PRICING_MIGRATIONS successful scripts)" 0
else
  check_result "Pricing DB Flyway migrations missing or uninitialized" 1
fi

# Delivery Flyway (V1 - V4)
DELIVERY_MIGRATIONS=$(docker exec swiftdrop-business-postgres psql -U delivery_app -d delivery_db -t -c \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ' || echo "0")
if [ "$DELIVERY_MIGRATIONS" -ge 1 ]; then
  check_result "Delivery DB Flyway migrations applied ($DELIVERY_MIGRATIONS successful scripts)" 0
else
  check_result "Delivery DB Flyway migrations missing or uninitialized" 1
fi

# Communications Flyway (V1)
COMMS_MIGRATIONS=$(docker exec swiftdrop-business-postgres psql -U communications_app -d communications_db -t -c \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ' || echo "0")
if [ "$COMMS_MIGRATIONS" -ge 1 ]; then
  check_result "Communications DB Flyway migrations applied ($COMMS_MIGRATIONS successful scripts)" 0
else
  check_result "Communications DB Flyway migrations missing or uninitialized" 1
fi

# Keycloak DB Tables
KEYCLOAK_TABLES=$(docker exec swiftdrop-keycloak-postgres psql -U keycloak -d keycloak_db -t -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';" 2>/dev/null | tr -d ' ' || echo "0")
if [ "$KEYCLOAK_TABLES" -ge 10 ]; then
  check_result "Keycloak persistent PostgreSQL schema initialized ($KEYCLOAK_TABLES tables)" 0
else
  check_result "Keycloak persistent PostgreSQL schema incomplete" 1
fi

# ------------------------------------------------------------------------------
# 2. Strict Database Role & Cross-Service Boundary Isolation
# ------------------------------------------------------------------------------
echo ""
echo "[2/6] Verifying database security boundaries & unprivileged roles..."

# Negative test: pricing_app cannot connect to delivery_db
if docker exec swiftdrop-business-postgres psql -U pricing_app -d delivery_db -c "SELECT 1;" >/dev/null 2>&1; then
  check_result "Negative authorization: pricing_app access to delivery_db was PERMITTED (violation!)" 1
else
  check_result "Negative authorization: pricing_app access to delivery_db was DENIED (correct boundary)" 0
fi

# Negative test: delivery_app cannot connect to communications_db
if docker exec swiftdrop-business-postgres psql -U delivery_app -d communications_db -c "SELECT 1;" >/dev/null 2>&1; then
  check_result "Negative authorization: delivery_app access to communications_db was PERMITTED (violation!)" 1
else
  check_result "Negative authorization: delivery_app access to communications_db was DENIED (correct boundary)" 0
fi

# Negative test: accounts_app cannot connect to business-postgres
if docker exec swiftdrop-business-postgres psql -U accounts_app -d pricing_db -c "SELECT 1;" >/dev/null 2>&1; then
  check_result "Negative authorization: accounts_app access to business-postgres was PERMITTED (violation!)" 1
else
  check_result "Negative authorization: accounts_app access to business-postgres was DENIED (correct boundary)" 0
fi

# ------------------------------------------------------------------------------
# 3. Apache Kafka KRaft Lifecycle Topics & Consumers
# ------------------------------------------------------------------------------
echo ""
echo "[3/6] Verifying Kafka streaming topics..."
KAFKA_TOPIC_OUTPUT=$(docker exec swiftdrop-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null || echo "")
if echo "$KAFKA_TOPIC_OUTPUT" | grep -qx "parcel.lifecycle.v1"; then
  check_result "Kafka topic 'parcel.lifecycle.v1' provisioned" 0
else
  check_result "Kafka topic 'parcel.lifecycle.v1' not found" 1
fi

if echo "$KAFKA_TOPIC_OUTPUT" | grep -qx "parcel.lifecycle.v1.dlt"; then
  check_result "Kafka dead-letter topic 'parcel.lifecycle.v1.dlt' provisioned" 0
else
  check_result "Kafka dead-letter topic 'parcel.lifecycle.v1.dlt' not found" 1
fi

# ------------------------------------------------------------------------------
# 4. S3 Private Object Storage & Anonymous Access Prevention
# ------------------------------------------------------------------------------
echo ""
echo "[4/6] Verifying MinIO private object storage..."
# Anonymous read to private bucket must return 403 Access Denied
ANON_S3_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:9000/swiftdrop-pod/" 2>/dev/null || echo "000")
if [ "$ANON_S3_STATUS" = "403" ]; then
  check_result "MinIO anonymous bucket access DENIED (HTTP 403 Access Denied - secure)" 0
else
  check_result "MinIO anonymous bucket access gave unexpected status: $ANON_S3_STATUS" 1
fi

# ------------------------------------------------------------------------------
# 5. Keycloak OIDC Issuer & Claims Contract
# ------------------------------------------------------------------------------
echo ""
echo "[5/6] Verifying Keycloak OIDC discovery & issuer integrity..."
OIDC_CONFIG=$(curl -s "http://127.0.0.1:8090/realms/swiftdrop/.well-known/openid-configuration" 2>/dev/null || echo "")
if echo "$OIDC_CONFIG" | grep -q '"issuer":"http://localhost:8090/realms/swiftdrop"'; then
  check_result "Keycloak OIDC issuer matches contract ('http://localhost:8090/realms/swiftdrop')" 0
else
  check_result "Keycloak OIDC issuer mismatch or discovery unavailable" 1
fi

if echo "$OIDC_CONFIG" | grep -q '"jwks_uri":"http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"'; then
  check_result "Keycloak JWKS endpoint matches contract" 0
else
  check_result "Keycloak JWKS endpoint mismatch" 1
fi

# ------------------------------------------------------------------------------
# 6. API Gateway Routing & Cross-Service Resolution
# ------------------------------------------------------------------------------
echo ""
echo "[6/6] Verifying Spring Cloud Gateway business routes..."

# Pricing zones route via Gateway
GATEWAY_ZONES=$(curl -s -f "http://127.0.0.1:8080/api/v1/zones" 2>/dev/null || echo "")
if echo "$GATEWAY_ZONES" | grep -q '"zoneCode"'; then
  check_result "Gateway route -> pricing-service:8082 (/api/v1/zones) returned active zones" 0
else
  check_result "Gateway route -> pricing-service:8082 (/api/v1/zones) failed" 1
fi

# React SPA health via loopback
SPA_HEALTH=$(curl -s "http://127.0.0.1:3000/health" 2>/dev/null || echo "")
if echo "$SPA_HEALTH" | grep -q "healthy"; then
  check_result "React SPA Nginx unprivileged health probe (/health) returned 'healthy'" 0
else
  check_result "React SPA health probe failed" 1
fi

echo ""
echo "=============================================================================="
echo " Verification Summary: ${PASSED_CHECKS}/${TOTAL_CHECKS} checks passed (${FAILED_CHECKS} failed)"
echo "=============================================================================="
if [ "$FAILED_CHECKS" -gt 0 ]; then
  exit 1
fi
