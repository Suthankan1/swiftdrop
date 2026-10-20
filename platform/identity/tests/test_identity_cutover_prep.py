#!/usr/bin/env python3
"""Unit tests for Phase 2B identity cutover preparation, backup integrity, and safety gates."""

from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import tempfile
import unittest

# Ensure platform/identity is on path
PLATFORM_IDENTITY_DIR = Path(__file__).resolve().parent.parent
sys_path_str = str(PLATFORM_IDENTITY_DIR)
import sys
if sys_path_str not in sys.path:
    sys.path.insert(0, sys_path_str)

cutover_mod = importlib.import_module("cutover-identity-migration")
CutoverError = cutover_mod.CutoverError
IdentityCutoverEngine = cutover_mod.IdentityCutoverEngine

backup_mod = importlib.import_module("backup-restore-identity")
BackupError = backup_mod.BackupError
BackupEngine = backup_mod.BackupEngine
StorageInspector = backup_mod.StorageInspector
SafetyGateEvaluator = backup_mod.SafetyGateEvaluator


class MockAdminClient:
    """Mock Keycloak Admin API for testing preflight and cutover logic."""

    def __init__(self, current_realm: str = "devarom"):
        self.current_realm = current_realm
        self.requests: list = []

    def request(self, path: str, method: str = "GET", payload: dict = None, expected_status: tuple = (200,)):
        self.requests.append({"path": path, "method": method, "payload": payload})

        if path == f"/admin/realms/{self.current_realm}" and method == "GET":
            return 200, {
                "id": "devarom",
                "realm": self.current_realm,
                "displayName": "SwiftDrop",
                "enabled": True,
            }

        if path == f"/admin/realms/{self.current_realm}/clients" and method == "GET":
            client_id = f"{self.current_realm}-frontend" if self.current_realm == "swiftdrop" else "devarom-frontend"
            return 200, [
                {
                    "id": "2d80857e-52df-4d3c-9413-bc74a53e1de5",
                    "clientId": client_id,
                    "publicClient": True,
                }
            ]

        if "/protocol-mappers/models" in path and method == "GET":
            return 200, [
                {
                    "id": "53ec84cc-aa93-4344-84ce-369bbee64f6e",
                    "name": "devarom-api-audience",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-audience-mapper",
                    "config": {
                        "included.custom.audience": f"{self.current_realm}-api"
                    },
                }
            ]

        if "/users" in path and method == "GET":
            return 200, [
                {
                    "id": "sub-alice-uuid-001",
                    "username": "alice",
                    "email": "alice@swiftdrop.test",
                    "emailVerified": True,
                }
            ]

        if "/credentials" in path and method == "GET":
            return 200, [{"id": "c1", "type": "password"}]

        if "/federated-identity" in path and method == "GET":
            return 200, [{"identityProvider": "google", "userId": "google-sub-1"}]

        if "/role-mappings/realm" in path and method == "GET":
            return 200, [{"name": "CUSTOMER"}]

        if "/identity-provider/instances" in path and method == "GET":
            return 200, [{"alias": "google", "providerId": "google"}]

        if method == "PUT":
            if "/admin/realms/" in path:
                if payload and "realm" in payload:
                    self.current_realm = payload["realm"]
            return 204, None

        return 200, {}


