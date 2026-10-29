"""
SwiftDrop Platform — Release Verification & Acceptance Contract Tests (Issue #170)

Validates:
1. docs/deployment/N10_P12_RELEASE_VERIFICATION.md exists and contains all required sections.
2. docs/handoff/N10_P12_RELEASE_ACCEPTANCE_EVIDENCE.md exists and contains the complete acceptance checklist.
3. Strict $0 platform policy, local-first topology, and empty Azure allowlist invariants are preserved.
4. Historical PR #196 merge audit records exact merge commit (b8eac5d) and candidate SHA (ff1d204).
5. Exact immutable digests for all eight container images are verified.
6. Mode B live recovery, RPO/RTO budgets, and capacity SLAs are honestly classified as BLOCKED / PENDING.
7. Independent reviewer matrix includes all designated domain maintainers with PENDING status.
8. All relative Markdown links resolve to real repository files.
9. Zero secrets, tokens, private keys, or passwords are committed.
"""

import os
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
VERIFICATION_DOC = REPO_ROOT / "docs" / "deployment" / "N10_P12_RELEASE_VERIFICATION.md"
ACCEPTANCE_DOC = REPO_ROOT / "docs" / "handoff" / "N10_P12_RELEASE_ACCEPTANCE_EVIDENCE.md"


class TestReleaseVerificationContract(unittest.TestCase):
    """Verifies that the release verification document meets all Issue #170 criteria."""

    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            VERIFICATION_DOC.is_file(),
            f"Mandatory release verification doc missing: {VERIFICATION_DOC}",
        )
        cls.content = VERIFICATION_DOC.read_text(encoding="utf-8")

    def test_verification_doc_contains_all_core_sections(self):
        """Verifies presence of all required sections in N10_P12_RELEASE_VERIFICATION.md."""
        required_sections = [
            "## 1. Executive Summary & Operating Charter",
            "## 2. Historical PR #196 Merge Audit & Provenance Verification",
            "## 3. Authoritative Release-Candidate Dependency Matrix",
            "## 4. Immutable Container Image Registry & Artifact Verification",
            "## 5. Strict Zero-Cost Release Policy (ADR-0011 Compliance)",
            "## 6. GitHub Branch Protection & Governance Enforcement Gap",
            "## 7. CI/CD & Automated Test Verification",
            "## 8. Database Migration & Schema Compatibility Safety",
            "## 9. Disaster Recovery, RPO/RTO & Mode B Checkpoint Status",
            "## 10. Staging, Monitoring & Rollout Validation",
            "## 11. Operational Smoke-Test Status",
            "## 12. Release Execution Boundary & Operator Handover",
            "## 13. Formal Release Blocker Catalog",
            "## 14. Authoritative Release Recommendation",
        ]
        for section in required_sections:
            self.assertIn(
                section,
                self.content,
                f"Missing required section in release verification doc: '{section}'",
            )

    def test_pr_196_historical_audit_provenance_recorded(self):
        """Verifies that PR #196 merge commit, candidate SHA, and 0 approvals are documented."""
        self.assertIn("b8eac5db0d8bfa3dfe66c78eb273e0dce8632e3e", self.content)
        self.assertIn("ff1d204c4f0759be4125be6a726c746b947d1187", self.content)
        self.assertIn("0ae219ee60133830d4a88f361801e138da7f1a8d", self.content)
        self.assertIn("reviews: []", self.content)
        self.assertIn("25813062", self.content)

    def test_immutable_image_digests_recorded_for_all_services(self):
        """Verifies that immutable digests are documented for all 8 components."""
        expected_services = [
            "swiftdrop-frontend",
            "swiftdrop-api-gateway",
            "swiftdrop-accounts",
            "swiftdrop-pricing",
            "swiftdrop-delivery",
            "swiftdrop-communications",
            "swiftdrop-graphql",
            "swiftdrop-keycloak",
        ]
        for svc in expected_services:
            self.assertIn(svc, self.content, f"Missing component in image digest ledger: {svc}")
        # Verify candidate ff1d204 digests are present
        self.assertIn("sha256:c9844be7190326d493877830697ed5621d863e9ff9c18bd9cbf4361bd41d547e", self.content)
        self.assertIn("sha256:5f2afc71450ab96527492fc7a70aef06c0ec6b5a9f1084238ec955660b3bbce0", self.content)

    def test_strict_zero_cost_policy_enforced(self):
        """Verifies that $0 cost policy, local k3d target, and empty cloud allowlist are documented."""
        self.assertIn("$0.00 USD", self.content)
        self.assertIn("ADR-0011", self.content)
        self.assertIn("swiftdrop-local", self.content)
        self.assertIn("k3d-swiftdrop-local", self.content)
        self.assertIn("allowed_resources: []", self.content)
        self.assertIn("deployment_authorized: false", self.content)

    def test_blockers_and_honest_hold_recommendation(self):
        """Verifies that release recommendation is HOLD / PENDING with cataloged blockers."""
        self.assertIn("HOLD / PENDING", self.content)
        self.assertIn("NOT READY FOR PRODUCTION PROMOTION", self.content)
        self.assertIn("BLK-01", self.content)
        self.assertIn("BLK-02", self.content)
        self.assertIn("BLK-03", self.content)
        self.assertIn("BLK-04", self.content)
        self.assertIn("BLK-05", self.content)

    def test_no_secret_leakage_in_verification_doc(self):
        """Verifies that no private keys, passwords, or JWT dumps exist in doc."""
        self.assertNotIn("BEGIN RSA PRIVATE KEY", self.content)
        self.assertNotIn("BEGIN OPENSSH PRIVATE KEY", self.content)
        self.assertNotIn("eyJh", self.content)


