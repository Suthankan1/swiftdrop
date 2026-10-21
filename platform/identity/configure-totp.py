#!/usr/bin/env python3
"""Safely configure Phase B Authenticator-App TOTP MFA on local Keycloak (Python 3.9+).

Configures RFC 6238 TOTP policy (HMAC-SHA1, 6 digits, 30s period, lookahead 1, single-use),
ensures the native CONFIGURE_TOTP required action is enabled with defaultAction=false (optional MFA),
and provides administrative recovery / credential reset for lost authenticator devices.
Zero hardcoded secrets, non-destructive to existing users, roles, email verification, or themes.
"""

import argparse
import copy
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request


DIRECTORY = Path(__file__).resolve().parent
REALM = os.environ.get("KEYCLOAK_REALM", "swiftdrop")
REALM_PATH = f"/admin/realms/{REALM}"
ACTION_PATH = REALM_PATH + "/authentication/required-actions/CONFIGURE_TOTP"
SNAPSHOT_PATH = DIRECTORY / ".local" / "totp-recovery.json"

DESIRED_OTP_POLICY = {
    "otpPolicyType": "totp",
    "otpPolicyAlgorithm": "HmacSHA1",
    "otpPolicyInitialCounter": 0,
    "otpPolicyDigits": 6,
    "otpPolicyLookAheadWindow": 1,
    "otpPolicyPeriod": 30,
    "otpPolicyCodeReusable": False,
    "otpSupportedApplications": [
        "totpAppGoogleName",
        "totpAppMicrosoftAuthenticatorName",
        "totpAppFreeOTPName",
    ],
}

OTP_KEYS = tuple(DESIRED_OTP_POLICY.keys())


class ConfigurationError(Exception):
    """An intentionally sanitized operator-facing failure."""


def read_env(path, overrides=None):
    """Read dotenv assignments as data; never source, expand or modify the file."""
    values = {}
    if path.exists():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key, separator, value = stripped.partition("=")
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                raise ConfigurationError(f"Unsupported dotenv assignment on line {number}; file unchanged.")
            value = value.strip()
            if value[:1] in ("'", '"'):
                quoted = re.fullmatch(r"(['\"])(.*?)\1\s*(?:#.*)?", value)
                if not quoted:
                    raise ConfigurationError(f"Unsupported dotenv quoting on line {number}; file unchanged.")
                value = quoted.group(2)
            else:
                value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
            values[key] = value
    values.update(os.environ if overrides is None else overrides)
    return values


def loopback_url(value):
    """Credentials must never leave a loopback origin, including via redirects."""
    try:
        parsed = urllib.parse.urlsplit(value)
        valid = (
            parsed.scheme in ("http", "https")
            and parsed.hostname in ("localhost", "127.0.0.1", "::1")
            and parsed.username is None and parsed.password is None
            and parsed.path in ("", "/") and not parsed.query and not parsed.fragment
            and (parsed.port is None or 0 < parsed.port < 65536)
        )
    except ValueError:
        valid = False
    if not valid:
        raise ConfigurationError("Only an HTTP(S) loopback origin is allowed for this local helper.")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class LocalAPI:
    def __init__(self, origin, timeout=10):
        self.origin = loopback_url(origin)
        self.timeout = timeout
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, payload=None, form=False):
        headers = {"Accept": "application/json"}
        if method in ("POST", "PUT") and not form:
            headers["Content-Type"] = "application/json"
        data = None
        if payload is not None:
            data = (urllib.parse.urlencode(payload) if form else json.dumps(payload)).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.origin + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise ConfigurationError(f"Local API request failed (HTTP {status}); sensitive response suppressed.") from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise ConfigurationError("Local API request failed; check service availability and configuration.") from None

    def authenticate(self, settings):
        password = settings.get("KC_BOOTSTRAP_ADMIN_PASSWORD", "")
        if not password:
            raise ConfigurationError("KC_BOOTSTRAP_ADMIN_PASSWORD is required; local dotenv file is unchanged.")
        response = self.request("POST", "/realms/master/protocol/openid-connect/token", {
            "grant_type": "password", "client_id": "admin-cli",
            "username": settings.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"), "password": password,
        }, form=True)
        if not isinstance(response, dict) or not isinstance(response.get("access_token"), str) or not response["access_token"]:
            raise ConfigurationError("Administrator authentication did not return an access token.")
        self.token = response["access_token"]


def realm_otp_projection(realm):
    """Extract only the OTP policy attributes from a realm dict."""
    return {k: realm.get(k) for k in OTP_KEYS}


