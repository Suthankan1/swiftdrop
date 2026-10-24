#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Business Dependencies Stop Helper
# Gracefully stops Kafka KRaft, Business PostgreSQL, and MinIO storage.
# SAFE & NON-DESTRUCTIVE: Preserves all persistent named volumes.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEP_DIR="${REPO_ROOT}/platform/dependencies"
ENV_FILE="${DEP_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Stopping Business Dependencies"
echo "=============================================================================="

if [ -f "${ENV_FILE}" ]; then
  docker compose -f "${DEP_DIR}/docker-compose.dependencies.yml" \
    --project-directory "${DEP_DIR}" \
    --env-file "${ENV_FILE}" \
    stop
else
  docker compose -f "${DEP_DIR}/docker-compose.dependencies.yml" \
    --project-directory "${DEP_DIR}" \
    stop
fi

echo ""
echo "Dependencies stopped successfully."
echo "Persistent volumes (swiftdrop-kafka-data, swiftdrop-business-postgres-data, swiftdrop-minio-data) are preserved."
echo "Restart anytime with: ./platform/dependencies/start-dependencies.sh"
echo "=============================================================================="
