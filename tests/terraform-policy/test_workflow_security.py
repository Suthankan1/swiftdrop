"""
Static workflow and CI security checks for SwiftDrop GitHub Actions and IaC policies.

Canonical Architecture Reference:
- docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
- platform/security/azure-allowlist-policy.yaml
- GitHub Issue #173 (N10-ZC02) Acceptance Criterion 5
"""

import re
import unittest
from pathlib import Path
from typing import Any, Dict, List, Tuple
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = REPO_ROOT / ".github/workflows"

# 40-character hex SHA regex for pinned GitHub Actions
SHA_PIN_REGEX = re.compile(r"^[a-zA-Z0-9_\-\./]+@[0-9a-fA-F]{40}(?:\s+#.*)?$")

# Prohibited shell command patterns in CI workflows
PROHIBITED_RUN_PATTERNS = [
    (re.compile(r"\bterraform\s+apply\b", re.IGNORECASE), "Prohibited 'terraform apply' execution path"),
    (re.compile(r"\bterraform\s+destroy\b", re.IGNORECASE), "Prohibited 'terraform destroy' execution path"),
    (re.compile(r"\baz\s+login\b", re.IGNORECASE), "Prohibited 'az login' cloud authentication"),
    (re.compile(r"\bazure-cli\b.*\blogin\b", re.IGNORECASE), "Prohibited Azure CLI authentication"),
    (re.compile(r"\bterraform\.tfstate\b", re.IGNORECASE), "Prohibited direct state file manipulation"),
]


def inspect_workflow_dict(workflow_name: str, wf_data: Dict[str, Any]) -> List[str]:
    """Inspects a parsed workflow dictionary and returns a list of security violations."""
    violations: List[str] = []

    # 1. Check Triggers: prohibit insecure pull_request_target
    triggers = wf_data.get("on") or wf_data.get(True) or {}
    if isinstance(triggers, dict):
        if "pull_request_target" in triggers:
            violations.append(f"{workflow_name}: Uses insecure trigger 'pull_request_target'")
    elif isinstance(triggers, list) and "pull_request_target" in triggers:
        violations.append(f"{workflow_name}: Uses insecure trigger 'pull_request_target'")

    # 2. Check Permissions: prohibit write-all, id-token: write on untrusted workflows
    top_permissions = wf_data.get("permissions")
    if top_permissions == "write-all":
        violations.append(f"{workflow_name}: Uses overly permissive 'permissions: write-all'")
    elif isinstance(top_permissions, dict):
        if top_permissions.get("id-token") == "write":
            violations.append(f"{workflow_name}: Grants top-level 'id-token: write' (Issue #174 boundary violation)")

    # 3. Inspect Jobs
    jobs = wf_data.get("jobs", {})
    if not isinstance(jobs, dict):
        return violations

    for job_id, job_data in jobs.items():
        if not isinstance(job_data, dict):
            continue

        job_perms = job_data.get("permissions")
        if job_perms == "write-all":
            violations.append(f"{workflow_name} (job {job_id}): Uses overly permissive 'permissions: write-all'")
        elif isinstance(job_perms, dict):
            if job_perms.get("id-token") == "write":
                violations.append(f"{workflow_name} (job {job_id}): Grants 'id-token: write'")

        steps = job_data.get("steps", [])
        if not isinstance(steps, list):
            continue

        for idx, step in enumerate(steps):
            if not isinstance(step, dict):
                continue
            step_name = step.get("name", f"step-{idx}")

            # Check action pinning
            uses = step.get("uses")
            if uses:
                if not uses.startswith("./") and not uses.startswith("docker://"):
                    if not SHA_PIN_REGEX.match(uses.strip()):
                        violations.append(
                            f"{workflow_name} (job {job_id}, {step_name}): Action not pinned to full commit SHA: '{uses}'"
                        )

            # Check run commands for forbidden execution paths
            run_cmd = step.get("run")
            if run_cmd and isinstance(run_cmd, str):
                for pat, desc in PROHIBITED_RUN_PATTERNS:
                    if pat.search(run_cmd):
                        violations.append(
                            f"{workflow_name} (job {job_id}, {step_name}): {desc}"
                        )

            # Check upload-artifact for state or plan leakage
            if uses and "actions/upload-artifact" in uses:
                step_with = step.get("with", {}) or {}
                path = step_with.get("path", "")
                if any(ext in path for ext in [".tfstate", ".tfplan", "terraform.tfstate"]):
                    violations.append(
                        f"{workflow_name} (job {job_id}, {step_name}): Attempts to upload state/plan artifact: '{path}'"
                    )

    return violations


