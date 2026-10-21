#!/usr/bin/env python3
"""SwiftDrop visible branding must preserve the existing OIDC identity contract."""

import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


IDENTITY_DIR = Path(__file__).resolve().parents[1]
THEME_DIR = IDENTITY_DIR / "themes" / "devarom" / "login"


class BrandingConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.realm = json.loads((IDENTITY_DIR / "realm-devarom.json").read_text())
        cls.client = next(c for c in cls.realm["clients"] if c["clientId"] == "devarom-frontend")

    def test_visible_realm_client_and_email_labels_use_swiftdrop(self):
        self.assertEqual(self.realm["displayName"], "SwiftDrop Parcel Delivery & Courier Management")
        self.assertEqual(self.realm["displayNameHtml"], '<div class="kc-logo-text"><span>SwiftDrop</span></div>')
        self.assertEqual(self.client["name"], "SwiftDrop Frontend SPA")
        self.assertEqual(self.realm["smtpServer"]["fromDisplayName"], "SwiftDrop")

    def test_phase_one_preserves_oidc_and_sender_identities(self):
        self.assertEqual(self.realm["id"], "devarom")
        self.assertEqual(self.realm["realm"], "devarom")
        self.assertEqual(self.realm["loginTheme"], "devarom")
        self.assertEqual(self.realm["defaultRole"]["name"], "default-roles-devarom")
        self.assertEqual(self.client["clientId"], "devarom-frontend")
        self.assertEqual(self.client["redirectUris"], ["http://localhost:5173/auth/callback"])
        self.assertEqual(self.client["webOrigins"], ["http://localhost:5173"])
        self.assertEqual(self.client["attributes"]["post.logout.redirect.uris"], "http://localhost:5173/##http://localhost:5173")
        audience = next(m for m in self.client["protocolMappers"] if m["name"] == "devarom-audience-mapper")
        self.assertEqual(audience["config"]["included.custom.audience"], "devarom-api")
        self.assertEqual(self.realm["smtpServer"]["from"], "no-reply@devarom.test")
        google = next(p for p in self.realm["identityProviders"] if p["alias"] == "google")
        self.assertEqual(google["config"]["clientId"], "${GOOGLE_CLIENT_ID}")
    def test_canonical_swiftdrop_realm_configuration(self):
        swiftdrop_file = IDENTITY_DIR / "realm-swiftdrop.json"
        if swiftdrop_file.exists():
            realm = json.loads(swiftdrop_file.read_text())
            client = next(c for c in realm["clients"] if c["clientId"] == "swiftdrop-frontend")
            self.assertEqual(realm["id"], "swiftdrop")
            self.assertEqual(realm["realm"], "swiftdrop")
            self.assertEqual(realm["displayName"], "SwiftDrop Parcel Delivery & Courier Management")
            self.assertEqual(realm["loginTheme"], "swiftdrop")
            self.assertEqual(realm["defaultRole"]["name"], "default-roles-swiftdrop")
            self.assertEqual(client["clientId"], "swiftdrop-frontend")
            self.assertEqual(client["name"], "SwiftDrop Frontend SPA")
            audience = next(m for m in client["protocolMappers"] if m["name"] == "swiftdrop-audience-mapper")
            self.assertEqual(audience["config"]["included.custom.audience"], "swiftdrop-api")
            self.assertEqual(realm["smtpServer"]["from"], "no-reply@swiftdrop.test")

    def test_login_registration_and_recovery_copy_has_no_obsolete_brand(self):
        swiftdrop_theme = IDENTITY_DIR / "themes" / "swiftdrop" / "login"
        target_dir = swiftdrop_theme if swiftdrop_theme.exists() else THEME_DIR
        template = (target_dir / "template.ftl").read_text()
        messages = (target_dir / "messages" / "messages_en.properties").read_text()
        self.assertIn('aria-label="SwiftDrop Home"', template)
        self.assertIn('SwiftDrop', template)
        self.assertIn('Create Account &bull; SwiftDrop', template)
        self.assertIn('Sign In &bull; SwiftDrop', template)
        self.assertIn('registerSubtitle=Get started with SwiftDrop', messages)
        self.assertIn('backToApplication=Return to SwiftDrop', messages)
        # Obsolete standalone Devarom brand copy is absent
        self.assertNotRegex(template + messages, r"(?i)(?<![a-z-])devarom(?![a-z-])")

    def test_existing_wordmark_artwork_changes_text_only(self):
        swiftdrop_theme = IDENTITY_DIR / "themes" / "swiftdrop" / "login"
        target_dir = swiftdrop_theme if swiftdrop_theme.exists() else THEME_DIR
        for name in ("swiftdrop-logo.svg", "swiftdrop-logo-dark.svg", "devarom-logo.svg", "devarom-logo-dark.svg"):
            asset_path = target_dir / "resources" / "img" / name
            if asset_path.exists():
                with self.subTest(asset=name):
                    svg = ET.parse(asset_path).getroot()
                    ns = {"svg": "http://www.w3.org/2000/svg"}
                    label = svg.find("svg:text", ns)
                    self.assertEqual("".join(label.itertext()), "SwiftDrop.")
                    self.assertEqual(len(svg.findall("svg:g/svg:rect", ns)), 3)


if __name__ == "__main__":
    unittest.main()
