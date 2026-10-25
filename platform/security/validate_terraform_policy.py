#!/usr/bin/env python3
"""
SwiftDrop Deny-by-Default Terraform Cost & Security Policy Validator.

Canonical References:
- docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
- platform/security/azure-allowlist-policy.yaml
- GitHub Issue #173 (N10-ZC02)

Enforces strict $0 hosting invariants by evaluating Terraform JSON plans against
the machine-readable Azure allowlist policy contract.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple
import yaml


# Stable Policy Error Codes
CODE_PASS = "POLICY_PASS"
CODE_DEPLOYMENT_NOT_AUTHORIZED = "DENY_AZURE_DEPLOYMENT_NOT_AUTHORIZED"
CODE_RESOURCE_NOT_APPROVED = "DENY_AZURE_RESOURCE_NOT_APPROVED"
CODE_UNKNOWN_RESOURCE_TYPE = "DENY_UNKNOWN_RESOURCE_TYPE"
CODE_UNKNOWN_REGION = "DENY_UNKNOWN_REGION"
CODE_UNKNOWN_SKU = "DENY_UNKNOWN_SKU"
CODE_UNAPPROVED_SKU = "DENY_UNAPPROVED_SKU"
CODE_UNAPPROVED_REGION = "DENY_UNAPPROVED_REGION"
CODE_PROMOTIONAL_CREDIT_DISQUALIFIED = "DENY_PROMOTIONAL_CREDIT_DISQUALIFIED"
CODE_UNAPPROVED_SUBSCRIPTION = "DENY_UNAPPROVED_SUBSCRIPTION"
CODE_BACKEND_STORAGE_NOT_APPROVED = "DENY_BACKEND_AZURE_STORAGE_NOT_APPROVED"
CODE_MISSING_PRICING_EVIDENCE = "DENY_MISSING_PRICING_EVIDENCE"
CODE_EVIDENCE_EXPIRED = "DENY_EVIDENCE_EXPIRED"
CODE_NONZERO_METER = "DENY_NONZERO_METER"
CODE_COST_CEILING_EXCEEDED = "DENY_COST_CEILING_EXCEEDED"
CODE_MALFORMED_PLAN = "ERROR_PLAN_FILE_INVALID"
CODE_MISSING_PLAN = "ERROR_PLAN_FILE_MISSING"
CODE_MALFORMED_POLICY = "ERROR_POLICY_FILE_INVALID"
CODE_MISSING_POLICY = "ERROR_POLICY_FILE_MISSING"


@dataclass
class PolicyViolation:
    code: str
    address: str
    resource_type: str
    rule_violated: str
    reason: str
    safe_next_action: str

    def to_dict(self) -> Dict[str, str]:
        return {
            "code": self.code,
            "address": self.address,
            "resource_type": self.resource_type,
            "rule_violated": self.rule_violated,
            "reason": self.reason,
            "safe_next_action": self.safe_next_action,
        }

    def format_log(self) -> str:
        return (
            f"DENY [{self.code}]\n"
            f"  Resource Address : {self.address}\n"
            f"  Resource Type    : {self.resource_type}\n"
            f"  Rule Violated    : {self.rule_violated}\n"
            f"  Reason           : {self.reason}\n"
            f"  Safe Next Action : {self.safe_next_action}"
        )


@dataclass
class ValidationResult:
    passed: bool
    status_code: str
    message: str
    violations: List[PolicyViolation] = field(default_factory=list)
    resource_count: int = 0
    scanned_addresses: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "passed": self.passed,
            "status_code": self.status_code,
            "message": self.message,
            "resource_count": self.resource_count,
            "scanned_addresses": self.scanned_addresses,
            "violations": [v.to_dict() for v in self.violations],
        }


class PolicyValidator:
    """Enforces strict $0 and deny-by-default cloud policy against Terraform plan JSON."""

    def __init__(self, policy_path: Path | str):
        self.policy_path = Path(policy_path)
        self.policy_data: Dict[str, Any] = {}
        self.load_policy()

    def load_policy(self) -> None:
        if not self.policy_path.exists():
            raise FileNotFoundError(f"Policy file not found: {self.policy_path}")
        try:
            with open(self.policy_path, "r", encoding="utf-8") as f:
                data = yaml.safe_load(f)
            if not isinstance(data, dict):
                raise ValueError("Policy YAML root must be a mapping object.")
            self.policy_data = data
            self._validate_policy_schema()
        except yaml.YAMLError as e:
            raise ValueError(f"Malformed policy YAML: {e}") from e

    def _validate_policy_schema(self) -> None:
        required_top = ["version", "policy_mode", "cost_ceiling_usd", "azure"]
        for key in required_top:
            if key not in self.policy_data:
                raise ValueError(f"Policy missing required top-level key: {key}")

        azure = self.policy_data.get("azure")
        if not isinstance(azure, dict):
            raise ValueError("Policy 'azure' block must be a mapping.")

        required_azure = [
            "deployment_authorized",
            "subscription_status",
            "promotional_credit_permitted",
            "billing_overage_permitted",
            "allowed_resources",
            "allowed_skus",
            "allowed_regions",
        ]
        for key in required_azure:
            if key not in azure:
                raise ValueError(f"Policy 'azure' block missing required key: {key}")

    @property
    def cost_ceiling_usd(self) -> float:
        return float(self.policy_data.get("cost_ceiling_usd", 0.0))

    @property
    def is_deployment_authorized(self) -> bool:
        return bool(self.policy_data.get("azure", {}).get("deployment_authorized", False))

    @property
    def subscription_status(self) -> str:
        return str(self.policy_data.get("azure", {}).get("subscription_status", "UNVERIFIED"))

    @property
    def promotional_credit_permitted(self) -> bool:
        return bool(self.policy_data.get("azure", {}).get("promotional_credit_permitted", False))

    @property
    def allowed_resources(self) -> Set[str]:
        res = self.policy_data.get("azure", {}).get("allowed_resources", [])
        return set(res if isinstance(res, list) else [])

    @property
    def allowed_skus(self) -> Dict[str, List[str]]:
        skus = self.policy_data.get("azure", {}).get("allowed_skus", {})
        return skus if isinstance(skus, dict) else {}

    @property
    def allowed_regions(self) -> Set[str]:
        regions = self.policy_data.get("azure", {}).get("allowed_regions", [])
        return set(regions if isinstance(regions, list) else [])

    def validate_plan_file(self, plan_path: Path | str) -> ValidationResult:
        plan_p = Path(plan_path)
        if not plan_p.exists():
            return ValidationResult(
                passed=False,
                status_code=CODE_MISSING_PLAN,
                message=f"Plan file not found: {plan_p}",
            )
        try:
            with open(plan_p, "r", encoding="utf-8") as f:
                plan_json = json.load(f)
            return self.validate_plan_dict(plan_json)
        except json.JSONDecodeError as e:
            return ValidationResult(
                passed=False,
                status_code=CODE_MALFORMED_PLAN,
                message=f"Plan JSON malformed: {e}",
            )

    def validate_plan_dict(self, plan: Any) -> ValidationResult:
        if not isinstance(plan, dict):
            return ValidationResult(
                passed=False,
                status_code=CODE_MALFORMED_PLAN,
                message="Plan root must be a JSON object.",
            )

        # Baseline cost ceiling check
        if self.cost_ceiling_usd > 0.0:
            return ValidationResult(
                passed=False,
                status_code=CODE_COST_CEILING_EXCEEDED,
                message=f"Policy cost ceiling ({self.cost_ceiling_usd}) exceeds strict zero-cost $0.00.",
            )

        violations: List[PolicyViolation] = []
        scanned_addresses: List[str] = []

        # 1. Inspect Backend Configuration
        backend_violations = self._inspect_backend_config(plan)
        violations.extend(backend_violations)

        # 2. Extract and inspect resources from resource_changes, planned_values, and configuration
        discovered_resources = self._discover_all_resources(plan)

        for res in discovered_resources:
            scanned_addresses.append(res["address"])
            res_violations = self._evaluate_resource(res)
            violations.extend(res_violations)

        # Remove duplicate violations for the same address + code
        unique_violations: List[PolicyViolation] = []
        seen = set()
        for v in violations:
            key = (v.address, v.code)
            if key not in seen:
                seen.add(key)
                unique_violations.append(v)

        passed = len(unique_violations) == 0
        status_code = CODE_PASS if passed else unique_violations[0].code
        message = (
            f"Validation passed: {len(discovered_resources)} resource(s) scanned, 0 violations."
            if passed
            else f"Validation failed with {len(unique_violations)} violation(s)."
        )

        return ValidationResult(
            passed=passed,
            status_code=status_code,
            message=message,
            violations=unique_violations,
            resource_count=len(discovered_resources),
            scanned_addresses=scanned_addresses,
        )

    def _inspect_backend_config(self, plan: Dict[str, Any]) -> List[PolicyViolation]:
        violations: List[PolicyViolation] = []
        config = plan.get("configuration", {})
        backend = config.get("backend", {}) or {}
        backend_type = backend.get("type", "")

        # Azure Storage backend for remote state creates billable storage accounts / transactions
        if "azurerm" in backend_type.lower():
            violations.append(
                PolicyViolation(
                    code=CODE_BACKEND_STORAGE_NOT_APPROVED,
                    address="terraform.backend.azurerm",
                    resource_type="backend_azurerm",
                    rule_violated="ADR-0011 Section 2 / Issue #174: Azure Storage state backend prohibited.",
                    reason="Terraform configuration specifies Azure Storage backend ('azurerm'), which incurs state transactions and storage costs.",
                    safe_next_action="Use local/ephemeral backend or wait for Issue #174 reviewed non-billable state boundary.",
                )
            )
        return violations

    def _discover_all_resources(self, plan: Dict[str, Any]) -> List[Dict[str, Any]]:
        """Discovers resources across resource_changes, planned_values, and configuration."""
        resources_by_address: Dict[str, Dict[str, Any]] = {}

        # Source A: resource_changes (primary execution plan)
        for rc in plan.get("resource_changes", []):
            if not isinstance(rc, dict):
                continue
            addr = rc.get("address", "")
            r_type = rc.get("type", "")
            mode = rc.get("mode", "managed")
            provider_name = rc.get("provider_name", "")
            change = rc.get("change", {}) or {}
            actions = change.get("actions", [])
            after = change.get("after") or {}
            after_unknown = change.get("after_unknown") or {}

            # Don't skip no-op if resource exists in cloud
            resources_by_address[addr] = {
                "address": addr,
                "type": r_type,
                "mode": mode,
                "provider_name": provider_name,
                "actions": actions,
                "values": after if isinstance(after, dict) else {},
                "after_unknown": after_unknown if isinstance(after_unknown, dict) else {},
                "source": "resource_changes",
            }

        # Source B: planned_values (catches nested child modules recursively)
        pv = plan.get("planned_values", {})
        if isinstance(pv, dict):
            self._extract_from_module(pv.get("root_module", {}), resources_by_address, source="planned_values")

        # Source C: configuration (catches module declarations and static definitions)
        cfg = plan.get("configuration", {})
        if isinstance(cfg, dict):
            self._extract_from_config_module(cfg.get("root_module", {}), resources_by_address, prefix="")

        return list(resources_by_address.values())

    def _extract_from_module(
        self,
        module_data: Dict[str, Any],
        resources: Dict[str, Dict[str, Any]],
        source: str,
    ) -> None:
        if not isinstance(module_data, dict):
            return

        for r in module_data.get("resources", []):
            if not isinstance(r, dict):
                continue
            addr = r.get("address", "")
            if not addr:
                continue
            if addr not in resources:
                resources[addr] = {
                    "address": addr,
                    "type": r.get("type", ""),
                    "mode": r.get("mode", "managed"),
                    "provider_name": r.get("provider_name", ""),
                    "actions": ["create"],
                    "values": r.get("values", {}) or {},
                    "after_unknown": {},
                    "source": source,
                }

        # Recursively visit child modules
        for child in module_data.get("child_modules", []):
            self._extract_from_module(child, resources, source=source)

    def _extract_from_config_module(
        self,
        cfg_module: Dict[str, Any],
        resources: Dict[str, Dict[str, Any]],
        prefix: str,
    ) -> None:
        if not isinstance(cfg_module, dict):
            return

        for r in cfg_module.get("resources", []):
            if not isinstance(r, dict):
                continue
            r_type = r.get("type", "")
            r_name = r.get("name", "")
            full_addr = f"{prefix}{r_type}.{r_name}" if prefix else f"{r_type}.{r_name}"
            if full_addr not in resources:
                provider_config_key = r.get("provider_config_key", "")
                resources[full_addr] = {
                    "address": full_addr,
                    "type": r_type,
                    "mode": r.get("mode", "managed"),
                    "provider_name": provider_config_key,
                    "actions": ["create"],
                    "values": r.get("expressions", {}) or {},
                    "after_unknown": {},
                    "source": "configuration",
                }

        module_calls = cfg_module.get("module_calls", {})
        if isinstance(module_calls, dict):
            for mod_name, mod_body in module_calls.items():
                if isinstance(mod_body, dict):
                    child_prefix = f"{prefix}module.{mod_name}." if prefix else f"module.{mod_name}."
                    child_mod = mod_body.get("module", {})
                    if isinstance(child_mod, dict):
                        self._extract_from_config_module(child_mod, resources, prefix=child_prefix)

    def _is_azure_resource(self, res: Dict[str, Any]) -> bool:
        r_type = res.get("type", "")
        provider = res.get("provider_name", "")
        address = res.get("address", "")

        if r_type.startswith("azurerm_"):
            return True
        if "azurerm" in provider.lower():
            return True
        if "registry.terraform.io/hashicorp/azurerm" in provider:
            return True
        return False

    def _evaluate_resource(self, res: Dict[str, Any]) -> List[PolicyViolation]:
        violations: List[PolicyViolation] = []
        r_type = res["type"]
        address = res["address"]
        mode = res.get("mode", "managed")
        values = res.get("values", {})
        after_unknown = res.get("after_unknown", {})

        # We evaluate managed cloud resources and data sources tied to Azure
        if not self._is_azure_resource(res):
            # Non-Azure resource (e.g., local_file, random_id, null_resource)
            return violations

        # Violation 1: Deployment Authorization Gate
        if not self.is_deployment_authorized:
            violations.append(
                PolicyViolation(
                    code=CODE_DEPLOYMENT_NOT_AUTHORIZED,
                    address=address,
                    resource_type=r_type,
                    rule_violated="ADR-0011 Section 1 / Issue #172: azure.deployment_authorized == false.",
                    reason=(
                        f"Azure managed resource proposed, but cloud deployment authorization is false. "
                        f"Strict $0 hosting requires local k3d/K3s deployment."
                    ),
                    safe_next_action="Remove cloud resource declarations. Deploy to local k3d/K3s environment.",
                )
            )

        # Violation 2: Subscription Verification Check
        if self.subscription_status != "VERIFIED":
            violations.append(
                PolicyViolation(
                    code=CODE_UNAPPROVED_SUBSCRIPTION,
                    address=address,
                    resource_type=r_type,
                    rule_violated="azure.subscription_status must be 'VERIFIED'.",
                    reason=(
                        f"Azure subscription status is '{self.subscription_status}'. "
                        f"Unverified or unapproved subscriptions are strictly prohibited."
                    ),
                    safe_next_action="Authenticate only verified academic subscriptions after spending authorization is granted.",
                )
            )

        # Violation 3: Promotional Credit Permitted Check
        if not self.promotional_credit_permitted:
            credit_indicators = ["credit", "promotional", "trial", "students_credit"]

            def contains_credit_claim(obj: Any) -> bool:
                if isinstance(obj, str):
                    return any(ind in obj.lower() for ind in credit_indicators)
                elif isinstance(obj, dict):
                    return any(contains_credit_claim(k) or contains_credit_claim(v) for k, v in obj.items())
                elif isinstance(obj, list):
                    return any(contains_credit_claim(item) for item in obj)
                return False

            if contains_credit_claim(values):
                violations.append(
                    PolicyViolation(
                        code=CODE_PROMOTIONAL_CREDIT_DISQUALIFIED,
                        address=address,
                        resource_type=r_type,
                        rule_violated="ADR-0011 Section 3: Promotional credits disqualified.",
                        reason="Resource relies on promotional credit/trial offset which does not qualify as non-billable service.",
                        safe_next_action="Use genuine $0 non-billable local tier.",
                    )
                )

        # Violation 4: Resource Allowlist
        if r_type not in self.allowed_resources:
            violations.append(
                PolicyViolation(
                    code=CODE_RESOURCE_NOT_APPROVED,
                    address=address,
                    resource_type=r_type,
                    rule_violated="azure.allowed_resources allowlist check.",
                    reason=(
                        f"Resource type '{r_type}' is not present in approved strict-$0 allowlist "
                        f"(current allowed count: {len(self.allowed_resources)})."
                    ),
                    safe_next_action="Remove unapproved resource from Terraform plan.",
                )
            )

        # Violation 5: Region / Location Validation
        location = values.get("location")
        if after_unknown.get("location"):
            violations.append(
                PolicyViolation(
                    code=CODE_UNKNOWN_REGION,
                    address=address,
                    resource_type=r_type,
                    rule_violated="Deterministic region validation.",
                    reason="Resource location is unknown at plan time (after_unknown.location is true).",
                    safe_next_action="Specify a deterministic, approved Azure region.",
                )
            )
        elif location:
            # If allowed_regions is configured and non-empty, check membership
            if self.allowed_regions and location not in self.allowed_regions:
                violations.append(
                    PolicyViolation(
                        code=CODE_UNAPPROVED_REGION,
                        address=address,
                        resource_type=r_type,
                        rule_violated="azure.allowed_regions check.",
                        reason=f"Region '{location}' is not in approved allowed_regions: {sorted(self.allowed_regions)}.",
                        safe_next_action="Select an approved region from the policy allowlist.",
                    )
                )

        # Violation 6: SKU / Tier Validation
        # Check standard SKU attribute names across Azure providers
        sku_val = (
            values.get("sku_name")
            or values.get("sku")
            or values.get("tier")
            or values.get("vm_size")
            or values.get("size")
        )
        if any(after_unknown.get(k) for k in ["sku_name", "sku", "tier", "vm_size", "size"]):
            violations.append(
                PolicyViolation(
                    code=CODE_UNKNOWN_SKU,
                    address=address,
                    resource_type=r_type,
                    rule_violated="Deterministic SKU validation.",
                    reason="Resource SKU or VM size is unknown at plan time.",
                    safe_next_action="Specify an explicit, approved non-billable SKU.",
                )
            )
        elif sku_val and r_type in self.allowed_skus:
            approved_skus = self.allowed_skus[r_type]
            if sku_val not in approved_skus:
                violations.append(
                    PolicyViolation(
                        code=CODE_UNAPPROVED_SKU,
                        address=address,
                        resource_type=r_type,
                        rule_violated="azure.allowed_skus check.",
                        reason=f"SKU '{sku_val}' is not in approved SKUs for '{r_type}': {approved_skus}.",
                        safe_next_action="Configure an approved SKU from the allowlist contract.",
                    )
                )

        # Violation 7: Evidence / Expiry Metadata Validation
        # Check custom metadata tags or attributes if provided
        tags = values.get("tags", {})
        if isinstance(tags, dict):
            # Check for expired pricing evidence
            expiry_str = tags.get("pricing_evidence_expiry")
            if expiry_str:
                try:
                    exp_date = datetime.date.fromisoformat(expiry_str)
                    if exp_date < datetime.date.today():
                        violations.append(
                            PolicyViolation(
                                code=CODE_EVIDENCE_EXPIRED,
                                address=address,
                                resource_type=r_type,
                                rule_violated="Pricing evidence validity window.",
                                reason=f"Pricing evidence expired on {expiry_str}.",
                                safe_next_action="Renew and verify zero-cost pricing evidence before re-evaluating.",
                            )
                        )
                except ValueError:
                    violations.append(
                        PolicyViolation(
                            code=CODE_EVIDENCE_EXPIRED,
                            address=address,
                            resource_type=r_type,
                            rule_violated="Pricing evidence validity format.",
                            reason=f"Malformed pricing evidence date: '{expiry_str}'.",
                            safe_next_action="Provide ISO-8601 date (YYYY-MM-DD).",
                        )
                    )

            # Check if pricing evidence explicitly claims nonzero meter
            if tags.get("pricing_meter_type") == "metered":
                violations.append(
                    PolicyViolation(
                        code=CODE_NONZERO_METER,
                        address=address,
                        resource_type=r_type,
                        rule_violated="Zero-cost non-metered requirement.",
                        reason="Resource specifies metered billing, which incurs variable overage charges.",
                        safe_next_action="Use strictly unmetered $0 services only.",
                    )
                )

        return violations


def validate_plan(policy_file: str | Path, plan_file: str | Path) -> ValidationResult:
    """Convenience helper function for programmatic testing."""
    validator = PolicyValidator(policy_file)
    return validator.validate_plan_file(plan_file)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="SwiftDrop Deny-by-Default Terraform Cost & Security Policy Validator."
    )
    parser.add_argument(
        "--policy",
        required=True,
        help="Path to canonical Azure allowlist YAML (e.g. platform/security/azure-allowlist-policy.yaml).",
    )
    parser.add_argument(
        "--plan",
        required=True,
        help="Path to Terraform JSON plan representation (or '-' for stdin).",
    )
    parser.add_argument(
        "--output-json",
        default=None,
        help="Optional path to write structured validation report JSON.",
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress non-error diagnostics.",
    )

    args = parser.parse_args()

    # Step 1: Load and validate policy
    try:
        validator = PolicyValidator(args.policy)
    except FileNotFoundError as e:
        print(f"ERROR [{CODE_MISSING_POLICY}]: {e}", file=sys.stderr)
        return 2
    except ValueError as e:
        print(f"ERROR [{CODE_MALFORMED_POLICY}]: {e}", file=sys.stderr)
        return 2

    # Step 2: Read plan
    plan_path = args.plan
    if plan_path == "-":
        try:
            plan_json = json.load(sys.stdin)
            result = validator.validate_plan_dict(plan_json)
        except json.JSONDecodeError as e:
            print(f"ERROR [{CODE_MALFORMED_PLAN}]: stdin JSON malformed: {e}", file=sys.stderr)
            return 2
    else:
        result = validator.validate_plan_file(plan_path)
        if result.status_code in (CODE_MISSING_PLAN, CODE_MALFORMED_PLAN):
            print(f"ERROR [{result.status_code}]: {result.message}", file=sys.stderr)
            return 2

    # Step 3: Write report if requested
    if args.output_json:
        out_p = Path(args.output_json)
        out_p.parent.mkdir(parents=True, exist_ok=True)
        with open(out_p, "w", encoding="utf-8") as f:
            json.dump(result.to_dict(), f, indent=2)

    # Step 4: Output diagnostics
    if result.passed:
        if not args.quiet:
            print(f"PASS [{result.status_code}]: {result.message}")
        return 0
    else:
        print(f"FAILED: {result.message}", file=sys.stderr)
        for v in result.violations:
            print("--------------------------------------------------------------------------------", file=sys.stderr)
            print(v.format_log(), file=sys.stderr)
        print("================================================================================", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
