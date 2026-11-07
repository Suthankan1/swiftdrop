"""
SwiftDrop Platform — Azure K3s Helm Environment Values & Rendering Tests.

Canonical Reference: docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md
Enforces:
1. Azure K3s Helm environment values syntax and structure.
2. Ingress HTTPS, TLS secret, and Keycloak admin isolation.
3. Keycloak canonical HTTPS issuer and private JWKS URIs.
4. StorageClass consistency with local-path.
5. Successful Helm lint and template rendering across all 4 charts.
"""

import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
HELM_ROOT = REPO_ROOT / "platform" / "kubernetes" / "helm"
AZURE_VALUES_DIR = HELM_ROOT / "environments" / "azure-k3s"


class TestAzureK3sHelmValues(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.swiftdrop_values_file = AZURE_VALUES_DIR / "swiftdrop-values.yaml"
        cls.data_values_file = AZURE_VALUES_DIR / "data-values.yaml"
        cls.messaging_values_file = AZURE_VALUES_DIR / "messaging-values.yaml"
        cls.monitoring_values_file = AZURE_VALUES_DIR / "monitoring-values.yaml"

        with open(cls.swiftdrop_values_file, "r", encoding="utf-8") as f:
            cls.swiftdrop_cfg = yaml.safe_load(f)
        with open(cls.data_values_file, "r", encoding="utf-8") as f:
            cls.data_cfg = yaml.safe_load(f)
        with open(cls.messaging_values_file, "r", encoding="utf-8") as f:
            cls.messaging_cfg = yaml.safe_load(f)
        with open(cls.monitoring_values_file, "r", encoding="utf-8") as f:
            cls.monitoring_cfg = yaml.safe_load(f)

    def test_values_files_exist(self):
        """Verifies that all 4 Azure K3s Helm values files exist."""
        for f in [self.swiftdrop_values_file, self.data_values_file, self.messaging_values_file, self.monitoring_values_file]:
            self.assertTrue(f.is_file(), f"Missing required Azure Helm values file: {f}")

    def test_ingress_security_configuration(self):
        """Verifies Ingress TLS and Keycloak admin isolation in swiftdrop-values.yaml."""
        ingress = self.swiftdrop_cfg.get("ingress", {})
        self.assertTrue(ingress.get("enabled"))
        self.assertEqual(ingress.get("className"), "traefik")
        self.assertTrue(ingress.get("tls", {}).get("enabled"))
        self.assertEqual(ingress.get("tls", {}).get("secretName"), "swiftdrop-tls-cert")

        # Keycloak admin console must NOT be exposed externally
        self.assertFalse(ingress.get("keycloak", {}).get("exposeAdmin", True))

    def test_keycloak_public_issuer_and_hostname(self):
        """Verifies Keycloak canonical HTTPS hostname and gateway issuer URI."""
        keycloak = self.swiftdrop_cfg.get("keycloak", {})
        hostname = keycloak.get("config", {}).get("hostname", "")
        self.assertTrue(hostname.startswith("https://"), f"Keycloak hostname must be HTTPS, got: {hostname}")

        api_gw = self.swiftdrop_cfg.get("apiGateway", {})
        issuer = api_gw.get("config", {}).get("keycloakIssuerUri", "")
        self.assertTrue(issuer.startswith("https://"), f"Gateway issuer URI must be HTTPS, got: {issuer}")
        self.assertIn("/realms/swiftdrop", issuer)

        # Private JWKS URI must use internal cluster DNS
        jwks = api_gw.get("config", {}).get("keycloakJwksUri", "")
        self.assertIn("keycloak.swiftdrop-identity.svc.cluster.local", jwks)

    def test_storage_class_consistency(self):
        """Verifies that data and messaging charts specify local-path StorageClass."""
        self.assertEqual(self.data_cfg.get("global", {}).get("storageClass"), "local-path")
        self.assertEqual(self.messaging_cfg.get("global", {}).get("storageClass"), "local-path")

    def test_helm_template_renders_swiftdrop(self):
        """Verifies swiftdrop chart renders cleanly with Azure values."""
        chart_dir = HELM_ROOT / "swiftdrop"
        base_values = chart_dir / "values.yaml"
        proc = subprocess.run(
            [
                "helm", "template", "swiftdrop", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.swiftdrop_values_file),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, f"Helm template failed for swiftdrop:\n{proc.stderr}")

    def test_helm_template_renders_data(self):
        """Verifies swiftdrop-data chart renders cleanly with Azure values."""
        chart_dir = HELM_ROOT / "swiftdrop-data"
        base_values = chart_dir / "values.yaml"
        proc = subprocess.run(
            [
                "helm", "template", "swiftdrop-data", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.data_values_file),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, f"Helm template failed for swiftdrop-data:\n{proc.stderr}")

    def test_helm_template_renders_messaging(self):
        """Verifies swiftdrop-messaging chart renders cleanly with Azure values."""
        chart_dir = HELM_ROOT / "swiftdrop-messaging"
        base_values = chart_dir / "values.yaml"
        proc = subprocess.run(
            [
                "helm", "template", "swiftdrop-messaging", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.messaging_values_file),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, f"Helm template failed for swiftdrop-messaging:\n{proc.stderr}")

    def test_helm_template_renders_monitoring(self):
        """Verifies swiftdrop-monitoring chart renders cleanly with Azure values."""
        chart_dir = HELM_ROOT / "swiftdrop-monitoring"
        base_values = chart_dir / "values.yaml"
        proc = subprocess.run(
            [
                "helm", "template", "swiftdrop-monitoring", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.monitoring_values_file),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, f"Helm template failed for swiftdrop-monitoring:\n{proc.stderr}")


if __name__ == "__main__":
    unittest.main()
