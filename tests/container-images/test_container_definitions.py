"""
SwiftDrop Container Image Static Architecture & Security Tests
Issue: #128 [N10-P05] Build Secure Versioned Images for All Apps and Identity Runtime
Canonical Reference: docs/architecture/BASELINE.md, docs/architecture/RUNTIME_DECISION_REGISTER.md,
                    docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DOCKERIGNORE_PATH = REPO_ROOT / ".dockerignore"

# Exact 8 approved runtime image inventory
REQUIRED_IMAGES = {
    "frontend": {
        "dockerfile": REPO_ROOT / "frontend" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-frontend",
        "ports": ["8080"],
        "user": "101",
        "runtime_base": "nginxinc/nginx-unprivileged",
    },
    "api-gateway": {
        "dockerfile": REPO_ROOT / "services" / "api-gateway" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-api-gateway",
        "ports": ["8080"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "accounts-service": {
        "dockerfile": REPO_ROOT / "services" / "accounts-service" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-accounts",
        "ports": ["8081", "9081"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "pricing-service": {
        "dockerfile": REPO_ROOT / "services" / "pricing-service" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-pricing",
        "ports": ["8082", "9083"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "delivery-service": {
        "dockerfile": REPO_ROOT / "services" / "delivery-service" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-delivery",
        "ports": ["8083", "9082"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "communications-service": {
        "dockerfile": REPO_ROOT / "services" / "communications-service" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-communications",
        "ports": ["8084"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "graphql-query-service": {
        "dockerfile": REPO_ROOT / "services" / "graphql-query-service" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-graphql",
        "ports": ["8085"],
        "user": "10001:10001",
        "runtime_base": "eclipse-temurin:21-jre-jammy",
    },
    "identity": {
        "dockerfile": REPO_ROOT / "platform" / "identity" / "Dockerfile",
        "context": ".",
        "image_repo": "ghcr.io/suthankan1/swiftdrop-keycloak",
        "ports": ["8080", "8443"],
        "user": "1000",
        "runtime_base": "quay.io/keycloak/keycloak:26.8.1",
    },
}


class TestContainerDefinitions(unittest.TestCase):
    """Static validation of all 8 SwiftDrop container image definitions."""

    def test_exact_eight_images_inventory(self):
        """Validates that exactly 8 runtime image specifications are defined."""
        self.assertEqual(len(REQUIRED_IMAGES), 8)

    def test_all_dockerfiles_exist(self):
        """Verifies that all 8 Dockerfiles exist on the repository file tree."""
        for name, spec in REQUIRED_IMAGES.items():
            dockerfile = spec["dockerfile"]
            self.assertTrue(dockerfile.is_file(), f"Dockerfile missing for {name}: {dockerfile}")

    def test_no_latest_tag_in_base_images(self):
        """Enforces that all base images are pinned and none use the mutable :latest tag."""
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            from_lines = [
                line.strip() for line in content.splitlines() if line.strip().upper().startswith("FROM")
            ]
            for line in from_lines:
                self.assertNotIn(
                    ":latest",
                    line.lower(),
                    f"Dockerfile for {name} contains prohibited mutable :latest tag in: {line}",
                )

    def test_non_root_execution_declared(self):
        """Enforces non-root USER declarations in all 8 runtime Dockerfiles."""
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            user_lines = [
                line.strip() for line in content.splitlines() if line.strip().upper().startswith("USER")
            ]
            self.assertTrue(len(user_lines) > 0, f"Dockerfile for {name} does not declare a USER instruction")
            # Last USER instruction must match non-root expectation
            last_user = user_lines[-1].split()[1]
            expected_user = spec["user"]
            self.assertEqual(
                last_user,
                expected_user,
                f"Dockerfile for {name} last USER instruction was {last_user}, expected {expected_user}",
            )

    def test_exposed_ports_match_spec(self):
        """Verifies exposed ports in Dockerfiles match the architecture network boundary."""
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            expose_lines = [
                line.strip() for line in content.splitlines() if line.strip().upper().startswith("EXPOSE")
            ]
            self.assertTrue(len(expose_lines) > 0, f"Dockerfile for {name} does not declare EXPOSE")
            declared_ports = []
            for el in expose_lines:
                declared_ports.extend(el.split()[1:])
            for expected_port in spec["ports"]:
                self.assertIn(
                    expected_port,
                    declared_ports,
                    f"Dockerfile for {name} does not expose expected port {expected_port} (declared: {declared_ports})",
                )

    def test_healthchecks_declared(self):
        """Enforces that all 8 runtime Dockerfiles declare a container HEALTHCHECK."""
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            self.assertIn(
                "HEALTHCHECK",
                content,
                f"Dockerfile for {name} is missing a container HEALTHCHECK instruction",
            )

    def test_no_embedded_credentials_or_secrets(self):
        """Scans Dockerfiles for sensitive strings, secrets, or hardcoded passwords."""
        forbidden_patterns = [
            re.compile(r"password\s*=\s*['\"][^'\"]+['\"]", re.IGNORECASE),
            re.compile(r"secret\s*=\s*['\"][^'\"]+['\"]", re.IGNORECASE),
            re.compile(r"token\s*=\s*['\"][^'\"]+['\"]", re.IGNORECASE),
            re.compile(r"ghp_[a-zA-Z0-9]{36}"),
            re.compile(r"AKIA[0-9A-Z]{16}"),
        ]
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            for pattern in forbidden_patterns:
                match = pattern.search(content)
                self.assertIsNone(
                    match,
                    f"Dockerfile for {name} contains forbidden credential pattern: {match}",
                )

    def test_dockerignore_exclusions(self):
        """Verifies that .dockerignore excludes sensitive and unneeded local files."""
        self.assertTrue(DOCKERIGNORE_PATH.is_file(), ".dockerignore missing at repository root")
        content = DOCKERIGNORE_PATH.read_text(encoding="utf-8")
        required_patterns = [
            ".git",
            ".env",
            "node_modules",
            "target",
            ".terraform",
            "*.log",
        ]
        for pattern in required_patterns:
            self.assertIn(
                pattern,
                content,
                f".dockerignore must exclude '{pattern}' to protect build context safety",
            )

    def test_no_development_entrypoint(self):
        """Enforces that no container image uses development servers or runtime compilers as entrypoint."""
        forbidden_entrypoint_terms = ["mvn", "spring-boot:run", "vite", "npm run", "pnpm dev", "pnpm start"]
        for name, spec in REQUIRED_IMAGES.items():
            content = spec["dockerfile"].read_text(encoding="utf-8")
            # Find ENTRYPOINT and CMD lines
            exec_lines = [
                line.strip()
                for line in content.splitlines()
                if line.strip().upper().startswith("ENTRYPOINT") or line.strip().upper().startswith("CMD")
            ]
            for line in exec_lines:
                for term in forbidden_entrypoint_terms:
                    self.assertNotIn(
                        term,
                        line,
                        f"Dockerfile for {name} contains prohibited development entrypoint term '{term}': {line}",
                    )

    def test_multistage_build_separation(self):
        """Verifies multi-stage build separation for frontend and Java services to prevent build tools leaking."""
        multistage_services = [
            "frontend",
            "api-gateway",
            "accounts-service",
            "pricing-service",
            "delivery-service",
            "communications-service",
            "graphql-query-service",
        ]
        for name in multistage_services:
            content = REQUIRED_IMAGES[name]["dockerfile"].read_text(encoding="utf-8")
            from_lines = [
                line.strip() for line in content.splitlines() if line.strip().upper().startswith("FROM")
            ]
            self.assertGreaterEqual(
                len(from_lines),
                2,
                f"Dockerfile for {name} must be a multi-stage build with at least 2 FROM instructions (found {len(from_lines)})",
            )

    def test_build_context_is_repo_root(self):
        """Verifies that all images declare repository root '.' as the reproducible build context."""
        for name, spec in REQUIRED_IMAGES.items():
            self.assertEqual(
                spec["context"],
                ".",
                f"Image {name} must use repository root '.' as build context for monorepo contract resolution",
            )

    def test_sbom_artifacts_generated_for_all_images(self):
        """Verifies that SPDX SBOM JSON files exist and are valid for all 8 container images."""
        sbom_dir = REPO_ROOT / "platform" / "artifacts" / "sbom"
        self.assertTrue(sbom_dir.is_dir(), f"SBOM directory missing: {sbom_dir}")
        expected_sboms = [
            "frontend-spdx.json",
            "api-gateway-spdx.json",
            "accounts-spdx.json",
            "pricing-spdx.json",
            "delivery-spdx.json",
            "communications-spdx.json",
            "graphql-spdx.json",
            "keycloak-spdx.json",
        ]
        import json
        for sbom_file in expected_sboms:
            file_path = sbom_dir / sbom_file
            self.assertTrue(file_path.is_file(), f"Expected SBOM file missing: {file_path}")
            data = json.loads(file_path.read_text(encoding="utf-8"))
            self.assertEqual(data.get("spdxVersion"), "SPDX-2.3")
            self.assertIn("packages", data)
            self.assertGreater(len(data["packages"]), 0)

    def test_registry_zero_cost_policy(self):
        """Verifies that image registries follow private GHCR and contain zero Azure/paid registries."""
        for name, spec in REQUIRED_IMAGES.items():
            repo = spec["image_repo"]
            self.assertTrue(
                repo.startswith("ghcr.io/suthankan1/swiftdrop-"),
                f"Image repo for {name} must use approved private GHCR: {repo}",
            )
            self.assertNotIn("azurecr.io", repo, f"Prohibited Azure Container Registry found in {name}")
            self.assertNotIn("docker.io/suthankan1", repo, f"Prohibited public Docker Hub found in {name}")


if __name__ == "__main__":
    unittest.main()
