#!/usr/bin/env python3
"""Safely configure Phase A on an existing local Keycloak realm (Python 3.9+).

Only realm SMTP/verifyEmail and the native VERIFY_EMAIL provider are updated.
Use --test-user-id for a native verification email to an existing disposable
unverified .test user, without changing its credentials, verification or roles.
Alternatively the native SMTP connection test can use an existing admin .test
mailbox. No admin mailbox is modified and no privileged account is created.
"""

import argparse
import copy
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


DIRECTORY = Path(__file__).resolve().parent
REALM = os.environ.get("KEYCLOAK_REALM", "devarom")
CLIENT_ID = os.environ.get("KEYCLOAK_CLIENT_ID", "devarom-frontend")
REALM_PATH = f"/admin/realms/{REALM}"
ACTION_PATH = REALM_PATH + "/authentication/required-actions/VERIFY_EMAIL"
COOLDOWN = "60"


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
        # Ignore proxy environment variables, so loopback credentials remain local.
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
            # Never print response bodies: they can contain credentials or action links.
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


def smtp_settings(settings):
    """This helper deliberately targets the isolated Mailpit development service."""
    smtp = {
        "host": settings.get("SMTP_HOST", "mailpit"),
        "port": settings.get("SMTP_PORT", "1025"),
        "from": settings.get("SMTP_FROM", "no-reply@devarom.test"),
        "fromDisplayName": settings.get("SMTP_FROM_DISPLAY_NAME", "SwiftDrop"),
        "auth": settings.get("SMTP_AUTH", "false"),
        "starttls": settings.get("SMTP_STARTTLS", "false"),
        "ssl": settings.get("SMTP_SSL", "false"),
    }
    if (smtp["host"] != "mailpit" or smtp["port"] != "1025"
            or any(smtp[key] != "false" for key in ("auth", "starttls", "ssl"))
            or not re.fullmatch(r"[^\s@]+@[^\s@]+\.test", smtp["from"])
            or any("\n" in value or "\r" in value for value in smtp.values())):
        raise ConfigurationError("This local helper requires Mailpit on mailpit:1025, no SMTP auth/TLS and a .test sender.")
    return smtp


def realm_projection(realm):
    return {"verifyEmail": realm.get("verifyEmail", False), "smtpServer": realm.get("smtpServer", {})}


def read_state(api):
    realm = api.request("GET", REALM_PATH)
    action = api.request("GET", ACTION_PATH)
    password_action = api.request("GET", REALM_PATH + "/authentication/required-actions/UPDATE_PASSWORD")
    if (not isinstance(realm, dict) or realm.get("realm") not in (REALM, "devarom", "swiftdrop") or not realm.get("id")
            or realm.get("registrationAllowed") is not True or realm.get("loginTheme") != "devarom"
            or not isinstance(realm.get("smtpServer", {}), dict)
            or not isinstance(action, dict) or action.get("alias") != "VERIFY_EMAIL"
            or action.get("providerId") != "VERIFY_EMAIL" or action.get("defaultAction") is not False
            or not isinstance(action.get("config", {}), dict)):
        raise ConfigurationError("Expected SwiftDrop realm/native required-action baseline differs; no changes applied.")
    attributes = realm.get("attributes", {})
    if not isinstance(attributes, dict):
        raise ConfigurationError("Realm attributes differ from the supported baseline; no changes applied.")
    expiry = attributes.get("actionTokenGeneratedByUserLifespan.verify-email", realm.get("actionTokenGeneratedByUserLifespan"))
    if str(expiry) != "300":
        raise ConfigurationError("Effective verification email expiry must remain 300 seconds; no changes applied.")
    if (not isinstance(password_action, dict) or password_action.get("alias") != "UPDATE_PASSWORD"
            or password_action.get("providerId") != "UPDATE_PASSWORD" or password_action.get("enabled") is not True
            or not isinstance(password_action.get("priority"), int) or not isinstance(action.get("priority"), int)
            or password_action["priority"] <= action["priority"]):
        raise ConfigurationError("Native UPDATE_PASSWORD must be enabled after VERIFY_EMAIL; no changes applied.")
    return realm, action


