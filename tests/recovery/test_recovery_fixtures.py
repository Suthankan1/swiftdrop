"""
Tests for deterministic synthetic recovery fixtures (Issue #168).
Validates fixture completeness, deterministic relationships, and schema alignment.
"""

import unittest
from tests.recovery.fixtures import build_deterministic_baseline_snapshot


class TestRecoveryFixtures(unittest.TestCase):
    """Verifies that the synthetic recovery baseline snapshot generates valid structures."""

    def setUp(self):
        self.snapshot = build_deterministic_baseline_snapshot()

    def test_fixture_generates_all_core_domains(self):
        """Verifies that all domain entities are populated in the synthetic fixture."""
        self.assertGreater(len(self.snapshot.accounts_profiles), 0)
        self.assertGreater(len(self.snapshot.merchants), 0)
        self.assertGreater(len(self.snapshot.staff_profiles), 0)
        self.assertGreater(len(self.snapshot.keycloak_users), 0)
        self.assertGreater(len(self.snapshot.pricing_zones), 0)
        self.assertGreater(len(self.snapshot.pricing_rate_versions), 0)
        self.assertGreater(len(self.snapshot.pricing_quotes), 0)
        self.assertGreater(len(self.snapshot.parcels), 0)
        self.assertGreater(len(self.snapshot.status_histories), 0)
        self.assertGreater(len(self.snapshot.idempotency_records), 0)
        self.assertGreater(len(self.snapshot.delivery_assignments), 0)
        self.assertGreater(len(self.snapshot.outbox_events), 0)
        self.assertGreater(len(self.snapshot.consumed_events), 0)
        self.assertGreater(len(self.snapshot.notifications), 0)
        self.assertGreater(len(self.snapshot.notification_attempts), 0)
        self.assertGreater(len(self.snapshot.webhook_subscriptions), 0)
        self.assertGreater(len(self.snapshot.webhook_deliveries), 0)
        self.assertGreater(len(self.snapshot.proof_of_deliveries), 0)
        self.assertGreater(len(self.snapshot.kafka_offsets), 0)

    def test_courier_staff_has_matching_keycloak_account(self):
        """Verifies that every staff courier profile corresponds to an active Keycloak user."""
        kc_users = {u.id: u for u in self.snapshot.keycloak_users}
        for staff in self.snapshot.staff_profiles:
            self.assertIn(staff.keycloak_sub, kc_users)
            user = kc_users[staff.keycloak_sub]
            self.assertIn(staff.staff_role, user.roles)

    def test_quote_to_parcel_foreign_keys_are_consistent(self):
        """Verifies that parcels with quotes reference existing quotes in the snapshot."""
        quotes = {q.id: q for q in self.snapshot.pricing_quotes}
        for parcel in self.snapshot.parcels:
            if parcel.quote_id:
                self.assertIn(parcel.quote_id, quotes)
                quote = quotes[parcel.quote_id]
                self.assertEqual(parcel.currency, quote.currency)


if __name__ == "__main__":
    unittest.main()
