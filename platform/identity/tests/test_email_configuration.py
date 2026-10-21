#!/usr/bin/env python3
"""Static Phase A configuration regression checks; does not test email delivery."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest


IDENTITY_DIR = Path(__file__).resolve().parents[1]
PRIVILEGED_ROLES = {"MERCHANT", "COURIER", "DISPATCHER", "ADMIN"}

SMOKE_FILE = Path(__file__).resolve().parent / "email_verification_smoke.py"
SMOKE_SPEC = importlib.util.spec_from_file_location("email_verification_smoke", SMOKE_FILE)
SMOKE_MOD = importlib.util.module_from_spec(SMOKE_SPEC)
SMOKE_SPEC.loader.exec_module(SMOKE_MOD)
generate_test_password = SMOKE_MOD.generate_test_password


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_realm(realm):
    require(realm.get("registrationAllowed") is True, "Public registration must remain enabled")
    require(realm.get("verifyEmail") is True, "Native email verification must be required")
    require(realm.get("actionTokenGeneratedByUserLifespan") == 300,
            "User-generated verification links must retain the 300-second lifetime")
    override = realm.get("attributes", {}).get("actionTokenGeneratedByUserLifespan.verify-email")
    require(override in (None, "300"), "Verification-specific expiry must not bypass the lifetime")

    # A partial import list can replace other native actions, including UPDATE_PASSWORD.
    # Preserve 26.8.1 defaults; the runtime upgrader merges the resend setting safely.
    require("requiredActions" not in realm,
            "Fresh imports must retain all native required actions rather than replace them")

    realm_name = realm.get("realm", "swiftdrop")
    expected_from = f"no-reply@{realm_name}.test"
    smtp = realm.get("smtpServer", {})
    expected = {
        "host": "mailpit", "port": "1025", "from": expected_from,
        "auth": "false", "ssl": "false", "starttls": "false",
    }
    require(all(smtp.get(key) == value for key, value in expected.items()),
            "SMTP must use the local Mailpit service and non-secret development values")
    require(set(smtp) <= set(expected) | {"fromDisplayName"},
            "Local SMTP must not contain credentials or external delivery settings")

    roles = {role["name"]: role for role in realm["roles"]["realm"]}
    require(PRIVILEGED_ROLES <= set(roles), "All privileged application roles must remain declared")
    default_role_name = f"default-roles-{realm_name}"
    require(realm.get("defaultRole", {}).get("name") == default_role_name,
            "Registration must retain the existing default role")
    pending = [default_role_name]
    visited = set()
    while pending:
        name = pending.pop()
        if name in visited:
            continue
        require(name not in PRIVILEGED_ROLES,
                "Registration must not grant a privileged role directly or transitively")
        require(name in roles, "Default role composites must resolve to declared realm roles")
        visited.add(name)
        composites = roles[name].get("composites", {})
        require(not any(composites.get("client", {}).values()),
                "Default roles must not grant client privileges")
        pending.extend(composites.get("realm", []))
    require(visited == {default_role_name, "CUSTOMER", "offline_access", "uma_authorization"},
            "Registration must retain only CUSTOMER and the existing Keycloak defaults")


def validate_compose(compose):
    services = compose.get("services", {})
    require("mailpit" in services and "keycloak" in services,
            "Compose must provide Keycloak and its local Mailpit service")
    keycloak = services["keycloak"]
    mailpit = services["mailpit"]
    require(keycloak.get("image") == "quay.io/keycloak/keycloak:26.8.1",
            "Keycloak must retain its pinned 26.8.1 version")
    require(mailpit.get("image") == "axllent/mailpit:v1.27.4",
            "Mailpit must use the reviewed pinned version")
    ports = mailpit.get("ports", [])
    require(len(ports) == 1, "Mailpit must expose only its local web UI, with no host SMTP port")
    port = ports[0]
    require(str(port.get("published", "")).isdigit()
            and 1 <= int(port["published"]) <= 65535
            and port.get("host_ip") == "127.0.0.1"
            and port.get("target") == 8025 and port.get("protocol", "tcp") == "tcp",
            "Mailpit web UI must bind a valid port only on 127.0.0.1")
    require(mailpit.get("environment", {}) == {"MP_MAX_MESSAGES": "100"},
            "Mailpit environment must contain only the bounded local message retention setting")
    require(not any(mailpit.get(key) for key in
                    ("command", "entrypoint", "env_file", "volumes", "configs", "secrets")),
            "Mailpit must remain a local catcher without relay, forwarding, or injected settings")
    require(not mailpit.get("privileged") and not mailpit.get("network_mode")
            and not keycloak.get("network_mode"), "Identity services must use the isolated Compose network")
    mailpit_networks = set(mailpit.get("networks", {"default": None}))
    keycloak_networks = set(keycloak.get("networks", {"default": None}))
    require(bool(mailpit_networks & keycloak_networks),
            "Keycloak must reach Mailpit by service discovery on a shared Compose network")


class EmailConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        realm_file = IDENTITY_DIR / "realm-swiftdrop.json"
        if not realm_file.exists():
            realm_file = IDENTITY_DIR / "realm-devarom.json"
        cls.realm = json.loads(realm_file.read_text())
        # Capture resolved configuration in memory only: it can contain ignored .env values.
        # Never include that configuration or Docker stderr in assertion output.
        result = subprocess.run(
            ["docker", "compose", "-f", str(IDENTITY_DIR / "docker-compose.identity.yml"),
             "--project-directory", str(IDENTITY_DIR), "config", "--format", "json"],
            capture_output=True, text=True, check=False,
        )
        if result.returncode:
            raise RuntimeError("Compose configuration could not be resolved; check the local identity environment")
        cls.compose = json.loads(result.stdout)

    def test_committed_email_policy_and_local_smtp(self):
        validate_realm(self.realm)

    def test_resolved_compose_has_only_local_mail_capture(self):
        validate_compose(self.compose)

    def test_disabling_verification_or_registration_is_rejected(self):
        for field in ("verifyEmail", "registrationAllowed"):
            with self.subTest(field=field):
                realm = copy.deepcopy(self.realm)
                realm[field] = False
                with self.assertRaises(ValueError):
                    validate_realm(realm)

    def test_partial_or_custom_required_actions_replacement_is_rejected(self):
        for actions in ([], [{"alias": "VERIFY_EMAIL", "providerId": "VERIFY_EMAIL"}],
                        [{"alias": "email-otp", "providerId": "custom-email-otp"}]):
            with self.subTest(actions=actions):
                realm = copy.deepcopy(self.realm)
                realm["requiredActions"] = actions
                with self.assertRaises(ValueError):
                    validate_realm(realm)

    def test_expiry_drift_is_rejected(self):
        for change in ("expiry", "override"):
            with self.subTest(change=change):
                realm = copy.deepcopy(self.realm)
                if change == "expiry":
                    realm["actionTokenGeneratedByUserLifespan"] = 0
                elif change == "override":
                    realm.setdefault("attributes", {})["actionTokenGeneratedByUserLifespan.verify-email"] = "3600"
                with self.assertRaises(ValueError):
                    validate_realm(realm)

    def test_external_smtp_and_committed_smtp_credentials_are_rejected(self):
        for field, value in (("host", "smtp.example.test"), ("password", "synthetic-only")):
            with self.subTest(field=field):
                realm = copy.deepcopy(self.realm)
                realm["smtpServer"][field] = value
                with self.assertRaises(ValueError):
                    validate_realm(realm)

    def test_privileged_registration_roles_are_rejected_even_transitively(self):
        for via_customer in (False, True):
            for privileged_role in sorted(PRIVILEGED_ROLES):
                with self.subTest(via_customer=via_customer, role=privileged_role):
                    realm = copy.deepcopy(self.realm)
                    default_role = realm.get("defaultRole", {}).get("name", "default-roles-swiftdrop")
                    role_name = "CUSTOMER" if via_customer else default_role
                    role = next(role for role in realm["roles"]["realm"] if role["name"] == role_name)
                    role["composite"] = True
                    role.setdefault("composites", {}).setdefault("realm", []).append(privileged_role)
                    with self.assertRaises(ValueError):
                        validate_realm(realm)

    def test_public_mail_ui_or_host_smtp_exposure_is_rejected(self):
        for change in ("public-ui", "host-smtp"):
            with self.subTest(change=change):
                compose = copy.deepcopy(self.compose)
                ports = compose["services"]["mailpit"]["ports"]
                if change == "public-ui":
                    ports[0]["host_ip"] = "0.0.0.0"
                else:
                    ports.append({"target": 1025, "published": "1025", "host_ip": "127.0.0.1"})
                with self.assertRaises(ValueError):
                    validate_compose(compose)

    def test_mail_relay_or_version_drift_is_rejected(self):
        for change in ("relay", "version"):
            with self.subTest(change=change):
                compose = copy.deepcopy(self.compose)
                mailpit = compose["services"]["mailpit"]
                if change == "relay":
                    mailpit["environment"] = {"MP_SMTP_RELAY_ALL": "true"}
                else:
                    mailpit["image"] = "axllent/mailpit:latest"
                with self.assertRaises(ValueError):
                    validate_compose(compose)

    def test_synthetic_test_password_generator_complexity_and_uniqueness(self):
        passwords = [generate_test_password(32) for _ in range(100)]
        self.assertEqual(len(set(passwords)), 100, "Generated passwords must be unique across invocations")
        for item in passwords:
            self.assertEqual(len(item), 32)
            self.assertTrue(any(c.isupper() for c in item), "Password must contain uppercase letters")
            self.assertTrue(any(c.islower() for c in item), "Password must contain lowercase letters")
            self.assertTrue(any(c.isdigit() for c in item), "Password must contain numeric digits")
            self.assertTrue(any(c in "!@#$%&*-_=+" for c in item), "Password must contain special characters")
        prefixes = {item[:6] for item in passwords}
        self.assertGreater(len(prefixes), 95, "Generated passwords must not share any common prefix")

    def test_synthetic_test_password_minimum_length_enforcement(self):
        with self.assertRaises(ValueError):
            generate_test_password(23)
        valid = generate_test_password(24)
        self.assertEqual(len(valid), 24)


if __name__ == "__main__":
    unittest.main(verbosity=2)