def desired_action(previous):
    action = copy.deepcopy(previous)
    action["enabled"] = True
    action.setdefault("config", {})["emailResendCooldownSeconds"] = COOLDOWN
    return action


def configured(realm, action, smtp):
    return (realm_projection(realm) == {"verifyEmail": True, "smtpServer": smtp}
            and action.get("enabled") is True
            and action.get("config", {}).get("emailResendCooldownSeconds") == COOLDOWN)


def admin_test_mailbox(api, username):
    query = urllib.parse.urlencode({"username": username, "exact": "true"})
    users = api.request("GET", "/admin/realms/master/users?" + query)
    if not isinstance(users, list) or len(users) != 1:
        raise ConfigurationError("Cannot identify the test administrator mailbox; no accounts changed.")
    mailbox = users[0].get("email", "")
    if not isinstance(mailbox, str) or not re.fullmatch(r"[^\s@]+@[^\s@]+\.test", mailbox):
        raise ConfigurationError("Use an existing admin .test mailbox or --test-user-id for a synthetic customer; no accounts changed.")
    return mailbox


def message_ids(mailpit, mailbox):
    query = urllib.parse.urlencode({"query": "to:" + mailbox, "limit": 100})
    response = mailpit.request("GET", "/api/v1/search?" + query)
    if not isinstance(response, dict) or not isinstance(response.get("messages"), list):
        raise ConfigurationError("Mailpit returned an unexpected search response; no changes applied.")
    return {message["ID"] for message in response["messages"]
            if isinstance(message, dict) and isinstance(message.get("ID"), str)
            and any(recipient.get("Address") == mailbox for recipient in message.get("To", [])
                    if isinstance(recipient, dict))}


def test_smtp(api, mailpit, smtp, username, receipt_timeout=10):
    mailbox = admin_test_mailbox(api, username)
    previous_ids = message_ids(mailpit, mailbox)
    # Pinned 26.8.1 endpoint sends using supplied settings BEFORE they are saved.
    # Recipient is the authenticated admin, not an unsupported testToEmail field.
    api.request("POST", REALM_PATH + "/testSMTPConnection", smtp)
    wait_for_receipt(mailpit, mailbox, previous_ids, receipt_timeout)


def wait_for_receipt(mailpit, mailbox, previous_ids, receipt_timeout):
    deadline = time.monotonic() + receipt_timeout
    while True:
        if message_ids(mailpit, mailbox) - previous_ids:
            return
        if time.monotonic() >= deadline:
            raise ConfigurationError("Native SMTP test email was not received by Mailpit; no changes applied.")
        time.sleep(0.25)


def test_user_mailbox(api, user_id):
    if not re.fullmatch(r"[A-Za-z0-9-]{1,100}", user_id):
        raise ConfigurationError("The disposable test user ID is not a valid local identifier.")
    user_path = REALM_PATH + "/users/" + user_id
    user = api.request("GET", user_path)
    mailbox = user.get("email", "") if isinstance(user, dict) else ""
    if (not isinstance(mailbox, str) or not re.fullmatch(r"[^\s@]+@[^\s@]+\.test", mailbox)
            or user.get("enabled") is not True or user.get("emailVerified") is not False):
        raise ConfigurationError("Preflight needs an enabled, unverified disposable .test user; no account settings changed.")
    roles = api.request("GET", user_path + "/role-mappings/realm/composite")
    permitted = {"CUSTOMER", "default-roles-devarom", "offline_access", "uma_authorization"}
    if not isinstance(roles, list) or any(not isinstance(role, dict) or role.get("name") not in permitted for role in roles):
        raise ConfigurationError("Disposable SMTP test user must not have privileged or unexpected realm roles.")
    mappings = api.request("GET", user_path + "/role-mappings")
    if (not isinstance(mappings, dict) or not isinstance(mappings.get("clientMappings", {}), dict)
            or "realm-management" in mappings.get("clientMappings", {})):
        raise ConfigurationError("Disposable SMTP test user must not have realm-management client roles.")
    return mailbox


