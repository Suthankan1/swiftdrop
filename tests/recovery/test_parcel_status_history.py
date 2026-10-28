"""
Tests for parcel version monotonicity, status history sequences, and terminal finality (Issue #168).
Validates optimistic concurrency invariants, chronological ordering, and state machine transitions.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import StatusHistoryRecord, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestParcelStatusHistory(unittest.TestCase):
    """Verifies that parcel versioning and status history preserve lifecycle invariants."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_baseline_status_history_is_monotonic(self):
        """Verifies that all parcel status histories are strictly monotonic (1..N)."""
        self.verifier.verify_parcel_status_history()
        ver_inv = next(
            inv for inv in self.verifier.invariants if inv.invariant_id == "INV-HST-02"
        )
        self.assertEqual(ver_inv.status, VerificationStatus.PASS)

    def test_version_gap_in_history_is_rejected(self):
        """Negative test: a jump in version sequence (e.g. 1 -> 4) is detected."""
        for h in self.snapshot.status_histories:
            if h.parcel_id == self.snapshot.parcels[1].parcel_id and h.resulting_version == 2:
                h.resulting_version = 4
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_parcel_status_history()
        ver_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-HST-02"
        )
        self.assertEqual(ver_inv.status, VerificationStatus.FAIL)

    def test_illegal_mutation_after_terminal_state_is_rejected(self):
        """Negative test: parcel transitioning out of terminal DELIVERED is rejected."""
        p1 = self.snapshot.parcels[0]
        p1.version = 6
        p1.status = "IN_TRANSIT"
        self.snapshot.status_histories.append(
            StatusHistoryRecord(
                history_id="hist-illegal-001",
                parcel_id=p1.parcel_id,
                previous_status="DELIVERED",
                new_status="IN_TRANSIT",
                reason="ILLEGAL_REOPEN",
                note="Illegal state transition",
                proof_id=None,
                actor_sub="sub-disp-sunil-006",
                resulting_version=6,
                occurred_at="2026-10-28T04:50:00Z",
            )
        )
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_parcel_status_history()
        tr_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-HST-03"
        )
        self.assertEqual(tr_inv.status, VerificationStatus.FAIL)


if __name__ == "__main__":
    unittest.main()
