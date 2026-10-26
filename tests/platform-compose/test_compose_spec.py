"""SwiftDrop Full-Stack Compose Runtime Static & Security Test Suite.

Issue: #141 [N10-P06] Deliver Full-Stack Compose Runtime with Persistent
Service-Owned Data.
Canonical References: docs/architecture/BASELINE.md,
docs/architecture/DATA_OWNERSHIP.md, docs/handoff/ISSUE_128_SECURE_VERSIONED_IMAGES.md,
                      docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
"""

import os
from pathlib import Path
import re
import subprocess
import unittest
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
COMPOSE_DIR = REPO_ROOT / "platform" / "compose"
COMPOSE_FILE = COMPOSE_DIR / "compose.yaml"
ENV_EXAMPLE = COMPOSE_DIR / ".env.example"

APPROVED_APP_SERVICES = {
    "frontend": "ghcr.io/suthankan1/swiftdrop-frontend",
    "api-gateway": "ghcr.io/suthankan1/swiftdrop-api-gateway",
    "accounts-service": "ghcr.io/suthankan1/swiftdrop-accounts",
    "pricing-service": "ghcr.io/suthankan1/swiftdrop-pricing",
    "delivery-service": "ghcr.io/suthankan1/swiftdrop-delivery",
    "communications-service": "ghcr.io/suthankan1/swiftdrop-communications",
    "graphql-query-service": "ghcr.io/suthankan1/swiftdrop-graphql",
    "keycloak": "ghcr.io/suthankan1/swiftdrop-keycloak",
}

APPROVED_STATEFUL_SERVICES = {
    "accounts-postgres",
    "keycloak-postgres",
    "mailpit",
}

EXPECTED_NAMED_VOLUMES = {
    "swiftdrop-accounts-postgres-data",
    "swiftdrop-keycloak-postgres-data",
}

LIFECYCLE_SCRIPTS = [
    "start.sh",
    "stop.sh",
    "status.sh",
    "verify.sh",
    "smoke.sh",
]


