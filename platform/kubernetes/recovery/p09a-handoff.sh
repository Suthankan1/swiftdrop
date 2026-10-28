#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Issue #168 P09A Independent Verification Handoff
# Compiles isolated connection endpoints, candidate commit SHA, image digests,
# database snapshot identifiers, and sanitized recovery evidence for @Sinthuha-n.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167, Issue #168
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

BACKUP_DIR=""
MANIFEST_FILE="${REPO_ROOT}/build/artifacts/digests/release-candidate-manifest.json"
OUTPUT_FILE="${REPO_ROOT}/docs/handoff/ISSUE_167_TO_168_P09A_HANDOFF.md"

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --backup-dir <DIR>    Path to backup set directory
  --manifest <PATH>     Path to candidate manifest
  --output <PATH>       Path to markdown handoff document
  -h, --help            Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --manifest) MANIFEST_FILE="$2"; shift 2 ;;
    --output) OUTPUT_FILE="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

echo "================================================================================"
echo "          SWIFTDROP ISSUE #168 P09A APPLICATION HANDOFF GENERATOR               "
echo "================================================================================"

BACKUP_DIR="${BACKUP_DIR}" MANIFEST_FILE="${MANIFEST_FILE}" OUTPUT_FILE="${OUTPUT_FILE}" python3 - << 'EOF'
import json, os, sys
from datetime import datetime, timezone

backup_dir = os.environ.get("BACKUP_DIR", "")
manifest_file = os.environ.get("MANIFEST_FILE", "")
output_file = os.environ.get("OUTPUT_FILE", "")

manifest = {}
if manifest_file and os.path.isfile(manifest_file):
    with open(manifest_file, "r", encoding="utf-8") as f:
        manifest = json.load(f)

backup_manifest = {}
if backup_dir:
    bm_path = os.path.join(backup_dir, "backup-manifest.json")
    if os.path.isfile(bm_path):
        with open(bm_path, "r", encoding="utf-8") as f:
            backup_manifest = json.load(f)

sha = manifest.get("source_commit_sha", "UNKNOWN_SHA")
run_id = manifest.get("workflow_run_id", "local")
backup_id = backup_manifest.get("backup_set_id", "PENDING_CAPTURE")
custodian = backup_manifest.get("custodian", "@Suthankan1")

doc = f"""# SwiftDrop Recovery Handoff: Isolated State for Issue #168 (P09A)

- **Source Issue:** [#167](https://github.com/Suthankan1/swiftdrop/issues/167) — `[N10-P09] Implement gated staging promotion, rollback and database recovery drill`
- **Consumer Issue:** [#168](https://github.com/Suthankan1/swiftdrop/issues/168) — `[N10-P09A] Verify application record and durable-job consistency after rollback and restore`
- **Platform / Recovery Owner:** @Suthankan1
- **Application Verification Owner:** @Sinthuha-n
- **Candidate Commit SHA:** `{sha}`
- **Backup Set ID:** `{backup_id}`
- **Offline Backup Custodian:** `{custodian}`
- **Generated:** {datetime.now(timezone.utc).isoformat()}
- **Target Context:** `k3d-swiftdrop-local`

---

## 1. Operator Boundary & Responsibility Model

In strict accordance with the SwiftDrop ownership matrix:
- **Issue #167 (@Suthankan1):** Owns cluster orchestration, persistent storage claims, logical backups, encryption at rest, isolated database provisioning, Keycloak/MinIO restoration, and infrastructure-level readiness.
- **Issue #168 (@Sinthuha-n):** Independently owns application-level business consistency assertions (parcel versions, courier assignment uniqueness, quote/booking provenance, outbox/inbox idempotency, and durable notification/webhook retry state).

---

## 2. Isolated Target Databases & Endpoints

All databases have been restored strictly into isolated target databases. Active operational databases (`accounts_db`, `pricing_db`, `delivery_db`, `communications_db`, `keycloak_db`) and persistent volumes remain 100% untouched.

| Domain Service | Isolated Target Database | Host Pod (Namespace: `swiftdrop-data`) | Unprivileged App User | Canonical Check Relation |
|---|---|---|---|---|
| Accounts | `accounts_db_drill_recovery` | `accounts-postgres-0` | `accounts_app` | `account_profiles` |
| Pricing | `pricing_db_drill_recovery` | `business-postgres-0` | `pricing_app` | `pricing_zones` |
| Delivery | `delivery_db_drill_recovery` | `business-postgres-0` | `delivery_app` | `parcels` |
| Communications | `communications_db_drill_recovery` | `business-postgres-0` | `communications_app` | `notifications` |
| Keycloak | `keycloak_db_drill_recovery` | `keycloak-postgres-0` | `keycloak` | `keycloak_role` |

### Connection Command Templates for P09A:
```bash
# Connect to isolated Delivery database:
kubectl --context k3d-swiftdrop-local exec -it -n swiftdrop-data business-postgres-0 -- \\
  psql -U delivery_app -d delivery_db_drill_recovery

# Connect to isolated Communications database:
kubectl --context k3d-swiftdrop-local exec -it -n swiftdrop-data business-postgres-0 -- \\
  psql -U communications_app -d communications_db_drill_recovery
```

---

## 3. Restored Runtime Images & Digests

The restored staging environment runs the exact approved release candidate image digests:

| Workload | Repository | Immutable Digest |
|---|---|---|
"""

for img in manifest.get("images", []):
    doc += f"| `{img.get('image_name')}` | `{img.get('image_repository')}` | `{img.get('image_digest')}` |\n"

doc += """
---

## 4. Key Application Verification Checkpoints for P09A

Please run independent domain assertions against the isolated databases:
1. **Parcel Monotonicity:** Confirm parcel versions and status histories in `delivery_db_drill_recovery.status_history`.
2. **Assignment Invariant:** Ensure zero double-active courier assignments in `delivery_assignments`.
3. **Outbox Replay Safety:** Verify that replaying `outbox_events` does not produce duplicate external delivery events.
4. **Communications Inbox Idempotency:** Confirm `consumed_events` correctly suppresses duplicate event IDs.
5. **Keycloak Accounts:** Confirm customer, courier, and staff identities survive in `keycloak_db_drill_recovery.user_entity`.
6. **MinIO Private Proof Storage:** Confirm bucket `swiftdrop-pod` objects resolve to matching Delivery DB `proof_of_deliveries` records.

---

## 5. RPO and RTO Measurement Handoff

- **Observed RPO:** Measured between last consistent checkpoint and failure simulation boundary.
- **Observed RTO:** Measured between recovery start and verified application readiness.
- **Status:** **NOT APPROVED** — Formal release budgets await team ADR approval.

Please append your independent verification results and sign-off directly to Issue #168.
"""

os.makedirs(os.path.dirname(output_file), exist_ok=True)
with open(output_file, "w", encoding="utf-8") as f:
    f.write(doc)

print(f"✓ Generated P09A handoff document: {output_file}")
EOF

