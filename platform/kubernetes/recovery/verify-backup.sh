#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Backup Integrity & Cryptographic Verification Tool
# Validates backup set manifests, recomputes SHA-256 checksums, verifies archive
# readability, and tests decryption before allowing any restore attempt.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

BACKUP_DIR=""
ENCRYPTION_PASSPHRASE="${SWIFTDROP_BACKUP_KEY:-swiftdrop-dev-recovery-custody-key}"

usage() {
  cat << EOF
Usage: $(basename "$0") --backup-dir <DIR> [OPTIONS]

Options:
  --backup-dir <DIR>    Path to backup set directory (REQUIRED)
  --key <PASSPHRASE>    Encryption key (optional, defaults to environment/dev key)
  -h, --help            Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --key) ENCRYPTION_PASSPHRASE="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [[ -z "${BACKUP_DIR}" || ! -d "${BACKUP_DIR}" ]]; then
  echo "FATAL: Backup directory missing or does not exist: '${BACKUP_DIR}'" >&2
  exit 1
fi

MANIFEST_FILE="${BACKUP_DIR}/backup-manifest.json"
CHECKSUM_FILE="${BACKUP_DIR}/checksums.sha256"

if [[ ! -f "${MANIFEST_FILE}" ]]; then
  echo "FATAL: Backup manifest 'backup-manifest.json' missing from '${BACKUP_DIR}'!" >&2
  exit 1
fi

echo "================================================================================"
echo "          SWIFTDROP BACKUP INTEGRITY & CHECKSUM VERIFICATION                    "
echo "================================================================================"
echo "Target Backup Directory: ${BACKUP_DIR}"

python3 - << EOF
import json, sys, os, hashlib, subprocess

manifest_file = "${MANIFEST_FILE}"
backup_dir = "${BACKUP_DIR}"
passphrase = "${ENCRYPTION_PASSPHRASE}"

with open(manifest_file, "r", encoding="utf-8") as f:
    manifest = json.load(f)

print(f"Backup Set ID:    {manifest.get('backup_set_id')}")
print(f"Custodian:        {manifest.get('custodian')}")
print(f"Captured:         {manifest.get('capture_started_at')} -> {manifest.get('capture_completed_at')}")
print("-" * 80)

failures = []
databases = manifest.get("databases", [])
if not databases:
    failures.append("No database archives listed in backup-manifest.json")

for db in databases:
    name = db.get("database_name")
    raw_file = os.path.join(backup_dir, f"{name}.dump")
    enc_file = os.path.join(backup_dir, f"{name}.dump.enc")
    expected_raw_sha = db.get("raw_sha256")
    expected_enc_sha = db.get("encrypted_sha256")
    expected_size = db.get("size_bytes", 0)

    # 1. Raw dump check
    if not os.path.isfile(raw_file):
        failures.append(f"Missing raw dump file: {raw_file}")
    else:
        with open(raw_file, "rb") as rf:
            raw_bytes = rf.read()
        actual_raw_sha = hashlib.sha256(raw_bytes).hexdigest()
        if actual_raw_sha != expected_raw_sha:
            failures.append(f"Checksum mismatch for {name}.dump: expected {expected_raw_sha}, got {actual_raw_sha}")
        else:
            print(f"  ✓ {name}.dump: SHA-256 match ({len(raw_bytes)} bytes)")

        # Verify pg_dump custom format header (first 5 bytes should be 'PGDMP')
        if len(raw_bytes) < 5 or raw_bytes[:5] != b"PGDMP":
            failures.append(f"{name}.dump is not a valid PostgreSQL custom-format archive (missing PGDMP magic header)")
        else:
            print(f"    ✓ PostgreSQL custom archive magic header verified.")

    # 2. Encrypted dump check
    if not os.path.isfile(enc_file):
        failures.append(f"Missing encrypted dump file: {enc_file}")
    else:
        with open(enc_file, "rb") as ef:
            enc_bytes = ef.read()
        actual_enc_sha = hashlib.sha256(enc_bytes).hexdigest()
        if actual_enc_sha != expected_enc_sha:
            failures.append(f"Checksum mismatch for {name}.dump.enc: expected {expected_enc_sha}, got {actual_enc_sha}")
        else:
            print(f"  ✓ {name}.dump.enc: SHA-256 match")

        # Test decryption
        test_dec_file = os.path.join(backup_dir, f".test_{name}.dump")
        try:
            cmd = [
                "openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "100000",
                "-in", enc_file, "-out", test_dec_file, "-k", passphrase
            ]
            subprocess.run(cmd, capture_output=True, check=True)
            with open(test_dec_file, "rb") as tdf:
                dec_bytes = tdf.read()
            dec_sha = hashlib.sha256(dec_bytes).hexdigest()
            if dec_sha != expected_raw_sha:
                failures.append(f"Decrypted output checksum mismatch for {name}!")
            else:
                print(f"    ✓ Decryption verified successfully against raw archive.")
        except Exception as e:
            failures.append(f"Failed decrypting {name}.dump.enc: {e}")
        finally:
            if os.path.isfile(test_dec_file):
                os.remove(test_dec_file)

if failures:
    print("=" * 80, file=sys.stderr)
    print("FATAL: Backup verification failed with errors:", file=sys.stderr)
    for err in failures:
        print(f"  ✗ {err}", file=sys.stderr)
    sys.exit(1)

print("-" * 80)
print("✓ ALL BACKUP ARCHIVES, CHECKSUMS, AND DECRYPTION KEYS VERIFIED VALID.")
EOF

echo "================================================================================"
echo "✓ BACKUP INTEGRITY VERIFICATION PASSED CLEANLY."
echo "================================================================================"
exit 0

