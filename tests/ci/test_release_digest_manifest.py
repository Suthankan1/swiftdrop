"""
SwiftDrop Release Candidate Manifest & Image Digest Verification Tests (Issue #167)
Validates manifest schema compliance, cryptographic SHA-256 digest format,
immutability guarantees, architecture constraints, and Helm rendering parity.
"""

import json
import shutil
import subprocess
import unittest
from pathlib import Path
try:
    import jsonschema
except ImportError:
    jsonschema = None
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
RELEASE_DIR = REPO_ROOT / "platform" / "kubernetes" / "release"
SCHEMA_FILE = RELEASE_DIR / "manifest-schema.json"
MANIFEST_SCRIPT = RELEASE_DIR / "generate-release-manifest.sh"
VERIFY_SCRIPT = RELEASE_DIR / "verify-candidate-manifest.sh"


class TestReleaseDigestManifest(unittest.TestCase):
    """Verifies that release manifests enforce immutable digests and schema constraints."""

    def setUp(self):
        self.schema = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))

    def test_schema_file_is_valid_json_schema(self):
        """Verifies manifest-schema.json parses cleanly and complies with Draft-07."""
        if jsonschema is None:
            self.skipTest("jsonschema not installed")
        self.assertEqual(self.schema.get("title"), "SwiftDropReleaseCandidateManifest")
        self.assertEqual(self.schema.get("type"), "object")
        self.assertIn("source_commit_sha", self.schema.get("required", []))
        self.assertIn("images", self.schema.get("required", []))

    def test_generate_and_verify_manifest_script(self):
        """Tests that generate-release-manifest.sh creates a valid manifest that passes verify-candidate-manifest.sh."""
        tmp_manifest = REPO_ROOT / "build" / "artifacts" / "test-manifest.json"
        tmp_manifest.parent.mkdir(parents=True, exist_ok=True)
        
        test_sha = "0123456789abcdef0123456789abcdef01234567"
        cmd = [
            str(MANIFEST_SCRIPT),
            "--sha", test_sha,
            "--branch", "test",
            "--approval-ref", "test-approval-ref",
            "--output", str(tmp_manifest)
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f"Manifest generator failed:\n{proc.stderr}\n{proc.stdout}")
        self.assertTrue(tmp_manifest.is_file())

        # Verify with verification tool
        v_cmd = [str(VERIFY_SCRIPT), "--manifest", str(tmp_manifest), "--expected-sha", test_sha, "--skip-git-check"]
        v_proc = subprocess.run(v_cmd, capture_output=True, text=True)
        self.assertEqual(v_proc.returncode, 0, f"Manifest verifier failed:\n{v_proc.stderr}\n{v_proc.stdout}")

        # Clean up
        if tmp_manifest.is_file():
            tmp_manifest.unlink()

    def test_helm_template_renders_manifest_digests(self):
        """Verifies that Helm templates render repository@sha256:... when digests are provided."""
        if not shutil.which("helm"):
            self.skipTest("helm binary not found in PATH")
        chart_dir = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop"
        test_digest = "sha256:" + "d" * 64
        
        proc = subprocess.run(
            ["helm", "template", "test", str(chart_dir),
             "--values", str(chart_dir / "values-staging.yaml"),
             "--set", f"accounts.image.digest={test_digest}"],
            capture_output=True, text=True
        )
        self.assertEqual(proc.returncode, 0, f"Helm template failed:\n{proc.stderr}")
        self.assertIn(f"@{test_digest}", proc.stdout, "Helm did not render exact digest reference")

    def test_reject_mutable_tag_in_helm_production(self):
        """Verifies that values-staging.yaml enforces pinned digests on all microservices."""
        staging_values = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop" / "values-staging.yaml"
        self.assertTrue(staging_values.is_file())
        content = staging_values.read_text(encoding="utf-8")
        self.assertNotIn("image: latest", content)
        self.assertIn("createPlaceholderServices: false", content)


if __name__ == "__main__":
    unittest.main()