def read_state(api):
    realm = api.request("GET", REALM_PATH)
    action = api.request("GET", ACTION_PATH)
    if (not isinstance(realm, dict) or realm.get("realm") not in (REALM, "devarom", "swiftdrop") or not realm.get("id")
            or realm.get("registrationAllowed") is not True or realm.get("loginTheme") not in ("swiftdrop", "devarom")
            or not isinstance(action, dict) or action.get("alias") != "CONFIGURE_TOTP"
            or action.get("providerId") != "CONFIGURE_TOTP"):
        raise ConfigurationError("Expected SwiftDrop realm baseline differs; no changes applied.")
    return realm, action


def is_otp_configured(realm, action):
    projection = realm_otp_projection(realm)
    expected = DESIRED_OTP_POLICY
    for k, v in expected.items():
        if k == "otpSupportedApplications":
            current_apps = projection.get(k) or []
            if not set(v).issubset(set(current_apps)):
                return False
        elif projection.get(k) != v:
            return False
    if action.get("enabled") is not True or action.get("defaultAction") is not False:
        return False
    return True


def desired_action(existing_action):
    target = copy.deepcopy(existing_action)
    target["enabled"] = True
    target["defaultAction"] = False
    return target


def load_snapshot(path, api, realm):
    if not path.exists():
        raise ConfigurationError("No recovery snapshot file found; cannot recover.")
    if path.is_symlink():
        raise ConfigurationError("Recovery snapshot must not be a symbolic link.")
    try:
        info = path.stat()
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise ConfigurationError("Recovery snapshot must be owned by current user with mode 0600.")
        snapshot = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ConfigurationError("Cannot read recovery snapshot; no changes applied.") from None
    if (not isinstance(snapshot, dict) or snapshot.get("version") != 1 or snapshot.get("origin") != api.origin
            or snapshot.get("realmId") != realm["id"]
            or not isinstance(snapshot.get("previous_otp"), dict)
            or snapshot.get("action", {}).get("alias") != "CONFIGURE_TOTP"):
        raise ConfigurationError("Recovery snapshot does not match this realm or its schema.")
    return snapshot


def save_snapshot(path, snapshot):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(snapshot, output, indent=2)
            output.flush()
            os.fsync(output.fileno())
    except OSError:
        raise ConfigurationError("Cannot safely create private recovery snapshot; no changes applied.") from None


def restore(api, snapshot):
    api.request("PUT", REALM_PATH, snapshot["previous_otp"])
    api.request("PUT", ACTION_PATH, snapshot["action"])
    realm, action = read_state(api)
    if (realm_otp_projection(realm) != snapshot["previous_otp"]
            or action.get("enabled") != snapshot["action"].get("enabled")
            or action.get("defaultAction") != snapshot["action"].get("defaultAction")):
        raise ConfigurationError("Recovery read-back differs; inspect Keycloak connectivity.")


def check_test_user(api, user_id):
    """Verify test user exists and lacks privileged staff roles."""
    user = api.request("GET", f"{REALM_PATH}/users/{user_id}")
    if not isinstance(user, dict) or not user.get("id"):
        raise ConfigurationError(f"Test user ID '{user_id}' not found.")
    roles = api.request("GET", f"{REALM_PATH}/users/{user_id}/role-mappings/realm")
    role_names = {r.get("name") for r in (roles or [])}
    privileged = {"ADMIN", "DISPATCHER", "COURIER", "MERCHANT"}
    if privileged & role_names:
        raise ConfigurationError("Test user must NOT possess privileged roles.")
    return user


def reset_user_mfa(api, user_id, require_re_enroll=True):
    """Administrative recovery: remove OTP credentials and re-queue CONFIGURE_TOTP."""
    user = check_test_user(api, user_id)
    credentials = api.request("GET", f"{REALM_PATH}/users/{user_id}/credentials") or []
    otp_creds = [c for c in credentials if c.get("type") == "otp"]
    deleted_count = 0
    for cred in otp_creds:
        cred_id = cred.get("id")
        if cred_id:
            api.request("DELETE", f"{REALM_PATH}/users/{user_id}/credentials/{cred_id}")
            deleted_count += 1
    if require_re_enroll:
        current_actions = user.get("requiredActions") or []
        if "CONFIGURE_TOTP" not in current_actions:
            new_actions = list(set(current_actions) | {"CONFIGURE_TOTP"})
            api.request("PUT", f"{REALM_PATH}/users/{user_id}", {"requiredActions": new_actions})
    return deleted_count