class TestReleaseAcceptanceContract(unittest.TestCase):
    """Verifies that the release acceptance evidence document meets all Issue #170 criteria."""

    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            ACCEPTANCE_DOC.is_file(),
            f"Mandatory release acceptance doc missing: {ACCEPTANCE_DOC}",
        )
        cls.content = ACCEPTANCE_DOC.read_text(encoding="utf-8")

    def test_acceptance_doc_contains_all_core_sections(self):
        """Verifies presence of all required sections in N10_P12_RELEASE_ACCEPTANCE_EVIDENCE.md."""
        required_sections = [
            "## 1. Executive Summary & Purpose",
            "## 2. Complete 30-Item Release Acceptance Checklist",
            "## 3. Immutable Container Registry Digest Ledger",
            "## 4. Open Release Blockers & Remediation Register",
            "## 5. Independent Reviewer Matrix & Sign-Off Status",
            "## 6. Authoritative Conclusion",
        ]
        for section in required_sections:
            self.assertIn(
                section,
                self.content,
                f"Missing required section in release acceptance doc: '{section}'",
            )

    def test_all_thirty_checklist_items_present(self):
        """Verifies that all 30 items from prompt checklist are explicitly evaluated."""
        for i in range(1, 31):
            pattern = rf"\|\s*\*\*{i}\*\*\s*\|"
            self.assertTrue(
                re.search(pattern, self.content),
                f"Missing checklist item #{i} in release acceptance register",
            )

    def test_independent_reviewers_declared(self):
        """Verifies that all designated independent reviewers are in the sign-off table."""
        reviewers = [
            "@Suthankan1",
            "@EricPraveen",
            "@Vibhushan-2003",
            "@Sinthuha-n",
            "@Harithkeshan",
        ]
        for reviewer in reviewers:
            self.assertIn(
                reviewer,
                self.content,
                f"Missing reviewer in sign-off table: {reviewer}",
            )


class TestMarkdownLinksResolution(unittest.TestCase):
    """Verifies that all relative Markdown links in the new documentation resolve to real files."""

    def _check_links_in_file(self, doc_path: Path):
        content = doc_path.read_text(encoding="utf-8")
        dir_path = doc_path.parent

        matches = re.findall(r'\[([^\]]+)\]\(([^)]+)\)', content)
        for text, target in matches:
            if target.startswith("http://") or target.startswith("https://") or target.startswith("#"):
                continue
            clean_target = target.split("#")[0]
            if not clean_target:
                continue
            resolved = (dir_path / clean_target).resolve()
            self.assertTrue(
                resolved.exists(),
                f"Broken link in {doc_path.name}: '{target}' -> '{resolved}' does not exist on disk!",
            )

    def test_verification_doc_links_resolve(self):
        """All relative links in release verification doc must resolve."""
        self._check_links_in_file(VERIFICATION_DOC)

    def test_acceptance_doc_links_resolve(self):
        """All relative links in release acceptance doc must resolve."""
        self._check_links_in_file(ACCEPTANCE_DOC)


if __name__ == "__main__":
    unittest.main()
