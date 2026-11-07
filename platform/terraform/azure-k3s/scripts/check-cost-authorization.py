#!/usr/bin/env python3
"""
SwiftDrop Azure K3s — Cost & Spending Authorization Gate Checker.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Policy Contract: platform/terraform/azure-k3s/azure-spending-gate-policy.yaml
Originating Task: Issue #178 (Optional Future Work)

Verifies that the spending authorization gate has been formally satisfied,
validates operator variables against signed policy invariants, and audits
reviewed Terraform execution plans before allowing deployment.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple
import yaml

UUID_REGEX = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)

DEFAULT_POLICY_PATH = Path(__file__).resolve().parent.parent / "azure-spending-gate-policy.yaml"

MANDATORY_RESOURCE_TYPES = [
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


class SpendingGateChecker:
    def __init__(self, policy_path: Path | str):
        self.policy_path = Path(policy_path)
        self.policy: Dict[str, Any] = {}
        self.load_policy()

    def load_policy(self) -> None:
        if not self.policy_path.exists():
            raise FileNotFoundError(f"Spending policy contract not found: {self.policy_path}")
        with open(self.policy_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        if not isinstance(data, dict):
            raise ValueError("Spending policy YAML root must be a mapping.")
        self.policy = data

    def verify_gate_locked(self) -> Tuple[bool, List[str]]:
        """Verifies that the gate is actively locked (used in PR CI / pre-authorization checks)."""
        reasons = []
        is_authorized = self.policy.get("spending_authorized", True)
        if is_authorized is not False:
            reasons.append(f"Gate is NOT locked: spending_authorized is {is_authorized} (expected false).")

        status = self.policy.get("status", "")
        if "BLOCKED" not in status:
            reasons.append(f"Gate status does not indicate blocked: '{status}'.")

        fin = self.policy.get("financial_controls", {})
        budget = float(fin.get("approved_budget_usd", 1.0))
        if budget != 0.0:
            reasons.append(f"Approved budget is non-zero in locked gate: ${budget}.")

        ceiling = float(fin.get("cost_ceiling_usd", 1.0))
        if ceiling != 0.0:
            reasons.append(f"Cost ceiling is non-zero in locked gate: ${ceiling}.")

        return len(reasons) == 0, reasons

    def verify_spending_authorization(self) -> Tuple[bool, List[str]]:
        """Verifies that all gate requirements are satisfied for an authorized deployment."""
        violations = []

        # 1. Top-level status & flag
        if not self.policy.get("spending_authorized", False):
            violations.append("Rule 1: spending_authorized is false. Human financial approval required.")

        status = self.policy.get("status", "")
        if status != "AUTHORIZED_BY_HUMAN_GOVERNANCE":
            violations.append(f"Rule 2: Policy status is '{status}', expected 'AUTHORIZED_BY_HUMAN_GOVERNANCE'.")

        # 2. Financial Controls
        fin = self.policy.get("financial_controls", {})
        budget = float(fin.get("approved_budget_usd", 0.0))
        if budget <= 0.0:
            violations.append("Rule 3: approved_budget_usd must be greater than $0.00.")

        ceiling = float(fin.get("cost_ceiling_usd", 0.0))
        if ceiling <= 0.0 or ceiling < budget:
            violations.append(f"Rule 4: cost_ceiling_usd (${ceiling}) must be >= approved_budget_usd (${budget}).")

        duration = int(fin.get("max_deployment_duration_hours", 0))
        if duration <= 0:
            violations.append("Rule 5: max_deployment_duration_hours must be greater than 0.")

        if fin.get("promotional_credit_permitted", True) is not False:
            violations.append("Rule 6: promotional_credit_permitted must remain false (promotional credits disqualified).")

        # 3. Approved Cloud Parameters
        params = self.policy.get("approved_parameters", {})
        sub_id = params.get("subscription_id", "").strip()
        if not sub_id or not UUID_REGEX.match(sub_id) or sub_id == "00000000-0000-0000-0000-000000000000":
            violations.append(f"Rule 7: Valid, non-placeholder subscription_id is required, got '{sub_id}'.")

        regions = params.get("approved_regions", [])
        if not regions or not isinstance(regions, list):
            violations.append("Rule 8: approved_regions list cannot be empty.")

        vm_skus = params.get("approved_vm_skus", [])
        if not vm_skus or not isinstance(vm_skus, list):
            violations.append("Rule 9: approved_vm_skus list cannot be empty.")

        disk_skus = params.get("approved_disk_skus", [])
        if not disk_skus or not isinstance(disk_skus, list):
            violations.append("Rule 10: approved_disk_skus list cannot be empty.")

        allowed_res = params.get("allowed_resources", [])
        if not allowed_res or not isinstance(allowed_res, list):
            violations.append("Rule 11: allowed_resources list cannot be empty.")
        else:
            for req_res in MANDATORY_RESOURCE_TYPES:
                if req_res not in allowed_res:
                    violations.append(f"Rule 11a: Missing approved resource type '{req_res}'.")

        # 4. Governance & Multi-Signature Sign-Off
        gov = self.policy.get("governance", {})
        if not gov.get("billing_owner", "").strip():
            violations.append("Rule 12: Named billing_owner is required.")

        if not gov.get("infrastructure_operator", "").strip():
            violations.append("Rule 13: Designated infrastructure_operator is required.")

        approval_ref = gov.get("approval_reference", "").strip()
        if not approval_ref or "UNAUTHORIZED" in approval_ref or "DUMMY" in approval_ref:
            violations.append("Rule 14: Auditable, genuine approval_reference token is required.")

        if not gov.get("approval_date", "").strip():
            violations.append("Rule 15: Formal approval_date (YYYY-MM-DD) is required.")

        reviewers = gov.get("required_reviewers", [])
        if len(reviewers) < 3:
            violations.append("Rule 16: Multi-signature sign-off requires at least 3 distinct peer reviewers.")
        else:
            for rev in reviewers:
                if not rev.get("signed", False):
                    violations.append(f"Rule 17: Reviewer '{rev.get('github_handle')}' ({rev.get('role')}) has not signed.")

        return len(violations) == 0, violations

    def verify_tfvars(self, tfvars_path: Path | str) -> Tuple[bool, List[str]]:
        """Verifies operator terraform.tfvars values strictly match approved policy."""
        path = Path(tfvars_path)
        if not path.is_file():
            return False, [f"terraform.tfvars not found at {path}"]

        content = path.read_text(encoding="utf-8")
        violations = []

        def extract_var(name: str) -> str:
            m = re.search(rf'^\s*{name}\s*=\s*("([^"]*)"|(\[[^\]]*\])|([^\s#]+))', content, re.MULTILINE)
            if m:
                if m.group(2) is not None:
                    return m.group(2)
                return (m.group(3) or m.group(4) or "").strip()
            return ""

        vars_spending_confirmed = extract_var("spending_authorization_confirmed").lower() == "true"
        policy_authorized = self.policy.get("spending_authorized", False)

        if not policy_authorized and vars_spending_confirmed:
            violations.append(
                "BLOCKED: terraform.tfvars sets spending_authorization_confirmed=true, but policy azure-spending-gate-policy.yaml is locked (spending_authorized=false). Cannot bypass locked policy."
            )

        if policy_authorized:
            params = self.policy.get("approved_parameters", {})
            gov = self.policy.get("governance", {})
            fin = self.policy.get("financial_controls", {})

            # Subscription ID match
            sub_id = extract_var("subscription_id")
            expected_sub = params.get("subscription_id", "")
            if sub_id != expected_sub:
                violations.append(f"Subscription ID in tfvars ('{sub_id}') does not match approved policy ('{expected_sub}').")

            # Region match
            location = extract_var("location") or "westeurope"
            approved_regions = params.get("approved_regions", [])
            if location not in approved_regions:
                violations.append(f"Location in tfvars ('{location}') is not in policy approved_regions: {approved_regions}.")

            # VM Size match
            vm_size = extract_var("vm_size") or "Standard_B4ms"
            approved_vms = params.get("approved_vm_skus", [])
            if vm_size not in approved_vms:
                violations.append(f"VM size in tfvars ('{vm_size}') is not in policy approved_vm_skus: {approved_vms}.")

            # Data Disk Type match
            disk_type = extract_var("data_disk_type") or "StandardSSD_LRS"
            approved_disks = params.get("approved_disk_skus", [])
            if disk_type not in approved_disks:
                violations.append(f"Disk type in tfvars ('{disk_type}') is not in policy approved_disk_skus: {approved_disks}.")

            # Budget match
            try:
                budget_val = float(extract_var("approved_budget_usd") or "0")
                policy_budget = float(fin.get("approved_budget_usd", 0.0))
                if budget_val <= 0 or budget_val > policy_budget:
                    violations.append(f"Approved budget in tfvars (${budget_val}) exceeds policy budget (${policy_budget}).")
            except ValueError:
                violations.append("Invalid approved_budget_usd format in tfvars.")

            # Approval reference match
            app_ref = extract_var("approval_reference")
            expected_ref = gov.get("approval_reference", "")
            if app_ref != expected_ref:
                violations.append(f"Approval reference in tfvars ('{app_ref}') does not match policy ('{expected_ref}').")

            # SSH Key non-placeholder
            ssh_key = extract_var("operator_ssh_public_key")
            if not ssh_key or "Placeholder" in ssh_key or "Dummy" in ssh_key:
                violations.append("Operator SSH public key in tfvars is missing or contains placeholder tokens.")

        return len(violations) == 0, violations

    def verify_plan(self, plan_json_path: Path | str) -> Tuple[bool, List[str]]:
        """Verifies rendered Terraform plan JSON contains only authorized resources."""
        path = Path(plan_json_path)
        if not path.is_file():
            return False, [f"Plan JSON not found at {path}"]

        with open(path, "r", encoding="utf-8") as f:
            plan = json.load(f)

        violations = []
        changes = plan.get("resource_changes", [])
        if not changes:
            violations.append("Plan contains no resource changes.")
            return False, violations

        allowed = set(self.policy.get("approved_parameters", {}).get("allowed_resources", MANDATORY_RESOURCE_TYPES))
        planned_resources = []

        for change in changes:
            r_type = change.get("type", "")
            r_name = change.get("name", "")
            actions = change.get("change", {}).get("actions", [])

            if "create" in actions or "update" in actions:
                planned_resources.append(f"{r_type}.{r_name}")
                if r_type not in allowed:
                    violations.append(f"Plan includes unauthorized resource type '{r_type}' ({r_name})!")

        if len(planned_resources) > 10:
            violations.append(f"Plan contains {len(planned_resources)} resource changes, expected <= 10.")

        return len(violations) == 0, violations


def main() -> int:
    parser = argparse.ArgumentParser(
        description="SwiftDrop Azure K3s Cost & Spending Authorization Gate Validator."
    )
    parser.add_argument(
        "--policy",
        default=str(DEFAULT_POLICY_PATH),
        help="Path to azure-spending-gate-policy.yaml.",
    )
    parser.add_argument(
        "--verify-gate-locked",
        action="store_true",
        help="Assert that the spending gate is actively locked (deny-by-default check for CI).",
    )
    parser.add_argument(
        "--verify-vars",
        metavar="PATH",
        help="Verify operator terraform.tfvars against policy contract.",
    )
    parser.add_argument(
        "--verify-plan",
        metavar="PATH",
        help="Verify generated Terraform plan JSON against policy contract.",
    )

    args = parser.parse_args()

    try:
        checker = SpendingGateChecker(args.policy)
    except Exception as e:
        print(f"ERROR: Failed to load spending policy: {e}", file=sys.stderr)
        return 2

    if args.verify_gate_locked:
        locked, reasons = checker.verify_gate_locked()
        if locked:
            print("PASS [SPENDING_GATE_LOCKED]: Azure K3s spending gate is actively locked (spending_authorized=false, budget=$0.00).")
            return 0
        else:
            print("FAIL [SPENDING_GATE_BREACH]: Spending gate is NOT locked:", file=sys.stderr)
            for r in reasons:
                print(f"  - {r}", file=sys.stderr)
            return 1

    if args.verify_vars:
        vars_ok, vars_errs = checker.verify_tfvars(args.verify_vars)
        if not vars_ok:
            print("FAIL [TFVARS_MISMATCH]: terraform.tfvars violates spending authorization policy:", file=sys.stderr)
            for err in vars_errs:
                print(f"  - {err}", file=sys.stderr)
            return 1
        print("PASS [TFVARS_VERIFIED]: terraform.tfvars matches signed spending policy.")

    if args.verify_plan:
        plan_ok, plan_errs = checker.verify_plan(args.verify_plan)
        if not plan_ok:
            print("FAIL [PLAN_VIOLATION]: Terraform plan violates spending authorization policy:", file=sys.stderr)
            for err in plan_errs:
                print(f"  - {err}", file=sys.stderr)
            return 1
        print("PASS [PLAN_VERIFIED]: Terraform plan contains only approved 10 Azure resources.")

    authorized, violations = checker.verify_spending_authorization()
    if authorized:
        print("PASS [SPENDING_AUTHORIZED]: All spending gate controls, parameters, and multi-signature approvals confirmed.")
        print("NOTE: Terraform does NOT monitor live billing at runtime. Runtime expenditure containment relies on Azure Cost Management budget alerts and prompt deallocation.")
        return 0
    else:
        print("BLOCKED [SPENDING_NOT_AUTHORIZED]: Azure cloud deployment is NOT authorized:", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        print("\nSafe Next Action: Complete the spending checklist in docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md and obtain 3 peer signatures.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
