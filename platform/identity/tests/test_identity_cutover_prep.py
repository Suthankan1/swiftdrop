#!/usr/bin/env python3
"""Unit tests for Phase 2C identity cutover safety hardening, disaster recovery verification, and safety gates."""

from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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
    """Mock Keycloak Admin API for testing preflight, pagination, and cutover logic."""

    def __init__(self, current_realm: str = "devarom", target_realm_exists: bool = False):
        self.current_realm = current_realm
        self.target_realm_exists = target_realm_exists
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

        # Check for target realm existence check
        if path == "/admin/realms/swiftdrop" and method == "GET":
            if self.target_realm_exists:
                return 200, {"id": "swiftdrop", "realm": "swiftdrop"}
            return 404, None

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
            # Support pagination parameters (first/max)
            first = 0
            if "first=" in path:
                try:
                    query = path.split("?", 1)[1]
                    for param in query.split("&"):
                        if param.startswith("first="):
                            first = int(param.split("=")[1])
                except Exception:
                    first = 0

            if first > 0:
                return 200, []  # End of pagination

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
        self.assertIn("journal", res)
        self.assertEqual(len(res["journal"]), 3)
        self.assertEqual(res["journal"][0]["step"], "rename_realm")
        self.assertEqual(res["journal"][1]["step"], "rename_client")
        self.assertEqual(res["journal"][2]["step"], "update_audience_mapper")

        post = res["post_cutover_verification"]
        self.assertEqual(post["realm_internal_id"], "devarom", "Realm internal ID must remain devarom")
        self.assertEqual(post["realm_name"], "swiftdrop", "Realm name must be swiftdrop")
        self.assertEqual(post["frontend_client_internal_uuid"], "2d80857e-52df-4d3c-9413-bc74a53e1de5")
        self.assertEqual(post["audience_mapper_id"], "53ec84cc-aa93-4344-84ce-369bbee64f6e")
        self.assertEqual(post["users"][0]["id"], "sub-alice-uuid-001", "User UUID must be 100% preserved")

    def test_cutover_engine_aborts_if_target_realm_already_exists(self):
        mock_admin = MockAdminClient("devarom", target_realm_exists=True)
        engine = IdentityCutoverEngine(mock_admin)

        with self.assertRaises(CutoverError) as ctx:
            engine.execute_in_place_cutover()
        self.assertIn("already exists", str(ctx.exception))

    def test_cutover_engine_user_pagination(self):
        mock_admin = MockAdminClient("devarom")
        engine = IdentityCutoverEngine(mock_admin)
        users = engine.get_all_users("devarom", page_size=10)
        self.assertEqual(len(users), 1)
        self.assertEqual(users[0]["id"], "sub-alice-uuid-001")

    def test_cutover_engine_stable_id_keyed_invariants_detects_mismatch(self):
        mock_admin = MockAdminClient("devarom")
        engine = IdentityCutoverEngine(mock_admin)
        pre = {
            "realm_internal_id": "devarom",
            "frontend_client_internal_uuid": "cid-1",
            "audience_mapper_id": "mid-1",
            "users_by_id": {
                "u1": {"username": "alice", "emailVerified": True, "credentials": ["password"], "federated": [], "roles": ["CUSTOMER"]}
            }
        }
        # Post state with mutated roles
        post_mutated = {
            "realm_internal_id": "devarom",
            "realm_name": "swiftdrop",
            "frontend_client_internal_uuid": "cid-1",
            "frontend_client_id": "swiftdrop-frontend",
            "audience_mapper_id": "mid-1",
            "current_audience": "swiftdrop-api",
            "users_by_id": {
                "u1": {"username": "alice", "emailVerified": True, "credentials": ["password"], "federated": [], "roles": ["ADMIN"]}
            }
        }
        passed, mismatches = engine.verify_identity_invariants(pre, post_mutated, "swiftdrop", "swiftdrop-frontend", "swiftdrop-api")
        self.assertFalse(passed)
        self.assertTrue(any("roles changed" in m for m in mismatches))

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

    def test_backup_engine_find_database_file_in_h2_subdir(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            backup_dir = Path(tmpdir)
            h2_dir = backup_dir / "h2"
            h2_dir.mkdir()
            db_file = h2_dir / "keycloakdb.mv.db"
            db_file.write_bytes(b"H2_TEST_CONTENT_IN_SUBDIR")

            sha256 = BackupEngine.compute_sha256(db_file)
            checksum_file = h2_dir / "keycloakdb.mv.db.sha256"
            checksum_file.write_text(f"{sha256}  keycloakdb.mv.db\n", encoding="utf-8")

            found_db, found_chk = BackupEngine.find_database_file(backup_dir)
            self.assertEqual(found_db, db_file)
            self.assertEqual(found_chk, checksum_file)

            res = BackupEngine.verify_backup_directory(backup_dir)
            self.assertTrue(res["checksum_verified"])

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

    def test_backup_live_cold_refuses_without_explicit_maintenance_confirmation(self):
        with self.assertRaises(BackupError) as ctx:
            BackupEngine.execute_live_cold_backup(confirm_maintenance_stop="WRONG-PHRASE")
        self.assertIn("maintenance operation that requires explicit authorization", str(ctx.exception))

    def test_safety_gate_returns_no_go_without_maintenance_approval(self):
        os.environ["MAINTENANCE_WINDOW_APPROVED"] = "false"
        os.environ["GOOGLE_CALLBACK_VERIFIED"] = "false"
        gate = SafetyGateEvaluator.evaluate_cutover_gate()

        self.assertEqual(gate["classification"], "NO-GO")
        self.assertGreaterEqual(len(gate["blocking_reasons"]), 2)
        self.assertIn("DO NOT EXECUTE ACTIVE REALM MIGRATION YET", gate["recommendation"])
        # Verify distinction between machine-verifiable and human-confirmed checks
        self.assertEqual(gate["checks"]["source_keycloak_running"]["type"], "MACHINE_VERIFIABLE")
        self.assertEqual(gate["checks"]["maintenance_window_approved"]["type"], "HUMAN_CONFIRMED")
        self.assertEqual(gate["checks"]["google_callback_verified"]["type"], "HUMAN_CONFIRMED")


class TestGoogleCloudCallbackPrerequisite(unittest.TestCase):
    def test_target_google_callback_format(self):
        legacy = "http://localhost:8090/realms/devarom/broker/google/endpoint"
        target = "http://localhost:8090/realms/swiftdrop/broker/google/endpoint"

        self.assertTrue(legacy.endswith("/realms/devarom/broker/google/endpoint"))
        self.assertTrue(target.endswith("/realms/swiftdrop/broker/google/endpoint"))
        self.assertNotEqual(legacy, target)


class SyntheticDockerEnvironment:
    """Deterministic mock for subprocess.run isolating Docker CLI process boundaries."""

    def __init__(self, containers: Optional[Dict[str, Dict[str, Any]]] = None):
        self.containers: Dict[str, Dict[str, Any]] = containers or {}
        self.calls: List[List[str]] = []

    def __call__(self, cmd: List[str], *args, **kwargs):
        self.calls.append(list(cmd))
        if not cmd or cmd[0] != "docker":
            return subprocess.CompletedProcess(args=cmd, returncode=1, stdout="", stderr="Unknown command")

        action = cmd[1] if len(cmd) > 1 else ""

        if action == "inspect":
            target = cmd[2] if len(cmd) > 2 else ""
            c = self.containers.get(target)
            if c and c.get("present", False):
                payload = [{
                    "State": {"Running": c.get("running", False)},
                    "Mounts": [],
                    "Config": {"Env": ["KC_DB_USERNAME=keycloak"]},
                }]
                return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=json.dumps(payload), stderr="")
            return subprocess.CompletedProcess(
                args=cmd, returncode=1, stdout="", stderr=f"Error: No such object: {target}"
            )

        if action == "stop":
            target = cmd[2] if len(cmd) > 2 else ""
            c = self.containers.get(target)
            if c and c.get("present", False):
                c["running"] = False
                return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=target, stderr="")
            return subprocess.CompletedProcess(
                args=cmd, returncode=1, stdout="", stderr=f"Error: No such container: {target}"
            )

        if action == "start":
            target = cmd[2] if len(cmd) > 2 else ""
            c = self.containers.get(target)
            if c and c.get("present", False):
                c["running"] = True
                return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=target, stderr="")
            return subprocess.CompletedProcess(
                args=cmd, returncode=1, stdout="", stderr=f"Error: No such container: {target}"
            )

        if action == "cp":
            src = cmd[2] if len(cmd) > 2 else ""
            dest = cmd[3] if len(cmd) > 3 else ""
            container_name = src.split(":", 1)[0]
            c = self.containers.get(container_name)
            if c and c.get("present", False):
                dest_path = Path(dest)
                h2_dir = dest_path / "h2"
                h2_dir.mkdir(parents=True, exist_ok=True)
                db_file = h2_dir / "keycloakdb.mv.db"
                db_file.write_bytes(c.get("content", b"SYNTHETIC_H2_DB_CONTENT_V1"))
                return subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")
            return subprocess.CompletedProcess(
                args=cmd, returncode=1, stdout="", stderr=f"Error: No such container: {container_name}"
            )

        if action == "exec":
            target = cmd[2] if len(cmd) > 2 else ""
            c = self.containers.get(target)
            if c and c.get("present", False) and c.get("running", False):
                return subprocess.CompletedProcess(
                    args=cmd, returncode=0, stdout="keycloakdb.mv.db\n", stderr=""
                )
            return subprocess.CompletedProcess(
                args=cmd, returncode=1, stdout="", stderr=f"Container {target} not running"
            )

        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")


