#!/usr/bin/env python3
"""Safely configure and test SMTP settings on Keycloak (Python 3.9+).

Supports configuring native Keycloak realm SMTP settings (including external
providers like Gmail, Amazon SES, SendGrid, etc.) via the Keycloak Admin REST API.
Zero hardcoded secrets, non-destructive to existing users, roles, email verification, or themes.
"""

import argparse
import copy
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


DIRECTORY = Path(__file__).resolve().parent
REPO_ROOT = DIRECTORY.parent.parent


class ConfigurationError(Exception):
    """An intentionally sanitized operator-facing failure."""


def read_env(path, overrides=None):
    """Read dotenv assignments as data; never source or execute the file."""
    values = {}
    if path and path.exists():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if stripped.startswith("export "):
                stripped = stripped[7:].strip()
            key, separator, value = stripped.partition("=")
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                continue
            value = value.strip()
            if value[:1] in ("'", '"'):
                quoted = re.fullmatch(r"(['\"])(.*?)\1\s*(?:#.*)?", value)
                if quoted:
                    value = quoted.group(2)
            else:
                value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
            values[key] = value
    values.update(os.environ if overrides is None else overrides)
    return values


class KeycloakAdminAPI:
    """Minimal Keycloak Admin REST client using standard library urllib."""

    def __init__(self, origin, timeout=15):
        self.origin = origin.rstrip("/")
        self.timeout = timeout
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def authenticate(self, username, password):
        """Authenticate against master realm to obtain an admin access token."""
        token_url = f"{self.origin}/realms/master/protocol/openid-connect/token"
        data = urllib.parse.urlencode({
            "client_id": "admin-cli",
            "grant_type": "password",
            "username": username,
            "password": password,
        }).encode("utf-8")
        req = urllib.request.Request(
            token_url,
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
            method="POST",
        )
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                result = json.loads(resp.read().decode("utf-8"))
                self.token = result.get("access_token")
                if not self.token:
                    raise ConfigurationError("Failed to extract access_token from Keycloak authentication response.")
        except urllib.error.HTTPError as err:
            raise ConfigurationError(f"Keycloak admin authentication failed with HTTP {err.code}.") from None
        except Exception as err:
            raise ConfigurationError(f"Could not connect to Keycloak at {self.origin}: {err}") from None

    def request(self, method, path, payload=None):
        url = f"{self.origin}{path}"
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
        }
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                body = resp.read()
                return json.loads(body.decode("utf-8")) if body else None
        except urllib.error.HTTPError as err:
            raise ConfigurationError(f"Keycloak API request to {path} failed with HTTP {err.code}.") from None
        except Exception as err:
            raise ConfigurationError(f"Keycloak API communication error on {path}: {err}") from None


def build_smtp_config(env):
    """Builds a validated smtpServer dictionary from environment configuration."""
    host = env.get("SMTP_HOST", "mailpit").strip()
    port = str(env.get("SMTP_PORT", "1025")).strip()
    from_address = env.get("SMTP_FROM", "no-reply@swiftdrop.test").strip()
    from_display = env.get("SMTP_FROM_DISPLAY_NAME", "SwiftDrop").strip()
    auth = str(env.get("SMTP_AUTH", "false")).strip().lower()
    starttls = str(env.get("SMTP_STARTTLS", "false")).strip().lower()
    ssl = str(env.get("SMTP_SSL", "false")).strip().lower()
    user = env.get("SMTP_USER", "").strip()
    password = env.get("SMTP_PASSWORD", "").strip()

    if not host:
        raise ConfigurationError("SMTP_HOST must not be empty.")
    if not port.isdigit() or not (1 <= int(port) <= 65535):
        raise ConfigurationError(f"Invalid SMTP_PORT: '{port}'.")
    if not from_address or "@" not in from_address:
        raise ConfigurationError(f"Invalid SMTP_FROM: '{from_address}'.")

    smtp = {
        "host": host,
        "port": port,
        "from": from_address,
        "fromDisplayName": from_display,
        "auth": auth,
        "starttls": starttls,
        "ssl": ssl,
    }
    if auth == "true":
        if not user:
            raise ConfigurationError("SMTP_USER is required when SMTP_AUTH is true.")
        if not password:
            raise ConfigurationError("SMTP_PASSWORD is required when SMTP_AUTH is true.")
        smtp["user"] = user
        smtp["password"] = password
    elif user:
        smtp["user"] = user

    return smtp


