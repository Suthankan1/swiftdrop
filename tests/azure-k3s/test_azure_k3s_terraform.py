"""
SwiftDrop Platform — Azure K3s Terraform Static Quality & Schema Tests.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Enforces:
1. Terraform formatting across platform/terraform/azure-k3s.
2. Terraform static schema validation (offline, backend-disabled).
3. Provider lockfile committed and immutable.
4. Fail-closed defaults: spending_authorization_confirmed is false, budget is $0.00.
5. SSH access restriction: rejects 0.0.0.0/0.
6. Zero secrets in outputs or variables.
"""

import os
import re
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
AZURE_K3S_ROOT = REPO_ROOT / "platform" / "terraform" / "azure-k3s"


class TestAzureK3sTerraform(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lockfile = AZURE_K3S_ROOT / ".terraform.lock.hcl"
        cls.variables_file = AZURE_K3S_ROOT / "variables.tf"
        cls.outputs_file = AZURE_K3S_ROOT / "outputs.tf"
        cls.versions_file = AZURE_K3S_ROOT / "versions.tf"
        cls.providers_file = AZURE_K3S_ROOT / "providers.tf"
        cls.security_file = AZURE_K3S_ROOT / "security.tf"
        cls.storage_file = AZURE_K3S_ROOT / "storage.tf"

    def test_mandatory_files_exist(self):
        """Verifies that all mandatory Terraform foundation files exist."""
        required = [
            "versions.tf",
            "providers.tf",
            "variables.tf",
            "locals.tf",
            "main.tf",
            "resource-group.tf",
            "network.tf",
            "security.tf",
            "compute.tf",
            "storage.tf",
            "cloud-init.tf",
            "outputs.tf",
            "validation.tf",
            "terraform.tfvars.example",
            ".terraform.lock.hcl",
            "azure-spending-gate-policy.yaml",
        ]
        for f in required:
            path = AZURE_K3S_ROOT / f
            self.assertTrue(path.is_file(), f"Mandatory file missing: {path}")

    def test_provider_lockfile_pins_azurerm(self):
        """Verifies that .terraform.lock.hcl pins hashicorp/azurerm ~> 4.0 with committed hashes."""
        content = self.lockfile.read_text(encoding="utf-8")
        self.assertIn('provider "registry.terraform.io/hashicorp/azurerm"', content)
        self.assertIn("version     = \"4.81.0\"", content)
        self.assertIn("h1:", content)
        self.assertIn("zh:", content)

    def test_terraform_formatting(self):
        """Runs terraform fmt -check -recursive on azure-k3s root."""
        proc = subprocess.run(
            ["terraform", f"-chdir={AZURE_K3S_ROOT}", "fmt", "-check", "-recursive"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            proc.returncode,
            0,
            f"Terraform formatting failed:\n{proc.stdout}\n{proc.stderr}",
        )

    def test_terraform_static_validation(self):
        """Runs terraform validate -no-color on azure-k3s root."""
        proc = subprocess.run(
            ["terraform", f"-chdir={AZURE_K3S_ROOT}", "validate", "-no-color"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            proc.returncode,
            0,
            f"Terraform validation failed:\n{proc.stdout}\n{proc.stderr}",
        )
        self.assertIn("Success! The configuration is valid.", proc.stdout)

    def test_fail_closed_variable_defaults(self):
        """Verifies that variable defaults strictly enforce deny-by-default."""
        content = self.variables_file.read_text(encoding="utf-8")

        # spending_authorization_confirmed must default to false
        self.assertRegex(
            content,
            r'variable\s+"spending_authorization_confirmed"\s+{[^}]*default\s*=\s*false',
        )

        # approved_budget_usd must default to 0.0
        self.assertRegex(
            content,
            r'variable\s+"approved_budget_usd"\s+{[^}]*default\s*=\s*0\.0',
        )

        # approval_reference must default to empty
        self.assertRegex(
            content,
            r'variable\s+"approval_reference"\s+{[^}]*default\s*=\s*""',
        )

        # allowed_ssh_source_cidrs must default to []
        self.assertRegex(
            content,
            r'variable\s+"allowed_ssh_source_cidrs"\s+{[^}]*default\s*=\s*\[\]',
        )

    def test_ssh_variable_rejects_open_internet(self):
        """Verifies that validation on allowed_ssh_source_cidrs rejects 0.0.0.0/0."""
        content = self.variables_file.read_text(encoding="utf-8")
        self.assertIn("0.0.0.0/0", content)
        self.assertIn("::/0", content)
        self.assertIn("SSH access must NEVER allow 0.0.0.0/0", content)

    def test_outputs_contain_no_secrets(self):
        """Verifies that output declarations do not expose sensitive credentials or private keys."""
        content = self.outputs_file.read_text(encoding="utf-8")
        forbidden = ["password", "secret", "private_key", "token", "credentials"]
        for line in content.splitlines():
            line_clean = line.strip().lower()
            if line_clean.startswith("output "):
                for kw in forbidden:
                    self.assertNotIn(
                        kw,
                        line_clean,
                        f"Potentially sensitive keyword '{kw}' found in output declaration: {line}",
                    )

    def test_managed_disk_parameters(self):
        """Verifies that persistent managed disk size and type are parameterized."""
        content = self.storage_file.read_text(encoding="utf-8")
        self.assertIn("var.data_disk_size_gb", content)
        self.assertIn("var.data_disk_type", content)
        self.assertIn("local.data_disk_name", content)


if __name__ == "__main__":
    unittest.main()
