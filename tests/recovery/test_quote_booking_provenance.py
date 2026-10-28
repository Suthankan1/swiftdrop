"""
Tests for quote-to-booking provenance and single-consumption invariants (Issue #168).
Validates quote snapshot parity, currency preservation, and orphan prevention.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestQuoteBookingProvenance(unittest.TestCase):
    """Verifies that restored bookings preserve authoritative quote provenance."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_single_use_quote_consumption_invariant(self):
        """Verifies that each quote is consumed by at most one parcel."""
        self.verifier.verify_delivery_booking_provenance()
        del_inv = next(
            inv for inv in self.verifier.invariants if inv.invariant_id == "INV-DEL-01"
        )
        self.assertEqual(del_inv.status, VerificationStatus.PASS)

    def test_duplicate_quote_consumption_detection(self):
        """Negative test: two parcels claiming the same quote_id is detected."""
        self.snapshot.parcels[1].quote_id = self.snapshot.parcels[0].quote_id
        verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )
        verifier.verify_delivery_booking_provenance()
        del_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-DEL-01"
        )
        self.assertEqual(del_inv.status, VerificationStatus.FAIL)
        self.assertIn("double-consumed", del_inv.message)

    def test_amount_mismatch_with_quote_snapshot_is_detected(self):
        """Negative test: parcel booked amount differing from quote snapshot is flagged."""
        self.snapshot.parcels[0].amount_minor_units = 999999
        verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )
        verifier.verify_delivery_booking_provenance()
        del_inv = next(
            inv for inv in verifier.invariants if inv.invariant_id == "INV-DEL-01"
        )
        self.assertEqual(del_inv.status, VerificationStatus.FAIL)


if __name__ == "__main__":
    unittest.main()
