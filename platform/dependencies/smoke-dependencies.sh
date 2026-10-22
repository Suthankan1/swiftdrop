#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Business Dependencies Smoke Test Runner
# Executes automated smoke tests for Kafka KRaft, PostgreSQL isolation,
# and S3 private storage. Safe & non-destructive.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEP_DIR="${REPO_ROOT}/platform/dependencies"
ENV_FILE="${DEP_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Business Dependencies Smoke Test Suite"
echo "=============================================================================="

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

# Run python smoke test suite
python3 "${DEP_DIR}/tests/test_dependencies_smoke.py"
