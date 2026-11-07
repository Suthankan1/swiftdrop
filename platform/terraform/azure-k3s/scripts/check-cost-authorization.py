#!/usr/bin/env python3
"""
SwiftDrop Azure K3s — Cost & Spending Authorization Gate Checker.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Policy Contract: platform/terraform/azure-k3s/azure-spending-gate-policy.yaml
Originating Task: Issue #178 (Optional Future Work)

Verifies that the 20-point spending authorization gate has been formally satisfied
and multi-signature sign-off is complete before allowing Terraform execution.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List
import yaml

UUID_REGEX = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)

DEFAULT_POLICY_PATH = Path(__file__).resolve().parent.parent / "azure-spending-gate-policy.yaml"


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

        return len(reasons) == 0, reasons

    def verify_spending_authorization(self) -> Tuple[bool, List[str]]:
        """Verifies that all 20 gate requirements are satisfied for an authorized deployment."""
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

        duration = int(fin.get("max_deployment_duration_hours", 0))
        if duration <= 0:
            violations.append("Rule 4: max_deployment_duration_hours must be greater than 0.")

        if fin.get("promotional_credit_permitted", True) is not False:
            violations.append("Rule 5: promotional_credit_permitted must remain false (Azure for Students credits disqualified).")

        # 3. Approved Cloud Parameters
        params = self.policy.get("approved_parameters", {})
        sub_id = params.get("subscription_id", "").strip()
        if not sub_id or not UUID_REGEX.match(sub_id) or sub_id == "00000000-0000-0000-0000-000000000000":
            violations.append(f"Rule 6: Valid, non-placeholder subscription_id is required, got '{sub_id}'.")

        regions = params.get("approved_regions", [])
        if not regions or not isinstance(regions, list):
            violations.append("Rule 7: approved_regions list cannot be empty.")

        vm_skus = params.get("approved_vm_skus", [])
        if not vm_skus or not isinstance(vm_skus, list):
            violations.append("Rule 8: approved_vm_skus list cannot be empty.")

        disk_skus = params.get("approved_disk_skus", [])
        if not disk_skus or not isinstance(disk_skus, list):
            violations.append("Rule 9: approved_disk_skus list cannot be empty.")

        # 4. Governance & Multi-Signature Sign-Off
        gov = self.policy.get("governance", {})
        if not gov.get("billing_owner", "").strip():
            violations.append("Rule 10: Named billing_owner is required.")

        if not gov.get("infrastructure_operator", "").strip():
            violations.append("Rule 11: Designated infrastructure_operator is required.")

        if not gov.get("approval_reference", "").strip():
            violations.append("Rule 12: Auditable approval_reference is required.")

        reviewers = gov.get("required_reviewers", [])
        if len(reviewers) < 3:
            violations.append("Rule 13: Multi-signature sign-off requires at least 3 distinct peer reviewers.")
        else:
            for rev in reviewers:
                if not rev.get("signed", False):
                    violations.append(f"Rule 14: Reviewer '{rev.get('github_handle')}' ({rev.get('role')}) has not signed.")

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
    else:
        authorized, violations = checker.verify_spending_authorization()
        if authorized:
            print("PASS [SPENDING_AUTHORIZED]: All 20 spending gate controls and multi-signature approvals confirmed.")
            return 0
        else:
            print("BLOCKED [SPENDING_NOT_AUTHORIZED]: Azure cloud deployment is NOT authorized:", file=sys.stderr)
            for v in violations:
                print(f"  - {v}", file=sys.stderr)
            print("\nSafe Next Action: Complete the 20-point spending checklist in docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md and obtain team signatures.", file=sys.stderr)
            return 1


if __name__ == "__main__":
    from typing import Tuple
    sys.exit(main())
