"""
Unit and regression tests for SwiftDrop deny-by-default Terraform cost & security policy.

Canonical Architecture Reference:
- docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
- platform/security/azure-allowlist-policy.yaml
- GitHub Issue #173 (N10-ZC02)
"""

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SECURITY_DIR = REPO_ROOT / "platform/security"
if str(SECURITY_DIR) not in sys.path:
    sys.path.insert(0, str(SECURITY_DIR))

from validate_terraform_policy import (
    CODE_BACKEND_STORAGE_NOT_APPROVED,
    CODE_COST_CEILING_EXCEEDED,
    CODE_DEPLOYMENT_NOT_AUTHORIZED,
    CODE_EVIDENCE_EXPIRED,
    CODE_MALFORMED_PLAN,
    CODE_MALFORMED_POLICY,
    CODE_MISSING_PLAN,
    CODE_MISSING_POLICY,
    CODE_NONZERO_METER,
    CODE_PASS,
    CODE_PROMOTIONAL_CREDIT_DISQUALIFIED,
    CODE_RESOURCE_NOT_APPROVED,
    CODE_UNAPPROVED_REGION,
    CODE_UNAPPROVED_SKU,
    CODE_UNAPPROVED_SUBSCRIPTION,
    CODE_UNKNOWN_REGION,
    CODE_UNKNOWN_SKU,
    PolicyValidator,
    validate_plan,
)

CANONICAL_POLICY = REPO_ROOT / "platform/security/azure-allowlist-policy.yaml"
FIXTURES_DIR = REPO_ROOT / "tests/terraform-policy/fixtures"
VALIDATOR_SCRIPT = REPO_ROOT / "platform/security/validate_terraform_policy.py"


class TestCanonicalPolicyContract(unittest.TestCase):
    """Verifies invariants of the canonical Azure allowlist policy contract."""

    def test_canonical_policy_exists_and_parses(self):
        self.assertTrue(CANONICAL_POLICY.exists(), f"Missing {CANONICAL_POLICY}")
        validator = PolicyValidator(CANONICAL_POLICY)
        self.assertEqual(validator.cost_ceiling_usd, 0.00)
        self.assertFalse(validator.is_deployment_authorized)
        self.assertFalse(validator.promotional_credit_permitted)
        self.assertEqual(len(validator.allowed_resources), 0)
        self.assertEqual(len(validator.allowed_regions), 0)
        self.assertEqual(len(validator.allowed_skus), 0)
        self.assertEqual(validator.subscription_status, "UNVERIFIED")

    def test_missing_policy_file_fails_closed(self):
        missing_path = REPO_ROOT / "platform/security/non_existent_policy.yaml"
        with self.assertRaises(FileNotFoundError):
            PolicyValidator(missing_path)

    def test_corrupted_policy_fails_closed(self):
        corrupted_path = FIXTURES_DIR / "corrupted-allowlist.yaml"
        with self.assertRaises(ValueError):
            PolicyValidator(corrupted_path)

    def test_missing_deployment_auth_fails_closed(self):
        missing_auth = FIXTURES_DIR / "missing-deployment-auth.yaml"
        with self.assertRaises(ValueError):
            PolicyValidator(missing_auth)


class TestPositivePlanValidation(unittest.TestCase):
    """Verifies that legitimate, non-cloud, $0 local plans pass policy evaluation."""

    def setUp(self):
        self.validator = PolicyValidator(CANONICAL_POLICY)

    def test_empty_plan_passes(self):
        fixture = FIXTURES_DIR / "empty-plan.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertTrue(result.passed, f"Expected empty plan to pass: {result.violations}")
        self.assertEqual(result.status_code, CODE_PASS)
        self.assertEqual(len(result.violations), 0)

    def test_local_only_plan_passes(self):
        fixture = FIXTURES_DIR / "local-only-plan.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertTrue(result.passed, f"Expected local-only plan to pass: {result.violations}")
        self.assertEqual(result.status_code, CODE_PASS)
        self.assertEqual(result.resource_count, 1)
        self.assertEqual(len(result.violations), 0)

    def test_noop_root_module_passes(self):
        fixture = FIXTURES_DIR / "noop-root-module-plan.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertTrue(result.passed, f"Expected noop plan to pass: {result.violations}")
        self.assertEqual(result.status_code, CODE_PASS)
        self.assertEqual(len(result.violations), 0)

    def test_false_positive_avoidance(self):
        """Metadata mentioning 'azure' or 'virtual_machine' in text must not cause false rejection."""
        plan = {
            "format_version": "1.2",
            "terraform_version": "1.16.3",
            "variables": {},
            "planned_values": {
                "root_module": {
                    "resources": [
                        {
                            "address": "local_file.architecture_notes",
                            "mode": "managed",
                            "type": "local_file",
                            "name": "architecture_notes",
                            "provider_name": "registry.terraform.io/hashicorp/local",
                            "values": {
                                "content": "Note: Azure Virtual Machine and AKS are prohibited under ADR-0011."
                            }
                        }
                    ]
                }
            },
            "resource_changes": [
                {
                    "address": "local_file.architecture_notes",
                    "mode": "managed",
                    "type": "local_file",
                    "name": "architecture_notes",
                    "provider_name": "registry.terraform.io/hashicorp/local",
                    "change": {
                        "actions": ["create"],
                        "before": None,
                        "after": {
                            "content": "Note: Azure Virtual Machine and AKS are prohibited under ADR-0011."
                        },
                        "after_unknown": {}
                    }
                }
            ],
            "configuration": {
                "root_module": {}
            }
        }
        result = self.validator.validate_plan_dict(plan)
        self.assertTrue(result.passed, f"Harmless text triggered false positive: {result.violations}")


