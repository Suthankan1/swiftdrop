"""Tests for platform/identity/configure-smtp.py.

Verifies:
1. Environment variable reading and parsing.
2. SMTP configuration dictionary construction and validation.
3. Secret masking (never printing passwords).
4. Keycloak API call sequence: authentication, realm get, testSMTPConnection, PUT realm.
"""

import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "configure-smtp.py"
SPEC = importlib.util.spec_from_file_location("configure_smtp", SCRIPT)
config_smtp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(config_smtp)


class TestConfigureSmtp(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.env_file = Path(self.temp_dir.name) / ".env"

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_build_smtp_config_gmail_defaults(self):
        env = {
            "SMTP_HOST": "smtp.gmail.com",
            "SMTP_PORT": "587",
            "SMTP_FROM": "test@gmail.com",
            "SMTP_FROM_DISPLAY_NAME": "SwiftDrop",
            "SMTP_AUTH": "true",
            "SMTP_STARTTLS": "true",
            "SMTP_SSL": "false",
            "SMTP_USER": "test@gmail.com",
            "SMTP_PASSWORD": "secret_app_password",
        }
        cfg = config_smtp.build_smtp_config(env)
        self.assertEqual(cfg["host"], "smtp.gmail.com")
        self.assertEqual(cfg["port"], "587")
        self.assertEqual(cfg["from"], "test@gmail.com")
        self.assertEqual(cfg["auth"], "true")
        self.assertEqual(cfg["starttls"], "true")
        self.assertEqual(cfg["ssl"], "false")
        self.assertEqual(cfg["user"], "test@gmail.com")
        self.assertEqual(cfg["password"], "secret_app_password")

    def test_build_smtp_config_validation_errors(self):
        # Empty host
        with self.assertRaises(config_smtp.ConfigurationError):
            config_smtp.build_smtp_config({"SMTP_HOST": ""})

        # Invalid port
        with self.assertRaises(config_smtp.ConfigurationError):
            config_smtp.build_smtp_config({"SMTP_HOST": "smtp.gmail.com", "SMTP_PORT": "999999"})

        # Invalid email
        with self.assertRaises(config_smtp.ConfigurationError):
            config_smtp.build_smtp_config({"SMTP_HOST": "smtp.gmail.com", "SMTP_PORT": "587", "SMTP_FROM": "invalid"})

        # Auth enabled but missing password
        with self.assertRaises(config_smtp.ConfigurationError):
            config_smtp.build_smtp_config({
                "SMTP_HOST": "smtp.gmail.com",
                "SMTP_PORT": "587",
                "SMTP_FROM": "user@gmail.com",
                "SMTP_AUTH": "true",
                "SMTP_USER": "user@gmail.com",
                "SMTP_PASSWORD": "",
            })

    def test_mask_smtp_config(self):
        cfg = {
            "host": "smtp.gmail.com",
            "password": "supersecretpassword",
            "user": "test@gmail.com",
        }
        masked = config_smtp.mask_smtp_config(cfg)
        self.assertEqual(masked["password"], "********")
        self.assertEqual(cfg["password"], "supersecretpassword")

    def test_read_env(self):
        content = """
# Comment line
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
export SMTP_USER='quotes@gmail.com'
EMPTY_LINE=
        """
        self.env_file.write_text(content.strip(), encoding="utf-8")
        env = config_smtp.read_env(self.env_file, overrides={})
        self.assertEqual(env.get("SMTP_HOST"), "smtp.gmail.com")
        self.assertEqual(env.get("SMTP_PORT"), "587")
        self.assertEqual(env.get("SMTP_USER"), "quotes@gmail.com")

    def test_configure_smtp_flow(self):
        mock_api = MagicMock()
        mock_api.request.side_effect = [
            {"realm": "swiftdrop", "id": "test-id"},  # GET realm
            None,  # POST testSMTPConnection
            None,  # PUT realm
            {"realm": "swiftdrop", "smtpServer": {"host": "smtp.gmail.com", "port": "587"}},  # GET updated realm
        ]
        smtp_cfg = {
            "host": "smtp.gmail.com",
            "port": "587",
            "from": "user@gmail.com",
            "fromDisplayName": "SwiftDrop",
            "auth": "true",
            "starttls": "true",
            "ssl": "false",
            "user": "user@gmail.com",
            "password": "secretpassword",
        }
        result = config_smtp.configure_smtp(mock_api, "swiftdrop", smtp_cfg, run_test=True)
        self.assertEqual(result["host"], "smtp.gmail.com")
        self.assertEqual(mock_api.request.call_count, 4)
        calls = [c[0] for c in mock_api.request.call_args_list]
        self.assertEqual(calls[0], ("GET", "/admin/realms/swiftdrop"))
        self.assertEqual(calls[1], ("POST", "/admin/realms/swiftdrop/testSMTPConnection", smtp_cfg))
        self.assertEqual(calls[2], ("PUT", "/admin/realms/swiftdrop", {"smtpServer": smtp_cfg}))
        self.assertEqual(calls[3], ("GET", "/admin/realms/swiftdrop"))


if __name__ == "__main__":
    unittest.main()
