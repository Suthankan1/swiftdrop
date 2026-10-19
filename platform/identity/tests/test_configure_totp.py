#!/usr/bin/env python3
"""Admin REST contract and unit tests for configure-totp.py using loopback HTTP server."""

import contextlib
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.parse


SCRIPT = Path(__file__).resolve().parents[1] / "configure-totp.py"
SPEC = importlib.util.spec_from_file_location("configure_totp", SCRIPT)
CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG)


class ServerState:
    def __init__(self):
        self.realm = {
            "id": "test-realm-id",
            "realm": "devarom",
            "registrationAllowed": True,
            "loginTheme": "devarom",
            "verifyEmail": True,
            "otpPolicyType": "totp",
            "otpPolicyAlgorithm": "HmacSHA1",
            "otpPolicyDigits": 6,
            "otpPolicyPeriod": 30,
            "otpPolicyInitialCounter": 0,
            "otpPolicyLookAheadWindow": 1,
            "otpPolicyCodeReusable": False,
            "otpSupportedApplications": [
                "totpAppGoogleName",
                "totpAppMicrosoftAuthenticatorName",
                "totpAppFreeOTPName",
            ],
        }
        self.action = {
            "alias": "CONFIGURE_TOTP",
            "providerId": "CONFIGURE_TOTP",
            "enabled": True,
            "defaultAction": False,
            "priority": 54,
            "config": {},
        }
        self.calls = []
        self.users = {
            "test-user-123": {
                "id": "test-user-123",
                "username": "customer-bob",
                "requiredActions": [],
            }
        }
        self.credentials = {
            "test-user-123": [
                {"id": "pwd-1", "type": "password"},
                {"id": "otp-1", "type": "otp", "userLabel": "My Phone"},
            ]
        }
        self.user_roles = {
            "test-user-123": [{"name": "CUSTOMER"}]
        }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, payload=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode())

    def handle_request(self):
        state = self.server.state
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = None
        if body:
            payload = (urllib.parse.parse_qs(body.decode()) if "form-urlencoded" in self.headers.get("Content-Type", "")
                       else json.loads(body))
        path = urllib.parse.urlsplit(self.path).path
        state.calls.append((self.command, path, copy.deepcopy(payload)))

        if path == "/realms/master/protocol/openid-connect/token":
            if payload.get("password") == ["test-admin-password"]:
                self.reply(200, {"access_token": "mock-admin-token"})
            else:
                self.reply(401, {"error": "unauthorized"})
            return

        if self.headers.get("Authorization") != "Bearer mock-admin-token":
            self.reply(403, {"error": "forbidden"})
            return

        # Realm GET/PUT
        if path == CONFIG.REALM_PATH:
            if self.command == "GET":
                self.reply(200, state.realm)
            elif self.command == "PUT":
                state.realm.update(payload)
                self.reply(204)
            return

        # Action GET/PUT
        if path == CONFIG.ACTION_PATH:
            if self.command == "GET":
                self.reply(200, state.action)
            elif self.command == "PUT":
                state.action.update(payload)
                self.reply(204)
            return

        # User queries
        if path.startswith(f"{CONFIG.REALM_PATH}/users/"):
            subpath = path[len(f"{CONFIG.REALM_PATH}/users/"):]
            parts = subpath.split("/")
            user_id = parts[0]
            if len(parts) == 1:
                if self.command == "GET":
                    if user_id in state.users:
                        self.reply(200, state.users[user_id])
                    else:
                        self.reply(404)
                elif self.command == "PUT":
                    if user_id in state.users:
                        state.users[user_id].update(payload)
                        self.reply(204)
                    else:
                        self.reply(404)
                return
            elif len(parts) == 2 and parts[1] == "credentials":
                if self.command == "GET":
                    self.reply(200, state.credentials.get(user_id, []))
                return
            elif len(parts) == 3 and parts[1] == "credentials":
                cred_id = parts[2]
                if self.command == "DELETE":
                    creds = state.credentials.get(user_id, [])
                    state.credentials[user_id] = [c for c in creds if c.get("id") != cred_id]
                    self.reply(204)
                return
            elif len(parts) == 3 and parts[1] == "role-mappings" and parts[2] == "realm":
                if self.command == "GET":
                    self.reply(200, state.user_roles.get(user_id, []))
                return

        self.reply(404)

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_PUT(self):
        self.handle_request()

    def do_DELETE(self):
        self.handle_request()


@contextlib.contextmanager
def run_mock_server(state):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.state = state
    thread = threading.Thread(target=server.serve_forever)
    thread.daemon = True
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class ConfigureTotpTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.snapshot_file = Path(self.temp_dir.name) / "totp-recovery.json"
        self.settings = {
            "KC_BOOTSTRAP_ADMIN_USERNAME": "admin",
            "KC_BOOTSTRAP_ADMIN_PASSWORD": "test-admin-password",
        }

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_check_passes_when_configured(self):
        state = ServerState()
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                CONFIG.run("check", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertIn("PASS:", out.getvalue())

    def test_check_fails_when_otp_algorithm_differs(self):
        state = ServerState()
        state.realm["otpPolicyAlgorithm"] = "HmacSHA256"
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            with self.assertRaises(CONFIG.ConfigurationError) as ctx:
                CONFIG.run("check", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertIn("differs", str(ctx.exception))

    def test_apply_is_idempotent_when_already_configured(self):
        state = ServerState()
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                CONFIG.run("apply", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertIn("already matches", out.getvalue())

    def test_apply_updates_realm_and_saves_recovery_snapshot(self):
        state = ServerState()
        state.realm["otpPolicyDigits"] = 8  # unconfigured
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                CONFIG.run("apply", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertIn("applied successfully", out.getvalue())
            self.assertEqual(state.realm["otpPolicyDigits"], 6)
            self.assertTrue(self.snapshot_file.exists())
            # Verify recovery snapshot file mode is 0600
            mode = self.snapshot_file.stat().st_mode & 0o777
            self.assertEqual(mode, 0o600)

    def test_recover_restores_previous_state(self):
        state = ServerState()
        state.realm["otpPolicyDigits"] = 8
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            # Apply to create snapshot with previous 8 digits
            CONFIG.run("apply", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertEqual(state.realm["otpPolicyDigits"], 6)

            # Now recover
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                CONFIG.run("recover", self.settings, api, snapshot_path=self.snapshot_file)
            self.assertIn("Restored previous OTP policy", out.getvalue())
            self.assertEqual(state.realm["otpPolicyDigits"], 8)

    def test_reset_user_deletes_otp_credential_and_queues_re_enrollment(self):
        state = ServerState()
        with run_mock_server(state) as base_url:
            api = CONFIG.LocalAPI(base_url)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                CONFIG.run(
                    "reset-user",
                    self.settings,
                    api,
                    test_user_id="test-user-123",
                    require_re_enroll=True,
                )
            self.assertIn("MFA reset complete", out.getvalue())
            # OTP credential was deleted
            creds = state.credentials["test-user-123"]
            self.assertEqual(len(creds), 1)
            self.assertEqual(creds[0]["type"], "password")
            # CONFIGURE_TOTP added to user's requiredActions
            self.assertIn("CONFIGURE_TOTP", state.users["test-user-123"]["requiredActions"])


if __name__ == "__main__":
    unittest.main()
