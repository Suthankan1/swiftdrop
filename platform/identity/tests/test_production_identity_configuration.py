#!/usr/bin/env python3
"""
Unit and regression tests for SwiftDrop Production Identity Configuration (Issue #150).

Covers:
1. Canonical HTTPS endpoints, origins, and redirect URIs for swiftdrop-frontend.
2. OAuth 2.0 PKCE S256 and public client constraints.
3. Browser security headers and Content Security Policy (Keycloak & Nginx).
4. Google Identity Broker configuration, credentials externalization, and role isolation.
5. Role-based TOTP MFA enforcement (mandatory for ADMIN/DISPATCHER, voluntary for CUSTOMER).
6. Secret externalization, rotation inventory, dry-run validation, and signing-key rollover.
7. Keycloak administrative path gating and Helm template consistency.
"""

import importlib.util
import json
import os
from pathlib import Path
import re
import unittest

DIRECTORY = Path(__file__).resolve().parent
IDENTITY_DIR = DIRECTORY.parent
REPO_ROOT = IDENTITY_DIR.parents[1]
REALM_FILE = IDENTITY_DIR / "realm-swiftdrop.json"
VALUES_FILE = REPO_ROOT / "platform/kubernetes/helm/swiftdrop/values.yaml"
NGINX_CONF_FILE = REPO_ROOT / "frontend/nginx.conf"

# Import rotate-identity-secrets.py dynamically
ROTATE_SCRIPT = IDENTITY_DIR / "rotate-identity-secrets.py"
ROTATE_SPEC = importlib.util.spec_from_file_location("rotate_identity_secrets", ROTATE_SCRIPT)
ROTATE_MOD = importlib.util.module_from_spec(ROTATE_SPEC)
ROTATE_SPEC.loader.exec_module(ROTATE_MOD)

# Import configure-totp.py dynamically
TOTP_SCRIPT = IDENTITY_DIR / "configure-totp.py"
TOTP_SPEC = importlib.util.spec_from_file_location("configure_totp", TOTP_SCRIPT)
TOTP_MOD = importlib.util.module_from_spec(TOTP_SPEC)
TOTP_SPEC.loader.exec_module(TOTP_MOD)


class ProductionIdentityConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert REALM_FILE.exists(), f"Realm file missing: {REALM_FILE}"
        with open(REALM_FILE, "r", encoding="utf-8") as f:
            cls.realm = json.load(f)

        assert VALUES_FILE.exists(), f"Values file missing: {VALUES_FILE}"
        with open(VALUES_FILE, "r", encoding="utf-8") as f:
            cls.values_text = f.read()

        assert NGINX_CONF_FILE.exists(), f"Nginx conf file missing: {NGINX_CONF_FILE}"
        with open(NGINX_CONF_FILE, "r", encoding="utf-8") as f:
            cls.nginx_text = f.read()

    # -------------------------------------------------------------------------
    # 1. Authoritative HTTPS Endpoints & PKCE Client Constraints
    # -------------------------------------------------------------------------

    def test_realm_identity_and_ssl_required(self):
        self.assertEqual(self.realm.get("id"), "swiftdrop")
        self.assertEqual(self.realm.get("realm"), "swiftdrop")
        self.assertEqual(self.realm.get("sslRequired"), "external")

    def test_frontend_public_client_pkce_and_redirects(self):
        clients = self.realm.get("clients", [])
        frontend_clients = [c for c in clients if c.get("clientId") == "swiftdrop-frontend"]
        self.assertEqual(len(frontend_clients), 1, "swiftdrop-frontend client must exist")
        client = frontend_clients[0]

        # OAuth2 Public Client constraints
        self.assertTrue(client.get("publicClient"), "Must be a public client (no client secret in browser)")
        self.assertTrue(client.get("standardFlowEnabled"), "Authorization code grant must be enabled")
        self.assertFalse(client.get("implicitFlowEnabled"), "Implicit flow must be disabled")
        self.assertFalse(client.get("directAccessGrantsEnabled"), "Direct access grant (password) must be disabled")

        # PKCE S256 enforcement
        pkce_method = client.get("attributes", {}).get("pkce.code.challenge.method")
        self.assertEqual(pkce_method, "S256", "PKCE code challenge method must be S256")

        # Redirect URIs allowlist
        redirect_uris = client.get("redirectUris", [])
        self.assertIn("https://swiftdrop.local:18443/auth/callback", redirect_uris)
        self.assertIn("https://app.swiftdrop.local:18443/auth/callback", redirect_uris)
        # Verify no open or wildcards
        for uri in redirect_uris:
            self.assertFalse(uri == "*" or uri.startswith("http://*") or uri.startswith("https://*"),
                             f"Insecure wildcard redirect URI detected: {uri}")

        # Web Origins
        web_origins = client.get("webOrigins", [])
        self.assertIn("https://swiftdrop.local:18443", web_origins)
        self.assertIn("https://app.swiftdrop.local:18443", web_origins)
        self.assertNotIn("*", web_origins, "Wildcard '*' origin is strictly forbidden")

        # Post Logout Redirect URIs
        post_logout = client.get("attributes", {}).get("post.logout.redirect.uris", "")
        self.assertIn("https://swiftdrop.local:18443", post_logout)
        self.assertIn("https://app.swiftdrop.local:18443", post_logout)

    # -------------------------------------------------------------------------
    # 2. Browser Security Headers & CSP
    # -------------------------------------------------------------------------

    def test_realm_browser_security_headers(self):
        headers = self.realm.get("browserSecurityHeaders", {})
        self.assertIn("contentSecurityPolicy", headers)
        csp = headers["contentSecurityPolicy"]
        self.assertIn("frame-ancestors 'self'", csp)
        self.assertNotIn("contentSecurityPolicy: *", csp)

        hsts = headers.get("strictTransportSecurity", "")
        self.assertIn("max-age=31536000", hsts)

        self.assertEqual(headers.get("xContentTypeOptions"), "nosniff")
        self.assertEqual(headers.get("xFrameOptions"), "SAMEORIGIN")

    def test_nginx_csp_allows_swiftdrop_local(self):
        self.assertIn("https://swiftdrop.local:18443", self.nginx_text)
        self.assertIn("https://*.swiftdrop.local:18443", self.nginx_text)
        self.assertIn("X-Content-Type-Options \"nosniff\"", self.nginx_text)
        self.assertIn("X-Frame-Options \"DENY\"", self.nginx_text)

    # -------------------------------------------------------------------------
    # 3. Google Identity Broker & Role Isolation
    # -------------------------------------------------------------------------

    def test_google_broker_configuration(self):
        idps = self.realm.get("identityProviders", [])
        google_idps = [p for p in idps if p.get("providerId") == "google" or p.get("alias") == "google"]
        self.assertEqual(len(google_idps), 1, "Google identity provider must be configured")
        google_idp = google_idps[0]

        # Verify provider settings
        self.assertEqual(google_idp.get("alias"), "google")
        self.assertEqual(google_idp.get("providerId"), "google")
        self.assertTrue(google_idp.get("enabled"))

        # Verify role isolation: default role must NOT be a privileged staff role
        default_role = self.realm.get("defaultRole", {})
        default_role_name = default_role.get("name", "") if isinstance(default_role, dict) else str(default_role)
        privileged_roles = {"ADMIN", "DISPATCHER", "COURIER", "MERCHANT"}
        self.assertNotIn(default_role_name, privileged_roles,
                         "Default role for public/broker registrations must never be a privileged staff role")

    # -------------------------------------------------------------------------
    # 4. Role-based TOTP MFA Policy
    # -------------------------------------------------------------------------

    def test_totp_enforce_staff_mfa_logic(self):
        class MockApi:
            def __init__(self):
                self.calls = []
                self.users_by_role = {
                    "ADMIN": [
                        {"id": "user-admin", "username": "admin-alice", "requiredActions": []},
                        {"id": "user-admin-mfa", "username": "admin-configured", "requiredActions": ["CONFIGURE_TOTP"]},
                    ],
                    "DISPATCHER": [
                        {"id": "user-disp", "username": "dispatcher-dan", "requiredActions": ["UPDATE_PROFILE"]},
                    ],
                }
                self.credentials = {
                    "user-admin": [],
                    "user-disp": [],
                    "user-admin-mfa": [{"type": "otp"}],
                }
                self.updates = []

            def request(self, method, path, body=None):
                self.calls.append((method, path, body))
                if method == "GET":
                    if "/roles/ADMIN/users" in path:
                        return self.users_by_role["ADMIN"]
                    if "/roles/DISPATCHER/users" in path:
                        return self.users_by_role["DISPATCHER"]
                    if "/credentials" in path:
                        uid = path.split("/users/")[1].split("/credentials")[0]
                        return self.credentials.get(uid, [])
                elif method == "PUT" and "/users/" in path:
                    uid = path.split("/users/")[1]
                    self.updates.append((uid, body))
                    return {"id": uid}
                return None

        api = MockApi()
        enforced = TOTP_MOD.enforce_staff_mfa(api)

        # Expected:
        # user-admin -> flagged and updated with CONFIGURE_TOTP
        # user-disp -> flagged and updated with ['UPDATE_PROFILE', 'CONFIGURE_TOTP']
        # user-admin-mfa -> already configured with OTP credential, skipped
        self.assertEqual(len(enforced), 2)
        enforced_usernames = [u[0] for u in enforced]
        self.assertIn("admin-alice", enforced_usernames)
        self.assertIn("dispatcher-dan", enforced_usernames)
        self.assertNotIn("admin-configured", enforced_usernames)

        updated_ids = [u[0] for u in api.updates]
        self.assertIn("user-admin", updated_ids)
        self.assertIn("user-disp", updated_ids)
        self.assertNotIn("user-admin-mfa", updated_ids)

    # -------------------------------------------------------------------------
    # 5. Secret Externalization & Credential Rotation Inventory
    # -------------------------------------------------------------------------

    def test_rotation_inventory_completeness(self):
        inventory = ROTATE_MOD.get_inventory()
        self.assertEqual(len(inventory), 6, "Inventory must contain exactly 6 documented credential types")
        ids = {item["id"] for item in inventory}
        expected_ids = {
            "keycloak-admin",
            "keycloak-db",
            "google-oauth",
            "smtp-relay",
            "realm-signing-keys",
            "local-tls-cert",
        }
        self.assertEqual(ids, expected_ids)

        for item in inventory:
            self.assertTrue(item["owning_component"])
            self.assertTrue(item["secret_name"])
            self.assertTrue(item["secret_keys"])
            self.assertTrue(item["rotation_method"])
            self.assertTrue(item["rollback_method"])
            self.assertTrue(item["verification_test"])
            self.assertTrue(item["rotation_authority"])

    def test_rotation_dry_run_passes(self):
        results = ROTATE_MOD.dry_run_rotation()
        self.assertEqual(results.get("helm_secret_refs"), "PASS")
        self.assertEqual(results.get("realm_integrity"), "PASS")
        self.assertEqual(results.get("zero_committed_secrets"), "PASS")

    def test_simulate_signing_key_rotation(self):
        res = ROTATE_MOD.simulate_signing_key_rotation()
        self.assertEqual(res.get("status"), "PASS")
        self.assertEqual(res.get("t0_active_kid"), "key-2026-v1")
        self.assertEqual(res.get("t1_active_kid"), "key-2026-v2")
        self.assertEqual(res.get("jwks_overlap_count"), 2)
        self.assertEqual(res.get("rollback_active_kid"), "key-2026-v1")

    # -------------------------------------------------------------------------
    # 6. Helm Values and Keycloak Ingress Administration Gating
    # -------------------------------------------------------------------------

    def test_helm_keycloak_admin_exposure_and_smtp_secrets(self):
        self.assertIn("exposeAdmin: true", self.values_text)
        self.assertIn("smtpSecretName: swiftdrop-smtp-secret", self.values_text)
        self.assertIn("smtpHostKey: host", self.values_text)
        self.assertIn("smtpPortKey: port", self.values_text)
        self.assertIn("smtpUserKey: username", self.values_text)
        self.assertIn("smtpPasswordKey: password", self.values_text)
        self.assertIn("smtpFromKey: from", self.values_text)

    def test_zero_committed_credentials_in_realm_and_values(self):
        # Strict security scan: no hardcoded credentials or private keys
        forbidden_patterns = [
            r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
            r"GOCSPX-[a-zA-Z0-9_\-]{20,}",
            r"password123",
            r"secret123",
        ]
        for pattern in forbidden_patterns:
            self.assertIsNone(
                re.search(pattern, self.values_text),
                f"Forbidden credential pattern detected in values.yaml: {pattern}",
            )
            self.assertIsNone(
                re.search(pattern, json.dumps(self.realm)),
                f"Forbidden credential pattern detected in realm-swiftdrop.json: {pattern}",
            )


if __name__ == "__main__":
    unittest.main()
