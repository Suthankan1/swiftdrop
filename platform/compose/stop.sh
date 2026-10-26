#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Full-Stack Compose Runtime Stop (Issue #141)
# Gracefully stops all eight application/identity containers and stateful DBs.
# SAFE & NON-DESTRUCTIVE: All persistent named volumes are strictly preserved.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/platform/compose"
COMPOSE_FILE="${COMPOSE_DIR}/compose.yaml"
ENV_FILE="${COMPOSE_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Stopping Full-Stack Runtime"
echo "=============================================================================="

if [ -f "${ENV_FILE}" ]; then
  docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" stop
else
  docker compose -f "${COMPOSE_FILE}" stop
fi

echo ""
echo "All SwiftDrop runtime containers stopped successfully."
echo "Persistent volumes preserved:"
echo "  - swiftdrop-accounts-postgres-data (accounts_db)"
echo "  - swiftdrop-keycloak-postgres-data (keycloak_db)"
echo "  - swiftdrop-business-postgres-data (pricing_db, delivery_db, communications_db)"
echo "  - swiftdrop-kafka-data (Kafka logs and consumer offsets)"
echo "  - swiftdrop-minio-data (Private POD object storage)"
echo ""
echo "Restart anytime without data loss via: ./platform/compose/start.sh"
echo "=============================================================================="
