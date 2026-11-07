"""
SwiftDrop Platform — Azure K3s Security & Spending Policy Tests.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Enforces:
1. Deny-by-default cloud spending gate invariants.
2. Network Security Group isolation rules.
3. Host-level cloud-init and K3s configuration security.
4. Data preservation and filesystem checks in storage scripts.
"""

import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
AZURE_K3S_ROOT = REPO_ROOT / "platform" / "terraform" / "azure-k3s"
GATE_POLICY_FILE = AZURE_K3S_ROOT / "azure-spending-gate-policy.yaml"
CHECK_SCRIPT = AZURE_K3S_ROOT / "scripts" / "check-cost-authorization.py"


class TestAzureK3sSecurityPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(GATE_POLICY_FILE, "r", encoding="utf-8") as f:
            cls.gate_policy = yaml.safe_load(f)
        cls.security_hcl = (AZURE_K3S_ROOT / "security.tf").read_text(encoding="utf-8")
        cls.compute_hcl = (AZURE_K3S_ROOT / "compute.tf").read_text(encoding="utf-8")
        cls.cloud_init_tpl = (AZURE_K3S_ROOT / "templates" / "cloud-init.yaml.tftpl").read_text(encoding="utf-8")
        cls.k3s_config_tpl = (AZURE_K3S_ROOT / "templates" / "k3s-config.yaml.tftpl").read_text(encoding="utf-8")
        cls.storage_mount_tpl = (AZURE_K3S_ROOT / "templates" / "storage-mount.sh.tftpl").read_text(encoding="utf-8")

    def test_gate_policy_is_strictly_locked(self):
        """Verifies that the spending authorization gate policy contract is locked."""
        self.assertFalse(self.gate_policy.get("spending_authorized", True))
        self.assertIn("BLOCKED", self.gate_policy.get("status", ""))
        fin = self.gate_policy.get("financial_controls", {})
        self.assertEqual(float(fin.get("approved_budget_usd", 1.0)), 0.0)
        self.assertEqual(float(fin.get("cost_ceiling_usd", 1.0)), 0.0)

    def test_check_cost_authorization_cli_locked_check(self):
        """Verifies that check-cost-authorization.py --verify-gate-locked exits 0."""
        proc = subprocess.run(
            ["python3", str(CHECK_SCRIPT), "--verify-gate-locked"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, f"Gate lock check failed:\n{proc.stdout}\n{proc.stderr}")
        self.assertIn("SPENDING_GATE_LOCKED", proc.stdout)

    def test_check_cost_authorization_cli_blocks_deployment(self):
        """Verifies that check-cost-authorization.py without authorization exits 1."""
        proc = subprocess.run(
            ["python3", str(CHECK_SCRIPT)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 1, "Checker should fail closed when not authorized")
        self.assertIn("SPENDING_NOT_AUTHORIZED", proc.stderr)

    def test_nsg_restricts_inbound_and_denies_unauthorized_ports(self):
        """Verifies NSG rules allow HTTPS and restricted SSH while explicitly dropping others."""
        # Must allow HTTPS 443
        self.assertIn('"allow-https"', self.security_hcl)
        self.assertIn('"443"', self.security_hcl)

        # Must allow SSH only dynamically from approved CIDRs
        self.assertIn('"allow-ssh-restricted"', self.security_hcl)
        self.assertIn('"22"', self.security_hcl)
        self.assertIn("var.allowed_ssh_source_cidrs", self.security_hcl)

        # Must contain explicit deny-all rule at priority 4000
        self.assertIn('"deny-all-other-inbound"', self.security_hcl)
        self.assertIn("priority                   = 4000", self.security_hcl)
        self.assertIn('access                     = "Deny"', self.security_hcl)

        # Forbidden open inbound ports
        forbidden_ports = ["6443", "5432", "29092", "9092", "9000", "9001", "8081", "8082", "8083", "8084", "8085", "3000", "9090"]
        for port in forbidden_ports:
            self.assertNotRegex(
                self.security_hcl,
                rf'destination_port_range\s*=\s*"{port}"\s*access\s*=\s*"Allow"',
                f"Port {port} must NOT be allowed inbound in NSG!",
            )

    def test_compute_disables_password_auth(self):
        """Verifies that Linux VM strictly disables password authentication."""
        self.assertIn("disable_password_authentication = true", self.compute_hcl)
        self.assertIn("admin_ssh_key", self.compute_hcl)

    def test_k3s_config_template_security(self):
        """Verifies K3s server configuration permissions and local-path storage."""
        self.assertIn('write-kubeconfig-mode: "0640"', self.k3s_config_tpl)
        self.assertIn('default-local-storage-path: "${k3s_storage_path}"', self.k3s_config_tpl)
        self.assertIn("disable:\n  - \"servicelb\"", self.k3s_config_tpl)

    def test_storage_mount_idempotency(self):
        """Verifies that storage mount script checks existing filesystem before formatting."""
        self.assertIn("blkid", self.storage_mount_tpl)
        self.assertIn("mkfs.ext4", self.storage_mount_tpl)
        self.assertIn("Preserving existing data", self.storage_mount_tpl)
        self.assertIn("/etc/fstab", self.storage_mount_tpl)
        self.assertIn("UUID=", self.storage_mount_tpl)


if __name__ == "__main__":
    unittest.main()
