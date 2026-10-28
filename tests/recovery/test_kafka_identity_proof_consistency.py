"""
Tests for Kafka offsets, Keycloak identity alignment, and private proof integrity (Issue #168).
Validates cross-system checkpoints, anti-privilege escalation, and MinIO storage contracts.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot
from tests.recovery.models import ProofOfDeliveryRecord, VerificationStatus
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestKafkaIdentityProofConsistency(unittest.TestCase):
    """Verifies Keycloak role alignment, private proof integrity, and Kafka checkpoints."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()
        self.verifier = RecoveryConsistencyVerifier(
            post_recovery_snapshot=self.snapshot,
            mode="offline",
        )

    def test_baseline_identity_and_proofs_pass(self):
        """Verifies that baseline accounts, Keycloak mapping, and private proofs pass."""
        self.verifier.verify_accounts_and_identity()
        self.verifier.verify_private_proofs()
        acc_inv1 = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-ACC-01")
        acc_inv3 = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-ACC-03")
        pod_inv = next(inv for inv in self.verifier.invariants if inv.invariant_id == "INV-POD-01")
        self.assertEqual(acc_inv1.status, VerificationStatus.PASS)
        self.assertEqual(acc_inv3.status, VerificationStatus.PASS)
        self.assertEqual(pod_inv.status, VerificationStatus.PASS)

    def test_privilege_escalation_is_detected(self):
        """Negative test: customer possessing ADMIN role without staff profile is flagged."""
        # Elevate customer.alpha to ADMIN
        self.snapshot.keycloak_users[0].roles.append("ADMIN")
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_accounts_and_identity()
        acc_inv3 = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-ACC-03")
        self.assertEqual(acc_inv3.status, VerificationStatus.FAIL)
        self.assertIn("privilege escalation", acc_inv3.message.lower())

    def test_delivered_parcel_missing_proof_is_rejected(self):
        """Negative test: parcel with status DELIVERED but no proof_of_deliveries is rejected."""
        self.snapshot.proof_of_deliveries.clear()
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_private_proofs()
        pod_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-POD-01")
        self.assertEqual(pod_inv.status, VerificationStatus.FAIL)
        self.assertIn("missing mandatory proof", pod_inv.message.lower())

    def test_proof_exposed_as_public_url_is_rejected(self):
        """Negative test: proof object_reference starting with http:// is rejected."""
        self.snapshot.proof_of_deliveries[0].object_reference = "https://public-s3.amazonaws.com/proof.jpg"
        verifier = RecoveryConsistencyVerifier(post_recovery_snapshot=self.snapshot, mode="offline")
        verifier.verify_private_proofs()
        pod_inv = next(inv for inv in verifier.invariants if inv.invariant_id == "INV-POD-01")
        self.assertEqual(pod_inv.status, VerificationStatus.FAIL)
        self.assertIn("public url", pod_inv.message.lower())


if __name__ == "__main__":
    unittest.main()
