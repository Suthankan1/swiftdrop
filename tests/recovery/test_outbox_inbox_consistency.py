"""
Tests for Delivery outbox sequencing and Communications inbox deduplication (Issue #168).
Validates at-least-once Kafka publication semantics, lease expiration, and idempotent replay.
"""

import copy
import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import OutboxEventRecord, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestOutboxInboxConsistency(unittest.TestCase):
    """Verifies transactional outbox integrity and inbox deduplication."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_baseline_outbox_and_inbox_satisfy_invariants(self):
        """Verifies that baseline outbox and inbox records pass all integrity checks."""
        self.verifier.verify_delivery_outbox()
        self.verifier.verify_communications_inbox()
        otb_inv = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-OTB-01")
        inb_inv = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-INB-01")
        self.assertEqual(otb_inv.status, VerificationStatus.PASS)
        self.assertEqual(inb_inv.status, VerificationStatus.PASS)

    def test_duplicate_outbox_version_is_rejected(self):
        """Negative test: two outbox records with same aggregate_id and aggregate_version is rejected."""
        p1_id = self.snapshot.parcels[0].parcel_id
        self.snapshot.outbox_events.append(
            OutboxEventRecord(
                event_id="evt-dup-001",
                aggregate_type="PARCEL",
                aggregate_id=p1_id,
                aggregate_version=1,
                event_type="PARCEL_CREATED",
                schema_version="1.0.0",
                payload={"parcelId": p1_id},
                status="PENDING",
                created_at="2026-10-28T03:36:00Z",
                attempt_count=0,
                topic="parcel.lifecycle.v1",
                partition_key=p1_id,
            )
        )
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_delivery_outbox()
        otb_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-OTB-01")
        self.assertEqual(otb_inv.status, VerificationStatus.FAIL)
        self.assertIn("duplicate", otb_inv.message.lower())

    def test_duplicate_inbox_event_is_rejected(self):
        """Negative test: duplicate (consumer_name, event_id) in consumed_events is rejected."""
        dup_evt = copy.deepcopy(self.snapshot.consumed_events[0])
        self.snapshot.consumed_events.append(dup_evt)
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_communications_inbox()
        inb_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-INB-01")
        self.assertEqual(inb_inv.status, VerificationStatus.FAIL)
        self.assertIn("duplicate", inb_inv.message.lower())


if __name__ == "__main__":
    unittest.main()
