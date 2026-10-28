"""
Tests for courier assignment invariants and single active assignment per parcel (Issue #168).
Validates uq_delivery_assignments_active_parcel and historical assignment decoupling.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import DeliveryAssignmentRecord, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestCourierAssignmentInvariants(unittest.TestCase):
    """Verifies that courier assignments strictly enforce single-active constraints."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_single_active_assignment_in_baseline(self):
        """Verifies that baseline snapshot satisfies at most one active courier assignment per parcel."""
        self.verifier.verify_courier_assignments()
        asg_inv = next(
            inv for inv in self.verifier.invariants if inv.invariant_id == "INV-ASG-01"
        )
        self.assertEqual(asg_inv.status, VerificationStatus.PASS)

    def test_conflicting_double_active_assignment_is_detected(self):
        """Negative test: two active assignments for the same parcel violates invariant."""
        p2_id = self.snapshot.parcels[1].parcel_id
        self.snapshot.delivery_assignments.append(
            DeliveryAssignmentRecord(
                assignment_id="asgn-conflict-001",
                parcel_id=p2_id,
                courier_sub="sub-cour-kamal-004",
                assigned_by="sub-disp-sunil-006",
                assigned_at="2026-10-28T04:40:00Z",
                ended_at=None,
                active=True,
            )
        )
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_courier_assignments()
        asg_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-ASG-01"
        )
        self.assertEqual(asg_inv.status, VerificationStatus.FAIL)
        self.assertIn("multiple active", asg_inv.message.lower())

    def test_active_assignment_on_terminal_parcel_is_rejected(self):
        """Negative test: parcel 1 is DELIVERED, having an active assignment on it is rejected."""
        self.snapshot.delivery_assignments[0].active = True
        self.snapshot.delivery_assignments[0].ended_at = None
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_courier_assignments()
        asg_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-ASG-01"
        )
        self.assertEqual(asg_inv.status, VerificationStatus.FAIL)


if __name__ == "__main__":
    unittest.main()