class TestWorkflowSecurity(unittest.TestCase):
    """Verifies all GitHub Actions workflows satisfy least privilege, immutability, and zero cloud mutations."""

    def test_existing_workflows_security(self):
        self.assertTrue(WORKFLOWS_DIR.exists(), f"Workflows directory missing: {WORKFLOWS_DIR}")
        workflow_files = list(WORKFLOWS_DIR.glob("*.yml")) + list(WORKFLOWS_DIR.glob("*.yaml"))
        self.assertGreater(len(workflow_files), 0, "No workflow files discovered in .github/workflows/")

        all_violations: List[str] = []
        for wf_file in workflow_files:
            with open(wf_file, "r", encoding="utf-8") as f:
                data = yaml.safe_load(f)
            self.assertIsInstance(data, dict, f"Malformed workflow file: {wf_file.name}")
            violations = inspect_workflow_dict(wf_file.name, data)
            all_violations.extend(violations)

        self.assertEqual(
            len(all_violations),
            0,
            f"Active GitHub Actions workflows violated security policies:\n" + "\n".join(all_violations),
        )

    # Negative control tests verifying the scanner detects insecure patterns
    def test_negative_reject_unpinned_action(self):
        insecure_wf = {
            "name": "Insecure Action Test",
            "on": ["push"],
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [
                        {"name": "Unpinned Checkout", "uses": "actions/checkout@v4"}
                    ]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("Action not pinned to full commit SHA" in v for v in violations))

    def test_negative_reject_pull_request_target(self):
        insecure_wf = {
            "name": "Insecure Trigger Test",
            "on": {"pull_request_target": {"branches": ["main"]}},
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [{"name": "Step", "run": "echo test"}]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("pull_request_target" in v for v in violations))

    def test_negative_reject_id_token_write(self):
        insecure_wf = {
            "name": "Insecure Permissions Test",
            "permissions": {"id-token": "write"},
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [{"name": "Step", "run": "echo test"}]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("id-token: write" in v for v in violations))

    def test_negative_reject_terraform_apply(self):
        insecure_wf = {
            "name": "Insecure Apply Test",
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [
                        {"name": "Apply", "run": "terraform apply -auto-approve tfplan"}
                    ]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("terraform apply" in v for v in violations))

    def test_negative_reject_terraform_destroy(self):
        insecure_wf = {
            "name": "Insecure Destroy Test",
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [
                        {"name": "Destroy", "run": "terraform destroy -auto-approve"}
                    ]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("terraform destroy" in v for v in violations))

    def test_negative_reject_az_login(self):
        insecure_wf = {
            "name": "Insecure Azure Login Test",
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [
                        {"name": "Login", "run": "az login --service-principal -u $ID -p $PASS"}
                    ]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("az login" in v for v in violations))

    def test_negative_reject_state_artifact_upload(self):
        insecure_wf = {
            "name": "Insecure State Upload Test",
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [
                        {
                            "name": "Upload State",
                            "uses": "actions/upload-artifact@4cec3d8aa04e39d1a68397de0c4cd6fb9dce8ec1 # v4.6.1",
                            "with": {"path": "platform/terraform/terraform.tfstate"}
                        }
                    ]
                }
            }
        }
        violations = inspect_workflow_dict("insecure.yml", insecure_wf)
        self.assertTrue(any("upload state/plan artifact" in v for v in violations))


if __name__ == "__main__":
    unittest.main()
