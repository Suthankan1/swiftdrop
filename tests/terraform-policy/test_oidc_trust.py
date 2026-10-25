"""
Automated OIDC Trust Policy & Security Boundary Test Suite for SwiftDrop.

Canonical References:
- docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md
- platform/security/github-oidc-trust-policy.yaml
- platform/security/azure-allowlist-policy.yaml
- GitHub Issue #174 (N10-ZC03)
"""

from __future__ import annotations

import copy
import re
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / "platform/security/github-oidc-trust-policy.yaml"
AZURE_ALLOWLIST_PATH = REPO_ROOT / "platform/security/azure-allowlist-policy.yaml"
WORKFLOWS_DIR = REPO_ROOT / ".github/workflows"

# Stable Diagnostic Error Codes
CODE_PASS = "OIDC_TRUST_PASS"
CODE_CLOUD_IDENTITY_DISABLED = "DENY_CLOUD_IDENTITY_DISABLED"
CODE_DEPLOYMENT_NOT_AUTHORIZED = "DENY_AZURE_DEPLOYMENT_NOT_AUTHORIZED"
CODE_INVALID_ISSUER = "DENY_INVALID_ISSUER"
CODE_INVALID_AUDIENCE = "DENY_INVALID_AUDIENCE"
CODE_WRONG_REPOSITORY = "DENY_WRONG_REPOSITORY"
CODE_WRONG_REPOSITORY_OWNER = "DENY_WRONG_REPOSITORY_OWNER"
CODE_WRONG_REPOSITORY_ID = "DENY_WRONG_REPOSITORY_ID"
CODE_UNAPPROVED_BRANCH = "DENY_UNAPPROVED_BRANCH"
CODE_UNAPPROVED_ENVIRONMENT = "DENY_UNAPPROVED_ENVIRONMENT"
CODE_UNAPPROVED_WORKFLOW_REF = "DENY_UNAPPROVED_WORKFLOW_REF"
CODE_UNAPPROVED_SUBJECT = "DENY_UNAPPROVED_SUBJECT"
CODE_WILDCARD_SUBJECT_PROHIBITED = "DENY_WILDCARD_SUBJECT_PROHIBITED"
CODE_PULL_REQUEST_OIDC_PROHIBITED = "DENY_PULL_REQUEST_OIDC_PROHIBITED"
CODE_FORK_OIDC_PROHIBITED = "DENY_FORK_OIDC_PROHIBITED"
CODE_UNPROTECTED_ENVIRONMENT = "DENY_UNPROTECTED_ENVIRONMENT"
CODE_BRANCH_CANNOT_PROVE_ENVIRONMENT = "DENY_BRANCH_CANNOT_PROVE_ENVIRONMENT"
CODE_TOP_LEVEL_ID_TOKEN_WRITE = "DENY_TOP_LEVEL_ID_TOKEN_WRITE"
CODE_UNTRUSTED_ID_TOKEN_WRITE = "DENY_UNTRUSTED_ID_TOKEN_WRITE"
CODE_BROAD_REPOSITORY_WRITE = "DENY_BROAD_REPOSITORY_WRITE"
CODE_PROHIBITED_AZURE_ROLE = "DENY_PROHIBITED_AZURE_ROLE"
CODE_UNAPPROVED_RBAC_SCOPE = "DENY_UNAPPROVED_RBAC_SCOPE"
CODE_UNKNOWN_AZURE_ACTION = "DENY_UNKNOWN_AZURE_ACTION"
CODE_CLIENT_SECRET_PROHIBITED = "DENY_CLIENT_SECRET_PROHIBITED"
CODE_UNAUTHORIZED_STATE_BACKEND = "DENY_UNAUTHORIZED_STATE_BACKEND"
CODE_MALFORMED_TRUST_POLICY = "ERROR_OIDC_POLICY_INVALID"
CODE_MISSING_TRUST_POLICY = "ERROR_OIDC_POLICY_MISSING"
CODE_EMPTY_CONDITION_FAILS_CLOSED = "DENY_EMPTY_CONDITION_FAILS_CLOSED"


