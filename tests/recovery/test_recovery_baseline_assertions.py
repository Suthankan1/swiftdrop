"""
Tests for pre/post baseline recovery assertions and RPO boundary auditing (Issue #168).
Validates point-in-time snapshot comparison and differentiation of RPO exclusion vs corruption.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import RecoveryMetadata, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestRecoveryBaselineAssertions(unittest.TestCase):
    """Verifies baseline assertions and point-in-time recovery boundaries."""

    def setUp(self):
        self.metadata = RecoveryMetadata(
            snapshot_cutoff="2026-10-28T05:00:00Z",
            observed_rpo_seconds=300,
            observed_rto_seconds=240,
        )
        self.snapshot = build_deterministic_baseline_snapshot(
            captured_at="2026-10-28T05:00:00Z"
        )
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            pre_recovery_baseline=self.snapshot,
            metadata=self.metadata,
            mode="offline",
        )

    def test_baseline_snapshot_passes_all_domain_invariants(self):
        """Positive control: the baseline deterministic snapshot passes 100% of invariants."""
        report = self.verifier.run_all()
        self.assertEqual(report.overall_status, VerificationStatus.PASS)
        for inv in report.invariants:
            self.assertEqual(
                inv.status,
                VerificationStatus.PASS,
                f"Invariant {inv.invariant_id} failed: {inv.message}",
            )

    def test_rpo_point_in_time_boundary_check(self):
        """Verifies that all baseline records fall on or before the snapshot cutoff."""
        self.verifier.verify_point_in_time_boundary()
        rpo_inv = next(
            inv for inv in self.verifier.invariants if inv.invariant_id == "INV-RPO-01"
        )
        self.assertEqual(rpo_inv.status, VerificationStatus.PASS)

    def test_missing_pre_checkpoint_record_is_flagged(self):
        """Negative test: a pre-checkpoint record missing from restored state is flagged."""
        # Create a post snapshot where parcel 1 is deleted
        post_snap = build_deterministic_baseline_snapshot()
        post_snap.parcels = [post_snap.parcels[1]]  # remove parcel 1

        verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=post_snap,
            pre_recovery_baseline=self.snapshot,
            metadata=self.metadata,
            mode="offline",
        )
        verifier.verify_point_in_time_boundary()
        rpo_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-RPO-01"
        )
        self.assertEqual(rpo_inv.status, VerificationStatus.FAIL)
        self.assertIn("missing", rpo_inv.message.lower())


if __name__ == "__main__":
    unittest.main()
