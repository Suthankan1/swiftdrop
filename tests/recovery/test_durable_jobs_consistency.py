"""
Tests for durable notification and webhook job consistency (Issue #168 / N10-P09A).
Validates bounded retries, attempt history parity, terminal states, and lease recovery.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import NotificationJobRecord, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestDurableJobsConsistency(unittest.TestCase):
    """Verifies durable notification and webhook job state machines."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_baseline_jobs_satisfy_all_invariants(self):
        """Verifies that baseline notification and webhook jobs satisfy all constraints."""
        self.verifier.verify_durable_jobs()
        notif_inv = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-JOB-01")
        wh_inv = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-JOB-02")
        self.assertEqual(notif_inv.status, VerificationStatus.PASS)
        self.assertEqual(wh_inv.status, VerificationStatus.PASS)

    def test_negative_attempt_count_is_rejected(self):
        """Negative test: attempts < 0 is rejected."""
        self.snapshot.notifications[0].attempts = -1
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_durable_jobs()
        notif_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-JOB-01")
        self.assertEqual(notif_inv.status, VerificationStatus.FAIL)

    def test_unbounded_retries_without_exhaustion_is_rejected(self):
        """Negative test: notification with attempts >= 5 must have status EXHAUSTED."""
        self.snapshot.notifications[1].attempts = 6
        self.snapshot.notifications[1].status = "FAILED"
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_durable_jobs()
        notif_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-JOB-01")
        self.assertEqual(notif_inv.status, VerificationStatus.FAIL)

    def test_completed_job_with_active_next_attempt_is_rejected(self):
        """Negative test: completed (SENT) job must not have a future next_attempt_at."""
        self.snapshot.notifications[0].next_attempt_at = "2026-10-28T06:00:00Z"
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_durable_jobs()
        notif_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-JOB-01")
        self.assertEqual(notif_inv.status, VerificationStatus.FAIL)

    def test_duplicate_notification_intent_is_rejected(self):
        """Negative test: duplicate (event_id, recipient_sub, channel) is rejected."""
        n0 = self.snapshot.notifications[0]
        self.snapshot.notifications.append(
            NotificationJobRecord(
                notification_id="notif-dup-999",
                event_id=n0.event_id,
                recipient_sub=n0.recipient_sub,
                parcel_id=n0.parcel_id,
                channel=n0.channel,
                destination_ref=n0.destination_ref,
                template=n0.template,
                status="PENDING",
                attempts=0,
                next_attempt_at="2026-10-28T05:00:00Z",
                created_at="2026-10-28T04:46:00Z",
                updated_at="2026-10-28T04:46:00Z",
            )
        )
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_durable_jobs()
        notif_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-JOB-01")
        self.assertEqual(notif_inv.status, VerificationStatus.FAIL)


if __name__ == "__main__":
    unittest.main()