class OidcTrustEvaluator:
    """Evaluates GitHub Actions OIDC token claims and workflow context against policy contract."""

    @staticmethod
    def load_policy(path: Optional[Path] = None) -> Dict[str, Any]:
        target = path or POLICY_PATH
        if not target.exists():
            raise FileNotFoundError(f"{CODE_MISSING_TRUST_POLICY}: {target}")
        with open(target, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        if not isinstance(data, dict):
            raise ValueError(f"{CODE_MALFORMED_TRUST_POLICY}: Policy root is not a dictionary")
        return data

    @staticmethod
    def validate_policy_structure(policy: Dict[str, Any]) -> List[str]:
        """Validates that policy contract contains mandatory fields and correct types."""
        violations: List[str] = []
        if policy.get("policy_mode") != "deny-by-default":
            violations.append("Policy mode must be 'deny-by-default'")

        cloud_id = policy.get("cloud_identity")
        if not isinstance(cloud_id, dict):
            violations.append(f"{CODE_MALFORMED_TRUST_POLICY}: missing 'cloud_identity' dictionary")
        else:
            if not isinstance(cloud_id.get("enabled"), bool):
                violations.append("cloud_identity.enabled must be boolean")
            if not isinstance(cloud_id.get("deployment_authorized"), bool):
                violations.append("cloud_identity.deployment_authorized must be boolean")

        gh = policy.get("github")
        if not isinstance(gh, dict):
            violations.append(f"{CODE_MALFORMED_TRUST_POLICY}: missing 'github' dictionary")
        else:
            if not isinstance(gh.get("repository_id"), int):
                violations.append("github.repository_id must be integer")
            if not isinstance(gh.get("allowed_events"), list):
                violations.append("github.allowed_events must be list")
            oidc = gh.get("oidc")
            if not isinstance(oidc, dict):
                violations.append(f"{CODE_MALFORMED_TRUST_POLICY}: missing 'github.oidc' dictionary")
            else:
                if not isinstance(oidc.get("approved_subjects"), list):
                    violations.append("github.oidc.approved_subjects must be list")

        azure = policy.get("azure")
        if not isinstance(azure, dict):
            violations.append(f"{CODE_MALFORMED_TRUST_POLICY}: missing 'azure' dictionary")
        else:
            if azure.get("client_secret_permitted") is not False:
                violations.append(f"{CODE_CLIENT_SECRET_PROHIBITED}: client secrets must not be permitted")
            prohibited = azure.get("prohibited_roles", [])
            for pr in ["Owner", "Contributor", "User Access Administrator"]:
                if pr not in prohibited:
                    violations.append(f"azure.prohibited_roles missing mandatory restriction: {pr}")

        state = policy.get("terraform_state")
        if not isinstance(state, dict):
            violations.append(f"{CODE_MALFORMED_TRUST_POLICY}: missing 'terraform_state' dictionary")
        else:
            if state.get("azure_storage_backend_permitted") is True:
                violations.append(f"{CODE_UNAUTHORIZED_STATE_BACKEND}: Azure storage backend must not be permitted")
            if state.get("remote_state_authorized") is True:
                violations.append(f"{CODE_UNAUTHORIZED_STATE_BACKEND}: Remote state is not authorized")

        return violations

    @classmethod
    def evaluate_request(
        cls,
        policy: Dict[str, Any],
        claims: Dict[str, Any],
        job_context: Dict[str, Any],
        environment_protections: Optional[Dict[str, Any]] = None,
    ) -> Tuple[bool, str, List[str]]:
        """
        Deterministically evaluates an OIDC token request against policy contract.
        Returns (is_allowed, status_code, diagnostic_reasons).
        """
        reasons: List[str] = []

        # 1. Structural Policy Invariant: Is Cloud Identity Enabled?
        cloud_id = policy.get("cloud_identity", {})
        if not cloud_id.get("enabled", False):
            return False, CODE_CLOUD_IDENTITY_DISABLED, ["Cloud identity is disabled in policy contract"]
        if not cloud_id.get("deployment_authorized", False):
            return False, CODE_DEPLOYMENT_NOT_AUTHORIZED, ["Cloud deployment is not authorized"]

        # 2. Issuer check
        gh_oidc = policy.get("github", {}).get("oidc", {})
        expected_issuer = gh_oidc.get("issuer", "https://token.actions.githubusercontent.com")
        if claims.get("iss") != expected_issuer:
            return False, CODE_INVALID_ISSUER, [f"Untrusted issuer: '{claims.get('iss')}' (expected '{expected_issuer}')"]

        # 3. Audience check
        expected_aud = gh_oidc.get("audience", "api://AzureADTokenExchange")
        actual_aud = claims.get("aud")
        if isinstance(actual_aud, list):
            if expected_aud not in actual_aud:
                return False, CODE_INVALID_AUDIENCE, [f"Audience '{expected_aud}' not present in {actual_aud}"]
        elif actual_aud != expected_aud:
            return False, CODE_INVALID_AUDIENCE, [f"Untrusted audience: '{actual_aud}' (expected '{expected_aud}')"]

        # 4. Repository & Owner & Immutable ID checks
        gh = policy.get("github", {})
        expected_repo = gh.get("repository", "Suthankan1/swiftdrop")
        if claims.get("repository") != expected_repo:
            return False, CODE_WRONG_REPOSITORY, [f"Untrusted repository: '{claims.get('repository')}'"]

        expected_owner = expected_repo.split("/")[0] if "/" in expected_repo else ""
        if claims.get("repository_owner") and claims.get("repository_owner") != expected_owner:
            return False, CODE_WRONG_REPOSITORY_OWNER, [f"Untrusted repository owner: '{claims.get('repository_owner')}'"]

        expected_repo_id = gh.get("repository_id")
        if expected_repo_id and claims.get("repository_id") is not None:
            if str(claims.get("repository_id")) != str(expected_repo_id):
                return False, CODE_WRONG_REPOSITORY_ID, [f"Repository ID mismatch: '{claims.get('repository_id')}' != '{expected_repo_id}'"]

        # 5. Fork and PR check
        if job_context.get("is_fork", False) or claims.get("repository_owner") != expected_owner:
            return False, CODE_FORK_OIDC_PROHIBITED, ["OIDC token request originated from a repository fork"]

        event_name = claims.get("event_name") or job_context.get("event_name", "")
        if event_name in ["pull_request", "pull_request_target"]:
            return False, CODE_PULL_REQUEST_OIDC_PROHIBITED, [f"OIDC token request prohibited on event '{event_name}'"]

        # 6. Job Permissions Check
        job_perms = job_context.get("permissions", {})
        if job_perms == "write-all":
            return False, CODE_BROAD_REPOSITORY_WRITE, ["Job uses overly permissive 'permissions: write-all'"]
        if isinstance(job_perms, dict):
            if job_perms.get("contents") == "write" and job_perms.get("id-token") == "write":
                return False, CODE_BROAD_REPOSITORY_WRITE, ["Job combines 'contents: write' with 'id-token: write'"]
            if job_context.get("is_untrusted", False) and job_perms.get("id-token") == "write":
                return False, CODE_UNTRUSTED_ID_TOKEN_WRITE, ["Untrusted job requests 'id-token: write'"]

        if job_context.get("top_level_permissions", {}).get("id-token") == "write":
            return False, CODE_TOP_LEVEL_ID_TOKEN_WRITE, ["Workflow uses top-level 'id-token: write'"]

        # 7. Subject Invariant & Wildcard Rejection
        subject = claims.get("sub", "")
        if not subject:
            return False, CODE_UNAPPROVED_SUBJECT, ["Missing 'sub' claim in OIDC token"]
        if "*" in subject:
            return False, CODE_WILDCARD_SUBJECT_PROHIBITED, [f"Wildcard subject rejected: '{subject}'"]
        if ":pull_request" in subject:
            return False, CODE_PULL_REQUEST_OIDC_PROHIBITED, [f"Pull request subject rejected: '{subject}'"]

        # 8. Approved Subjects & Branches Matching (Fail closed on empty list)
        approved_subjects = gh_oidc.get("approved_subjects", [])
        if not approved_subjects:
            return False, CODE_EMPTY_CONDITION_FAILS_CLOSED, ["No approved OIDC subjects configured in policy"]
        if subject not in approved_subjects:
            return False, CODE_UNAPPROVED_SUBJECT, [f"Subject '{subject}' is not in approved list"]

        # 9. Branch and Environment Evaluation
        ref = claims.get("ref", "")
        approved_branches = gh_oidc.get("approved_branches", [])
        if ref and approved_branches and ref not in approved_branches:
            return False, CODE_UNAPPROVED_BRANCH, [f"Ref '{ref}' not in approved branches"]

        environment_name = claims.get("environment") or job_context.get("environment")
        if environment_name:
            approved_envs = gh_oidc.get("approved_environments", [])
            if not approved_envs or environment_name not in approved_envs:
                return False, CODE_UNAPPROVED_ENVIRONMENT, [f"Environment '{environment_name}' not approved"]

            # Environment protection verification
            env_rules = environment_protections or {}
            env_info = env_rules.get(environment_name, {})
            if not env_info.get("has_required_reviewers", False):
                return False, CODE_UNPROTECTED_ENVIRONMENT, [f"Environment '{environment_name}' lacks required reviewers"]

        elif subject.startswith(f"repo:{expected_repo}:environment:"):
            # Subject claims environment but job lacks verified environment context
            return False, CODE_BRANCH_CANNOT_PROVE_ENVIRONMENT, ["Environment subject without verified environment context"]

        # 10. Role and Scope Restrictions in Azure
        azure = policy.get("azure", {})
        prohibited_roles = azure.get("prohibited_roles", [])
        requested_roles = job_context.get("requested_roles", [])
        for r in requested_roles:
            if r in prohibited_roles:
                return False, CODE_PROHIBITED_AZURE_ROLE, [f"Role '{r}' is strictly prohibited"]

        requested_scope = job_context.get("requested_scope", "")
        if requested_scope in ["/", "*"] or (requested_scope and not requested_scope.startswith("/subscriptions/")):
            return False, CODE_UNAPPROVED_RBAC_SCOPE, [f"Broad or unapproved RBAC scope: '{requested_scope}'"]

        return True, CODE_PASS, ["OIDC token request and claims verified against policy contract"]


class TestOidcTrustContract(unittest.TestCase):
    """Verifies the canonical OIDC trust contract invariants and deterministic evaluation."""

    def setUp(self):
        self.policy = OidcTrustEvaluator.load_policy(POLICY_PATH)

    def test_canonical_policy_exists_and_parses(self):
        self.assertTrue(POLICY_PATH.exists(), f"Policy missing at {POLICY_PATH}")
        self.assertIsInstance(self.policy, dict)
        self.assertEqual(self.policy.get("policy_mode"), "deny-by-default")
        violations = OidcTrustEvaluator.validate_policy_structure(self.policy)
        self.assertEqual(len(violations), 0, f"Policy structure violations: {violations}")

    def test_canonical_policy_cloud_identity_disabled(self):
        cloud_id = self.policy.get("cloud_identity", {})
        self.assertFalse(cloud_id.get("enabled"), "cloud_identity.enabled must be FALSE")
        self.assertFalse(cloud_id.get("deployment_authorized"), "cloud_identity.deployment_authorized must be FALSE")
        self.assertFalse(cloud_id.get("authentication_authorized"), "cloud_identity.authentication_authorized must be FALSE")

    def test_canonical_policy_empty_approval_lists(self):
        gh = self.policy.get("github", {})
        self.assertEqual(gh.get("allowed_events"), [], "allowed_events must be empty")
        oidc = gh.get("oidc", {})
        self.assertFalse(oidc.get("enabled"), "github.oidc.enabled must be FALSE")
        self.assertEqual(oidc.get("approved_subjects"), [], "approved_subjects must be empty")
        self.assertEqual(oidc.get("approved_branches"), [], "approved_branches must be empty")
        self.assertEqual(oidc.get("approved_environments"), [], "approved_environments must be empty")
        self.assertFalse(oidc.get("permissions", {}).get("id_token_write_authorized"), "id_token_write_authorized must be FALSE")

    def test_canonical_policy_azure_invariants(self):
        azure = self.policy.get("azure", {})
        self.assertEqual(azure.get("allowed_role_assignments"), [], "allowed_role_assignments must be empty")
        self.assertEqual(azure.get("allowed_resource_scopes"), [], "allowed_resource_scopes must be empty")
        self.assertFalse(azure.get("client_secret_permitted"), "client_secret_permitted must be FALSE")
        prohibited = azure.get("prohibited_roles", [])
        for role in ["Owner", "Contributor", "User Access Administrator"]:
            self.assertIn(role, prohibited, f"Prohibited roles must contain {role}")

    def test_canonical_policy_terraform_state_invariants(self):
        state = self.policy.get("terraform_state", {})
        self.assertEqual(state.get("backend_type"), "none")
        self.assertFalse(state.get("remote_state_authorized"), "remote_state_authorized must be FALSE")
        self.assertFalse(state.get("azure_storage_backend_permitted"), "azure_storage_backend_permitted must be FALSE")
        self.assertEqual(state.get("primary_custodian"), "Suthankan1")

    def test_hypothetical_valid_fixture_structurally_validates_without_activating(self):
        """A hypothetical valid policy can be verified structurally while remaining inactive."""
        hypothetical = copy.deepcopy(self.policy)
        hypothetical["github"]["oidc"]["approved_subjects"] = [
            "repo:Suthankan1/swiftdrop:environment:production"
        ]
        hypothetical["github"]["oidc"]["approved_branches"] = ["refs/heads/main"]
        hypothetical["github"]["oidc"]["approved_environments"] = ["production"]
        # Structure must remain valid
        violations = OidcTrustEvaluator.validate_policy_structure(hypothetical)
        self.assertEqual(len(violations), 0)

        # But request MUST still fail closed because cloud_identity.enabled is False
        claims = {
            "iss": "https://token.actions.githubusercontent.com",
            "aud": "api://AzureADTokenExchange",
            "repository": "Suthankan1/swiftdrop",
            "repository_owner": "Suthankan1",
            "repository_id": 1420084393,
            "sub": "repo:Suthankan1/swiftdrop:environment:production",
            "ref": "refs/heads/main",
            "environment": "production",
            "event_name": "workflow_dispatch",
        }
        job_ctx = {
            "permissions": {"id-token": "write", "contents": "read"},
            "environment": "production",
            "is_fork": False,
        }
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(hypothetical, claims, job_ctx)
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_CLOUD_IDENTITY_DISABLED)


