"""
SwiftDrop CI/CD Performance, Parallelization & Security Architecture Regression Tests
Ensures pipeline structural invariants, dependency graphs, fail-closed gates,
concurrency semantics, and failure-injection handling.
"""

import json
import shutil
import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
CI_WORKFLOW = WORKFLOWS_DIR / "ci.yml"
SECURITY_WORKFLOW = WORKFLOWS_DIR / "security-scans.yml"
CONTRACTS_WORKFLOW = WORKFLOWS_DIR / "contract-checks.yml"

EXPECTED_SERVICES = [
    "api-gateway",
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
]

EXPECTED_CONTAINER_TARGETS = [
    "frontend",
    "api-gateway",
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
    "keycloak",
]

EXPECTED_REQUIRED_GATE_JOBS = [
    "frontend-quality",
    "java-shared-libraries",
    "java-service-tests",
    "identity-tests",
    "terraform-policy",
    "kubernetes-cluster-policy",
    "container-images-validation",
    "platform-compose-validation",
    "secret-scanning",
    "dependency-scanning",
    "contract-validation",
    "container-security",
    "workflow-regression-tests",
]


def evaluate_gate_policy(needs_dict, event_name, ref, repo):
    """
    Simulates the fail-closed logic of SwiftDrop Required CI Gate.
    Returns (exit_code, trusted_publication_eligible, failures_list).
    """
    failures = []
    for job_id in EXPECTED_REQUIRED_GATE_JOBS:
        job_info = needs_dict.get(job_id)
        if not job_info:
            failures.append((job_id, "Job was not reported in workflow execution context"))
        else:
            res = job_info.get("result", "unknown")
            if res != "success":
                failures.append((job_id, f"Expected 'success', found '{res}'"))

    trusted_pub = False
    if event_name == "push" and repo == "Suthankan1/swiftdrop" and (ref == "refs/heads/test" or ref.startswith("refs/tags/")):
        if not failures:
            trusted_pub = True

    exit_code = 1 if failures else 0
    return exit_code, trusted_pub, failures


