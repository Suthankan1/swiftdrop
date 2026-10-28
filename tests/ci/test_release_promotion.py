"""
SwiftDrop Staging Promotion & Release Gate Regression Tests (Issue #167)
Verifies workflow structure, concurrency locks, promotion guards, candidate provenance,
and fail-closed gate evaluation under valid and adversarial scenarios.
"""

import json
import os
import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
STAGING_WORKFLOW = WORKFLOWS_DIR / "staging-promotion.yml"
CI_WORKFLOW = WORKFLOWS_DIR / "ci.yml"
RELEASE_DIR = REPO_ROOT / "platform" / "kubernetes" / "release"
SCHEMA_FILE = RELEASE_DIR / "manifest-schema.json"


class TestReleasePromotion(unittest.TestCase):
    """Regression tests for the staging promotion workflow and release gates."""

    def test_staging_promotion_workflow_exists_and_parses(self):
        """Verifies staging-promotion.yml exists and contains required workflow fields."""
        self.assertTrue(STAGING_WORKFLOW.is_file(), f"Missing workflow: {STAGING_WORKFLOW}")
        data = yaml.safe_load(STAGING_WORKFLOW.read_text(encoding="utf-8"))
        self.assertIsInstance(data, dict)
        self.assertEqual(data.get("name"), "SwiftDrop Staging Promotion & Release Gate")
        self.assertIn("jobs", data)

    def test_workflow_dispatch_inputs_are_mandatory(self):
        """Verifies candidate_sha and approval_reference are required inputs."""
        data = yaml.safe_load(STAGING_WORKFLOW.read_text(encoding="utf-8"))
        # In YAML 1.1, unquoted 'on' is parsed as boolean True
        on_clause = data.get("on") or data.get(True)
        self.assertIsNotNone(on_clause, "Workflow missing 'on' trigger specification")
        dispatch = on_clause.get("workflow_dispatch", {})
        inputs = dispatch.get("inputs", {})
        
        self.assertIn("candidate_sha", inputs)
        self.assertTrue(inputs["candidate_sha"].get("required"), "candidate_sha must be required")
        
        self.assertIn("approval_reference", inputs)
        self.assertTrue(inputs["approval_reference"].get("required"), "approval_reference must be required")

    def test_concurrency_serializes_promotions_without_cancellation(self):
        """Verifies promotion concurrency is strictly serialized (cancel-in-progress: false)."""
        data = yaml.safe_load(STAGING_WORKFLOW.read_text(encoding="utf-8"))
        concurrency = data.get("concurrency", {})
        self.assertEqual(concurrency.get("cancel-in-progress"), False)
        self.assertEqual(concurrency.get("group"), "release-promotion-staging")

    def test_staging_preflight_gate_depends_on_candidate_verification(self):
        """Verifies staging-preflight-gate explicitly needs candidate-gate-verification."""
        data = yaml.safe_load(STAGING_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("candidate-gate-verification", jobs)
        self.assertIn("staging-preflight-gate", jobs)
        
        needs = jobs["staging-preflight-gate"].get("needs")
        if isinstance(needs, list):
            self.assertIn("candidate-gate-verification", needs)
        else:
            self.assertEqual(needs, "candidate-gate-verification")

    def test_staging_preflight_targets_staging_environment(self):
        """Verifies staging-preflight-gate is bound to the staging environment."""
        data = yaml.safe_load(STAGING_WORKFLOW.read_text(encoding="utf-8"))
        env_spec = data["jobs"]["staging-preflight-gate"].get("environment", {})
        if isinstance(env_spec, dict):
            self.assertEqual(env_spec.get("name"), "staging")
        else:
            self.assertEqual(env_spec, "staging")

    def test_scripts_prohibit_cloud_provisioning(self):
        """Scans all release scripts for prohibited cloud resource provisioning or unapproved tools."""
        prohibited = ["az aks create", "terraform apply", "terraform destroy", "aws eks", "gcloud container"]
        for script in RELEASE_DIR.glob("*.sh"):
            content = script.read_text(encoding="utf-8")
            for term in prohibited:
                self.assertNotIn(term, content, f"Found prohibited command '{term}' in {script.name}")


class TestReleaseNegativeScenarios(unittest.TestCase):
    """Negative tests: verifies that missing or invalid artifacts block promotion."""

    def test_invalid_sha_fails_manifest_verification(self):
        """Negative test: non-hex or short commit SHA is rejected."""
        verify_script = RELEASE_DIR / "verify-candidate-manifest.sh"
        manifest_script = RELEASE_DIR / "generate-release-manifest.sh"
        tmp_manifest = REPO_ROOT / "build" / "artifacts" / "test-neg-manifest.json"
        tmp_manifest.parent.mkdir(parents=True, exist_ok=True)
        try:
            subprocess.run(
                [str(manifest_script), "--sha", "0" * 40, "--branch", "test", "--approval-ref", "test", "--output", str(tmp_manifest)],
                capture_output=True, check=True
            )
            proc = subprocess.run(
                [str(verify_script), "--manifest", str(tmp_manifest), "--expected-sha", "invalid-short-sha", "--skip-git-check"],
                capture_output=True, text=True
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertTrue("FATAL" in proc.stdout or "FATAL" in proc.stderr)
        finally:
            if tmp_manifest.is_file():
                tmp_manifest.unlink()

    def test_missing_manifest_fails_promotion_preflight(self):
        """Negative test: missing manifest file blocks promotion."""
        promote_script = RELEASE_DIR / "promote-staging.sh"
        proc = subprocess.run(
            [str(promote_script), "--manifest", "/nonexistent/manifest.json", "--approval-ref", "test", "--dry-run"],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)

    def test_missing_approval_fails_promotion(self):
        """Negative test: empty approval reference blocks promotion."""
        promote_script = RELEASE_DIR / "promote-staging.sh"
        proc = subprocess.run(
            [str(promote_script), "--approval-ref", "", "--dry-run"],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("FATAL", proc.stderr + proc.stdout)


if __name__ == "__main__":
    unittest.main()