def mask_smtp_config(smtp):
    """Returns a safe copy of SMTP config with passwords masked for logging."""
    safe = copy.deepcopy(smtp)
    if "password" in safe and safe["password"]:
        safe["password"] = "********"
    return safe


def configure_smtp(api, realm_name, smtp_config, run_test=False):
    """Applies SMTP settings to the target realm via Keycloak Admin API."""
    realm_path = f"/admin/realms/{realm_name}"

    # Read current realm to confirm existence
    realm = api.request("GET", realm_path)
    if not realm or realm.get("realm") != realm_name:
        raise ConfigurationError(f"Realm '{realm_name}' not found on Keycloak.")

    # Optionally test SMTP connection before persisting
    if run_test:
        test_path = f"{realm_path}/testSMTPConnection"
        try:
            api.request("POST", test_path, smtp_config)
            print("✓ Keycloak testSMTPConnection probe: SUCCESS")
        except ConfigurationError as err:
            raise ConfigurationError(f"Keycloak testSMTPConnection failed: {err}") from None

    # Update realm smtpServer
    update_payload = {"smtpServer": smtp_config}
    api.request("PUT", realm_path, update_payload)

    # Verify read-back
    updated_realm = api.request("GET", realm_path)
    current_smtp = updated_realm.get("smtpServer", {})
    if current_smtp.get("host") != smtp_config.get("host") or str(current_smtp.get("port")) != str(smtp_config.get("port")):
        raise ConfigurationError("Verification read-back failed: smtpServer settings did not persist.")

    print(f"✓ Realm '{realm_name}' SMTP successfully updated:")
    print(json.dumps(mask_smtp_config(current_smtp), indent=2))
    return current_smtp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, help="Path to .env file to load")
    parser.add_argument("--keycloak-url", help="Keycloak base URL (defaults to KEYCLOAK_HOSTNAME or http://127.0.0.1:8090)")
    parser.add_argument("--realm", default=os.environ.get("KEYCLOAK_REALM", "swiftdrop"), help="Target Keycloak realm")
    parser.add_argument("--test", action="store_true", help="Send a test SMTP email to admin mailbox before persisting")
    args = parser.parse_args()

    # Locate default env file if not specified
    env_file = args.env_file
    if not env_file:
        for candidate in [REPO_ROOT / "platform" / "compose" / ".env", REPO_ROOT / "platform" / "identity" / ".env"]:
            if candidate.is_file():
                env_file = candidate
                break

    env = read_env(env_file)
    keycloak_url = (
        args.keycloak_url
        or env.get("KEYCLOAK_ADMIN_SERVERURL")
        or env.get("KEYCLOAK_HOSTNAME")
        or "http://127.0.0.1:8090"
    )
    admin_user = env.get("KEYCLOAK_ADMIN_USER") or env.get("KC_BOOTSTRAP_ADMIN_USERNAME") or "admin"
    admin_password = env.get("KEYCLOAK_ADMIN_PASSWORD") or env.get("KC_BOOTSTRAP_ADMIN_PASSWORD")

    if not admin_password:
        raise ConfigurationError("Keycloak admin password not found in environment (set KEYCLOAK_ADMIN_PASSWORD).")

    smtp_config = build_smtp_config(env)
    print(f"Connecting to Keycloak at {keycloak_url} as {admin_user}...")
    api = KeycloakAdminAPI(keycloak_url)
    api.authenticate(admin_user, admin_password)

    print(f"Applying SMTP configuration to realm '{args.realm}'...")
    configure_smtp(api, args.realm, smtp_config, run_test=args.test)


if __name__ == "__main__":
    try:
        main()
    except ConfigurationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
