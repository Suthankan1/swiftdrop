#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Isolated PostgreSQL Logical Backup Tool
# Performs point-in-time custom archive (pg_dump -F c) backups of all five
# logical service databases, generates cryptographic SHA-256 checksums, encrypts
# backup artifacts for offline custody, and creates a structured backup manifest.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

CONTEXT_NAME="k3d-swiftdrop-local"
DATA_NAMESPACE="swiftdrop-data"
BACKUP_ROOT="${HOME}/.swiftdrop/backups"
BACKUP_ID="backup-$(date +%Y%m%d%H%M%S)"
ENCRYPTION_PASSPHRASE="${SWIFTDROP_BACKUP_KEY:-swiftdrop-dev-recovery-custody-key}"
CUSTODIAN="@Suthankan1"
RETENTION_DAYS=30
DRY_RUN=false
FIXTURE_FILE=""

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>      Kubernetes context (default: k3d-swiftdrop-local)
  --namespace <NAMESPACE>  Data namespace (default: swiftdrop-data)
  --backup-dir <DIR>       Destination directory root (default: ~/.swiftdrop/backups)
  --backup-id <ID>         Backup set identifier (default: auto-generated timestamp)
  --custodian <HANDLE>     Designated offline backup custodian (default: @Suthankan1)
  --fixture <FILE>         Simulate backup using test fixture
  --dry-run                Perform syntax and permission checks without dumping data
  -h, --help               Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    --namespace) DATA_NAMESPACE="$2"; shift 2 ;;
    --backup-dir) BACKUP_ROOT="$2"; shift 2 ;;
    --backup-id) BACKUP_ID="$2"; shift 2 ;;
    --custodian) CUSTODIAN="$2"; shift 2 ;;
    --fixture) FIXTURE_FILE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

DEST_DIR="${BACKUP_ROOT}/${BACKUP_ID}"

echo "================================================================================"
echo "          SWIFTDROP POSTGRESQL MULTI-SERVICE BACKUP                             "
echo "================================================================================"
echo "Backup ID:        ${BACKUP_ID}"
echo "Context:          ${CONTEXT_NAME}"
echo "Data Namespace:   ${DATA_NAMESPACE}"
echo "Destination:      ${DEST_DIR}"
echo "Custodian:        ${CUSTODIAN}"
echo "Retention:        ${RETENTION_DAYS} days"

if [[ "${DRY_RUN}" != "true" && -z "${FIXTURE_FILE}" ]]; then
  if [[ "${CONTEXT_NAME}" != "k3d-swiftdrop-local" ]]; then
    echo "FATAL: Backup tool strictly restricted to approved context 'k3d-swiftdrop-local'!" >&2
    exit 1
  fi
fi

mkdir -p "${DEST_DIR}"
chmod 700 "${DEST_DIR}"

CAPTURE_START="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

if [[ -n "${FIXTURE_FILE}" && -f "${FIXTURE_FILE}" ]]; then
  echo "--- RUNNING IN FIXTURE MODE (${FIXTURE_FILE}) ---"
  cp "${FIXTURE_FILE}" "${DEST_DIR}/backup-manifest.json"
  echo "✓ Fixture backup manifest staged at ${DEST_DIR}/backup-manifest.json"
  exit 0
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "✓ Dry-run completed. Backup directories and parameters validated."
  exit 0
fi

# All 5 logical databases across the 3 physical PostgreSQL pods
DATABASES=(
  "accounts-postgres-0:accounts_app:accounts_db"
  "business-postgres-0:pricing_app:pricing_db"
  "business-postgres-0:delivery_app:delivery_db"
  "business-postgres-0:communications_app:communications_db"
  "keycloak-postgres-0:keycloak:keycloak_db"
)

MANIFEST_DBS=()

