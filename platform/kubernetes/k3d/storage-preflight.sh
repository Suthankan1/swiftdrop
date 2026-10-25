#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Local Storage Preflight Validation
# Canonical Reference: docs/architecture/BASELINE.md, docs/security/
# GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

STORAGE_ROOT="${SWIFTDROP_STORAGE_ROOT:-${HOME}/.swiftdrop/k3d/storage}"
MIN_FREE_SPACE_GB=10
RECOMMENDED_SPACE_GB=20

echo "================================================================================"
echo "          SWIFTDROP LOCAL PERSISTENT STORAGE PREFLIGHT VALIDATION               "
echo "================================================================================"
echo "Target storage root: ${STORAGE_ROOT}"
echo "Repository root:     ${REPO_ROOT}"

# 1. Path isolation check: Storage must NOT reside within the git working tree
REAL_STORAGE_DIR=""
if [ -d "${STORAGE_ROOT}" ]; then
  REAL_STORAGE_DIR="$(cd "${STORAGE_ROOT}" && pwd -P)"
else
  # Check parent if storage dir does not exist yet
  PARENT_DIR="$(dirname "${STORAGE_ROOT}")"
  mkdir -p "${PARENT_DIR}"
  REAL_STORAGE_DIR="$(cd "${PARENT_DIR}" && pwd -P)/$(basename "${STORAGE_ROOT}")"
fi

REAL_REPO_DIR="$(cd "${REPO_ROOT}" && pwd -P)"
case "${REAL_STORAGE_DIR}" in
  "${REAL_REPO_DIR}"*)
    echo "ERROR: Storage root (${STORAGE_ROOT}) cannot be placed inside the git repository (${REPO_ROOT})!" >&2
    exit 1
    ;;
esac

# 2. Directory creation with secure operator-only permissions
if [ ! -d "${STORAGE_ROOT}" ]; then
  echo "Creating storage root directory: ${STORAGE_ROOT}"
  mkdir -p "${STORAGE_ROOT}"
  chmod 700 "${STORAGE_ROOT}"
fi

# 3. Ownership and permission check: Must NOT be world-writable
PERMS="$(stat -f "%OLp" "${STORAGE_ROOT}" 2>/dev/null || stat -c "%a" "${STORAGE_ROOT}" 2>/dev/null || echo "unknown")"
if [ "${PERMS}" = "unknown" ]; then
  echo "WARNING: Could not determine permissions for ${STORAGE_ROOT}."
else
  # Check if other (world) has write permission (e.g. 777, 757, etc.)
  OTHER_DIGIT="${PERMS: -1}"
  if [ "$(( OTHER_DIGIT & 2 ))" -ne 0 ]; then
    echo "ERROR: Storage root ${STORAGE_ROOT} has world-writable permissions (${PERMS})! Hardening to 700..." >&2
    chmod 700 "${STORAGE_ROOT}"
  fi
fi

# 4. Disk space verification
AVAILABLE_KB=$(df -k "${STORAGE_ROOT}" | awk 'NR==2 {print $4}')
AVAILABLE_GB=$(( AVAILABLE_KB / 1024 / 1024 ))
echo "Available disk space: ${AVAILABLE_GB} GiB"

if [ "${AVAILABLE_GB}" -lt "${MIN_FREE_SPACE_GB}" ]; then
  echo "ERROR: Insufficient disk space on ${STORAGE_ROOT}. Found ${AVAILABLE_GB} GiB, required >= ${MIN_FREE_SPACE_GB} GiB!" >&2
  exit 1
elif [ "${AVAILABLE_GB}" -lt "${RECOMMENDED_SPACE_GB}" ]; then
  echo "WARNING: Disk space (${AVAILABLE_GB} GiB) is below recommended headroom (${RECOMMENDED_SPACE_GB} GiB)."
else
  echo "✓ Disk space verification PASSED (${AVAILABLE_GB} GiB available)."
fi

# 5. Service-owned storage isolation boundaries (informational contract for #154)
echo "--------------------------------------------------------------------------------"
echo "Planned Service-Owned Storage Allocation Boundaries (Issue #154):"
echo "  • Accounts DB:         accounts-db/         (Accounts service owned)"
echo "  • Pricing DB:          pricing-db/          (Pricing service owned)"
echo "  • Delivery DB:         delivery-db/         (Delivery service owned)"
echo "  • Communications DB:   communications-db/   (Communications service owned)"
echo "  • Keycloak DB:         keycloak-db/         (Platform / Identity owned)"
echo "  • Kafka KRaft:         kafka-broker/        (Platform Kafka broker owned)"
echo "  • MinIO Objects:       minio-data/          (Platform Private S3 owned)"
echo "--------------------------------------------------------------------------------"
echo "✓ Local storage preflight PASSED: ${STORAGE_ROOT}"