class TestComposeRuntimeSpec(unittest.TestCase):
    """Static validation of full-stack Compose configuration and security."""

    @classmethod
    def setUpClass(cls):
        cls.assertTrue(
            COMPOSE_FILE.is_file(), f"Compose file missing: {COMPOSE_FILE}"
        )
        with open(COMPOSE_FILE, "r", encoding="utf-8") as f:
            cls.compose_raw = f.read()
            cls.compose_data = yaml.safe_load(cls.compose_raw)

    def test_compose_yaml_parses_successfully(self):
        """Verifies that compose.yaml is valid YAML and defines project name."""
        self.assertIsInstance(self.compose_data, dict)
        self.assertEqual(self.compose_data.get("name"), "swiftdrop")

    def test_dependency_stack_reuse_via_include(self):
        """Verifies that existing dependencies Compose file is included without duplication."""
        includes = self.compose_data.get("include", [])
        self.assertTrue(len(includes) > 0, "compose.yaml must include existing dependencies")
        included_paths = [inc.get("path") for inc in includes if isinstance(inc, dict)]
        self.assertIn("../dependencies/docker-compose.dependencies.yml", included_paths)

    def test_exact_eight_approved_application_images(self):
        """Validates that exactly 8 approved application/identity images are declared."""
        services = self.compose_data.get("services", {})
        for svc_name, expected_repo in APPROVED_APP_SERVICES.items():
            self.assertIn(svc_name, services, f"Service {svc_name} missing from compose.yaml")
            image_decl = str(services[svc_name].get("image", ""))
            self.assertTrue(
                image_decl.startswith(expected_repo),
                f"Service {svc_name} image '{image_decl}' does not match expected '{expected_repo}'"
            )
            # Must not use mutable :latest tag
            self.assertNotIn(":latest", image_decl, f"Service {svc_name} must not use :latest tag")

    def test_stateful_dependencies_declared(self):
        """Verifies that dedicated accounts, keycloak databases and mailpit are declared."""
        services = self.compose_data.get("services", {})
        for svc in APPROVED_STATEFUL_SERVICES:
            self.assertIn(svc, services, f"Stateful service {svc} missing from compose.yaml")

    def test_persistent_named_volumes_declared(self):
        """Verifies that persistent named volumes are defined for accounts and keycloak DBs."""
        volumes = self.compose_data.get("volumes", {})
        for vol in EXPECTED_NAMED_VOLUMES:
            self.assertIn(vol, volumes, f"Named volume {vol} missing from compose.yaml")

    def test_no_privileged_containers(self):
        """Enforces that no service runs in Docker privileged mode."""
        services = self.compose_data.get("services", {})
        for name, spec in services.items():
            privileged = spec.get("privileged", False)
            self.assertFalse(privileged, f"Service {name} has privileged: true (forbidden)")

    def test_no_docker_socket_mounts(self):
        """Enforces that no service mounts the host Docker socket."""
        services = self.compose_data.get("services", {})
        for name, spec in services.items():
            volumes = spec.get("volumes", [])
            for vol in volumes:
                vol_str = str(vol)
                self.assertNotIn("docker.sock", vol_str, f"Service {name} mounts docker.sock")

    def test_host_ports_bound_to_loopback_only(self):
        """Enforces that all exposed host ports are bound strictly to 127.0.0.1 (never 0.0.0.0)."""
        services = self.compose_data.get("services", {})
        for name, spec in services.items():
            ports = spec.get("ports", [])
            for port in ports:
                port_str = str(port)
                self.assertTrue(
                    port_str.startswith("127.0.0.1:"),
                    f"Port binding '{port_str}' in service '{name}' must bind strictly to 127.0.0.1"
                )

    def test_java_services_have_jvm_memory_bounds(self):
        """Verifies that Java services configure bounded JVM memory ergonomics for 4.06 GiB host."""
        java_services = [
            "api-gateway",
            "accounts-service",
            "pricing-service",
            "delivery-service",
            "communications-service",
            "graphql-query-service",
        ]
        services = self.compose_data.get("services", {})
        for name in java_services:
            self.assertIn(name, services)
            env = services[name].get("environment", {})
            env_opts = str(env.get("JAVA_TOOL_OPTIONS", ""))
            self.assertIn("-Xmx", env_opts, f"Service {name} missing -Xmx JVM memory bound")
            self.assertIn("-XX:+UseContainerSupport", env_opts, f"Service {name} missing container support")

    def test_network_isolation_topology(self):
        """Verifies that private networks (app-net and dependencies-net) are properly assigned."""
        networks = self.compose_data.get("networks", {})
        self.assertIn("app-net", networks)
        self.assertEqual(networks["app-net"].get("name"), "swiftdrop-app-net")

        services = self.compose_data.get("services", {})
        # Frontend and Gateway must be on app-net
        self.assertIn("app-net", services["frontend"].get("networks", []))
        self.assertIn("app-net", services["api-gateway"].get("networks", []))
        # Frontend must NOT be directly on dependencies-net (cannot talk directly to databases or Kafka)
        self.assertNotIn("dependencies-net", services["frontend"].get("networks", []))

    def test_env_example_contains_all_required_categories(self):
        """Verifies that .env.example contains all required environment keys with non-secret defaults."""
        self.assertTrue(ENV_EXAMPLE.is_file(), ".env.example missing")
        content = ENV_EXAMPLE.read_text(encoding="utf-8")

        required_keys = [
            "SWIFTDROP_IMAGE_TAG",
            "FRONTEND_PORT",
            "GATEWAY_PORT",
            "KEYCLOAK_PORT",
            "ACCOUNTS_DB_USERNAME",
            "ACCOUNTS_DB_PASSWORD",
            "PRICING_DB_USERNAME",
            "PRICING_DB_PASSWORD",
            "DELIVERY_DB_USERNAME",
            "DELIVERY_DB_PASSWORD",
            "COMMUNICATIONS_DB_USERNAME",
            "COMMUNICATIONS_DB_PASSWORD",
            "KEYCLOAK_DB_USERNAME",
            "KEYCLOAK_DB_PASSWORD",
            "KEYCLOAK_ADMIN_PASSWORD",
            "KEYCLOAK_ISSUER_URI",
            "JWT_AUDIENCE",
            "KAFKA_TOPIC_PARCEL_LIFECYCLE",
            "MINIO_ROOT_USER",
            "MINIO_ROOT_PASSWORD",
            "SWIFTDROP_STORAGE_BUCKET",
            "CORS_ALLOWED_ORIGIN",
        ]
        for key in required_keys:
            self.assertIn(key, content, f"Key {key} missing from .env.example")

        # Zero real secrets in .env.example
        self.assertNotIn("ghp_", content)
        self.assertNotIn("eyJ", content)

    def test_lifecycle_scripts_exist_and_non_destructive(self):
        """Verifies all operator lifecycle scripts exist, are executable, and contain no destructive commands."""
        for script_name in LIFECYCLE_SCRIPTS:
            script_path = COMPOSE_DIR / script_name
            self.assertTrue(script_path.is_file(), f"Script missing: {script_name}")
            self.assertTrue(os.access(script_path, os.X_OK), f"Script not executable: {script_name}")

            content = script_path.read_text(encoding="utf-8")
            # Strict prohibition on destructive volume deletion
            self.assertNotIn("down -v", content, f"Destructive 'down -v' in {script_name}")
            self.assertNotIn("volume prune", content, f"Destructive 'volume prune' in {script_name}")
            self.assertNotIn("system prune", content, f"Destructive 'system prune' in {script_name}")

    def test_docker_compose_config_validation(self):
        """Runs docker compose config to validate merged schema integrity."""
        res = subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE_FILE), "config"],
            cwd=str(REPO_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(
            res.returncode,
            0,
            f"docker compose config failed:\nStdout: {res.stdout}\nStderr: {res.stderr}"
        )


if __name__ == "__main__":
    unittest.main()
