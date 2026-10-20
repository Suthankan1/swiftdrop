"""Admin REST contract tests using a real loopback HTTP server, not Keycloak."""

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


SCRIPT = Path(__file__).resolve().parents[1] / "configure-email-verification.py"
SPEC = importlib.util.spec_from_file_location("configure_email_verification", SCRIPT)
CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG)


class ServerState:
    def __init__(self):
        self.realm = {
            "id": "test-realm-id", "realm": "devarom", "verifyEmail": False,
            "registrationAllowed": True, "loginTheme": "devarom", "smtpServer": {},
            "accessTokenLifespan": 300, "actionTokenGeneratedByUserLifespan": 300,
            "ssoSessionIdleTimeout": 1800, "identityProviders": [{"alias": "google", "enabled": True}],
            "clients": [{"clientId": "devarom-frontend", "attributes": {"pkce.code.challenge.method": "S256"}}],
        }
        self.action = {"alias": "VERIFY_EMAIL", "providerId": "VERIFY_EMAIL", "enabled": True,
                       "defaultAction": False, "priority": 50, "name": "Verify Email", "config": {"keep": "yes"}}
        self.password_action = {"alias": "UPDATE_PASSWORD", "providerId": "UPDATE_PASSWORD", "enabled": True,
                                "defaultAction": False, "priority": 57, "config": {}}
        self.email = "smtp-admin@devarom.test"
        self.messages = []
        self.calls = []
        self.smtp_failure = False
        self.deliver = True
        self.auth_failure = False
        self.fail_gate_update = False
        self.redirect_token = None
        self.test_user = {"id": "disposable-user", "email": "smtp-customer@devarom.test", "enabled": True,
                          "emailVerified": False, "requiredActions": []}
        self.test_user_roles = [{"name": "CUSTOMER"}, {"name": "default-roles-devarom"}]
        self.test_user_mappings = {"clientMappings": {"account": {"mappings": [{"name": "view-profile"}]}}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_PUT(self):
        self.handle_request()

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
            if state.redirect_token:
                self.send_response(307)
                self.send_header("Location", state.redirect_token)
                self.end_headers()
                return
            if state.auth_failure or payload.get("password") != ["unit-test-password"]:
                self.reply(401, {"error_description": "secret-response-must-not-appear"})
            else:
                self.reply(200, {"access_token": "unit-test-token"})
            return
        if path == "/api/v1/search":
            self.reply(200, {"messages": state.messages})
            return
        if self.headers.get("Authorization") != "Bearer unit-test-token":
            self.reply(403, {"error": "secret-response-must-not-appear"})
            return
        if self.command == "GET" and path == CONFIG.REALM_PATH:
            self.reply(200, state.realm)
        elif self.command == "GET" and path == CONFIG.ACTION_PATH:
            self.reply(200, state.action)
        elif self.command == "GET" and path.endswith("/authentication/required-actions/UPDATE_PASSWORD"):
            self.reply(200, state.password_action)
        elif self.command == "GET" and path == "/admin/realms/master/users":
            self.reply(200, [{"username": "test-admin", "email": state.email}])
        elif self.command == "GET" and path == CONFIG.REALM_PATH + "/users/disposable-user":
            self.reply(200, state.test_user)
        elif self.command == "GET" and path.endswith("/users/disposable-user/role-mappings/realm/composite"):
            self.reply(200, state.test_user_roles)
        elif self.command == "GET" and path.endswith("/users/disposable-user/role-mappings"):
            self.reply(200, state.test_user_mappings)
        elif self.command == "PUT" and path.endswith("/users/disposable-user/send-verify-email"):
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            if (state.smtp_failure or self.server.state.realm["smtpServer"] != CONFIG.smtp_settings({})
                    or query != {"client_id": ["devarom-frontend"], "lifespan": ["300"]} or payload is not None):
                self.reply(500, {"error": "secret-response-must-not-appear"})
            else:
                if state.deliver:
                    state.messages.append({"ID": str(len(state.messages) + 1),
                                           "To": [{"Address": state.test_user["email"]}]})
                self.reply(204)
        elif self.command == "POST" and path.endswith("/testSMTPConnection"):
            if state.smtp_failure:
                self.reply(500, {"error": "secret-response-must-not-appear"})
            else:
                if state.deliver:
                    state.messages.append({"ID": str(len(state.messages) + 1), "To": [{"Address": state.email}]})
                self.reply(204)
        elif self.command == "PUT" and path == CONFIG.REALM_PATH:
            if state.fail_gate_update and payload.get("verifyEmail") is True:
                self.reply(500, {"error": "secret-response-must-not-appear"})
            else:
                state.realm.update(payload)
                self.reply(204)
        elif self.command == "PUT" and path == CONFIG.ACTION_PATH:
            state.action = payload
            self.reply(204)
        else:
            self.reply(404)


class EmailVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.snapshot = Path(self.temp.name) / ".local" / "recovery.json"
        self.state = ServerState()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.state = self.state
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        origin = "http://127.0.0.1:" + str(self.server.server_port)
        self.api = CONFIG.LocalAPI(origin)
        self.mailpit = CONFIG.LocalAPI(origin)
        self.settings = {"KC_BOOTSTRAP_ADMIN_USERNAME": "test-admin", "KC_BOOTSTRAP_ADMIN_PASSWORD": "unit-test-password"}

    def run_mode(self, mode="apply", test_user_id=None):
        with contextlib.redirect_stdout(io.StringIO()):
            CONFIG.run(mode, self.settings, self.api, self.mailpit, self.snapshot,
                       receipt_timeout=0, test_user_id=test_user_id)

    def puts(self):
        return [call for call in self.state.calls if call[0] == "PUT"]

    def test_native_smtp_and_new_receipt_precede_narrow_update(self):
        original = copy.deepcopy(self.state.realm)
        self.run_mode()
        calls = self.state.calls
        smtp_index = next(index for index, call in enumerate(calls) if call[1].endswith("/testSMTPConnection"))
        first_put = next(index for index, call in enumerate(calls) if call[0] == "PUT")
        self.assertGreater(first_put, smtp_index)
        self.assertTrue(any(call[1] == "/api/v1/search" for call in calls[smtp_index + 1:first_put]))
        realm_put = next(call[2] for call in self.puts() if call[1] == CONFIG.REALM_PATH)
        self.assertEqual(set(realm_put), {"verifyEmail", "smtpServer"})
        self.assertEqual(realm_put["smtpServer"]["fromDisplayName"], "SwiftDrop")
        self.assertEqual(realm_put["smtpServer"]["from"], "no-reply@devarom.test")
        for key in set(original) - {"verifyEmail", "smtpServer"}:
            self.assertEqual(self.state.realm[key], original[key])
        self.assertEqual(self.state.action["config"], {"keep": "yes", "emailResendCooldownSeconds": "60"})
        self.assertFalse(self.state.action["defaultAction"])
        self.assertEqual(self.snapshot.stat().st_mode & 0o777, 0o600)
        self.assertTrue(CONFIG.configured(self.state.realm, self.state.action, CONFIG.smtp_settings({})))

    def test_nonprivileged_native_email_precedes_gate_and_preserves_account(self):
        original_user = copy.deepcopy(self.state.test_user)
        self.state.email = ""  # Existing administrator requires no mailbox changes.
        self.run_mode(test_user_id="disposable-user")
        calls = self.state.calls
        native_index = next(index for index, call in enumerate(calls) if call[1].endswith("send-verify-email"))
        gate_index = next(index for index, call in enumerate(calls)
                          if call[0] == "PUT" and call[2] and call[2].get("verifyEmail") is True)
        self.assertLess(native_index, gate_index)
        self.assertTrue(any(call[1] == "/api/v1/search" for call in calls[native_index + 1:gate_index]))
        realm_puts = [call[2] for call in self.puts() if call[1] == CONFIG.REALM_PATH]
        self.assertEqual(set(realm_puts[0]), {"smtpServer"})
        self.assertEqual(self.state.test_user, original_user)
        self.assertFalse(any(call[1] == "/admin/realms/master/users" for call in calls))

    def test_nonprivileged_delivery_failure_restores_previous_smtp_and_gate(self):
        original_realm = copy.deepcopy(self.state.realm)
        self.state.smtp_failure = True
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "was restored"):
            self.run_mode(test_user_id="disposable-user")
        self.assertEqual(self.state.realm, original_realm)
        self.assertFalse(any(call[2] and call[2].get("verifyEmail") is True for call in self.puts()))

    def test_nonprivileged_missing_receipt_restores_previous_gate_even_if_enabled(self):
        self.state.realm["verifyEmail"] = True
        original_realm = copy.deepcopy(self.state.realm)
        self.state.deliver = False
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "was restored"):
            self.run_mode(test_user_id="disposable-user")
        self.assertEqual(self.state.realm, original_realm)

    def test_nonprivileged_preflight_rejects_verified_or_privileged_user(self):
        for modification in ("verified", "staff", "realm-admin"):
            with self.subTest(modification=modification):
                self.state.test_user["emailVerified"] = modification == "verified"
                self.state.test_user_roles = [{"name": "ADMIN" if modification == "staff" else "CUSTOMER"}]
                self.state.test_user_mappings = {"clientMappings": {"realm-management": {}}} if modification == "realm-admin" else {}
                with self.assertRaises(CONFIG.ConfigurationError):
                    self.run_mode(test_user_id="disposable-user")
                self.assertEqual(self.puts(), [])

    def test_repeat_nonprivileged_apply_sends_mail_without_config_mutations(self):
        self.run_mode(test_user_id="disposable-user")
        self.state.calls.clear()
        self.run_mode(test_user_id="disposable-user")
        self.assertEqual([call for call in self.puts() if call[1] in (CONFIG.REALM_PATH, CONFIG.ACTION_PATH)], [])
        self.assertEqual(len(self.state.messages), 2)

    def test_smtp_failure_cannot_enable_gate_or_change_smtp(self):
        self.state.smtp_failure = True
        with self.assertRaises(CONFIG.ConfigurationError) as raised:
            self.run_mode()
        self.assertNotIn("secret-response", str(raised.exception))
        self.assertFalse(self.state.realm["verifyEmail"])
        self.assertEqual(self.puts(), [])
        self.assertFalse(self.snapshot.exists())

    def test_missing_receipt_cannot_enable_gate(self):
        self.state.deliver = False
        self.state.messages.append({"ID": "old", "To": [{"Address": self.state.email}]})
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "not received"):
            self.run_mode()
        self.assertEqual(self.puts(), [])

    def test_real_mailbox_is_rejected_without_account_updates(self):
        self.state.email = "real@example.com"
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "existing admin .test mailbox"):
            self.run_mode()
        self.assertFalse(any(call[1].endswith("testSMTPConnection") for call in self.state.calls))
        self.assertEqual(self.puts(), [])

    def test_absent_admin_mailbox_fails_closed(self):
        self.state.email = ""
        with self.assertRaises(CONFIG.ConfigurationError):
            self.run_mode()
        self.assertEqual(self.puts(), [])

    def test_repeat_apply_has_no_additional_mutations_or_snapshot_overwrite(self):
        self.run_mode()
        previous_snapshot = self.snapshot.read_bytes()
        self.state.calls.clear()
        self.run_mode()
        self.assertEqual(self.puts(), [])
        self.assertEqual(self.snapshot.read_bytes(), previous_snapshot)
        self.assertEqual(len(self.state.messages), 2)

    def test_check_does_not_send_mail_or_write_state(self):
        self.run_mode()
        self.state.calls.clear()
        self.run_mode("check")
        self.assertEqual(self.puts(), [])
        self.assertFalse(any(call[1].endswith("testSMTPConnection") for call in self.state.calls))

    def test_check_detects_policy_drift(self):
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "differs"):
            self.run_mode("check")
        self.assertEqual(self.puts(), [])

    def test_recover_restores_previous_configuration_only(self):
        original_realm = copy.deepcopy(self.state.realm)
        original_action = copy.deepcopy(self.state.action)
        self.run_mode()
        self.state.calls.clear()
        self.run_mode("recover")
        self.assertEqual(self.state.realm, original_realm)
        self.assertEqual(self.state.action, original_action)
        self.assertEqual(set(self.puts()[0][2]), {"verifyEmail", "smtpServer"})
        self.assertFalse(any("users" in call[1] for call in self.state.calls))

    def test_failed_update_restores_required_action_and_realm(self):
        original_action = copy.deepcopy(self.state.action)
        self.state.fail_gate_update = True
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "was restored"):
            self.run_mode()
        self.assertFalse(self.state.realm["verifyEmail"])
        self.assertEqual(self.state.action, original_action)

    def test_wrong_baseline_cannot_change_anything(self):
        self.state.realm["realm"] = "another-realm"
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "baseline differs"):
            self.run_mode()
        self.assertEqual(self.puts(), [])

    def test_effective_email_expiry_override_is_checked_without_changing_it(self):
        self.state.realm["attributes"] = {"actionTokenGeneratedByUserLifespan.verify-email": "3600"}
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "expiry must remain 300"):
            self.run_mode(test_user_id="disposable-user")
        self.assertEqual(self.puts(), [])
        self.assertEqual(self.state.realm["attributes"]["actionTokenGeneratedByUserLifespan.verify-email"], "3600")

    def test_native_password_required_action_must_follow_verification(self):
        for action in ({}, {"alias": "UPDATE_PASSWORD", "providerId": "UPDATE_PASSWORD", "enabled": False, "priority": 57},
                       {"alias": "UPDATE_PASSWORD", "providerId": "UPDATE_PASSWORD", "enabled": True, "priority": 40}):
            with self.subTest(action=action):
                self.state.password_action = action
                with self.assertRaisesRegex(CONFIG.ConfigurationError, "UPDATE_PASSWORD must be enabled after"):
                    self.run_mode()
                self.assertEqual(self.puts(), [])

    def test_unauthorized_authentication_is_sanitized(self):
        self.state.auth_failure = True
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "HTTP 401") as raised:
            self.run_mode()
        self.assertNotIn("secret-response", str(raised.exception))
        self.assertEqual(self.puts(), [])

    def test_insufficient_admin_permission_fails_before_changes(self):
        self.api.authenticate(self.settings)
        self.api.token = "invalid-unit-test-token"
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "HTTP 403"):
            CONFIG.read_state(self.api)
        self.assertEqual(self.puts(), [])

    def test_redirect_is_not_followed_with_credentials(self):
        self.state.redirect_token = self.api.origin + "/would-receive-password"
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "HTTP 307"):
            self.run_mode()
        self.assertEqual(len(self.state.calls), 1)

    def test_masked_previous_smtp_secret_prevents_unsafe_recovery_snapshot(self):
        self.state.realm["smtpServer"] = {"host": "old", "password": "**********"}
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "masked"):
            self.run_mode()
        self.assertEqual(self.puts(), [])
        self.assertFalse(self.snapshot.exists())

    def test_insecure_snapshot_permissions_reject_recovery(self):
        self.run_mode()
        self.snapshot.chmod(0o644)
        self.state.calls.clear()
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "0600"):
            self.run_mode("recover")
        self.assertEqual(self.puts(), [])

    def test_recovery_snapshot_for_other_realm_is_rejected(self):
        self.run_mode()
        self.state.realm["id"] = "different-realm-id"
        self.state.calls.clear()
        with self.assertRaisesRegex(CONFIG.ConfigurationError, "does not match"):
            self.run_mode("recover")
        self.assertEqual(self.puts(), [])

    def test_dotenv_is_data_preserved_without_command_execution(self):
        dotenv = Path(self.temp.name) / ".env"
        text = "# private\nKC_BOOTSTRAP_ADMIN_PASSWORD='literal $(touch unwanted) `whoami` $HOME'\nGOOGLE_CLIENT_SECRET=keep=all\n"
        dotenv.write_text(text)
        values = CONFIG.read_env(dotenv, {"SMTP_PORT": "1025"})
        self.assertEqual(values["KC_BOOTSTRAP_ADMIN_PASSWORD"], "literal $(touch unwanted) `whoami` $HOME")
        self.assertEqual(values["GOOGLE_CLIENT_SECRET"], "keep=all")
        self.assertEqual(dotenv.read_text(), text)

    def test_non_loopback_origins_and_embedded_credentials_are_rejected(self):
        for url in ("https://attacker.example", "http://127.0.0.1.attacker.example", "http://user:pass@localhost",
                    "http://localhost/path", "http://localhost?x=y", "file:///tmp/secret"):
            with self.subTest(url=url), self.assertRaises(CONFIG.ConfigurationError):
                CONFIG.LocalAPI(url)

    def test_non_local_smtp_configuration_is_rejected(self):
        for settings in ({"SMTP_HOST": "smtp.example.com"}, {"SMTP_AUTH": "true"},
                         {"SMTP_FROM": "person@example.com"}, {"SMTP_PORT": "25"}):
            with self.subTest(settings=settings), self.assertRaises(CONFIG.ConfigurationError):
                CONFIG.smtp_settings(settings)


if __name__ == "__main__":
    unittest.main()
