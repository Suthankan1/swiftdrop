"""
SwiftDrop Platform — Operator Handover & Evidence Index Contract Tests (Issue #177)

Validates:
1. docs/deployment/N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md exists and contains all required sections.
2. docs/handoff/N10_ZC06_RELEASE_EVIDENCE_INDEX.md exists and contains the complete evidence index.
3. Strict $0 platform policy, local-first topology, and empty Azure allowlist invariants are preserved.
4. Pinned toolchain versions match verified repository specifications.
5. Context isolation safeguards (k3d-swiftdrop-local, loopback ports) are enforced.
6. Reused recovery evidence indexes measured RPO (300s) and RTO (240s) as explicitly NOT APPROVED.
7. Independent reviewer handover table is defined with unprivileged reviewers and pending human review.
8. All relative Markdown links resolve to real repository files.
9. Zero secrets, tokens, private keys, or passwords are committed.
"""

import os
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
HANDOVER_DOC = REPO_ROOT / "docs" / "deployment" / "N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md"
EVIDENCE_INDEX_DOC = REPO_ROOT / "docs" / "handoff" / "N10_ZC06_RELEASE_EVIDENCE_INDEX.md"


class TestOperatorHandoverRunbookContract(unittest.TestCase):
    """Verifies that the $0 operator handover runbook meets all Issue #177 criteria."""

    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            HANDOVER_DOC.is_file(),
            f"Mandatory operator handover runbook missing: {HANDOVER_DOC}",
        )
        cls.content = HANDOVER_DOC.read_text(encoding="utf-8")

    def test_runbook_contains_all_core_sections(self):
        """Verifies presence of all required runbook sections per Issue #177 specification."""
        required_sections = [
            "## 1. Executive Overview & Operating Charter",
            "## 2. Environment & Topology Architecture",
            "## 3. Pinned Toolchain & Component Versions",
            "## 4. Cluster & Context Selection Protocol",
            "## 5. Startup, Health Verification & Graceful Shutdown",
            "## 6. Image Digest Promotion, Manifest Verification & Rollback",
            "## 7. Secret Management, Custody & Emergency Rotation",
            "## 8. Storage, State Custody & Disaster Recovery Operations",
            "## 9. Observability, Metrics & Secret-Safe Troubleshooting",
            "## 10. Operator Handover Acceptance & Human Gate Sign-Off",
        ]
        for section in required_sections:
            self.assertIn(
                section,
                self.content,
                f"Missing required section in operator handover runbook: '{section}'",
            )

    def test_zero_cost_policy_and_empty_allowlist_enforced(self):
        """Verifies that $0 cost policy, local k3d target, and empty cloud allowlist are documented."""
        self.assertIn("$0.00 USD", self.content)
        self.assertIn("ADR-0011", self.content)
        self.assertIn("swiftdrop-local", self.content)
        self.assertIn("k3d-swiftdrop-local", self.content)
        self.assertIn("Azure for Students $100 credit", self.content)
        self.assertIn("NOT", self.content)

    def test_pinned_tool_versions_recorded(self):
        """Verifies that actual pinned tool versions are documented accurately."""
        expected_versions = [
            ("Docker Engine", "29.9.2"),
            ("k3d CLI", "v5.9.0"),
            ("K3s Server Image", "v1.31.2-k3s1"),
            ("Kubernetes API", "v1.31.2+k3s1"),
            ("Terraform", "1.16.3"),
            ("PostgreSQL", "16-alpine"),
            ("Apache Kafka", "4.2.1"),
            ("Keycloak IdP", "26.8"),
            ("Prometheus", "v3.13.3"),
            ("Grafana", "11.2.2"),
        ]
        for name, version in expected_versions:
            self.assertIn(version, self.content, f"Expected pinned version for {name} ({version}) missing in runbook")

    def test_cluster_context_selection_safeguards(self):
        """Verifies that safe context selection commands and loopback safeguards are present."""
        self.assertIn("kubectl config use-context k3d-swiftdrop-local", self.content)
        self.assertIn("127.0.0.1:16443", self.content)
        self.assertIn("--context k3d-swiftdrop-local", self.content)

    def test_startup_and_shutdown_scripts_referenced(self):
        """Verifies that approved lifecycle scripts in platform/kubernetes/k3d/ are referenced."""
        self.assertIn("platform/kubernetes/k3d/create.sh", self.content)
        self.assertIn("platform/kubernetes/k3d/verify.sh", self.content)
        self.assertIn("platform/kubernetes/k3d/stop.sh", self.content)
        self.assertIn("platform/kubernetes/k3d/start.sh", self.content)
        self.assertIn("platform/kubernetes/k3d/storage-preflight.sh", self.content)

    def test_immutable_image_digests_and_rollback_safeguards(self):
        """Verifies documentation of immutable sha256 digests and safe application rollback."""
        self.assertIn("sha256:778ce75418fcf9eca3135afec8bd58b77be747d3f38534aca6bbbf6b9aeac1dc", self.content)
        self.assertIn("promote-staging.sh", self.content)
        self.assertIn("rollback-app.sh", self.content)
        self.assertIn("verify-candidate-manifest.sh", self.content)

    def test_secret_custody_and_sanitization(self):
        """Verifies secret ownership by @Suthankan1 and zero plaintext credentials in runbook."""
        self.assertIn("@Suthankan1", self.content)
        self.assertIn("<SECURE_PRICING_PASSWORD>", self.content)
        # Ensure no accidental leaked private keys or bearer tokens
        self.assertNotIn("BEGIN RSA PRIVATE KEY", self.content)
        self.assertNotIn("BEGIN OPENSSH PRIVATE KEY", self.content)
        self.assertNotIn("eyJh", self.content)  # Typical JWT header