class TestWorkflowPipeline(unittest.TestCase):
    """Verifies that GitHub Actions workflows adhere to parallelization and release security contracts."""

    def test_all_workflows_exist_and_parse_valid_yaml(self):
        """Verifies that all required workflow YAML files exist and parse cleanly."""
        for wf in [CI_WORKFLOW, SECURITY_WORKFLOW, CONTRACTS_WORKFLOW]:
            self.assertTrue(wf.is_file(), f"Workflow file missing: {wf}")
            content = wf.read_text(encoding="utf-8")
            data = yaml.safe_load(content)
            self.assertIsInstance(data, dict, f"Workflow {wf.name} did not parse as dictionary")
            self.assertIn("name", data)
            self.assertIn("jobs", data)

    def test_ci_workflow_concurrency_policy(self):
        """Verifies safe concurrency rules: cancel obsolete PRs without cancelling test push publication."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        concurrency = data.get("concurrency", {})
        self.assertIsInstance(concurrency, dict)
        
        # Concurrency group must differentiate PR numbers from branch refs
        group_expr = str(concurrency.get("group", ""))
        self.assertIn("github.event.pull_request.number", group_expr)
        self.assertIn("github.ref", group_expr)

        # cancel-in-progress must be conditional on pull_request event
        cancel_expr = str(concurrency.get("cancel-in-progress", ""))
        self.assertIn("github.event_name == 'pull_request'", cancel_expr)

    def test_exact_six_java_services_in_matrix(self):
        """Verifies that the Java service matrix contains exactly the 6 approved microservices."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("java-service-tests", jobs)
        java_job = jobs["java-service-tests"]
        
        matrix = java_job.get("strategy", {}).get("matrix", {})
        services = matrix.get("service", [])
        self.assertEqual(sorted(services), sorted(EXPECTED_SERVICES))

        # Check physical existence of all 6 services
        for svc in EXPECTED_SERVICES:
            svc_dir = REPO_ROOT / "services" / svc
            self.assertTrue(svc_dir.is_dir(), f"Service directory missing for {svc}")
            self.assertTrue((svc_dir / "pom.xml").is_file(), f"pom.xml missing for {svc}")
            self.assertTrue((svc_dir / "mvnw").is_file(), f"mvnw missing for {svc}")

    def test_shared_library_dependency_order(self):
        """Verifies that java-service-tests explicitly needs java-shared-libraries."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("java-shared-libraries", jobs)
        self.assertIn("java-service-tests", jobs)
        
        needs = jobs["java-service-tests"].get("needs", [])
        if isinstance(needs, str):
            needs = [needs]
        self.assertIn("java-shared-libraries", needs)

    def test_exact_eight_container_targets_in_matrix(self):
        """Verifies that container-security in ci.yml contains exactly 8 approved targets."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("container-security", jobs)
        cs_job = jobs["container-security"]
        
        targets = cs_job.get("strategy", {}).get("matrix", {}).get("target", [])
        target_names = [t.get("name") for t in targets]
        self.assertEqual(sorted(target_names), sorted(EXPECTED_CONTAINER_TARGETS))

        # Verify physical Dockerfiles exist and only Keycloak has --ignore-unfixed
        for t in targets:
            name = t.get("name")
            df_path = REPO_ROOT / t.get("dockerfile")
            self.assertTrue(df_path.is_file(), f"Dockerfile missing for {name}: {df_path}")
            extra_args = t.get("extra_trivy_args", "")
            if name == "keycloak":
                self.assertIn("--ignore-unfixed", extra_args)
            else:
                self.assertEqual(extra_args, "", f"Target {name} must not have extra trivy bypasses")

    def test_required_ci_gate_needs_completeness(self):
        """Verifies that required-ci-gate depends on all required validation jobs and runs always()."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("required-ci-gate", jobs)
        gate_job = jobs["required-ci-gate"]

        self.assertEqual(gate_job.get("if"), "always()")
        needs = gate_job.get("needs", [])
        self.assertEqual(sorted(needs), sorted(EXPECTED_REQUIRED_GATE_JOBS))

    def test_publish_runtime_images_guards_and_permissions(self):
        """Verifies that publish-runtime-images only triggers on trusted pushes and requires the gate."""
        data = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
        jobs = data.get("jobs", {})
        self.assertIn("publish-runtime-images", jobs)
        pub_job = jobs["publish-runtime-images"]

        # Must depend on required-ci-gate
        needs = pub_job.get("needs", [])
        if isinstance(needs, str):
            needs = [needs]
        self.assertIn("required-ci-gate", needs)

        # Trigger guard must require push to test or release tag
        if_expr = str(pub_job.get("if", ""))
        self.assertIn("github.event_name == 'push'", if_expr)
        self.assertIn("github.repository == 'Suthankan1/swiftdrop'", if_expr)
        self.assertIn("refs/heads/test", if_expr)

        # Packages: write must ONLY be granted to publish-runtime-images
        self.assertEqual(pub_job.get("permissions", {}).get("packages"), "write")
        for j_id, j_spec in jobs.items():
            if j_id != "publish-runtime-images":
                pkg_perm = j_spec.get("permissions", {}).get("packages")
                self.assertNotEqual(pkg_perm, "write", f"Job {j_id} must not have packages: write")

    def test_actionlint_passes_cleanly(self):
        """Runs actionlint against all workflows if the actionlint binary is installed."""
        actionlint_bin = shutil.which("actionlint")
        if not actionlint_bin:
            self.skipTest("actionlint binary not found in PATH")
        
        proc = subprocess.run(
            [actionlint_bin, str(CI_WORKFLOW), str(SECURITY_WORKFLOW), str(CONTRACTS_WORKFLOW)],
            capture_output=True,
            text=True
        )
        self.assertEqual(
            proc.returncode, 0,
            f"actionlint failed on workflows:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )

    # --------------------------------------------------------------------------
    # Failure Injection & Gate Logic Scenarios
    # --------------------------------------------------------------------------

    def test_gate_positive_control_on_push_to_test(self):
        """Positive control: all jobs succeeded on test push -> gate passes, publication eligible."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 0)
        self.assertTrue(eligible)
        self.assertEqual(len(fails), 0)

    def test_gate_positive_control_on_pull_request(self):
        """Positive control: all jobs succeeded on PR -> gate passes, but publication NOT eligible."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "pull_request", "refs/pull/123/merge", "Suthankan1/swiftdrop")
        self.assertEqual(code, 0)
        self.assertFalse(eligible)
        self.assertEqual(len(fails), 0)

    def test_gate_fails_closed_when_accounts_service_fails(self):
        """Failure injection: accounts service fails -> gate returns 1 and blocks publication."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["java-service-tests"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)
        self.assertIn("java-service-tests", [f[0] for f in fails])

    def test_gate_fails_closed_when_delivery_service_fails(self):
        """Failure injection: delivery service tests fail -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["java-service-tests"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "pull_request", "refs/pull/250/merge", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_shared_libraries_fail(self):
        """Failure injection: shared protobuf/security fail -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["java-shared-libraries"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_container_security_fails(self):
        """Failure injection: Trivy vulnerability or container build failure -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["container-security"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_gitleaks_secret_detected(self):
        """Failure injection: Gitleaks finds a secret -> secret-scanning fails -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["secret-scanning"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "pull_request", "refs/pull/999/merge", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_contract_check_fails(self):
        """Failure injection: OpenAPI lint or contract backward compatibility breaks -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["contract-validation"] = {"result": "failure"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_job_is_skipped(self):
        """Failure injection: a mandatory job was skipped -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["terraform-policy"] = {"result": "skipped"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)
        self.assertIn("terraform-policy", [f[0] for f in fails])

    def test_gate_fails_closed_when_job_is_cancelled(self):
        """Failure injection: a job was cancelled -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        mock_needs["frontend-quality"] = {"result": "cancelled"}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)

    def test_gate_fails_closed_when_job_result_is_missing(self):
        """Failure injection: job omitted from execution context -> gate returns 1."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        del mock_needs["kubernetes-cluster-policy"]
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "Suthankan1/swiftdrop")
        self.assertEqual(code, 1)
        self.assertFalse(eligible)
        self.assertIn("kubernetes-cluster-policy", [f[0] for f in fails])

    def test_untrusted_fork_cannot_publish_even_if_checks_pass(self):
        """Security invariant: fork PR or unauthorized repo cannot obtain publication eligibility."""
        mock_needs = {job_id: {"result": "success"} for job_id in EXPECTED_REQUIRED_GATE_JOBS}
        code, eligible, fails = evaluate_gate_policy(mock_needs, "push", "refs/heads/test", "attacker/swiftdrop")
        self.assertEqual(code, 0)
        self.assertFalse(eligible)


if __name__ == "__main__":
    unittest.main()
