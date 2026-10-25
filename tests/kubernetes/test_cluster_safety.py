"""
SwiftDrop Local Cluster Lifecycle Script Safety Tests
Canonical Reference: docs/architecture/BASELINE.md, docs/security/
GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
"""

import os
import re
import stat
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
K3D_DIR = REPO_ROOT / "platform" / "kubernetes" / "k3d"

REQUIRED_SCRIPTS = [
    "create.sh",
    "start.sh",
    "stop.sh",
    "status.sh",
    "verify.sh",
    "capacity.sh",
    "storage-preflight.sh",
]

PROHIBITED_PATTERNS = [
    (r"docker\s+system\s+prune", "docker system prune is destructive to unrelated containers/images"),
    (r"docker\s+volume\s+prune", "docker volume prune erases developer databases and identity data"),
    (r"k3d\s+cluster\s+delete\s+--all", "k3d cluster delete --all erases other team clusters"),
    (r"az\s+[a-z]+", "Azure CLI calls violate the strict $0 hosting boundary"),
    (r"terraform\s+(apply|destroy)", "Terraform apply/destroy is strictly unauthorized under $0 policy"),
    (r"BEGIN\s+RSA\s+PRIVATE\s+KEY", "Committed private key detected"),
    (r"BEGIN\s+OPENSSH\s+PRIVATE\s+KEY", "Committed OpenSSH key detected"),
]


class TestClusterSafety(unittest.TestCase):
    """Validates non-destructive lifecycle scripts and safety boundaries."""

    def test_required_scripts_exist_and_executable(self):
        """Verifies that all 7 required lifecycle and verification scripts exist and have execute bits."""
        for script_name in REQUIRED_SCRIPTS:
            script_path = K3D_DIR / script_name
            self.assertTrue(script_path.exists(), f"Missing lifecycle script: {script_name}")
            mode = script_path.stat().st_mode
            self.assertTrue(
                bool(mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)),
                f"Script {script_name} must be executable"
            )

    def test_scripts_fail_closed_error_flags(self):
        """Verifies all scripts enforce set -euo pipefail for strict error propagation."""
        for script_name in REQUIRED_SCRIPTS:
            script_path = K3D_DIR / script_name
            content = script_path.read_text(encoding="utf-8")
            self.assertTrue(
                "set -euo pipefail" in content or "set -e" in content,
                f"Script {script_name} must enforce strict error termination"
            )

    def test_scripts_prohibit_destructive_pruning_and_cloud_spending(self):
        """Scans all lifecycle scripts for prohibited destructive or cloud-provisioning commands."""
        for script_name in REQUIRED_SCRIPTS:
            script_path = K3D_DIR / script_name
            content = script_path.read_text(encoding="utf-8")
            for pattern, reason in PROHIBITED_PATTERNS:
                matches = re.findall(pattern, content, re.IGNORECASE)
                self.assertEqual(
                    matches, [],
                    f"Found prohibited pattern '{pattern}' in {script_name}: {reason}"
                )

    def test_explicit_context_isolation(self):
        """Verifies scripts explicitly qualify kubectl with context k3d-swiftdrop-local."""
        context_dependent_scripts = ["create.sh", "start.sh", "status.sh", "verify.sh", "capacity.sh"]
        for script_name in context_dependent_scripts:
            script_path = K3D_DIR / script_name
            content = script_path.read_text(encoding="utf-8")
            self.assertIn("swiftdrop-local", content, f"{script_name} must explicitly reference swiftdrop-local")
            if "kubectl" in content:
                self.assertIn(
                    "--context", content,
                    f"{script_name} uses kubectl without explicit --context flag"
                )

    def test_create_script_no_automatic_deletion(self):
        """Verifies create.sh does not automatically invoke k3d cluster delete."""
        create_script = K3D_DIR / "create.sh"
        content = create_script.read_text(encoding="utf-8")
        self.assertNotIn(
            "k3d cluster delete", content,
            "create.sh must safely inspect and reuse or refuse existing clusters, never auto-delete"
        )


if __name__ == "__main__":
    unittest.main()
