"""Render both Compose modes with synthetic credentials; never start containers."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "platform" / "compose"
DATABASES = {"accounts-postgres", "business-postgres", "keycloak-postgres"}
APP_DATABASES = {
    "accounts-service": "ACCOUNTS",
    "pricing-service": "PRICING",
    "delivery-service": "DELIVERY",
    "communications-service": "COMMUNICATIONS",
}
DATABASE_PREFIXES = (*APP_DATABASES.values(), "KEYCLOAK")


def synthetic_environment(external=False):
    # Do not inherit real DB credentials, Compose profiles or other shell overrides.
    env = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
    for prefix in DATABASE_PREFIXES:
        env[f"{prefix}_DB_USERNAME"] = f"{prefix.lower()}_app.synthetic"
        env[f"{prefix}_DB_PASSWORD"] = "synthetic-config-check-only"
        if external:
            env[f"{prefix}_DB_URL"] = (
                "jdbc:postgresql://example.pooler.supabase.com:5432/"
                f"{prefix.lower()}_db?sslmode=require"
            )
    for name in (
        "KEYCLOAK_ADMIN_PASSWORD", "MINIO_ROOT_PASSWORD", "POSTGRES_PASSWORD",
        "DELIVERY_CLIENT_SECRET", "COMMUNICATIONS_CLIENT_SECRET", "GRAPHQL_CLIENT_SECRET",
    ):
        env[name] = "synthetic-config-check-only"
    env["WEBHOOK_ENCRYPTION_KEY"] = "0" * 64
    return env


@unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI unavailable")
class SupabaseComposeTests(unittest.TestCase):
    @classmethod
    def render(cls, external=False, all_profiles=False, env=None):
        cmd = ["docker", "compose", "--env-file", "/dev/null", "-f", str(COMPOSE / "compose.yaml")]
        if external:
            cmd.extend(["-f", str(COMPOSE / "compose.supabase.yaml")])
        if all_profiles:
            cmd.extend(["--profile", "*"])
        cmd.extend(["config", "--format", "json"])
        result = subprocess.run(
            cmd, cwd=ROOT, env=env or synthetic_environment(external),
            capture_output=True, text=True, check=False,
        )
        # Avoid echoing rendered credentials or Compose errors in test reports.
        if result.returncode:
            raise AssertionError(f"Compose validation failed with exit {result.returncode}")
        return json.loads(result.stdout)

    @classmethod
    def setUpClass(cls):
        cls.local = cls.render()
        cls.external = cls.render(external=True)
        cls.all_external = cls.render(external=True, all_profiles=True)

    def test_default_local_mode_retains_all_databases_and_dependencies(self):
        services = self.local["services"]
        self.assertTrue(DATABASES <= services.keys())
        for service, prefix in APP_DATABASES.items():
            host = "accounts-postgres" if prefix == "ACCOUNTS" else "business-postgres"
            self.assertEqual(
                services[service]["environment"][f"{prefix}_DB_URL"],
                f"jdbc:postgresql://{host}:5432/{prefix.lower()}_db",
            )
            self.assertIn(host, services[service]["depends_on"])
        self.assertEqual(
            services["keycloak"]["environment"]["KC_DB_URL"],
            "jdbc:postgresql://keycloak-postgres:5432/keycloak_db",
        )
        self.assertIn("--import-realm", services["keycloak"]["command"])

    def test_external_mode_excludes_local_databases_from_active_dependency_graph(self):
        services = self.external["services"]
        self.assertFalse(DATABASES & services.keys())
        for service, spec in services.items():
            dependencies = set(spec.get("depends_on", {}))
            self.assertFalse(DATABASES & dependencies, f"{service} starts local PostgreSQL")
            self.assertTrue(dependencies <= services.keys(), f"{service} has unresolved dependencies")
        for database in DATABASES:
            self.assertEqual(self.all_external["services"][database]["profiles"], ["local-postgres"])

    def test_external_mode_preserves_other_runtime_dependencies(self):
        for name, spec in self.external["services"].items():
            self.assertEqual(
                set(spec.get("depends_on", {})),
                set(self.local["services"][name].get("depends_on", {})) - DATABASES,
                name,
            )
        # Compose omits inactive resources from its active model; declarations
        # and existing volumes are retained when all profiles are inspected.
        self.assertEqual(set(self.all_external["volumes"]), set(self.local["volumes"]))
        for name in ("kafka", "minio", "minio-init", "grpc-certs", "api-gateway", "frontend"):
            self.assertIn(name, self.external["services"])

    def test_external_urls_credentials_and_small_pools_reach_only_owned_services(self):
        env = synthetic_environment(external=True)
        for service, prefix in APP_DATABASES.items():
            settings = self.external["services"][service]["environment"]
            for suffix in ("URL", "USERNAME", "PASSWORD"):
                key = f"{prefix}_DB_{suffix}"
                self.assertEqual(settings[key], env[key])
            self.assertEqual(settings["SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"], "2")
            self.assertEqual(settings["SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"], "0")
            self.assertEqual(settings.get("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", settings.get("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT")), "5000")
            for other in set(DATABASE_PREFIXES) - {prefix}:
                self.assertNotIn(f"{other}_DB_PASSWORD", settings)
        keycloak = self.external["services"]["keycloak"]["environment"]
        self.assertEqual(keycloak["KC_DB_URL"], env["KEYCLOAK_DB_URL"])
        self.assertEqual(keycloak["KC_DB_USERNAME"], env["KEYCLOAK_DB_USERNAME"])
        self.assertEqual(keycloak["KC_DB_PASSWORD"], env["KEYCLOAK_DB_PASSWORD"])
        for key, expected in (("INITIAL", "0"), ("MIN", "0"), ("MAX", "2")):
            self.assertEqual(keycloak[f"KC_DB_POOL_{key}_SIZE"], expected)
        frontend = self.external["services"]["frontend"].get("environment", {})
        self.assertFalse(any("DB_PASSWORD" in key or "DB_URL" in key for key in frontend))

    def test_external_mode_disables_schema_changes_and_realm_import(self):
        for service in APP_DATABASES:
            settings = self.external["services"][service]["environment"]
            self.assertEqual(settings["SPRING_FLYWAY_ENABLED"], "false")
            self.assertEqual(settings["SPRING_JPA_HIBERNATE_DDL_AUTO"], "validate")
        command = self.external["services"]["keycloak"]["command"]
        self.assertNotIn("--import-realm", command)
        self.assertIn("--spi-connections-jpa--quarkus--migration-strategy=validate", command)
        self.assertIn("--spi-connections-jpa--quarkus--initialize-empty=false", command)

    def test_inspection_admin_credentials_never_reach_runtime_or_image_builds(self):
        for service, spec in self.external["services"].items():
            settings = spec.get("environment", {})
            self.assertFalse(any(key.startswith("SUPABASE_") for key in settings), service)
            runtime_and_build = json.dumps({"environment": settings, "build": spec.get("build", {})})
            self.assertNotIn("synthetic-inspection-admin-only", runtime_and_build, service)

    def test_external_urls_are_required_instead_of_falling_back_to_local_databases(self):
        for prefix in DATABASE_PREFIXES:
            with self.subTest(prefix=prefix):
                env = synthetic_environment(external=True)
                del env[f"{prefix}_DB_URL"]
                result = subprocess.run(
                    ["docker", "compose", "--env-file", "/dev/null", "-f", str(COMPOSE / "compose.yaml"),
                     "-f", str(COMPOSE / "compose.supabase.yaml"), "config", "--quiet"],
                    cwd=ROOT, env=env, capture_output=True, text=True, check=False,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f"{prefix}_DB_URL", result.stderr)


class SupabaseLifecycleGuardTests(unittest.TestCase):
    def test_mutating_local_suites_refuse_explicit_external_mode_before_any_tool_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "tool-called"
            docker = Path(directory) / "docker"
            docker.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 99\n", encoding="utf-8")
            docker.chmod(0o755)
            env = dict(os.environ, PATH=f"{directory}:{os.environ.get('PATH', '')}")
            for name in ("verify.sh", "smoke.sh"):
                with self.subTest(script=name):
                    result = subprocess.run(
                        ["bash", str(COMPOSE / name), "--supabase"],
                        cwd=ROOT, env=env, capture_output=True, text=True, check=False,
                    )
                    self.assertEqual(result.returncode, 1)
                    self.assertIn("supabase.py check", result.stdout)
                    self.assertFalse(marker.exists())

    def test_local_suites_refuse_a_running_external_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            docker = Path(directory) / "docker"
            docker.write_text(
                "#!/bin/sh\nif [ \"$1\" = ps ]; then\n"
                "  case \"$*\" in\n"
                "    *label=com.docker.compose.project=swiftdrop*label=com.swiftdrop.database-mode=supabase*)\n"
                "      printf 'custom-keycloak-container-id\\n'; exit 0;;\n"
                "  esac\nfi\nexit 99\n",
                encoding="utf-8",
            )
            docker.chmod(0o755)
            env = dict(os.environ, PATH=f"{directory}:{os.environ.get('PATH', '')}")
            for name in ("verify.sh", "smoke.sh"):
                with self.subTest(script=name):
                    result = subprocess.run(
                        ["bash", str(COMPOSE / name)], cwd=ROOT, env=env,
                        capture_output=True, text=True, check=False,
                    )
                    self.assertEqual(result.returncode, 1)
                    self.assertIn("supabase.py check", result.stdout)


if __name__ == "__main__":
    unittest.main()