class TestNegativePlanRejection(unittest.TestCase):
    """Asserts deterministic denial for all prohibited Azure resources and unsafe configurations."""

    def setUp(self):
        self.validator = PolicyValidator(CANONICAL_POLICY)

    def _assert_denied(self, fixture_name: str, expected_codes: list[str], expected_resource_type: str = ""):
        fixture = FIXTURES_DIR / fixture_name
        result = self.validator.validate_plan_file(fixture)
        self.assertFalse(result.passed, f"Expected {fixture_name} to be DENIED, but it passed.")
        codes = [v.code for v in result.violations]
        for exp in expected_codes:
            self.assertIn(
                exp,
                codes,
                f"Expected violation code '{exp}' in {fixture_name}, got: {codes}",
            )
        if expected_resource_type:
            types = [v.resource_type for v in result.violations]
            self.assertIn(
                expected_resource_type,
                types,
                f"Expected resource type '{expected_resource_type}' in {fixture_name}, got: {types}",
            )
        for v in result.violations:
            self.assertTrue(v.rule_violated, "Violation must report rule violated")
            self.assertTrue(v.reason, "Violation must report human-readable reason")
            self.assertTrue(v.safe_next_action, "Violation must report safe next action")

    def test_azure_vm_denied(self):
        self._assert_denied(
            "azure-vm.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_linux_virtual_machine",
        )

    def test_azure_managed_disk_denied(self):
        self._assert_denied(
            "azure-managed-disk.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_managed_disk",
        )

    def test_azure_public_ip_denied(self):
        self._assert_denied(
            "azure-public-ip.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_public_ip",
        )

    def test_azure_nat_gateway_denied(self):
        self._assert_denied(
            "azure-nat-gateway.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_nat_gateway",
        )

    def test_azure_load_balancer_denied(self):
        self._assert_denied(
            "azure-load-balancer.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_lb",
        )

    def test_azure_aks_cluster_denied(self):
        self._assert_denied(
            "azure-aks-cluster.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_kubernetes_cluster",
        )

    def test_azure_storage_account_denied(self):
        self._assert_denied(
            "azure-storage-account.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_storage_account",
        )

    def test_azure_backend_storage_denied(self):
        self._assert_denied(
            "azure-backend-storage.json",
            [CODE_BACKEND_STORAGE_NOT_APPROVED],
            "backend_azurerm",
        )

    def test_azure_functions_denied(self):
        self._assert_denied(
            "azure-functions.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_linux_function_app",
        )

    def test_azure_static_web_app_denied(self):
        self._assert_denied(
            "azure-static-web-app.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_static_web_app",
        )

    def test_azure_paid_addon_denied(self):
        self._assert_denied(
            "azure-paid-addon.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_log_analytics_workspace",
        )

    def test_azure_unknown_resource_denied(self):
        self._assert_denied(
            "azure-unknown-resource.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_quantum_workspace",
        )

    def test_azure_nested_module_denied(self):
        fixture = FIXTURES_DIR / "azure-nested-module.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertFalse(result.passed)
        addresses = [v.address for v in result.violations]
        self.assertTrue(
            any("module.platform_compute.module.workers" in addr for addr in addresses),
            f"Nested module resource address not inspected: {addresses}",
        )

    def test_azure_provider_alias_denied(self):
        self._assert_denied(
            "azure-provider-alias.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED, CODE_RESOURCE_NOT_APPROVED],
            "azurerm_virtual_network",
        )

    def test_azure_multiple_instances_denied(self):
        fixture = FIXTURES_DIR / "azure-multiple-instances.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertFalse(result.passed)
        addresses = [v.address for v in result.violations]
        self.assertTrue(any("[0]" in addr for addr in addresses))
        self.assertTrue(any("[1]" in addr for addr in addresses))

    def test_azure_unknown_region_denied(self):
        self._assert_denied(
            "azure-unknown-region.json",
            [CODE_UNKNOWN_REGION],
            "azurerm_resource_group",
        )

    def test_azure_unknown_sku_denied(self):
        self._assert_denied(
            "azure-unknown-sku.json",
            [CODE_UNKNOWN_SKU],
            "azurerm_linux_virtual_machine",
        )

    def test_azure_unapproved_sku_denied(self):
        self._assert_denied(
            "azure-unapproved-sku.json",
            [CODE_RESOURCE_NOT_APPROVED],
            "azurerm_linux_virtual_machine",
        )

    def test_azure_unapproved_region_denied(self):
        self._assert_denied(
            "azure-unapproved-region.json",
            [CODE_RESOURCE_NOT_APPROVED],
            "azurerm_resource_group",
        )

    def test_azure_expired_evidence_denied(self):
        self._assert_denied(
            "azure-expired-evidence.json",
            [CODE_EVIDENCE_EXPIRED],
            "azurerm_resource_group",
        )

    def test_azure_nonzero_meter_denied(self):
        self._assert_denied(
            "azure-nonzero-meter.json",
            [CODE_NONZERO_METER],
            "azurerm_resource_group",
        )

    def test_azure_promotional_credit_disqualified(self):
        self._assert_denied(
            "azure-promotional-credit.json",
            [CODE_PROMOTIONAL_CREDIT_DISQUALIFIED],
            "azurerm_linux_virtual_machine",
        )

    def test_azure_unapproved_subscription_denied(self):
        self._assert_denied(
            "azure-unapproved-subscription.json",
            [CODE_UNAPPROVED_SUBSCRIPTION],
            "azurerm_resource_group",
        )

    def test_azure_unknown_planned_values_denied(self):
        self._assert_denied(
            "azure-unknown-planned-values.json",
            [CODE_UNKNOWN_REGION],
            "azurerm_storage_account",
        )

    def test_unapproved_override_attempt_denied(self):
        self._assert_denied(
            "unapproved-override.json",
            [CODE_DEPLOYMENT_NOT_AUTHORIZED],
            "azurerm_linux_virtual_machine",
        )

    def test_malformed_plan_fails_closed(self):
        fixture = FIXTURES_DIR / "malformed-plan.json"
        result = self.validator.validate_plan_file(fixture)
        self.assertFalse(result.passed)
        self.assertEqual(result.status_code, CODE_MALFORMED_PLAN)

    def test_missing_plan_fails_closed(self):
        missing = FIXTURES_DIR / "non-existent-plan.json"
        result = self.validator.validate_plan_file(missing)
        self.assertFalse(result.passed)
        self.assertEqual(result.status_code, CODE_MISSING_PLAN)


