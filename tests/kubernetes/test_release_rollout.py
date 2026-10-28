"""
SwiftDrop Staged Rollout, Migration Preflight & Application Rollback Tests (Issue #167)
Validates rollout safeguards, values profile separation, migration safety preflights,
expand-and-contract schema compatibility, and deterministic application rollback.
"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
RELEASE_DIR = REPO_ROOT / "platform" / "kubernetes" / "release"
HELM_DIR = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop"

PREFLIGHT_SCRIPT = RELEASE_DIR / "migration-preflight.sh"
PROMOTE_SCRIPT = RELEASE_DIR / "promote-staging.sh"
ROLLBACK_SCRIPT = RELEASE_DIR / "rollback-app.sh"
VERIFY_SCRIPT = RELEASE_DIR / "verify-rollout.sh"


class TestReleaseRollout(unittest.TestCase):
    """Verifies that release promotion and rollback scripts enforce safety invariants."""

    def test_values_profile_separation(self):
        """Verifies distinct separation between values-test.yaml and values-staging.yaml."""
        test_values = yaml.safe_load((HELM_DIR / "values-test.yaml").read_text(encoding="utf-8"))
        staging_values = yaml.safe_load((HELM_DIR / "values-staging.yaml").read_text(encoding="utf-8"))

        # values-test may allow placeholder services for static templating
        self.assertTrue(test_values.get("statefulInterfaces", {}).get("createPlaceholderServices", False))

        # values-staging MUST strictly prohibit placeholder services
        self.assertFalse(staging_values.get("statefulInterfaces", {}).get("createPlaceholderServices", True))

    def test_preflight_rejects_wrong_kubernetes_context(self):
        """Negative test: preflight script refuses to run against non-approved contexts."""
        proc = subprocess.run(
            [str(PREFLIGHT_SCRIPT), "--context", "production-aks", "--namespace", "default"],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("FATAL", proc.stderr + proc.stdout)
        self.assertIn("k3d-swiftdrop-local", proc.stderr + proc.stdout)

    def test_preflight_detects_failed_migrations_in_fixture(self):
        """Negative test: migration preflight fails closed when a database contains failed migrations."""
        fixture_data = {
            "databases": {
                "accounts_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "pricing_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "delivery_db": {"connected": True, "has_failed_migrations": True, "backward_compatible": True},  # FAILED!
                "communications_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "keycloak_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
            }
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(fixture_data, tf)
            tf_path = tf.name

        try:
            proc = subprocess.run(
                [str(PREFLIGHT_SCRIPT), "--fixture", tf_path],
                capture_output=True, text=True
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("FAILED Flyway migration", proc.stderr + proc.stdout)
        finally:
            Path(tf_path).unlink()

    def test_preflight_detects_schema_incompatibility_in_fixture(self):
        """Negative test: migration preflight blocks rollout when destructive changes are detected."""
        fixture_data = {
            "databases": {
                "accounts_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": False},  # Incompatible!
                "pricing_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "delivery_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "communications_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
                "keycloak_db": {"connected": True, "has_failed_migrations": False, "backward_compatible": True},
            }
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(fixture_data, tf)
            tf_path = tf.name

        try:
            proc = subprocess.run(
                [str(PREFLIGHT_SCRIPT), "--fixture", tf_path],
                capture_output=True, text=True
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("destructive changes incompatible", proc.stderr + proc.stdout)
        finally:
            Path(tf_path).unlink()

    def test_rollback_requires_operator_approval(self):
        """Negative test: application rollback fails without explicit operator approval."""
        proc = subprocess.run(
            [str(ROLLBACK_SCRIPT), "--operator-approval", ""],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("operator approval", proc.stderr.lower() + proc.stdout.lower())

    def test_rollback_blocks_on_schema_incompatibility(self):
        """Negative test: application rollback fails closed when schema change is irreversible."""
        proc = subprocess.run(
            [str(ROLLBACK_SCRIPT), "--operator-approval", "test-token", "--schema-incompatible"],
            capture_output=True, text=True
        )
        self.assertEqual(proc.returncode, 2, "Expected exit code 2 on schema incompatibility")
        self.assertIn("SCHEMA INCOMPATIBILITY", proc.stderr + proc.stdout)

    def test_rollback_fixture_positive_and_negative(self):
        """Verifies rollback script handles positive and negative fixture scenarios cleanly."""
        # Positive fixture
        pos_data = {"schema_compatible": True, "has_previous_revision": True}
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(pos_data, tf)
            pos_path = tf.name

        try:
            proc = subprocess.run(
                [str(ROLLBACK_SCRIPT), "--operator-approval", "test-token", "--fixture", pos_path],
                capture_output=True, text=True
            )
            self.assertEqual(proc.returncode, 0)
        finally:
            Path(pos_path).unlink()

        # Negative fixture: no previous revision
        neg_data = {"schema_compatible": True, "has_previous_revision": False}
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(neg_data, tf)
            neg_path = tf.name

        try:
            proc = subprocess.run(
                [str(ROLLBACK_SCRIPT), "--operator-approval", "test-token", "--fixture", neg_path],
                capture_output=True, text=True
            )
            self.assertNotEqual(proc.returncode, 0)
        finally:
            Path(neg_path).unlink()

    def test_verify_rollout_fixture_rejects_unready_or_mismatched_workload(self):
        """Negative test: verify-rollout.sh fails when a workload is unready or digest mismatched."""
        fixture_data = {
            "workloads": {
                "frontend": {"ready": True, "digest_matched": True},
                "api-gateway": {"ready": True, "digest_matched": True},
                "accounts-service": {"ready": False, "desired_replicas": 1, "ready_replicas": 0, "digest_matched": True},  # NOT READY!
                "pricing-service": {"ready": True, "digest_matched": True},
                "delivery-service": {"ready": True, "digest_matched": True},
                "communications-service": {"ready": True, "digest_matched": True},
                "graphql-query-service": {"ready": True, "digest_matched": True},
                "keycloak": {"ready": True, "digest_matched": True},
            }
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(fixture_data, tf)
            tf_path = tf.name

        try:
            proc = subprocess.run(
                [str(VERIFY_SCRIPT), "--fixture", tf_path],
                capture_output=True, text=True
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("accounts-service is not ready", proc.stderr + proc.stdout)
        finally:
            Path(tf_path).unlink()


if __name__ == "__main__":
    unittest.main()