class TestColdBackupSafetyRegressionAndIsolation(unittest.TestCase):
    """Verifies that cold-backup safety gates and container state evaluation are strictly isolated from host Docker."""

    MAINTENANCE_PHRASE = "SWIFTDROP-MAINTENANCE-STOP-AUTHORIZED"

    def test_unauthorized_backup_missing_confirmation_refuses_before_subprocess(self):
        """Unauthorized attempt with missing confirmation fails closed before any Docker subprocess call."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True},
        })
        with patch.object(backup_mod.subprocess, "run", docker_env):
            with self.assertRaises(BackupError) as ctx:
                BackupEngine.execute_live_cold_backup(confirm_maintenance_stop="")
            self.assertIn("maintenance operation that requires explicit authorization", str(ctx.exception))
            self.assertEqual(len(docker_env.calls), 0, "No subprocess calls allowed for unauthorized backup")

    def test_unauthorized_backup_wrong_confirmation_refuses_before_subprocess(self):
        """Unauthorized attempt with incorrect phrase fails closed before any Docker subprocess call."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True},
        })
        with patch.object(backup_mod.subprocess, "run", docker_env):
            with self.assertRaises(BackupError) as ctx:
                BackupEngine.execute_live_cold_backup(confirm_maintenance_stop="UNAUTHORIZED-KEY")
            self.assertIn("maintenance operation that requires explicit authorization", str(ctx.exception))
            self.assertEqual(len(docker_env.calls), 0, "No subprocess calls allowed for unauthorized backup")

    def test_authorized_canonical_keycloak_running_lifecycle(self):
        """Canonical swiftdrop-keycloak running executes stop, cold copy, verification, and restart."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True, "content": b"H2_CANONICAL_RUNNING_DATA"},
            "devarom-keycloak": {"present": False, "running": False},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertTrue(res["checksum_verified"])
            self.assertEqual(res["status"], "VALID")
            self.assertEqual(res["metadata"]["container_name"], "swiftdrop-keycloak")

            # Verify subprocess call sequence: stop -> cp -> start
            called_actions = [c[1] for c in docker_env.calls if len(c) > 1 and c[0] == "docker"]
            self.assertIn("stop", called_actions)
            self.assertIn("cp", called_actions)
            self.assertIn("start", called_actions)
            stop_idx = called_actions.index("stop")
            cp_idx = called_actions.index("cp")
            start_idx = called_actions.index("start")
            self.assertTrue(stop_idx < cp_idx < start_idx, "Expected stop -> cp -> start ordering")

    def test_authorized_canonical_keycloak_stopped_lifecycle(self):
        """Canonical swiftdrop-keycloak stopped copies H2 storage without stopping or restarting."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": False, "content": b"H2_CANONICAL_STOPPED_DATA"},
            "devarom-keycloak": {"present": False, "running": False},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertTrue(res["checksum_verified"])
            self.assertEqual(res["status"], "VALID")
            called_actions = [c[1] for c in docker_env.calls if len(c) > 1 and c[0] == "docker"]
            self.assertNotIn("stop", called_actions, "Stopped container must not receive stop signal")
            self.assertIn("cp", called_actions)
            self.assertNotIn("start", called_actions, "Previously stopped container must not be restarted")

    def test_authorized_canonical_keycloak_absent_fails_closed(self):
        """Canonical swiftdrop-keycloak absent fails closed without creating backup or copying."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": False, "running": False},
            "devarom-keycloak": {"present": False, "running": False},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                with self.assertRaises(BackupError) as ctx:
                    BackupEngine.execute_live_cold_backup(
                        output_dir=out_dir,
                        confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                    )
                self.assertIn("not accessible", str(ctx.exception))
            self.assertFalse(out_dir.exists(), "Backup directory must not be created on absent container")

    def test_authorized_legacy_keycloak_running_lifecycle(self):
        """Legacy devarom-keycloak running executes stop, cold copy, verification, and restart."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": False, "running": False},
            "devarom-keycloak": {"present": True, "running": True, "content": b"H2_LEGACY_RUNNING_DATA"},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertTrue(res["checksum_verified"])
            self.assertEqual(res["status"], "VALID")
            self.assertEqual(res["metadata"]["container_name"], "devarom-keycloak")

            called_actions = [c[1] for c in docker_env.calls if len(c) > 1 and c[0] == "docker"]
            self.assertIn("stop", called_actions)
            self.assertIn("cp", called_actions)
            self.assertIn("start", called_actions)

    def test_authorized_legacy_keycloak_stopped_lifecycle(self):
        """Legacy devarom-keycloak stopped copies H2 storage without stopping or restarting."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": False, "running": False},
            "devarom-keycloak": {"present": True, "running": False, "content": b"H2_LEGACY_STOPPED_DATA"},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertTrue(res["checksum_verified"])
            self.assertEqual(res["status"], "VALID")
            called_actions = [c[1] for c in docker_env.calls if len(c) > 1 and c[0] == "docker"]
            self.assertNotIn("stop", called_actions)
            self.assertIn("cp", called_actions)
            self.assertNotIn("start", called_actions)

    def test_both_containers_exist_canonical_running_legacy_stopped(self):
        """When canonical is running and legacy is stopped, canonical is active."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True, "content": b"CANONICAL_ACTIVE"},
            "devarom-keycloak": {"present": True, "running": False, "content": b"LEGACY_DORMANT"},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertEqual(res["metadata"]["container_name"], "swiftdrop-keycloak")
            # Verify devarom-keycloak was never stopped, copied, or started
            targeted_containers = [c[2] for c in docker_env.calls if len(c) > 2 and c[1] in ("stop", "start")]
            self.assertTrue(all(name == "swiftdrop-keycloak" for name in targeted_containers))

    def test_both_containers_exist_canonical_stopped_legacy_running(self):
        """When canonical is stopped and legacy is running, running legacy is active."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": False, "content": b"CANONICAL_DORMANT"},
            "devarom-keycloak": {"present": True, "running": True, "content": b"LEGACY_ACTIVE"},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertEqual(res["metadata"]["container_name"], "devarom-keycloak")
            targeted_containers = [c[2] for c in docker_env.calls if len(c) > 2 and c[1] in ("stop", "start")]
            self.assertTrue(all(name == "devarom-keycloak" for name in targeted_containers))

    def test_both_containers_exist_both_running_fails_closed(self):
        """When both canonical and legacy containers are running simultaneously, fail closed."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True},
            "devarom-keycloak": {"present": True, "running": True},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                with self.assertRaises(BackupError) as ctx:
                    BackupEngine.execute_live_cold_backup(
                        output_dir=out_dir,
                        confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                    )
                self.assertIn("Ambiguous active Keycloak container state", str(ctx.exception))
            # Verify zero state-changing operations took place
            called_actions = [c[1] for c in docker_env.calls if len(c) > 1 and c[0] == "docker"]
            self.assertNotIn("stop", called_actions)
            self.assertNotIn("cp", called_actions)

    def test_both_containers_exist_both_stopped_prefers_canonical(self):
        """When both canonical and legacy containers are stopped, prefer canonical swiftdrop-keycloak."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": False, "content": b"CANONICAL_STOPPED"},
            "devarom-keycloak": {"present": True, "running": False, "content": b"LEGACY_STOPPED"},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            out_dir = Path(tmpdir) / "backup_out"
            with patch.object(backup_mod.subprocess, "run", docker_env):
                res = BackupEngine.execute_live_cold_backup(
                    output_dir=out_dir,
                    confirm_maintenance_stop=self.MAINTENANCE_PHRASE,
                )
            self.assertEqual(res["metadata"]["container_name"], "swiftdrop-keycloak")

    def test_safety_gate_evaluation_isolated_from_developer_state(self):
        """Safety gate evaluation with synthetic running Keycloak and empty backup dir."""
        docker_env = SyntheticDockerEnvironment({
            "swiftdrop-keycloak": {"present": True, "running": True},
            "devarom-keycloak": {"present": False, "running": False},
        })
        with tempfile.TemporaryDirectory() as tmpdir:
            empty_backup_dir = Path(tmpdir) / "empty_backups"
            empty_backup_dir.mkdir()
            os.environ["MAINTENANCE_WINDOW_APPROVED"] = "false"
            os.environ["GOOGLE_CALLBACK_VERIFIED"] = "false"

            with patch.object(backup_mod.subprocess, "run", docker_env):
                gate = SafetyGateEvaluator.evaluate_cutover_gate(backup_base_dir=empty_backup_dir)

            self.assertEqual(gate["classification"], "NO-GO")
            self.assertTrue(gate["checks"]["source_keycloak_running"]["passed"])
            self.assertFalse(gate["checks"]["backup_integrity"]["passed"])
            self.assertFalse(gate["checks"]["maintenance_window_approved"]["passed"])


if __name__ == "__main__":
    unittest.main()