class TestCliInvocation(unittest.TestCase):
    """Tests command-line interface execution and exit codes."""

    def test_cli_pass_empty_plan(self):
        cmd = [
            sys.executable,
            str(VALIDATOR_SCRIPT),
            "--policy",
            str(CANONICAL_POLICY),
            "--plan",
            str(FIXTURES_DIR / "empty-plan.json"),
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"Expected CLI 0, got {res.returncode}: {res.stderr}")
        self.assertIn("PASS", res.stdout)

    def test_cli_deny_vm_plan(self):
        cmd = [
            sys.executable,
            str(VALIDATOR_SCRIPT),
            "--policy",
            str(CANONICAL_POLICY),
            "--plan",
            str(FIXTURES_DIR / "azure-vm.json"),
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(res.returncode, 1, f"Expected CLI 1, got {res.returncode}: {res.stdout}")
        self.assertIn("FAILED", res.stderr)
        self.assertIn(CODE_DEPLOYMENT_NOT_AUTHORIZED, res.stderr)

    def test_cli_error_malformed_plan(self):
        cmd = [
            sys.executable,
            str(VALIDATOR_SCRIPT),
            "--policy",
            str(CANONICAL_POLICY),
            "--plan",
            str(FIXTURES_DIR / "malformed-plan.json"),
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(res.returncode, 2, f"Expected CLI 2, got {res.returncode}: {res.stderr}")
        self.assertIn("ERROR", res.stderr)


if __name__ == "__main__":
    unittest.main()
