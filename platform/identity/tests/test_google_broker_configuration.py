#!/usr/bin/env python3
"""Static Google OIDC broker configuration and security invariant regression tests."""

import json
from pathlib import Path
import re
import unittest


IDENTITY_DIR = Path(__file__).resolve().parents[1]
REALM_FILE = IDENTITY_DIR / "realm-devarom.json"
ENV_EXAMPLE = IDENTITY_DIR / ".env.example"
PRIVILEGED_ROLES = {"MERCHANT", "COURIER", "DISPATCHER", "ADMIN"}
EXPECTED_BROKER_REDIRECT = "http://localhost:8090/realms/devarom/broker/google/endpoint"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_google_broker(realm):
    idps = realm.get("identityProviders", [])
    google_idps = [p for p in idps if p.get("alias") == "google"]
    require(len(google_idps) == 1, "Exactly one identity provider with alias 'google' must be declared")
    google = google_idps[0]

    require(google.get("providerId") == "google", "providerId must be 'google'")
    require(
        google.get("firstBrokerLoginFlowAlias") == "first broker login",
        "firstBrokerLoginFlowAlias must be 'first broker login'",
    )
    require(google.get("trustEmail") is True, "trustEmail must be true for verified Google accounts")
    require(
        google.get("updateProfileFirstLoginMode") == "on",
        "updateProfileFirstLoginMode must be 'on'",
    )
    require(google.get("storeToken") is False, "storeToken must be false (no external token storage in Keycloak DB)")
    require(google.get("linkOnly") is False, "linkOnly must be false to allow first-time broker registration")

    cfg = google.get("config", {})
    require(
        cfg.get("clientId") == "${GOOGLE_CLIENT_ID}",
        "clientId must use '${GOOGLE_CLIENT_ID}' placeholder; never hardcode credentials",
    )
    require(
        cfg.get("clientSecret") == "${GOOGLE_CLIENT_SECRET}",
        "clientSecret must use '${GOOGLE_CLIENT_SECRET}' placeholder; never hardcode credentials",
    )
    require(
        cfg.get("defaultScope") == "openid profile email",
        "defaultScope must request 'openid profile email'",
    )
    require(cfg.get("syncMode") == "IMPORT", "syncMode must be 'IMPORT'")
    require(cfg.get("useJwksUrl") == "true", "useJwksUrl must be 'true'")

    # Role privilege confinement: Google broker users must only receive CUSTOMER
    default_role = realm.get("defaultRole", {})
    require(
        default_role.get("name") == "default-roles-devarom",
        "Default role must remain 'default-roles-devarom'",
    )
    composites = set(
        next(
            (r.get("composites", {}).get("realm", [])
             for r in realm.get("roles", {}).get("realm", [])
             if r.get("name") == "default-roles-devarom"),
            [],
        )
    )
    require("CUSTOMER" in composites, "Default roles must include 'CUSTOMER'")
    require(
        not (composites & PRIVILEGED_ROLES),
        f"Default roles must NOT include privileged staff roles: {composites & PRIVILEGED_ROLES}",
    )


class GoogleBrokerConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.realm = json.loads(REALM_FILE.read_text(encoding="utf-8"))

    def test_committed_google_broker_invariants(self):
        """Verify realm-devarom.json contains correct Google broker architecture and placeholders."""
        validate_google_broker(self.realm)

    def test_no_hardcoded_google_secrets_in_realm(self):
        """Ensure realm JSON does not contain any committed Google OAuth client secrets."""
        raw = REALM_FILE.read_text(encoding="utf-8")
        # Check for Google client secret pattern: GOCSPX-... or similar 35-character secrets
        self.assertIsNone(
            re.search(r"GOCSPX-[A-Za-z0-9_-]{28}", raw),
            "Hardcoded Google OAuth client secret detected in realm-devarom.json!",
        )

    def test_env_example_contains_blank_placeholders(self):
        """Verify .env.example contains blank Google OAuth variable placeholders."""
        content = ENV_EXAMPLE.read_text(encoding="utf-8")
        self.assertIn("GOOGLE_CLIENT_ID=", content)
        self.assertIn("GOOGLE_CLIENT_SECRET=", content)
        # Verify placeholders have no value in committed example
        for line in content.splitlines():
            line = line.strip()
            if line.startswith("GOOGLE_CLIENT_ID="):
                self.assertEqual(line, "GOOGLE_CLIENT_ID=")
            elif line.startswith("GOOGLE_CLIENT_SECRET="):
                self.assertEqual(line, "GOOGLE_CLIENT_SECRET=")

    def test_hardcoded_secret_injection_is_rejected(self):
        """Verify tampering with clientSecret to a literal value fails invariant validation."""
        tampered = json.loads(json.dumps(self.realm))
        for idp in tampered.get("identityProviders", []):
            if idp.get("alias") == "google":
                idp["config"]["clientSecret"] = "raw_secret_here"
        with self.assertRaises(ValueError):
            validate_google_broker(tampered)

    def test_privileged_role_elevation_is_rejected(self):
        """Verify granting staff roles on default-roles-devarom fails invariant check."""
        tampered = json.loads(json.dumps(self.realm))
        for r in tampered.get("roles", {}).get("realm", []):
            if r.get("name") == "default-roles-devarom":
                r.setdefault("composites", {}).setdefault("realm", []).append("ADMIN")
        with self.assertRaises(ValueError):
            validate_google_broker(tampered)


if __name__ == "__main__":
    unittest.main()
