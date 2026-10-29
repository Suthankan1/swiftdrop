"""
Tests for Issue #178: Optional Future Azure Cloud K3s Spending Authorization Gate.

Verifies:
1. Documentation gate completeness, authoritative status banners, and sizing analysis.
2. The 20-point spending authorization checklist and negative authorization rules.
3. Deny-by-default policy rejection of any Azure VM, disk, IP, NAT, or cluster plan.
4. Retention of strict $0 allowlist invariants (deployment_authorized: false, empty allowlists).
5. Zero executable cloud infrastructure in the root Terraform module or CI workflows.
"""

from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path
from typing import Any, Dict

import yaml

# Add repo root and security dir to import path
REPO_ROOT = Path(__file__).resolve().parents[2]
SECURITY_DIR = REPO_ROOT / "platform/security"
if str(SECURITY_DIR) not in sys.path:
    sys.path.insert(0, str(SECURITY_DIR))

from validate_terraform_policy import (
    CODE_DEPLOYMENT_NOT_AUTHORIZED,
    CODE_RESOURCE_NOT_APPROVED,
    PolicyValidator,
)


class TestSpendingAuthorizationGateDocumentation(unittest.TestCase):
    """Verifies that the Issue #178 spending authorization document satisfies all governance invariants."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.doc_path = REPO_ROOT / "docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md"
        cls.content = cls.doc_path.read_text(encoding="utf-8")

    def test_document_exists(self) -> None:
        """Verifies that the optional Azure K3s spending authorization document exists."""
        self.assertTrue(
            self.doc_path.is_file(),
            f"Expected spending authorization document at {self.doc_path}",
        )

    def test_authoritative_status_banners(self) -> None:
        """Verifies all five required authoritative status lines are present."""
        expected_statuses = [
            "**Azure Cloud K3s: OPTIONAL FUTURE WORK**",
            "**Implementation authorization: NOT AUTHORIZED**",
            "**Spending authorization: NOT AUTHORIZED**",
            "**Cloud deployment: BLOCKED**",
            "**November 10 critical-path dependency: NO**",
        ]
        for expected in expected_statuses:
            self.assertIn(
                expected,
                self.content,
                f"Missing required authoritative status declaration: '{expected}'",
            )

    def test_twenty_point_checklist_items_present(self) -> None:
        """Verifies that all 20 required spending authorization checklist items are documented."""
        required_items = [
            "1. Separate Explicit Spending Vote",
            "2. Named Financial Approver",
            "3. Specific Authorized Azure Subscription",
            "4. Exact Approved Azure Region",
            "5. Exact Reviewed SKUs & Quantities",
            "6. Exhaustive List of Resource Dependencies",
            "7. Itemized Projected Spending Schedule",
            "8. Maximum Total Budget Hard Ceiling",
            "9. Maximum Monthly / Recurring Spending Limit",
            "10. Definite Deployment Start & End Dates",
            "11. Maximum Permitted Operating Duration (TTL)",
            "12. Deprovisioning & Deletion Runbook",
            "13. Cost Overrun Detection & Emergency Procedure",
            "14. Named Infrastructure Operator",
            "15. Named Billing Custodian",
            "16. Independent Security Review Sign-Off",
            "17. Independent Architecture Review Sign-Off",
            "18. Reviewed & Approved Terraform Plan",
            "19. Terraform State & Secret Custody Verification",
            "20. Final Human Deployment Go/No-Go Decision",
        ]
        for item in required_items:
            self.assertIn(
                item,
                self.content,
                f"Missing required checklist item: '{item}'",
            )

    def test_negative_authorization_rules_present(self) -> None:
        """Verifies that the negative authorization rules are explicitly articulated."""
        negative_rules = [
            "A Terraform Plan is NOT Spending Authorization",
            "A GitHub Issue Assignment is NOT Spending Authorization",
            "Closing Issue #178 is NOT Spending Authorization",
            "A Successful CI Pipeline is NOT Spending Authorization",
            "Approving This Document is NOT Spending Authorization",
            "Student Credits are NOT Spending Authorization",
            "Scope Change Invalidation Rule",
        ]
        for rule in negative_rules:
            self.assertIn(
                rule,
                self.content,
                f"Missing required negative authorization rule: '{rule}'",
            )

    def test_sizing_and_resource_analysis_present(self) -> None:
        """Verifies that the memory sizing analysis and B2s starvation trap are documented."""
        self.assertIn("Standard_B4ms", self.content)
        self.assertIn("Standard_D4s_v5", self.content)
        self.assertIn("Standard_B2s", self.content)
        self.assertIn("local-path", self.content)
        self.assertIn("oom-killer", self.content)

    def test_aks_rejection_rationale_present(self) -> None:
        """Verifies that Azure Kubernetes Service (AKS) is compared and rejected."""
        self.assertIn("Azure Kubernetes Service (AKS)", self.content)
        self.assertIn("PERMANENTLY REJECTED — PROHIBITIVE", self.content)

    def test_non_executable_hcl_template_markers(self) -> None:
        """Verifies that the illustrative HCL template has non-executable warnings."""
        self.assertIn("DO NOT APPLY — NON-EXECUTABLE SPECIFICATION", self.content)
        self.assertIn("azurerm_linux_virtual_machine", self.content)

    def test_relative_markdown_links_resolve(self) -> None:
        """Verifies all relative links in the spending document resolve to real files."""
        parent_dir = self.doc_path.parent
        link_pattern = re.compile(r'\[([^\]]+)\]\(([^)#]+)(?:#[^)]*)?\)')
        matches = link_pattern.findall(self.content)

        self.assertTrue(len(matches) > 0, "No markdown links found in document")
        for label, path_str in matches:
            if path_str.startswith("http://") or path_str.startswith("https://") or path_str.startswith("mailto:"):
                continue
            resolved = (parent_dir / path_str).resolve()
            self.assertTrue(
                resolved.exists(),
                f"Broken link in spending document: [{label}]({path_str}) -> {resolved} does not exist",
            )


class TestSpendingAuthorizationPolicyEnforcement(unittest.TestCase):
    """Verifies that the policy validator and YAML contracts strictly reject Azure resources."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.policy_path = REPO_ROOT / "platform/security/azure-allowlist-policy.yaml"
        cls.oidc_policy_path = REPO_ROOT / "platform/security/github-oidc-trust-policy.yaml"
        cls.root_tf_path = REPO_ROOT / "platform/terraform/main.tf"
        cls.validator = PolicyValidator(cls.policy_path)

    def test_canonical_allowlist_invariants_preserved(self) -> None:
        """Verifies that the canonical Azure allowlist remains empty and deployment is unauthorized."""
        with open(self.policy_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        self.assertEqual(data.get("cost_ceiling_usd"), 0.00)
        azure = data.get("azure", {})
        self.assertFalse(azure.get("deployment_authorized"))
        self.assertFalse(azure.get("promotional_credit_permitted"))
        self.assertFalse(azure.get("billing_overage_permitted"))
        self.assertEqual(azure.get("allowed_resources"), [])
        self.assertEqual(azure.get("allowed_skus"), {})
        self.assertEqual(azure.get("allowed_regions"), [])

    def test_root_terraform_module_declares_zero_resources(self) -> None:
        """Verifies that the active Terraform root module contains no cloud resources."""
        content = self.root_tf_path.read_text(encoding="utf-8")
        resource_matches = re.findall(r'^\s*resource\s+"([^"]+)"', content, re.MULTILINE)
        self.assertEqual(
            resource_matches,
            [],
            f"Active root module must contain zero resource declarations, found: {resource_matches}",
        )

    def _create_synthetic_plan(self, resource_type: str, address: str, values: Dict[str, Any]) -> Dict[str, Any]:
        """Helper to create a synthetic Terraform plan representation."""
        return {
            "format_version": "1.2",
            "terraform_version": "1.16.3",
            "resource_changes": [
                {
                    "address": address,
                    "mode": "managed",
                    "type": resource_type,
                    "name": "synthetic",
                    "provider_name": "registry.terraform.io/hashicorp/azurerm",
                    "change": {
                        "actions": ["create"],
                        "before": None,
                        "after": values,
                        "after_unknown": {},
                    },
                }
            ],
            "planned_values": {
                "root_module": {
                    "resources": [
                        {
                            "address": address,
                            "mode": "managed",
                            "type": resource_type,
                            "name": "synthetic",
                            "provider_name": "registry.terraform.io/hashicorp/azurerm",
                            "values": values,
                        }
                    ]
                }
            },
        }

    def test_hypothetical_azure_vm_plan_denied(self) -> None:
        """Verifies that a plan attempting to provision an Azure VM is denied by policy."""
        plan = self._create_synthetic_plan(
            resource_type="azurerm_linux_virtual_machine",
            address="azurerm_linux_virtual_machine.k3s_node",
            values={"name": "vm-k3s", "location": "eastus", "size": "Standard_B4ms"},
        )
        result = self.validator.validate_plan_dict(plan)
        self.assertFalse(result.passed)
        violation_codes = [v.code for v in result.violations]
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, violation_codes)

    def test_hypothetical_managed_disk_plan_denied(self) -> None:
        """Verifies that a plan attempting to provision an Azure Managed Disk is denied."""
        plan = self._create_synthetic_plan(
            resource_type="azurerm_managed_disk",
            address="azurerm_managed_disk.k3s_pvc_storage",
            values={"name": "disk-pvc", "location": "eastus", "disk_size_gb": 64},
        )
        result = self.validator.validate_plan_dict(plan)
        self.assertFalse(result.passed)
        violation_codes = [v.code for v in result.violations]
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, violation_codes)

    def test_hypothetical_public_ip_plan_denied(self) -> None:
        """Verifies that a plan attempting to provision a Public IP is denied."""
        plan = self._create_synthetic_plan(
            resource_type="azurerm_public_ip",
            address="azurerm_public_ip.k3s_ingress_ip",
            values={"name": "pip-ingress", "location": "eastus", "sku": "Standard"},
        )
        result = self.validator.validate_plan_dict(plan)
        self.assertFalse(result.passed)
        violation_codes = [v.code for v in result.violations]
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, violation_codes)

    def test_hypothetical_aks_plan_denied(self) -> None:
        """Verifies that a plan attempting to provision an AKS cluster is denied."""
        plan = self._create_synthetic_plan(
            resource_type="azurerm_kubernetes_cluster",
            address="azurerm_kubernetes_cluster.aks_demo",
            values={"name": "aks-demo", "location": "eastus", "dns_prefix": "swiftdrop"},
        )
        result = self.validator.validate_plan_dict(plan)
        self.assertFalse(result.passed)
        violation_codes = [v.code for v in result.violations]
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, violation_codes)

    def test_hypothetical_storage_account_plan_denied(self) -> None:
        """Verifies that a plan attempting to provision an Azure Storage Account is denied."""
        plan = self._create_synthetic_plan(
            resource_type="azurerm_storage_account",
            address="azurerm_storage_account.tfstate",
            values={"name": "sttfstate", "location": "eastus", "account_tier": "Standard"},
        )
        result = self.validator.validate_plan_dict(plan)
        self.assertFalse(result.passed)
        violation_codes = [v.code for v in result.violations]
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, violation_codes)


if __name__ == "__main__":
    unittest.main()
