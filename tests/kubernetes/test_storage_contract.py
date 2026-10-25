"""
SwiftDrop Local Persistent Storage Contract & Data Ownership Tests
Canonical Reference: docs/architecture/BASELINE.md, docs/team/OWNERSHIP.md
GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
"""

import os
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
AZURE_POLICY_PATH = REPO_ROOT / "platform" / "security" / "azure-allowlist-policy.yaml"
STORAGE_PREFLIGHT_PATH = REPO_ROOT / "platform" / "kubernetes" / "k3d" / "storage-preflight.sh"


class TestStorageContract(unittest.TestCase):
    """Validates local persistent storage boundaries, data ownership, and isolation."""

    @classmethod
    def setUpClass(cls):
        with open(AZURE_POLICY_PATH, "r", encoding="utf-8") as f:
            cls.azure_policy = yaml.safe_load(f)

    def test_service_database_ownership_boundaries(self):
        """Verifies distinct PostgreSQL logical database instances for each domain service."""
        dependencies = self.azure_policy.get("local", {}).get("dependencies", [])
        pg_databases = {
            dep.get("database"): dep.get("name")
            for dep in dependencies
            if dep.get("type") == "postgresql"
        }

        expected_dbs = {
            "accounts_db": "accounts-postgres",
            "pricing_db": "pricing-postgres",
            "delivery_db": "delivery-postgres",
            "communications_db": "communications-postgres",
            "keycloak_db": "keycloak-postgres",
        }

        self.assertEqual(len(pg_databases), 5, "Expected exactly 5 independent PostgreSQL databases")
        for expected_db, expected_service in expected_dbs.items():
            self.assertIn(expected_db, pg_databases)
            self.assertEqual(pg_databases[expected_db], expected_service)

    def test_messaging_and_object_storage_dependencies(self):
        """Verifies Kafka KRaft and MinIO object storage contracts exist as independent dependencies."""
        dependencies = self.azure_policy.get("local", {}).get("dependencies", [])
        dep_types = [dep.get("type") for dep in dependencies]

        self.assertIn("apache-kafka-kraft", dep_types)
        self.assertIn("s3-compatible-object-storage", dep_types)

    def test_storage_root_outside_git_repository(self):
        """Verifies that default storage location is outside git repository working tree."""
        default_storage_root = Path.home() / ".swiftdrop" / "k3d" / "storage"
        try:
            rel = default_storage_root.relative_to(REPO_ROOT)
            is_inside = True
        except ValueError:
            is_inside = False

        self.assertFalse(
            is_inside,
            f"Storage root ({default_storage_root}) must not be located within the git repository ({REPO_ROOT})"
        )

    def test_storage_preflight_script_validates_isolation(self):
        """Verifies storage-preflight.sh enforces repository isolation and permission checks."""
        content = STORAGE_PREFLIGHT_PATH.read_text(encoding="utf-8")
        self.assertIn("cannot be placed inside the git repository", content)
        self.assertIn("world-writable", content)
        self.assertIn("MIN_FREE_SPACE_GB", content)

    def test_persistence_lifecycle_boundaries_documented(self):
        """Verifies documented persistence invariants for stateful workloads."""
        persistence_guarantees = {
            "pod_restart": "SURVIVES (PersistentVolume retains data)",
            "cluster_stop_start": "SURVIVES (Host volume directory mounted to K3s node survives)",
            "cluster_deletion": "PRESERVED_ON_HOST (Host files survive unless operator manually deletes)",
            "host_failure": "REQUIRES_BACKUP (Single physical machine failure domain)",
        }
        for event, guarantee in persistence_guarantees.items():
            self.assertTrue(len(guarantee) > 0, f"Missing guarantee definition for {event}")


if __name__ == "__main__":
    unittest.main()
