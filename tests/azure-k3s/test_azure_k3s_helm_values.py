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


VERIFIED_RUNTIME_DIGESTS = {
    "frontend": "sha256:5ed569ffac2ec50001c4c3b3f1cf1ac466cc7a30de5ee81f4cbf4361f9a6d76a",
    "apiGateway": "sha256:62e0a3e7c2a54d9c0b63be2bb1306ceba5d4b8242009c666d595de816bcf1993",
    "accounts": "sha256:96e9b5fba3d12774c8d710ce920a08ea2ca8e299b26657ad782e5039ef4ff60b",
    "pricing": "sha256:ba3d2931c67f261bb7cc4433b70615452ff7fecf105a2ec29f87a4632c1cb6f4",
    "delivery": "sha256:d97ce899cfd5283201031b04851b91b38e5d31b507f2af8b4ed5ca33891268be",
    "communications": "sha256:af6645f62fe7ecc908a7bd80832b6369a802e9b4fd2d86dbc61b7ce6b8e2a370",
    "graphql": "sha256:cd21baf610531dce93470cab5e34c59c658cb84076529262399cc83add4521a8",
    "keycloak": "sha256:04f3e86b749f9d7b0d5b14e6124272ce76b1d48dc87f053572894af3ca434516",
}

DEPLOYMENT_TO_SERVICE_KEY = {
    "frontend": "frontend",
    "api-gateway": "apiGateway",
    "accounts-service": "accounts",
    "pricing-service": "pricing",
    "delivery-service": "delivery",
    "communications-service": "communications",
    "graphql-query-service": "graphql",
    "keycloak": "keycloak",
}

OBSOLETE_DIGESTS = [
    "sha256:778ce75418fcf9eca3135afec8bd58b77be747d3f38534aca6bbbf6b9aeac1dc",
    "sha256:b42c2dcd199aaedbe4a5cb8c34198a02fe19de452c0a1b9e2c53ed3decf060a5",
    "sha256:450c7671e2e6d4796b13a9226da42ad6429c094a0c8e65523955b46b9e70182c",
    "sha256:b75182da6660d8c56f451948580bdbd17262fc2970c9215c61bdc3874fc7e9aa",
    "sha256:f40955a3072e3b0d6246a0503530ccd39aac47b6a96c4c44082512eaef224763",
    "sha256:418e65e64ca0af6b2ed7e8e8359e03f5264f6c2bf4a29bb7409f6f52cbc4cac6",
    "sha256:268a8e7bb919974126b33c047065069955df9f36c790b5b561001710986f2043",
    "sha256:75effccfc0bf90a2947bbc9dee322ad7a644ea26477e655a5f0e9d5895cd8501",
]


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

    def test_verified_immutable_images_configured_in_azure_values(self):
        """Verifies all eight verified immutable images and digests are configured in swiftdrop-values.yaml."""
        for svc_key, expected_digest in VERIFIED_RUNTIME_DIGESTS.items():
            svc_cfg = self.swiftdrop_cfg.get(svc_key, {})
            self.assertTrue(svc_cfg.get("enabled"), f"Service {svc_key} must be enabled")
            image_cfg = svc_cfg.get("image", {})
            self.assertIn("repository", image_cfg, f"Service {svc_key} missing repository")
            self.assertTrue(
                image_cfg.get("repository", "").startswith("ghcr.io/suthankan1/swiftdrop-"),
                f"Service {svc_key} repository must target ghcr.io/suthankan1/swiftdrop-*",
            )
            self.assertEqual(
                image_cfg.get("digest"),
                expected_digest,
                f"Service {svc_key} digest mismatch in azure values",
            )
            self.assertEqual(image_cfg.get("tag"), "95c4b0f")

    def test_helm_template_renders_verified_digests_instead_of_obsolete(self):
        """Verifies that rendering swiftdrop with Azure values uses the exact verified digests, not b8ac497."""
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
            check=True,
        )
        rendered_docs = [d for d in yaml.safe_load_all(proc.stdout) if d is not None]
        deployment_images = {}
        for doc in rendered_docs:
            if doc.get("kind") == "Deployment":
                name = doc["metadata"]["name"]
                img = doc["spec"]["template"]["spec"]["containers"][0]["image"]
                deployment_images[name] = img

        self.assertEqual(len(deployment_images), 8, "Expected exactly 8 microservice deployments")

        # Verify each deployment has the exact verified digest
        for dep_name, svc_key in DEPLOYMENT_TO_SERVICE_KEY.items():
            rendered_image = deployment_images.get(dep_name)
            self.assertIsNotNone(rendered_image, f"Missing deployment: {dep_name}")
            expected_digest = VERIFIED_RUNTIME_DIGESTS[svc_key]
            self.assertIn(
                expected_digest,
                rendered_image,
                f"Deployment {dep_name} does not render verified digest {expected_digest}. Got: {rendered_image}",
            )

        # Verify absolute absence of any obsolete digests
        for obsolete_digest in OBSOLETE_DIGESTS:
            for dep_name, rendered_img in deployment_images.items():
                self.assertNotIn(
                    obsolete_digest,
                    rendered_img,
                    f"Deployment {dep_name} still renders obsolete digest {obsolete_digest}!",
                )

    def test_base_values_preserve_local_development_defaults(self):
        """Verifies that base swiftdrop values.yaml preserves local development defaults (b8ac497)."""
        base_values_file = HELM_ROOT / "swiftdrop" / "values.yaml"
        with open(base_values_file, "r", encoding="utf-8") as f:
            base_cfg = yaml.safe_load(f)

        for svc_key in VERIFIED_RUNTIME_DIGESTS.keys():
            base_svc = base_cfg.get(svc_key, {})
            base_img = base_svc.get("image", {})
            self.assertEqual(base_img.get("tag"), "b8ac497", f"Base default for {svc_key} must remain b8ac497")

    def test_image_pull_secrets_support(self):
        """Verifies workloads template supports imagePullSecrets when configured, and omits when empty."""
        chart_dir = HELM_ROOT / "swiftdrop"
        base_values = chart_dir / "values.yaml"

        # 1. When empty, imagePullSecrets should not be rendered
        proc_empty = subprocess.run(
            [
                "helm", "template", "swiftdrop", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.swiftdrop_values_file),
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        docs_empty = [d for d in yaml.safe_load_all(proc_empty.stdout) if d is not None]
        for doc in docs_empty:
            if doc.get("kind") == "Deployment":
                spec = doc["spec"]["template"]["spec"]
                self.assertNotIn("imagePullSecrets", spec)

        # 2. When configured via global.imagePullSecrets, all deployments render it
        proc_with_secret = subprocess.run(
            [
                "helm", "template", "swiftdrop", str(chart_dir),
                "-f", str(base_values),
                "-f", str(self.swiftdrop_values_file),
                "--set", "global.imagePullSecrets[0].name=ghcr-k8s-cred",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        docs_with_secret = [d for d in yaml.safe_load_all(proc_with_secret.stdout) if d is not None]
        deployment_count = 0
        for doc in docs_with_secret:
            if doc.get("kind") == "Deployment":
                deployment_count += 1
                spec = doc["spec"]["template"]["spec"]
                self.assertIn("imagePullSecrets", spec)
                self.assertEqual(spec["imagePullSecrets"], [{"name": "ghcr-k8s-cred"}])
        self.assertEqual(deployment_count, 8)



if __name__ == "__main__":
    unittest.main()
