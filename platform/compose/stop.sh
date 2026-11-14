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

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  echo "Usage: ./platform/compose/stop.sh [--supabase | --local]"
  exit 0
fi

if [ "${1:-}" = "--local" ]; then
  shift
  ENV_FILE="${SWIFTDROP_COMPOSE_ENV_FILE:-${COMPOSE_DIR}/.env.local-postgres}"
  if [ ! -f "${ENV_FILE}" ]; then
    ENV_FILE="${COMPOSE_DIR}/.env"
  fi
  if [ -f "${ENV_FILE}" ]; then
    docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" stop "$@"
  else
    docker compose -f "${COMPOSE_FILE}" stop "$@"
  fi
else
  if [ "${1:-}" = "--supabase" ]; then shift; fi
  ENV_FILE="${SWIFTDROP_COMPOSE_ENV_FILE:-${COMPOSE_DIR}/.env}"
  if [ -f "${ENV_FILE}" ]; then
    docker compose --env-file "${ENV_FILE}" \
      -f "${COMPOSE_FILE}" -f "${COMPOSE_DIR}/compose.supabase.yaml" stop "$@"
  else
    docker compose -f "${COMPOSE_FILE}" -f "${COMPOSE_DIR}/compose.supabase.yaml" stop "$@"
  fi
fi

echo "=============================================================================="
echo " SwiftDrop Platform — Stopping Full-Stack Runtime"
echo "=============================================================================="
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