def load_snapshot(path, api, realm):
    if path.is_symlink():
        raise ConfigurationError("Recovery snapshot must not be a symbolic link.")
    try:
        info = path.stat()
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise ConfigurationError("Recovery snapshot must be owned by the current user with mode 0600.")
        snapshot = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ConfigurationError("Cannot read recovery snapshot; no changes applied.") from None
    if (not isinstance(snapshot, dict) or snapshot.get("version") != 1 or snapshot.get("origin") != api.origin
            or snapshot.get("realmId") != realm["id"]
            or set(snapshot.get("previous", {})) != {"verifyEmail", "smtpServer"}
            or not isinstance(snapshot["previous"]["verifyEmail"], bool)
            or not isinstance(snapshot["previous"]["smtpServer"], dict)
            or snapshot.get("action", {}).get("alias") != "VERIFY_EMAIL"
            or snapshot.get("action", {}).get("providerId") != "VERIFY_EMAIL"):
        raise ConfigurationError("Recovery snapshot does not match this realm/origin or its narrow schema.")
    return snapshot


def save_snapshot(path, snapshot):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(snapshot, output)
            output.flush()
            os.fsync(output.fileno())
    except OSError:
        raise ConfigurationError("Cannot safely create the private recovery snapshot; no changes applied.") from None


def restore(api, snapshot):
    # Restore the realm gate/SMTP in one narrow update. Queued user actions remain.
    api.request("PUT", REALM_PATH, snapshot["previous"])
    api.request("PUT", ACTION_PATH, snapshot["action"])
    realm, action = read_state(api)
    if realm_projection(realm) != snapshot["previous"] or action != snapshot["action"]:
        raise ConfigurationError("Recovery read-back differs; repair SMTP and inspect the private snapshot.")


