"""
SwiftDrop Platform — Hosting Feasibility: Cloud Denial & Strict $0 Policy Tests (Issue #176)

Enforces:
1. Strict $0 hosting ceiling: cost_ceiling_usd == 0.00.
2. Azure cloud deployment authorization is false: azure.deployment_authorized == false.
3. Azure allowed resources list is empty: azure.allowed_resources == [].
4. Promotional credits are explicitly disqualified: azure.promotional_credit_permitted == false.
5. Absolute rejection of candidate cloud resources:
   - Azure VMs (B1s, B2s, D2s_v5)
   - Azure Managed Disks
   - Azure Public IP Addresses
   - Azure NAT Gateway
   - Azure Load Balancer
   - Azure Kubernetes Service (AKS) nodes
   - Azure Storage Accounts / Blob Containers
   - Azure Paid DNS Zones / Domain Registrations
   - Azure Functions / App Service Plans
   - Unauthorized public relay/tunnel agents
"""

import os
import unittest
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
POLICY_FILE = os.path.join(REPO_ROOT, "platform/security/azure-allowlist-policy.yaml")
ADR_0011_FILE = os.path.join(REPO_ROOT, "docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md")


class TestCloudDenialPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(POLICY_FILE, "r") as f:
            cls.policy = yaml.safe_load(f)
        with open(ADR_0011_FILE, "r") as f:
            cls.adr_content = f.read()

    def test_cost_ceiling_is_strictly_zero(self):
        """Verifies that cost_ceiling_usd is mathematically 0.00."""
        ceiling = self.policy.get("cost_ceiling_usd")
        self.assertIsNotNone(ceiling, "cost_ceiling_usd must be defined")
        self.assertEqual(float(ceiling), 0.00, f"cost_ceiling_usd must be exactly 0.00, got {ceiling}")

    def test_azure_deployment_unauthorized(self):
        """Verifies that azure.deployment_authorized is false."""
        azure_cfg = self.policy.get("azure", {})
        self.assertFalse(
            azure_cfg.get("deployment_authorized", True),
            "azure.deployment_authorized must be false",
        )

    def test_azure_allowlist_is_empty(self):
        """Verifies that azure.allowed_resources is strictly empty ([])."""
        azure_cfg = self.policy.get("azure", {})
        allowed = azure_cfg.get("allowed_resources", ["NOT_EMPTY"])
        self.assertEqual(allowed, [], f"azure.allowed_resources must be empty list, got: {allowed}")

    def test_promotional_credits_disqualified(self):
        """Verifies that promotional credits are disqualified as $0 justification."""
        azure_cfg = self.policy.get("azure", {})
        self.assertFalse(
            azure_cfg.get("promotional_credit_permitted", True),
            "promotional_credit_permitted must be false",
        )

    def test_rejection_of_billable_cloud_candidates(self):
        """Verifies policy rejection of billable cloud resources and infrastructure."""
        billable_candidates = [
            "azurerm_virtual_machine",
            "azurerm_linux_virtual_machine",
            "azurerm_managed_disk",
            "azurerm_public_ip",
            "azurerm_nat_gateway",
            "azurerm_lb",
            "azurerm_kubernetes_cluster",
            "azurerm_storage_account",
            "azurerm_dns_zone",
            "azurerm_app_service",
            "azurerm_function_app",
            "azurerm_static_site",
            "cloudflared_tunnel",
            "ngrok_tunnel",
            "tailscale_funnel",
        ]
        allowed = self.policy.get("azure", {}).get("allowed_resources", [])
        for candidate in billable_candidates:
            self.assertNotIn(
                candidate,
                allowed,
                f"Candidate '{candidate}' is illegally present in the allowed_resources list!",
            )

    def test_local_target_is_authorized_default(self):
        """Verifies that local k3d-k3s target is explicitly authorized."""
        local_cfg = self.policy.get("local", {})
        self.assertEqual(local_cfg.get("target"), "k3d-k3s")
        self.assertTrue(local_cfg.get("authorized"))
        self.assertEqual(local_cfg.get("primary_host_operator"), "Suthankan1")


if __name__ == "__main__":
    unittest.main()
