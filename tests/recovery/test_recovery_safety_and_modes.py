"""
Tests for recovery verification runner safety, execution modes, and secret sanitization (Issue #168).
Validates Mode A/B separation, target safety guards, and fail-closed reporting.
"""

import json
import tempfile
import unittest
from pathlib import Path
from tests.recovery.models import RecoveryMetadata, VerificationStatus
from tests.recovery.runner import RecoveryVerificationRunner


class TestRecoverySafetyAndModes(unittest.TestCase):
    """Verifies operational safety, read-only guards, and execution modes."""

    def test_mode_a_offline_runs_and_passes(self):
        """Verifies Mode A offline deterministic verification returns exit code 0."""
        with tempfile.TemporaryDirectory() as td:
            json_out = Path(td) / "report.json"
            md_out = Path(td) / "report.md"
            runner = RecoveryVerificationRunner(
                mode="offline",
                output_json=str(json_out),
                output_md=str(md_out),
            )
            exit_code, report = runner.run_mode_a_offline()
            self.assertEqual(exit_code, 0)
            self.assertEqual(report.overall_status, VerificationStatus.PASS)
            self.assertTrue(json_out.is_file())
            self.assertTrue(md_out.is_file())

            # Read JSON and verify structure
            data = json.loads(json_out.read_text(encoding="utf-8"))
            self.assertIn("report_id", data)
            self.assertIn("metadata", data)
            self.assertIn("invariants", data)

    def test_mode_b_blocks_when_targets_missing(self):
        """Verifies Mode B fails closed with exit code 2 and BLOCKED status when targets are not provided."""
        with tempfile.TemporaryDirectory() as td:
            json_out = Path(td) / "report.json"
            runner = RecoveryVerificationRunner(
                mode="operator-checkpoint",
                output_json=str(json_out),
            )
            exit_code, report = runner.run_mode_b_operator_checkpoint()
            self.assertEqual(exit_code, 2)
            self.assertEqual(report.overall_status, VerificationStatus.BLOCKED)
            self.assertIn("Operator target database for accounts", report.unverified_items[0])

    def test_mode_b_rejects_unsafe_target_without_drill_recovery(self):
        """Security test: rejects target that does not contain '_drill_recovery'."""
        runner = RecoveryVerificationRunner(mode="operator-checkpoint")
        exit_code, report = runner.run_mode_b_operator_checkpoint(
            accounts_target="postgres://accounts_app:secret@localhost:5432/accounts_db",  # UNSAFE: live db!
            pricing_target="pricing_db_drill_recovery",
            delivery_target="delivery_db_drill_recovery",
            comms_target="communications_db_drill_recovery",
            keycloak_target="keycloak_db_drill_recovery",
        )
        self.assertEqual(exit_code, 1)
        self.assertEqual(report.overall_status, VerificationStatus.FAIL)

    def test_sanitization_never_leaks_secrets(self):
        """Security test: verifies that passwords, tokens, and secret keys are never included in evidence."""
        with tempfile.TemporaryDirectory() as td:
            json_out = Path(td) / "report.json"
            runner = RecoveryVerificationRunner(
                mode="offline",
                output_json=str(json_out),
            )
            runner.run_mode_a_offline()
            content = json_out.read_text(encoding="utf-8")

            forbidden_patterns = [
                "password",
                "secret_key",
                "client_secret",
                "Bearer ey",
                "token=",
            ]
            for pat in forbidden_patterns:
                self.assertNotIn(pat, content.lower())


if __name__ == "__main__":
    unittest.main()