def run(mode, settings, api, snapshot_path=SNAPSHOT_PATH, test_user_id=None, require_re_enroll=True):
    api.authenticate(settings)
    realm, action = read_state(api)

    if mode == "reset-user":
        if not test_user_id:
            raise ConfigurationError("--user-id is required for reset-user.")
        deleted = reset_user_mfa(api, test_user_id, require_re_enroll=require_re_enroll)
        print(f"PASS: MFA reset complete for user '{test_user_id}'. Removed {deleted} OTP credential(s). "
              f"Re-enrollment required: {require_re_enroll}.")
        return

    if mode == "recover":
        snapshot = load_snapshot(snapshot_path, api, realm)
        restore(api, snapshot)
        print("PASS: Restored previous OTP policy and CONFIGURE_TOTP action; accounts and credentials unchanged.")
        return

    if mode == "check":
        if not is_otp_configured(realm, action):
            raise ConfigurationError("Realm OTP policy or CONFIGURE_TOTP required action differs from expected policy.")
        print("PASS: TOTP MFA foundation (RFC 6238, 6 digits, 30s period, single-use, optional CUSTOMER) is configured.")
        return

    # apply mode
    already = is_otp_configured(realm, action)
    if already:
        print("PASS: TOTP configuration already matches expected policy; no changes required.")
        if test_user_id:
            user = check_test_user(api, test_user_id)
            current_actions = user.get("requiredActions") or []
            if "CONFIGURE_TOTP" not in current_actions:
                api.request("PUT", f"{REALM_PATH}/users/{test_user_id}",
                            {"requiredActions": list(set(current_actions) | {"CONFIGURE_TOTP"})})
                print(f"Queued CONFIGURE_TOTP on disposable user '{test_user_id}'.")
        return

    previous_otp = realm_otp_projection(realm)
    target_action = desired_action(action)
    target_realm = copy.deepcopy(DESIRED_OTP_POLICY)

    snapshot = None
    if snapshot_path.exists():
        snapshot = load_snapshot(snapshot_path, api, realm)
    else:
        snapshot = {
            "version": 1,
            "origin": api.origin,
            "realmId": realm["id"],
            "previous_otp": previous_otp,
            "action": action,
        }
        save_snapshot(snapshot_path, snapshot)

    try:
        if target_action != action:
            api.request("PUT", ACTION_PATH, target_action)
        api.request("PUT", REALM_PATH, target_realm)
        updated_realm, updated_action = read_state(api)
        if not is_otp_configured(updated_realm, updated_action):
            raise ConfigurationError("Configuration read-back differs after apply.")
    except ConfigurationError:
        if snapshot is not None:
            try:
                restore(api, snapshot)
            except Exception:
                raise ConfigurationError("Apply failed and recovery could not be verified; inspect service.") from None
        raise

    if test_user_id:
        user = check_test_user(api, test_user_id)
        current_actions = user.get("requiredActions") or []
        if "CONFIGURE_TOTP" not in current_actions:
            api.request("PUT", f"{REALM_PATH}/users/{test_user_id}",
                        {"requiredActions": list(set(current_actions) | {"CONFIGURE_TOTP"})})
            print(f"Queued CONFIGURE_TOTP on disposable user '{test_user_id}'.")

    print("PASS: Native TOTP MFA policy applied successfully.")
    print("Private recovery snapshot saved; email verification, clients, roles, and themes remain untouched.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "apply", "recover", "reset-user"))
    parser.add_argument("--env-file", type=Path, default=DIRECTORY / ".env")
    parser.add_argument("--snapshot-file", type=Path, default=SNAPSHOT_PATH)
    parser.add_argument("--admin-url", help="HTTP(S) loopback origin; defaults to localhost:KEYCLOAK_PORT")
    parser.add_argument("--user-id", help="Target user ID for reset-user or apply validation")
    parser.add_argument("--no-re-enroll", action="store_true", help="Do not queue CONFIGURE_TOTP after reset")
    args = parser.parse_args(argv)
    try:
        settings = read_env(args.env_file)
        api = LocalAPI(args.admin_url or "http://localhost:" + settings.get("KEYCLOAK_PORT", "8090"))
        run(
            args.mode,
            settings,
            api,
            snapshot_path=args.snapshot_file,
            test_user_id=args.user_id,
            require_re_enroll=not args.no_re_enroll,
        )
        return 0
    except (ConfigurationError, OSError) as error:
        message = str(error) if isinstance(error, ConfigurationError) else "Cannot read local configuration; no changes applied."
        print("FAIL: " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
