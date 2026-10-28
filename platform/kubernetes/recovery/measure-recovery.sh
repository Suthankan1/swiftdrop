#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Recovery Point & Time Objective (RPO / RTO) Measurement
# Calculates real, observed RPO and RTO from cryptographic timestamps, distinguishing
# observed measurements from unapproved or proposed release budgets.
# Canonical Reference: docs/architecture/BASELINE.md, Issue #167
# ==============================================================================
set -euo pipefail

BACKUP_MANIFEST=""
TIMESTAMPS_FILE=""
OUTPUT_REPORT=""

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --manifest <PATH>        Path to backup-manifest.json
  --timestamps <PATH>      Path to JSON file containing recovery execution timestamps
  --output <PATH>          Path to save formatted RPO/RTO report (optional)
  -h, --help               Show this help message
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) BACKUP_MANIFEST="$2"; shift 2 ;;
    --timestamps) TIMESTAMPS_FILE="$2"; shift 2 ;;
    --output) OUTPUT_REPORT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

python3 - << EOF
import json, sys, os
from datetime import datetime, timezone

manifest_path = "${BACKUP_MANIFEST}"
timestamps_path = "${TIMESTAMPS_FILE}"
output_path = "${OUTPUT_REPORT}"

# Load manifest if provided
last_checkpoint = None
if manifest_path and os.path.isfile(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as f:
        m = json.load(f)
        last_checkpoint = m.get("last_consistent_checkpoint_at") or m.get("capture_started_at")

# Load timestamps file
ts_data = {}
if timestamps_path and os.path.isfile(timestamps_path):
    with open(timestamps_path, "r", encoding="utf-8") as f:
        ts_data = json.load(f)

# Required timestamp boundaries
t_checkpoint_str = ts_data.get("last_consistent_checkpoint_at") or last_checkpoint
t_failure_str = ts_data.get("failure_detected_at")
t_recovery_start_str = ts_data.get("recovery_started_at")
t_restore_complete_str = ts_data.get("restore_completed_at")
t_app_ready_str = ts_data.get("application_readiness_at")
t_biz_verified_str = ts_data.get("business_verification_completed_at")

def parse_iso(ts):
    if not ts:
        return None
    # Parse ISO 8601 with Z or offset
    ts = ts.replace("Z", "+00:00")
    return datetime.fromisoformat(ts)

t_checkpoint = parse_iso(t_checkpoint_str)
t_failure = parse_iso(t_failure_str)
t_recovery_start = parse_iso(t_recovery_start_str)
t_restore_complete = parse_iso(t_restore_complete_str)
t_app_ready = parse_iso(t_app_ready_str)
t_biz_verified = parse_iso(t_biz_verified_str)

print("=" * 80)
print("             SWIFTDROP RPO & RTO RECOVERY MEASUREMENT EVIDENCE                  ")
print("=" * 80)

# Calculate Observed RPO
observed_rpo_sec = None
if t_checkpoint and t_failure:
    observed_rpo_sec = max(0, int((t_failure - t_checkpoint).total_seconds()))

# Calculate Observed RTO
observed_rto_sec = None
# RTO measures from agreed failure/recovery boundary to verified application recovery
start_boundary = t_recovery_start or t_failure
end_boundary = t_biz_verified or t_app_ready or t_restore_complete
if start_boundary and end_boundary:
    observed_rto_sec = max(0, int((end_boundary - start_boundary).total_seconds()))

print(f"Timestamp Log:")
print(f"  Last Consistent Checkpoint:        {t_checkpoint_str or 'MISSING'}")
print(f"  Failure Detected:                  {t_failure_str or 'MISSING'}")
print(f"  Recovery Execution Started:        {t_recovery_start_str or 'MISSING'}")
print(f"  Database Restore Completed:        {t_restore_complete_str or 'MISSING'}")
print(f"  Application Readiness Achieved:    {t_app_ready_str or 'MISSING'}")
print(f"  Business Verification Completed:   {t_biz_verified_str or 'MISSING'}")
print("-" * 80)

print(f"OBSERVED RPO (Recovery Point Objective):")
if observed_rpo_sec is not None:
    print(f"  Measured: {observed_rpo_sec} seconds ({observed_rpo_sec // 60}m {observed_rpo_sec % 60}s)")
else:
    print(f"  Measured: UNMEASURED (Missing checkpoint or failure timestamp)")
print(f"  Budget Status: NOT APPROVED (Pending formal team decision in ADR)")

print("")
print(f"OBSERVED RTO (Recovery Time Objective):")
if observed_rto_sec is not None:
    print(f"  Measured: {observed_rto_sec} seconds ({observed_rto_sec // 60}m {observed_rto_sec % 60}s)")
else:
    print(f"  Measured: UNMEASURED (Missing recovery start or verified completion timestamp)")
print(f"  Budget Status: NOT APPROVED (Pending formal team decision in ADR)")

print("-" * 80)
print("NOTE: A successful restore drill with an UNAPPROVED budget does not automatically")
print("satisfy full production release acceptance. Team budget sign-off is required.")
print("=" * 80)

if output_path:
    report = {
        "calculated_at": datetime.now(timezone.utc).isoformat(),
        "timestamps": {
            "last_consistent_checkpoint_at": t_checkpoint_str,
            "failure_detected_at": t_failure_str,
            "recovery_started_at": t_recovery_start_str,
            "restore_completed_at": t_restore_complete_str,
            "application_readiness_at": t_app_ready_str,
            "business_verification_completed_at": t_biz_verified_str
        },
        "rpo": {
            "observed_seconds": observed_rpo_sec,
            "approved_budget_seconds": None,
            "status": "NOT APPROVED (pending team budget decision)"
        },
        "rto": {
            "observed_seconds": observed_rto_sec,
            "approved_budget_seconds": None,
            "status": "NOT APPROVED (pending team budget decision)"
        }
    }
    with open(output_path, "w", encoding="utf-8") as out:
        json.dump(report, out, indent=2)
    print(f"✓ Formatted measurement report saved to {output_path}")
EOF