class TestNegativeOidcRejection(unittest.TestCase):
    """Comprehensive negative tests verifying explicit rejection of untrusted claims and escalations."""

    def setUp(self):
        self.policy = OidcTrustEvaluator.load_policy(POLICY_PATH)
        # Construct an active test policy for testing claim validation logic
        self.active_test_policy = copy.deepcopy(self.policy)
        self.active_test_policy["cloud_identity"]["enabled"] = True
        self.active_test_policy["cloud_identity"]["deployment_authorized"] = True
        self.active_test_policy["github"]["oidc"]["enabled"] = True
        self.active_test_policy["github"]["oidc"]["approved_subjects"] = [
            "repo:Suthankan1/swiftdrop:environment:production",
            "repo:Suthankan1/swiftdrop:ref:refs/heads/main",
        ]
        self.active_test_policy["github"]["oidc"]["approved_branches"] = ["refs/heads/main"]
        self.active_test_policy["github"]["oidc"]["approved_environments"] = ["production"]

        self.valid_claims = {
            "iss": "https://token.actions.githubusercontent.com",
            "aud": "api://AzureADTokenExchange",
            "repository": "Suthankan1/swiftdrop",
            "repository_owner": "Suthankan1",
            "repository_id": 1420084393,
            "sub": "repo:Suthankan1/swiftdrop:environment:production",
            "ref": "refs/heads/main",
            "environment": "production",
            "event_name": "workflow_dispatch",
        }
        self.valid_job_ctx = {
            "permissions": {"id-token": "write", "contents": "read"},
            "environment": "production",
            "is_fork": False,
            "requested_roles": ["Reader"],
            "requested_scope": "/subscriptions/sub-123/resourceGroups/rg-validation",
        }
        self.valid_env_protections = {
            "production": {"has_required_reviewers": True, "branch_policy": "main"}
        }

    def test_negative_wrong_repository(self):
        claims = dict(self.valid_claims, repository="MaliciousOrg/swiftdrop")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_WRONG_REPOSITORY)

    def test_negative_wrong_repository_owner(self):
        claims = dict(self.valid_claims, repository_owner="Attacker")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_WRONG_REPOSITORY_OWNER)

    def test_negative_wrong_repository_id(self):
        claims = dict(self.valid_claims, repository_id=999999999)
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_WRONG_REPOSITORY_ID)

    def test_negative_wrong_branch(self):
        claims = dict(self.valid_claims, ref="refs/heads/feature-unreviewed")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNAPPROVED_BRANCH)

    def test_negative_wrong_environment(self):
        claims = dict(self.valid_claims, environment="unapproved-env")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNAPPROVED_ENVIRONMENT)

    def test_negative_wrong_issuer(self):
        claims = dict(self.valid_claims, iss="https://fake-token-provider.example.com")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_INVALID_ISSUER)

    def test_negative_wrong_audience(self):
        claims = dict(self.valid_claims, aud="api://MaliciousAudience")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_INVALID_AUDIENCE)

    def test_negative_wrong_subject(self):
        claims = dict(self.valid_claims, sub="repo:Suthankan1/swiftdrop:ref:refs/heads/unapproved")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNAPPROVED_SUBJECT)

    def test_negative_wildcard_subject(self):
        claims = dict(self.valid_claims, sub="repo:Suthankan1/swiftdrop:*")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_WILDCARD_SUBJECT_PROHIBITED)

    def test_negative_pull_request_origin(self):
        claims = dict(self.valid_claims, event_name="pull_request", sub="repo:Suthankan1/swiftdrop:pull_request")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_PULL_REQUEST_OIDC_PROHIBITED)

    def test_negative_fork_origin(self):
        job_ctx = dict(self.valid_job_ctx, is_fork=True)
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_FORK_OIDC_PROHIBITED)

    def test_negative_unprotected_environment(self):
        # Environment protections missing required reviewers
        unprotected_envs = {"production": {"has_required_reviewers": False}}
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, self.valid_job_ctx, unprotected_envs
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNPROTECTED_ENVIRONMENT)

    def test_negative_cloud_identity_enabled_without_deployment_auth(self):
        pol = copy.deepcopy(self.active_test_policy)
        pol["cloud_identity"]["deployment_authorized"] = False
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            pol, self.valid_claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_DEPLOYMENT_NOT_AUTHORIZED)

    def test_negative_top_level_id_token_write(self):
        job_ctx = dict(self.valid_job_ctx, top_level_permissions={"id-token": "write"})
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_TOP_LEVEL_ID_TOKEN_WRITE)

    def test_negative_untrusted_job_id_token_write(self):
        job_ctx = dict(self.valid_job_ctx, is_untrusted=True)
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNTRUSTED_ID_TOKEN_WRITE)

    def test_negative_broad_repository_write(self):
        job_ctx = dict(self.valid_job_ctx, permissions={"contents": "write", "id-token": "write"})
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_BROAD_REPOSITORY_WRITE)

    def test_negative_prohibited_azure_roles(self):
        for role in ["Owner", "Contributor", "User Access Administrator"]:
            job_ctx = dict(self.valid_job_ctx, requested_roles=[role])
            allowed, code, _ = OidcTrustEvaluator.evaluate_request(
                self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
            )
            self.assertFalse(allowed)
            self.assertEqual(code, CODE_PROHIBITED_AZURE_ROLE)

    def test_negative_broad_root_scope(self):
        job_ctx = dict(self.valid_job_ctx, requested_scope="/")
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            self.active_test_policy, self.valid_claims, job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_UNAPPROVED_RBAC_SCOPE)

    def test_negative_client_secret_presence_rejected(self):
        pol = copy.deepcopy(self.policy)
        pol["azure"]["client_secret_permitted"] = True
        violations = OidcTrustEvaluator.validate_policy_structure(pol)
        self.assertTrue(any(CODE_CLIENT_SECRET_PROHIBITED in v for v in violations))

    def test_negative_empty_conditions_fail_closed(self):
        pol = copy.deepcopy(self.active_test_policy)
        pol["github"]["oidc"]["approved_subjects"] = []
        allowed, code, _ = OidcTrustEvaluator.evaluate_request(
            pol, self.valid_claims, self.valid_job_ctx, self.valid_env_protections
        )
        self.assertFalse(allowed)
        self.assertEqual(code, CODE_EMPTY_CONDITION_FAILS_CLOSED)

    def test_negative_malformed_policy_file_fails_closed(self):
        with self.assertRaises(Exception):
            OidcTrustEvaluator.load_policy(Path("/tmp/nonexistent-oidc-policy.yaml"))

    def test_negative_unauthorized_state_backend_rejected(self):
        pol = copy.deepcopy(self.policy)
        pol["terraform_state"]["azure_storage_backend_permitted"] = True
        violations = OidcTrustEvaluator.validate_policy_structure(pol)
        self.assertTrue(any(CODE_UNAUTHORIZED_STATE_BACKEND in v for v in violations))


if __name__ == "__main__":
    unittest.main()
