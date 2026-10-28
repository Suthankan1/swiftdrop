"""
Negative recovery regression test suite (Issue #168 / N10-P09A).
Iterates across all 17 negative failure-injection scenarios and verifies
that the consistency verifier detects and flags each corruption condition.
"""

import unittest
from tests.recovery.models import RecoveryMetadata, VerificationStatus
from tests.recovery.negative_fixtures import (
    NEGATIVE_FIXTURE_FACTORIES,
    create_corrupted_snapshot,
)
from tests.recovery.verifier import RecoveryConsistencyVerifier


class TestNegativeRecoveryRegressions(unittest.TestCase):
    """Verifies that all 17 negative corruption scenarios trigger explicit verification failures."""

    def setUp(self):
        self.metadata = RecoveryMetadata()

    def test_all_negative_scenarios_trigger_overall_failure(self):
        """Iterates over each negative fixture and verifies fail-closed rejection."""
        for scenario_name, (modifier, expected_domain) in NEGATIVE_FIXTURE_FACTORIES.items():
            with self.subTest(scenario=scenario_name):
                corrupted_snapshot = create_corrupted_snapshot(modifier, scenario_name)
                verifier = RecoveryConsistencyVerifier(
                    post_recovery_snapshot=corrupted_snapshot,
                    metadata=self.metadata,
                    mode="offline",
                )
                report = verifier.run_all()
                self.assertEqual(
                    report.overall_status,
                    VerificationStatus.FAIL,
                    f"Scenario {scenario_name} unexpectedly passed or was not marked FAIL!",
                )
                failed_invariants = [
                    inv for inv in report.invariants if inv.status == VerificationStatus.FAIL
                ]
                self.assertGreater(
                    len(failed_invariants),
                    0,
                    f"Scenario {scenario_name} had zero failing invariants!",
                )


if __name__ == "__main__":
    unittest.main()