for entry in "${DATABASES[@]}"; do
  IFS=':' read -r pod user db <<< "${entry}"
  RAW_FILE="${DEST_DIR}/${db}.dump"
  ENC_FILE="${DEST_DIR}/${db}.dump.enc"

  echo ""
  echo "Backing up logical database '${db}' from pod '${pod}'..."
  
  # Take custom-format archive dump
  kubectl --context "${CONTEXT_NAME}" exec -n "${DATA_NAMESPACE}" "${pod}" -- \
    pg_dump -U "${user}" -d "${db}" -F c > "${RAW_FILE}"

  if [ ! -s "${RAW_FILE}" ]; then
    echo "FATAL: Dump file for '${db}' is empty or failed!" >&2
    exit 1
  fi

  # Compute SHA-256 of raw dump
  RAW_SHA="$(shasum -a 256 "${RAW_FILE}" | awk '{print $1}')"
  FILE_SIZE="$(wc -c < "${RAW_FILE}" | tr -d ' ')"

  # Get latest Flyway schema version where applicable
  if [[ "${db}" != "keycloak_db" ]]; then
    SCHEMA_VER="$(kubectl --context "${CONTEXT_NAME}" exec -n "${DATA_NAMESPACE}" "${pod}" -- \
      psql -U "${user}" -d "${db}" -tAc "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;" 2>/dev/null || echo "v1")"
  else
    SCHEMA_VER="keycloak-26.0"
  fi

  # Encrypt dump with AES-256-CBC for offline custody
  openssl enc -aes-256-cbc -salt -pbkdf2 -iter 100000 \
    -in "${RAW_FILE}" \
    -out "${ENC_FILE}" \
    -k "${ENCRYPTION_PASSPHRASE}"
  
  ENC_SHA="$(shasum -a 256 "${ENC_FILE}" | awk '{print $1}')"

  # Secure permissions
  chmod 600 "${RAW_FILE}" "${ENC_FILE}"

  echo "  ✓ Dump complete: ${FILE_SIZE} bytes (SHA-256: ${RAW_SHA:0:16}...)"
  echo "  ✓ Encrypted archive generated (SHA-256: ${ENC_SHA:0:16}...)"
  echo "  ✓ Schema version recorded: ${SCHEMA_VER}"

  MANIFEST_DBS+=("${db}:${pod}:${SCHEMA_VER}:${FILE_SIZE}:${RAW_SHA}:${ENC_SHA}")
done

CAPTURE_END="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

# Generate structured backup manifest
python3 - << EOF
import json

manifest = {
    "backup_set_id": "${BACKUP_ID}",
    "source_environment": "k3d-swiftdrop-local",
    "source_namespace": "${DATA_NAMESPACE}",
    "capture_started_at": "${CAPTURE_START}",
    "capture_completed_at": "${CAPTURE_END}",
    "last_consistent_checkpoint_at": "${CAPTURE_START}",
    "custodian": "${CUSTODIAN}",
    "retention_days": int("${RETENTION_DAYS}"),
    "encryption": {
        "algorithm": "aes-256-cbc",
        "kdf": "pbkdf2",
        "iterations": 100000
    },
    "databases": []
}

raw_dbs = "${MANIFEST_DBS[*]}".split()
for item in raw_dbs:
    parts = item.split(":")
    if len(parts) == 6:
        manifest["databases"].append({
            "database_name": parts[0],
            "source_pod": parts[1],
            "schema_version": parts[2],
            "size_bytes": int(parts[3]),
            "raw_sha256": parts[4],
            "encrypted_sha256": parts[5]
        })

with open("${DEST_DIR}/backup-manifest.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)

print("✓ Structured backup manifest written to ${DEST_DIR}/backup-manifest.json")
EOF

# Create checksums.sha256
(cd "${DEST_DIR}" && shasum -a 256 *.dump *.dump.enc > checksums.sha256)
chmod 600 "${DEST_DIR}/checksums.sha256" "${DEST_DIR}/backup-manifest.json"

echo ""
echo "================================================================================"
echo "✓ ALL 5 POSTGRESQL DATABASES BACKED UP AND ENCRYPTED SUCCESSFULLY."
echo "Backup Manifest: ${DEST_DIR}/backup-manifest.json"
echo "Checksums File:  ${DEST_DIR}/checksums.sha256"
echo "================================================================================"
exit 0