class TestReleaseEvidenceIndexContract(unittest.TestCase):
    """Verifies that the release evidence index meets all Issue #177 criteria."""

    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            EVIDENCE_INDEX_DOC.is_file(),
            f"Mandatory release evidence index missing: {EVIDENCE_INDEX_DOC}",
        )
        cls.content = EVIDENCE_INDEX_DOC.read_text(encoding="utf-8")

    def test_all_governing_issues_indexed(self):
        """Verifies that all required issues (#172, #173, #174, #175, #176, #160, #167, #168, #169, #170, #178) are indexed."""
        required_issues = [
            "#172",
            "#173",
            "#174",
            "#175",
            "#176",
            "#160",
            "#167",
            "#168",
            "#169",
            "#177",
            "#170",
            "#178",
        ]
        for issue in required_issues:
            self.assertIn(
                issue,
                self.content,
                f"Missing required governing issue reference in evidence index: '{issue}'",
            )

    def test_reused_recovery_evidence_and_unapproved_budget_status(self):
        """Verifies that measured RPO (300s) and RTO (240s) are recorded as NOT APPROVED."""
        self.assertIn("300 seconds", self.content)
        self.assertIn("240 seconds", self.content)
        self.assertIn("NOT APPROVED", self.content)
        self.assertIn("~/.swiftdrop/backups/", self.content)
        self.assertIn("accounts_db_drill_recovery", self.content)
        self.assertIn("delivery_db_drill_recovery", self.content)

    def test_independent_reviewers_and_human_signoff_protocol(self):
        """Verifies that named reviewers are declared and that review sign-off is pending human review."""
        reviewers = [
            "@Suthankan1",
            "@EricPraveen",
            "@Sinthuha-n",
            "@Vibhushan-2003",
        ]
        for reviewer in reviewers:
            self.assertIn(
                reviewer,
                self.content,
                f"Missing required independent reviewer in evidence index: '{reviewer}'",
            )
        self.assertIn("PENDING_REVIEW", self.content)
        self.assertIn("SUBMITTED FOR PEER ACCEPTANCE", self.content)

    def test_issue_178_classified_as_optional_future_gate(self):
        """Verifies that Issue #178 is documented strictly as an optional future bonus and NOT AUTHORIZED."""
        self.assertIn("#178", self.content)
        self.assertIn("NOT AUTHORIZED", self.content)
        self.assertIn("NOT APPLICABLE", self.content)

    def test_release_handoff_to_issue_170_documents_open_blockers(self):
        """Verifies that handoff to Issue #170 records the release recommendation as HOLD / NOT READY."""
        self.assertIn("HOLD / NOT READY FOR PRODUCTION MERGE", self.content)
        self.assertIn("Mode B", self.content)
        self.assertIn("RPO/RTO Budget Sign-Off", self.content)
        self.assertIn("Capacity Numeric SLAs", self.content)


class TestMarkdownLinksResolution(unittest.TestCase):
    """Verifies that all relative Markdown links in the new documentation resolve to real files."""

    def _check_links_in_file(self, doc_path: Path):
        content = doc_path.read_text(encoding="utf-8")
        dir_path = doc_path.parent

        # Matches [label](relative/path.md)
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

    def test_handover_runbook_links_resolve(self):
        """All relative links in operator handover runbook must resolve."""
        self._check_links_in_file(HANDOVER_DOC)

    def test_evidence_index_links_resolve(self):
        """All relative links in release evidence index must resolve."""
        self._check_links_in_file(EVIDENCE_INDEX_DOC)


if __name__ == "__main__":
    unittest.main()
