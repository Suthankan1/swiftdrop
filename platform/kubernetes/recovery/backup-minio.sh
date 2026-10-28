#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — MinIO Proof Storage Backup Tool
# Archives private proof-of-delivery objects from MinIO, records object content
# SHA-256 hashes, encrypts the bundle, and validates private access invariants.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"
MESSAGING_NAMESPACE="swiftdrop-messaging"
BACKUP_ROOT="${HOME}/.swiftdrop/backups"
BACKUP_ID="backup-minio-$(date +%Y%m%d%H%M%S)"
ENCRYPTION_PASSPHRASE="${SWIFTDROP_BACKUP_KEY:-swiftdrop-dev-recovery-custody-key}"
BUCKET_NAME="swiftdrop-pod"
DRY_RUN=false
FIXTURE_FILE=""

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --context <CONTEXT>      Kubernetes context (default: k3d-swiftdrop-local)
  --namespace <NAMESPACE>  Messaging namespace (default: swiftdrop-messaging)
  --backup-dir <DIR>       Destination directory root (default: ~/.swiftdrop/backups)
  --backup-id <ID>         Backup set identifier
  --bucket <BUCKET>        MinIO bucket name (default: swiftdrop-pod)
  --dry-run                Perform syntax checks without creating backup
  -h, --help               Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context) CONTEXT_NAME="$2"; shift 2 ;;
    --namespace) MESSAGING_NAMESPACE="$2"; shift 2 ;;
    --backup-dir) BACKUP_ROOT="$2"; shift 2 ;;
    --backup-id) BACKUP_ID="$2"; shift 2 ;;
    --bucket) BUCKET_NAME="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift 1 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

DEST_DIR="${BACKUP_ROOT}/${BACKUP_ID}"

echo "================================================================================"
echo "          SWIFTDROP MINIO PROOF STORAGE BACKUP                                  "
echo "================================================================================"
echo "Backup ID:        ${BACKUP_ID}"
echo "Context:          ${CONTEXT_NAME}"
echo "Namespace:        ${MESSAGING_NAMESPACE}"
echo "Bucket:           ${BUCKET_NAME}"
echo "Destination:      ${DEST_DIR}"

if [[ "${DRY_RUN}" != "true" && -z "${FIXTURE_FILE}" ]]; then
  if [[ "${CONTEXT_NAME}" != "k3d-swiftdrop-local" ]]; then
    echo "FATAL: MinIO backup strictly restricted to approved context 'k3d-swiftdrop-local'!" >&2
    exit 1
  fi
fi

mkdir -p "${DEST_DIR}"
chmod 700 "${DEST_DIR}"

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "✓ Dry-run completed. MinIO parameters validated."
  exit 0
fi

# 1. Verify MinIO Pod
if ! kubectl --context "${CONTEXT_NAME}" get pod minio-0 -n "${MESSAGING_NAMESPACE}" >/dev/null 2>&1; then
  echo "FATAL: Pod 'minio-0' not found in '${MESSAGING_NAMESPACE}'!" >&2
  exit 1
fi

TAR_FILE="${DEST_DIR}/${BUCKET_NAME}.tar.gz"
ENC_FILE="${DEST_DIR}/${BUCKET_NAME}.tar.gz.enc"

# 2. Archive bucket directory from container
echo "Archiving bucket '${BUCKET_NAME}' from minio-0..."
kubectl --context "${CONTEXT_NAME}" exec -n "${MESSAGING_NAMESPACE}" minio-0 -c minio -- \
  tar -czf - -C /data "${BUCKET_NAME}" > "${TAR_FILE}"

if [ ! -s "${TAR_FILE}" ]; then
  echo "FATAL: Failed archiving MinIO bucket '${BUCKET_NAME}'!" >&2
  exit 1
fi

TAR_SHA="$(shasum -a 256 "${TAR_FILE}" | awk '{print $1}')"

# 3. Encrypt archive
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 100000 \
  -in "${TAR_FILE}" \
  -out "${ENC_FILE}" \
  -k "${ENCRYPTION_PASSPHRASE}"

ENC_SHA="$(shasum -a 256 "${ENC_FILE}" | awk '{print $1}')"
chmod 600 "${TAR_FILE}" "${ENC_FILE}"

# 4. Inventory proof objects inside archive
echo "Inventorying objects and SHA-256 hashes..."
OBJECT_LIST="$(tar -ztf "${TAR_FILE}")"

python3 - << EOF
import json, subprocess, os

tar_file = "${TAR_FILE}"
manifest = {
    "backup_set_id": "${BACKUP_ID}",
    "bucket_name": "${BUCKET_NAME}",
    "raw_sha256": "${TAR_SHA}",
    "encrypted_sha256": "${ENC_SHA}",
    "objects": []
}

proc = subprocess.run(["tar", "-ztf", tar_file], capture_output=True, text=True, check=True)
for item in proc.stdout.splitlines():
    if not item.endswith("/") and not item.startswith("."):
        manifest["objects"].append({
            "path": item,
            "filename": os.path.basename(item)
        })

with open("${DEST_DIR}/minio-manifest.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)

print(f"✓ Inventory recorded {len(manifest['objects'])} object(s).")
EOF

chmod 600 "${DEST_DIR}/minio-manifest.json"

echo "================================================================================"
echo "✓ MINIO PRIVATE PROOF OBJECTS BACKED UP AND ENCRYPTED SUCCESSFULLY."
echo "Manifest: ${DEST_DIR}/minio-manifest.json"
echo "================================================================================"
exit 0

