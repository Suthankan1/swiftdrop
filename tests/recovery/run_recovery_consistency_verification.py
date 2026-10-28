#!/usr/bin/env python3
"""
SwiftDrop Canonical Recovery Consistency Verification Runner (Issue #168 / N10-P09A)
======================================================================================
Canonical test entry point executing post-rollback and restore application assertions:
- Mode A (--mode offline): Deterministic synthetic validation, RPO boundary tests, negative corruption detection
- Mode B (--mode operator-checkpoint): Evaluates live restored database targets (*_drill_recovery)
Fails closed with nonzero exit code on assertion failure or missing prerequisites.
"""

import argparse
import sys
from pathlib import Path

# Ensure repo root is on sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tests.recovery.models import RecoveryMetadata
from tests.recovery.runner import RecoveryVerificationRunner


def main():
    parser = argparse.ArgumentParser(
        description="SwiftDrop Application Recovery Consistency Verification Runner (Issue #168 / N10-P09A)"
    )
    parser.add_argument(
        "--mode",
        choices=["offline", "operator-checkpoint"],
        default="offline",
        help="Verification execution mode: 'offline' (deterministic self-test) or 'operator-checkpoint' (live targets)",
    )
    parser.add_argument(
        "--output-json",
        default="build/reports/recovery/recovery-consistency-report.json",
        help="Path to write structured JSON report",
    )
    parser.add_argument(
        "--output-md",
        default="build/reports/recovery/recovery-consistency-report.md",
        help="Path to write human-readable markdown report",
    )
    parser.add_argument(
        "--candidate-sha",
        default="7c0cc6275044d708422c1b1f9e49d577d95a4c88",
        help="Release candidate Git commit SHA under evaluation",
    )
    parser.add_argument(
        "--backup-set-id",
        default="backup-drill-20261028-1145",
        help="Backup set identifier from operator recovery drill",
    )
    parser.add_argument(
        "--snapshot-cutoff",
        default="2026-10-28T05:00:00Z",
        help="Point-in-time snapshot timestamp boundary",
    )
    parser.add_argument(
        "--accounts-target",
        help="Connection string or pod identifier for accounts_db_drill_recovery",
    )
    parser.add_argument(
        "--pricing-target",
        help="Connection string or pod identifier for pricing_db_drill_recovery",
    )
    parser.add_argument(
        "--delivery-target",
        help="Connection string or pod identifier for delivery_db_drill_recovery",
    )
    parser.add_argument(
        "--comms-target",
        help="Connection string or pod identifier for communications_db_drill_recovery",
    )
    parser.add_argument(
        "--keycloak-target",
        help="Connection string or pod identifier for keycloak_db_drill_recovery",
    )

    args = parser.parse_args()

    metadata = RecoveryMetadata(
        candidate_sha=args.candidate_sha,
        backup_set_id=args.backup_set_id,
        snapshot_cutoff=args.snapshot_cutoff,
    )

    runner = RecoveryVerificationRunner(
        mode=args.mode,
        metadata=metadata,
        output_json=args.output_json,
        output_md=args.output_md,
    )

    if args.mode == "offline":
        exit_code, _ = runner.run_mode_a_offline()
    else:
        exit_code, _ = runner.run_mode_b_operator_checkpoint(
            accounts_target=args.accounts_target,
            pricing_target=args.pricing_target,
            delivery_target=args.delivery_target,
            comms_target=args.comms_target,
            keycloak_target=args.keycloak_target,
        )

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