def run(mode, settings, api, mailpit, snapshot_path, receipt_timeout=10, test_user_id=None):
    api.authenticate(settings)
    realm, action = read_state(api)
    if mode == "recover":
        snapshot = load_snapshot(snapshot_path, api, realm)
        restore(api, snapshot)
        print("Restored previous SMTP/verification configuration; accounts and queued user actions are unchanged.")
        print("Repairing SMTP is preferred: already queued VERIFY_EMAIL actions can still require email delivery.")
        return
    smtp = smtp_settings(settings)
    if mode == "check":
        if not configured(realm, action, smtp):
            raise ConfigurationError("Email verification/SMTP/required-action configuration differs from the local policy.")
        print("PASS: native verification, local SMTP and 60-second resend cooldown are configured (read-only check).")
        return

    already_configured = configured(realm, action, smtp)
    mailbox = test_user_mailbox(api, test_user_id) if test_user_id else None
    previous_ids = message_ids(mailpit, mailbox) if mailbox else None
    if not test_user_id:
        # This supported API tests supplied SMTP settings without persisting them.
        test_smtp(api, mailpit, smtp, settings.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"), receipt_timeout)
        if already_configured:
            print("PASS: native SMTP test received by Mailpit; configuration already matches, no changes needed.")
            return
    previous = realm_projection(realm)
    for key in ("password", "authTokenClientSecret"):
        secret = previous["smtpServer"].get(key, "")
        if secret and (secret.startswith("${") or set(secret) == {"*"}):
            raise ConfigurationError("Existing SMTP secret is masked; automatic recovery cannot safely restore it. No changes applied.")
    target_action = desired_action(action)
    target_realm = {"verifyEmail": True, "smtpServer": smtp}
    snapshot = None
    if not already_configured and snapshot_path.exists():
        snapshot = load_snapshot(snapshot_path, api, realm)
        if snapshot["previous"] != previous or snapshot["action"] != action:
            raise ConfigurationError("An earlier recovery snapshot exists; recover/review it before another policy change.")
    elif not already_configured:
        snapshot = {"version": 1, "origin": api.origin, "realmId": realm["id"],
                    "previous": previous, "action": action}
        save_snapshot(snapshot_path, snapshot)

    # Detect concurrent edits before a narrow SMTP-only staging update.
    latest_realm, latest_action = read_state(api)
    if realm_projection(latest_realm) != previous or latest_action != action:
        raise ConfigurationError("Verification settings changed during preflight; no changes applied.")
    try:
        if test_user_id:
            if previous["smtpServer"] != smtp:
                # The previous gate is deliberately absent from this SMTP update.
                api.request("PUT", REALM_PATH, {"smtpServer": smtp})
            query = urllib.parse.urlencode({"client_id": CLIENT_ID, "lifespan": 300})
            api.request("PUT", REALM_PATH + "/users/" + test_user_id + "/send-verify-email?" + query)
            wait_for_receipt(mailpit, mailbox, previous_ids, receipt_timeout)
            if already_configured:
                print("PASS: native verification email received by Mailpit; configuration already matches, no changes needed.")
                return
            latest_realm, latest_action = read_state(api)
            expected = {"verifyEmail": previous["verifyEmail"], "smtpServer": smtp}
            if realm_projection(latest_realm) != expected or latest_action != action:
                raise ConfigurationError("Verification settings changed during email preflight.")
        if target_action != action:
            api.request("PUT", ACTION_PATH, target_action)
        api.request("PUT", REALM_PATH, target_realm)
        updated_realm, updated_action = read_state(api)
        if not configured(updated_realm, updated_action, smtp):
            raise ConfigurationError("Configuration read-back differs.")
    except ConfigurationError:
        if snapshot is None:
            raise
        try:
            restore(api, snapshot)
        except ConfigurationError:
            raise ConfigurationError("Apply failed and recovery could not be verified; run recover after repairing connectivity.") from None
        raise ConfigurationError("Apply failed; previous SMTP/verification configuration was restored.") from None
    print("PASS: native SMTP test received by Mailpit before enabling verification; narrow update verified.")
    print("Private recovery snapshot saved; existing accounts, keys, clients, roles and token lifetimes are unchanged.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "apply", "recover"))
    parser.add_argument("--env-file", type=Path, default=DIRECTORY / ".env")
    parser.add_argument("--snapshot-file", type=Path, default=DIRECTORY / ".local" / "email-verification-recovery.json")
    parser.add_argument("--admin-url", help="HTTP(S) loopback origin; defaults to localhost:KEYCLOAK_PORT")
    parser.add_argument("--mailpit-url", help="HTTP(S) loopback origin; defaults to localhost:MAILPIT_UI_PORT")
    parser.add_argument("--test-user-id", help="Existing nonprivileged, enabled, unverified disposable .test user ID")
    args = parser.parse_args(argv)
    try:
        settings = read_env(args.env_file)
        api = LocalAPI(args.admin_url or "http://localhost:" + settings.get("KEYCLOAK_PORT", "8090"))
        mailpit = LocalAPI(args.mailpit_url or "http://localhost:" + settings.get("MAILPIT_UI_PORT", "8025"))
        run(args.mode, settings, api, mailpit, args.snapshot_file, test_user_id=args.test_user_id)
        return 0
    except (ConfigurationError, OSError) as error:
        # Only explicitly sanitized failures are displayed. Raw OS errors may name
        # arbitrary paths but never include response bodies or environment values.
        message = str(error) if isinstance(error, ConfigurationError) else "Cannot read local configuration; no changes applied."
        print("FAIL: " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
