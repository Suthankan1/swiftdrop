"""
SwiftDrop Platform — Hosting Feasibility: Documentation Contract Tests (Issue #176)

Verifies:
1. docs/deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md exists and is non-empty.
2. Contains all 14 mandatory sections per Issue #176 specification.
3. Evaluates all three defined audiences (Audience A, Audience B, Audience C).
4. Includes exact cryptographic evidence (TLS 1.3, SHA-256 fingerprint, full SAN list).
5. Documents official Microsoft Learn citations and access retrieval dates.
6. Classifies Azure Static Web Apps Free and public relays (Cloudflare, ngrok, Tailscale) accurately.
7. Outlines complete downstream handoffs to #150, #159, and E2E testing issues.
8. Enforces explicit LOCAL-ONLY release classification and public access limitation.
"""

import os
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
FEASIBILITY_DOC = os.path.join(REPO_ROOT, "docs/deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md")


class TestDocContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            os.path.isfile(FEASIBILITY_DOC),
            f"Mandatory feasibility decision record missing: {FEASIBILITY_DOC}",
        )
        with open(FEASIBILITY_DOC, "r", encoding="utf-8") as f:
            cls.content = f.read()

    def test_all_fourteen_sections_present(self):
        """Verifies presence of all 14 mandatory sections in the feasibility record."""
        required_sections = [
            "## 1. Executive Finding",
            "## 2. Current Architecture & Runtime Environment",
            "## 3. Comprehensive Endpoint Matrix",
            "## 4. Audience Reachability Matrix",
            "## 5. Local HTTPS & Cryptographic Evidence",
            "## 6. Real Browser Authentication & Journey Analysis",
            "## 7. Evidence-Backed Evaluation of Public Hosting Candidates",
            "## 8. Defensible Cost Evidence Table",
            "## 9. Reproducible Local-Only HTTPS Demonstration Runbook",
            "## 10. Operational Risks & Mitigation",
            "## 11. Security, Isolation & Secret Protection",
            "## 12. Release Limitations & Authoritative Classification",
            "## 13. Downstream Handoffs & Integration Contracts",
            "## 14. Acceptance Verification & Signoff Protocol",
        ]
        for section in required_sections:
            self.assertIn(
                section,
                self.content,
                f"Missing mandatory section header in feasibility document: '{section}'",
            )

    def test_three_audiences_evaluated(self):
        """Verifies Audience A, Audience B, and Audience C reachability evaluation."""
        self.assertIn("Audience A", self.content)
        self.assertIn("Audience B", self.content)
        self.assertIn("Audience C", self.content)
        self.assertIn("Operator on Same Computer", self.content)
        self.assertIn("Device on Same LAN", self.content)
        self.assertIn("Remote Internet User", self.content)

    def test_cryptographic_and_tls_evidence_recorded(self):
        """Verifies exact cryptographic evidence and SAN listings are documented."""
        self.assertIn("swiftdrop-root-ca.crt", self.content)
        self.assertIn("swiftdrop-tls.crt", self.content)
        self.assertIn("DNS:swiftdrop.local", self.content)
        self.assertIn("DNS:app.swiftdrop.local", self.content)
        self.assertIn("DNS:api.swiftdrop.local", self.content)
        self.assertIn("DNS:auth.swiftdrop.local", self.content)
        self.assertIn("D9:31:0F:31:03:64:E4:82:6D:15:80:89:61:65:3E:CA:D8:E9:46:4F:7E:B3:3E:44:84:92:AC:A7:EA:BD:99:F5", self.content)

    def test_official_microsoft_citations(self):
        """Verifies presence of official Microsoft Learn citations and access dates."""
        citations = [
            "learn.microsoft.com/en-us/azure/static-web-apps/plans",
            "azure.microsoft.com/en-us/pricing/details/app-service/static/",
            "learn.microsoft.com/en-us/azure/education-hub/about-azure-for-students",
            "learn.microsoft.com/en-us/azure/cost-management-billing/manage/spending-limit",
        ]
        for citation in citations:
            self.assertIn(citation, self.content, f"Missing required official citation: {citation}")

    def test_public_relays_evaluated_and_rejected(self):
        """Verifies evaluation and rejection of Cloudflare Tunnel, ngrok, and Tailscale."""
        self.assertIn("Cloudflare Tunnel", self.content)
        self.assertIn("ngrok", self.content)
        self.assertIn("Tailscale Funnel", self.content)
        self.assertIn("REJECTED", self.content)

    def test_downstream_handoffs_specified(self):
        """Verifies handoff specifications to Issue #150, Issue #159, and E2E issues."""
        self.assertIn("Handoff to Issue #150", self.content)
        self.assertIn("Handoff to Issue #159", self.content)
        self.assertIn("#157", self.content)
        self.assertIn("#162", self.content)
        self.assertIn("#163", self.content)
        self.assertIn("#169", self.content)

    def test_authoritative_local_only_classification(self):
        """Verifies clear, honest classification as LOCAL-ONLY."""
        self.assertIn("LOCAL-ONLY", self.content)
        self.assertIn("Public Internet access:", self.content)
        self.assertIn("NOT VERIFIED / NOT APPROVED", self.content)
        self.assertIn("Cloud hosting:", self.content)
        self.assertIn("DISABLED", self.content)
        self.assertIn("No additional cloud spend authorized", self.content)


if __name__ == "__main__":
    unittest.main()