class TestCutoverPreflightAndSafety(unittest.TestCase):
    def test_preflight_verifies_realm_and_client_without_mutations(self):
        mock_admin = MockAdminClient("devarom")
        engine = IdentityCutoverEngine(mock_admin)
        pre = engine.run_read_only_preflight("devarom")

        self.assertEqual(pre["realm_internal_id"], "devarom")
        self.assertEqual(pre["realm_name"], "devarom")
        self.assertEqual(pre["frontend_client_id"], "devarom-frontend")
        self.assertEqual(pre["frontend_client_internal_uuid"], "2d80857e-52df-4d3c-9413-bc74a53e1de5")
        self.assertEqual(pre["current_audience"], "devarom-api")
        self.assertTrue(pre["google_broker_configured"])
        self.assertEqual(pre["user_count"], 1)

        # Confirm zero PUT/POST mutation calls were made during preflight
        methods = [r["method"] for r in mock_admin.requests]
        self.assertTrue(all(m == "GET" for m in methods), "Preflight must be strictly read-only")

    def test_in_place_cutover_preserves_internal_ids_and_user_uuids(self):
        mock_admin = MockAdminClient("devarom")
        engine = IdentityCutoverEngine(mock_admin)
        res = engine.execute_in_place_cutover(
            source_realm="devarom",
            target_realm="swiftdrop",
            target_client_id="swiftdrop-frontend",
            target_audience="swiftdrop-api",
        )

        self.assertTrue(res["identity_invariants_preserved"])
        post = res["post_cutover_verification"]
        self.assertEqual(post["realm_internal_id"], "devarom", "Realm internal ID must remain devarom")
        self.assertEqual(post["realm_name"], "swiftdrop", "Realm name must be swiftdrop")
        self.assertEqual(post["frontend_client_internal_uuid"], "2d80857e-52df-4d3c-9413-bc74a53e1de5")
        self.assertEqual(post["audience_mapper_id"], "53ec84cc-aa93-4344-84ce-369bbee64f6e")
        self.assertEqual(post["users"][0]["id"], "sub-alice-uuid-001", "User UUID must be 100% preserved")

    def test_in_place_rollback_restores_legacy_names(self):
        mock_admin = MockAdminClient("swiftdrop")
        engine = IdentityCutoverEngine(mock_admin)
        res = engine.execute_in_place_rollback(
            current_realm="swiftdrop",
            target_realm="devarom",
            target_client_id="devarom-frontend",
            target_audience="devarom-api",
        )
        post = res["post_rollback_verification"]
        self.assertEqual(post["realm_name"], "devarom")
        self.assertEqual(post["realm_internal_id"], "devarom")
        self.assertEqual(post["users"][0]["id"], "sub-alice-uuid-001")


class TestBackupAndSafetyGate(unittest.TestCase):
    def test_backup_engine_checksum_verification_success(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            backup_dir = Path(tmpdir)
            db_file = backup_dir / "keycloakdb.mv.db"
            db_file.write_bytes(b"H2_TEST_DATABASE_CONTENT")

            sha256 = BackupEngine.compute_sha256(db_file)
            checksum_file = backup_dir / "keycloakdb.mv.db.sha256"
            checksum_file.write_text(f"{sha256}  keycloakdb.mv.db\n", encoding="utf-8")

            res = BackupEngine.verify_backup_directory(backup_dir)
            self.assertTrue(res["checksum_verified"])
            self.assertEqual(res["sha256"], sha256)
            self.assertEqual(res["status"], "VALID")

    def test_backup_engine_detects_corrupted_checksum(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            backup_dir = Path(tmpdir)
            db_file = backup_dir / "keycloakdb.mv.db"
            db_file.write_bytes(b"CORRUPTED_H2_CONTENT")

            checksum_file = backup_dir / "keycloakdb.mv.db.sha256"
            checksum_file.write_text("0000000000000000000000000000000000000000000000000000000000000000  keycloakdb.mv.db\n")

            with self.assertRaises(BackupError) as ctx:
                BackupEngine.verify_backup_directory(backup_dir)
            self.assertIn("Checksum mismatch", str(ctx.exception))

    def test_safety_gate_returns_no_go_without_maintenance_approval(self):
        os.environ["MAINTENANCE_WINDOW_APPROVED"] = "false"
        os.environ["GOOGLE_CALLBACK_VERIFIED"] = "false"
        gate = SafetyGateEvaluator.evaluate_cutover_gate()

        self.assertEqual(gate["classification"], "NO-GO")
        self.assertGreaterEqual(len(gate["blocking_reasons"]), 2)
        self.assertIn("DO NOT EXECUTE ACTIVE REALM MIGRATION YET", gate["recommendation"])


class TestGoogleCloudCallbackPrerequisite(unittest.TestCase):
    def test_target_google_callback_format(self):
        legacy = "http://localhost:8090/realms/devarom/broker/google/endpoint"
        target = "http://localhost:8090/realms/swiftdrop/broker/google/endpoint"

        self.assertTrue(legacy.endswith("/realms/devarom/broker/google/endpoint"))
        self.assertTrue(target.endswith("/realms/swiftdrop/broker/google/endpoint"))
        self.assertNotEqual(legacy, target)


if __name__ == "__main__":
    unittest.main()
