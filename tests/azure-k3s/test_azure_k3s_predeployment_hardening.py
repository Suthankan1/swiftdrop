"""
SwiftDrop Platform — Azure K3s Pre-Deployment Hardening & Readiness Tests.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Enforces:
1. Persistent storage protection (prevent_destroy = true on managed disk).
2. Data disk initialization race prevention (retry loop, LUN 0 check, root disk exclusion).
3. Systemd storage dependency preventing K3s stateful workloads on OS root disk.
4. Fail-closed financial authorization (lifecycle preconditions in resource-group.tf).
5. Private Kubernetes administration access via SSH tunnel (port 6443 blocked).
6. Cloud-init and host firewall (UFW) CNI bridge/overlay forwarding rules.
7. Complete application secret mappings across all namespaces.
"""

from __future__ import annotations

import os
import re
import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
AZURE_K3S_ROOT = REPO_ROOT / "platform" / "terraform" / "azure-k3s"
TEMPLATES_DIR = AZURE_K3S_ROOT / "templates"
SCRIPTS_DIR = AZURE_K3S_ROOT / "scripts"
DOCS_DIR = AZURE_K3S_ROOT / "docs"


class TestAzureK3sPredeploymentHardening(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.storage_tf = (AZURE_K3S_ROOT / "storage.tf").read_text(encoding="utf-8")
        cls.compute_tf = (AZURE_K3S_ROOT / "compute.tf").read_text(encoding="utf-8")
        cls.resource_group_tf = (AZURE_K3S_ROOT / "resource-group.tf").read_text(encoding="utf-8")
        cls.security_tf = (AZURE_K3S_ROOT / "security.tf").read_text(encoding="utf-8")
        cls.locals_tf = (AZURE_K3S_ROOT / "locals.tf").read_text(encoding="utf-8")
        cls.storage_mount_sh = (TEMPLATES_DIR / "storage-mount.sh.tftpl").read_text(encoding="utf-8")
        cls.cloud_init_yaml = (TEMPLATES_DIR / "cloud-init.yaml.tftpl").read_text(encoding="utf-8")
        cls.k3s_config_yaml = (TEMPLATES_DIR / "k3s-config.yaml.tftpl").read_text(encoding="utf-8")
        cls.runbook_md = (DOCS_DIR / "DEPLOYMENT_RUNBOOK.md").read_text(encoding="utf-8")
        cls.prep_md = (DOCS_DIR / "DEPLOYMENT_PREPARATION.md").read_text(encoding="utf-8")
        cls.policy_file = AZURE_K3S_ROOT / "azure-spending-gate-policy.yaml"

        with open(cls.policy_file, "r", encoding="utf-8") as f:
            cls.gate_policy = yaml.safe_load(f)

    # --------------------------------------------------------------------------
    # 1. Persistent Storage Protection Tests
    # --------------------------------------------------------------------------
    def test_managed_disk_enforces_prevent_destroy(self):
        """Verifies that azurerm_managed_disk.data has prevent_destroy = true."""
        self.assertRegex(
            self.storage_tf,
            r'resource\s+"azurerm_managed_disk"\s+"data"\s+{[^}]*prevent_destroy\s*=\s*true',
            "azurerm_managed_disk.data MUST enforce prevent_destroy = true to prevent data loss!",
        )

    # --------------------------------------------------------------------------
    # 2. Data Disk Initialization Race & Mount Safety Tests
    # --------------------------------------------------------------------------
    def test_storage_mount_script_polling_retry_and_timeout(self):
        """Verifies storage mount script implements retry loop with timeout for SCSI LUN 0."""
        self.assertIn("TIMEOUT_SECONDS=", self.storage_mount_sh)
        self.assertIn("POLL_INTERVAL=", self.storage_mount_sh)
        self.assertIn("while true; do", self.storage_mount_sh)
        self.assertIn("Timed out", self.storage_mount_sh)

    def test_storage_mount_identifies_and_protects_root_and_temp_disks(self):
        """Verifies that storage mount script identifies and strictly rejects root/temp disks."""
        self.assertIn("ROOT_DEV=", self.storage_mount_sh)
        self.assertIn("TEMP_DEV=", self.storage_mount_sh)
        self.assertIn("matches OS root disk", self.storage_mount_sh)
        self.assertIn("matches Azure ephemeral disk", self.storage_mount_sh)

    def test_storage_mount_scans_exact_azure_lun0_paths(self):
        """Verifies explicit data-disk identity verification for SCSI controller 1, LUN 0."""
        self.assertIn("/dev/disk/azure/scsi1/lun0", self.storage_mount_sh)
        self.assertIn("/dev/disk/by-id/azure-disk-*-lun-0", self.storage_mount_sh)
        self.assertIn("*scsi*-0:1:0:0*", self.storage_mount_sh)

    def test_storage_mount_preserves_existing_filesystem_and_uuid(self):
        """Verifies storage script checks blkid and preserves existing filesystem and UUID."""
        self.assertIn("Preserving existing data", self.storage_mount_sh)
        self.assertIn("mkfs.ext4", self.storage_mount_sh)
        self.assertIn("UUID=$EXISTING_UUID", self.storage_mount_sh)
        self.assertIn("/etc/fstab", self.storage_mount_sh)

    def test_storage_mount_deploys_systemd_mount_guard(self):
        """Verifies storage script deploys systemd drop-in preventing K3s start without mount."""
        self.assertIn("storage-dependency.conf", self.storage_mount_sh)
        self.assertIn("RequiresMountsFor=${mount_point}", self.storage_mount_sh)
        self.assertIn("ConditionPathIsMountPoint=${mount_point}", self.storage_mount_sh)

    # --------------------------------------------------------------------------
    # 3. Financial Authorization & Genuine Precondition Gate Tests
    # --------------------------------------------------------------------------
    def test_resource_group_preconditions_evaluate_signed_policy(self):
        """Verifies resource-group.tf contains genuinely blocking preconditions matching policy."""
        self.assertIn("local.policy_spending_authorized == true", self.resource_group_tf)
        self.assertIn('local.policy_status == "AUTHORIZED_BY_HUMAN_GOVERNANCE"', self.resource_group_tf)
        self.assertIn("var.spending_authorization_confirmed == true", self.resource_group_tf)
        self.assertIn("var.approved_budget_usd <= local.policy_approved_budget", self.resource_group_tf)
        self.assertIn("var.approval_reference == local.policy_approval_reference", self.resource_group_tf)
        self.assertIn("var.subscription_id == local.policy_subscription_id", self.resource_group_tf)
        self.assertIn("contains(local.policy_approved_regions, var.location)", self.resource_group_tf)
        self.assertIn("contains(local.policy_approved_vm_skus, var.vm_size)", self.resource_group_tf)
        self.assertIn("contains(local.policy_approved_disk_skus, var.data_disk_type)", self.resource_group_tf)
        self.assertIn("length(local.policy_unsigned_reviewers) == 0", self.resource_group_tf)

    def test_check_cost_authorization_detects_unauthorized_tfvars(self):
        """Verifies check-cost-authorization.py blocks when gate is locked or vars attempt unauthorized spending."""
        script = SCRIPTS_DIR / "check-cost-authorization.py"
        example_vars = AZURE_K3S_ROOT / "terraform.tfvars.example"

        proc = subprocess.run(
            ["python3", str(script), "--verify-vars", str(example_vars)],
            capture_output=True,
            text=True,
        )
        # While gate is locked, check-cost-authorization must block deployment
        self.assertEqual(proc.returncode, 1)
        self.assertIn("SPENDING_NOT_AUTHORIZED", proc.stderr)

    def test_policy_has_complete_10_resource_inventory(self):
        """Verifies that azure-spending-gate-policy.yaml explicitly lists the approved 10 resources."""
        allowed = self.gate_policy.get("approved_parameters", {}).get("allowed_resources", [])
        expected = [
            "azurerm_resource_group",
            "azurerm_virtual_network",
            "azurerm_subnet",
            "azurerm_public_ip",
            "azurerm_network_security_group",
            "azurerm_network_interface",
            "azurerm_network_interface_security_group_association",
            "azurerm_managed_disk",
            "azurerm_linux_virtual_machine",
            "azurerm_virtual_machine_data_disk_attachment",
        ]
        self.assertEqual(len(allowed), 10)
        for res in expected:
            self.assertIn(res, allowed, f"Resource {res} must be in allowed_resources list!")

    # --------------------------------------------------------------------------
    # 4. Private Kubernetes Administration Access Tests
    # --------------------------------------------------------------------------
    def test_k8s_api_port_6443_strictly_blocked_in_nsg(self):
        """Verifies NSG has NO rule opening port 6443 inbound."""
        self.assertNotRegex(
            self.security_tf,
            r'destination_port_range\s*=\s*"6443"\s*access\s*=\s*"Allow"',
        )

    def test_setup_kube_access_script_exists_and_executable(self):
        """Verifies setup-kube-access.sh script exists with executable bit set."""
        script = SCRIPTS_DIR / "setup-kube-access.sh"
        self.assertTrue(script.is_file())
        self.assertTrue(os.access(script, os.X_OK))

    def test_setup_kube_access_enforces_0600_permissions_and_ssh_forwarding(self):
        """Verifies setup-kube-access.sh locks kubeconfig to 0600 and forwards 6443."""
        content = (SCRIPTS_DIR / "setup-kube-access.sh").read_text(encoding="utf-8")
        self.assertIn("chmod 600", content)
        self.assertIn("127.0.0.1:6443", content)
        self.assertIn("-L", content)

    def test_runbook_uses_ssh_tunnel_and_never_modifies_public_ip(self):
        """Verifies runbook does NOT rewrite kubeconfig to public IP."""
        self.assertNotIn('sed -i.bak "s/127.0.0.1/${PUBLIC_IP}/g"', self.runbook_md)
        self.assertIn("setup-kube-access.sh", self.runbook_md)
        self.assertIn("ssh tunnel", self.runbook_md.lower())

    def test_k3s_config_includes_loopback_and_localhost_sans(self):
        """Verifies K3s config includes 127.0.0.1 and localhost in TLS SANs for tunnel cert validation."""
        self.assertIn('"127.0.0.1"', self.k3s_config_yaml)
        self.assertIn('"localhost"', self.k3s_config_yaml)

    # --------------------------------------------------------------------------
    # 5. Hardened Cloud-Init & K3s Startup Tests
    # --------------------------------------------------------------------------
    def test_cloud_init_configures_ufw_for_cni_traffic(self):
        """Verifies cloud-init configures UFW forwarding policy and CNI bridge/overlay rules."""
        self.assertIn('DEFAULT_FORWARD_POLICY="ACCEPT"', self.cloud_init_yaml)
        self.assertIn("ufw allow in on cni0", self.cloud_init_yaml)
        self.assertIn("ufw allow in on flannel.1", self.cloud_init_yaml)
        self.assertIn("10.42.0.0/16", self.cloud_init_yaml)
        self.assertIn("10.43.0.0/16", self.cloud_init_yaml)
        self.assertIn("8472/udp", self.cloud_init_yaml)

    def test_cloud_init_fails_closed_on_storage_or_k3s_failure(self):
        """Verifies cloud-init aborts if storage mount or K3s readiness fails."""
        self.assertIn("swiftdrop-storage-mount.sh", self.cloud_init_yaml)
        self.assertIn("swiftdrop-bootstrap.failed", self.cloud_init_yaml)
        self.assertIn("swiftdrop-bootstrap.complete", self.cloud_init_yaml)
        self.assertIn("node readiness timed out", self.cloud_init_yaml.lower())

    # --------------------------------------------------------------------------
    # 6. Documentation Consistency Tests
    # --------------------------------------------------------------------------
    def test_runbook_declares_exact_10_azure_resources(self):
        """Verifies runbook correctly specifies exactly 10 Azure resources."""
        self.assertIn("Must show exactly 10 Azure resources", self.runbook_md)
        self.assertNotIn("Must show exactly 8 Azure resources", self.runbook_md)

    def test_secret_injection_covers_all_four_namespaces(self):
        """Verifies deployment documentation includes secret commands for data, messaging, apps, identity."""
        for ns in ["swiftdrop-data", "swiftdrop-messaging", "swiftdrop-apps", "swiftdrop-identity"]:
            self.assertIn(f"--namespace {ns}", self.runbook_md)
            self.assertIn(f"--namespace {ns}", self.prep_md)

    def test_api_gateway_splits_cors_origins(self):
        """Verifies API Gateway SecurityConfig supports comma-delimited allowed origins."""
        gw_sec = (REPO_ROOT / "services" / "api-gateway" / "src" / "main" / "java" / "com" / "swiftdrop" / "apigateway" / "security" / "SecurityConfig.java").read_text(encoding="utf-8")
        self.assertIn('allowedOrigin.split(",")', gw_sec)


if __name__ == "__main__":
    unittest.main()
