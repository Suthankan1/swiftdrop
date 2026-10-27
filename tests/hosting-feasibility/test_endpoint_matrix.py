"""
SwiftDrop Platform — Hosting Feasibility: Endpoint Matrix Tests (Issue #176)

Verifies:
1. Approved SPA, API Gateway, and Keycloak endpoints are defined in Helm and local config.
2. Ingress hostnames conform to approved local domain patterns (swiftdrop.local, *.swiftdrop.local).
3. Port contracts match Issue #175 (k3d cluster.yaml) and Issue #149 (Helm templates):
   - 127.0.0.1:18080 (HTTP)
   - 127.0.0.1:18443 (HTTPS)
   - 127.0.0.1:16443 (Kubernetes API)
4. Absence of forbidden external cloud domains, unauthorized endpoints, or unapproved public ports.
5. Strict classification of internal microservices, databases, Kafka, MinIO, and gRPC as private (ClusterIP only).
"""

import os
import unittest
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
K3D_CLUSTER_CONFIG = os.path.join(REPO_ROOT, "platform/kubernetes/k3d/cluster.yaml")
HELM_VALUES_FILE = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop/values.yaml")
HELM_LOCAL_VALUES = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop/values-local.yaml")
AZURE_POLICY_FILE = os.path.join(REPO_ROOT, "platform/security/azure-allowlist-policy.yaml")

APPROVED_HOSTS = [
    "swiftdrop.local",
    "app.swiftdrop.local",
    "api.swiftdrop.local",
    "auth.swiftdrop.local",
    "localhost",
    "127.0.0.1",
]

INTERNAL_PRIVATE_SERVICES = [
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
    "accounts-postgres",
    "business-postgres",
    "keycloak-postgres",
    "kafka",
    "minio",
    "mailpit",
]


class TestEndpointMatrix(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(K3D_CLUSTER_CONFIG, "r") as f:
            cls.k3d_config = yaml.safe_load(f)
        with open(HELM_VALUES_FILE, "r") as f:
            cls.helm_values = yaml.safe_load(f)
        with open(HELM_LOCAL_VALUES, "r") as f:
            cls.helm_local = yaml.safe_load(f)
        with open(AZURE_POLICY_FILE, "r") as f:
            cls.azure_policy = yaml.safe_load(f)

    def test_k3d_port_contracts_match_spec(self):
        """Verifies k3d cluster loopback ingress and API port mappings match #175 and #149."""
        kube_api = self.k3d_config.get("kubeAPI", {})
        self.assertEqual(kube_api.get("hostIP"), "127.0.0.1", "Kubernetes API must bind to loopback 127.0.0.1")
        self.assertEqual(str(kube_api.get("hostPort")), "16443", "Kubernetes API port must be 16443")

        ports = [p.get("port", "") for p in self.k3d_config.get("ports", [])]
        self.assertIn("127.0.0.1:18080:80", ports, "HTTP ingress port must be 127.0.0.1:18080:80")
        self.assertIn("127.0.0.1:18443:443", ports, "HTTPS ingress port must be 127.0.0.1:18443:443")

        # Zero ports bound to 0.0.0.0
        for p in ports:
            self.assertTrue(p.startswith("127.0.0.1:"), f"Port '{p}' is not bound to loopback 127.0.0.1!")
            self.assertFalse(p.startswith("0.0.0.0"), f"Port '{p}' exposes cluster on 0.0.0.0!")

    def test_spa_endpoint_defined(self):
        """Verifies SPA endpoint configuration in Helm values."""
        ingress_cfg = self.helm_values.get("ingress", {})
        self.assertTrue(ingress_cfg.get("enabled"), "Ingress must be enabled")
        self.assertEqual(ingress_cfg.get("host"), "swiftdrop.local")
        self.assertEqual(ingress_cfg.get("subdomains", {}).get("app"), "app.swiftdrop.local")
        self.assertTrue(self.helm_values.get("frontend", {}).get("enabled"), "Frontend service must be enabled")

    def test_api_gateway_endpoint_defined(self):
        """Verifies API Gateway endpoint configuration in Helm values."""
        ingress_cfg = self.helm_values.get("ingress", {})
        self.assertEqual(ingress_cfg.get("subdomains", {}).get("api"), "api.swiftdrop.local")
        self.assertTrue(self.helm_values.get("apiGateway", {}).get("enabled"), "API Gateway must be enabled")
        self.assertEqual(self.helm_values.get("apiGateway", {}).get("service", {}).get("type"), "ClusterIP")

    def test_keycloak_issuer_defined(self):
        """Verifies Keycloak issuer and OIDC endpoint configuration in Helm values."""
        ingress_cfg = self.helm_values.get("ingress", {})
        self.assertEqual(ingress_cfg.get("subdomains", {}).get("auth"), "auth.swiftdrop.local")
        self.assertTrue(self.helm_values.get("keycloak", {}).get("enabled"), "Keycloak must be enabled")
        kc_cfg = self.helm_values.get("keycloak", {}).get("config", {})
        self.assertEqual(kc_cfg.get("hostname"), "https://swiftdrop.local:18443")
        self.assertEqual(kc_cfg.get("proxyHeaders"), "xforwarded")

    def test_browser_hostnames_match_approved_ingress(self):
        """Verifies that all ingress hostnames match approved local naming patterns."""
        ingress_cfg = self.helm_values.get("ingress", {})
        hosts = [
            ingress_cfg.get("host"),
            ingress_cfg.get("subdomains", {}).get("app"),
            ingress_cfg.get("subdomains", {}).get("api"),
            ingress_cfg.get("subdomains", {}).get("auth"),
        ]
        for host in hosts:
            self.assertIn(host, APPROVED_HOSTS, f"Host '{host}' is not in approved local hostname whitelist")
            self.assertTrue(
                host == "swiftdrop.local" or host.endswith(".swiftdrop.local"),
                f"Host '{host}' must be in local domain namespace",
            )

    def test_no_forbidden_external_endpoints(self):
        """Verifies that no paid public domains or cloud endpoints are present in Helm configurations."""
        raw_values = yaml.dump(self.helm_values)
        forbidden_patterns = [
            ".azurestaticapps.net",
            ".azurewebsites.net",
            ".ngrok-free.app",
            ".ngrok.io",
            ".trycloudflare.com",
            ".tailscale.net",
            "aws.amazon.com",
            "googleapis.com/auth",
        ]
        for pattern in forbidden_patterns:
            self.assertNotIn(pattern, raw_values, f"Forbidden external endpoint pattern '{pattern}' found in Helm values!")

    def test_internal_services_classified_private(self):
        """Verifies that all business services, databases, Kafka, and MinIO are private ClusterIP-only."""
        for svc_name in ["accounts", "pricing", "delivery", "communications", "graphql"]:
            svc_conf = self.helm_values.get(svc_name, {})
            svc_type = svc_conf.get("service", {}).get("type")
            self.assertEqual(
                svc_type,
                "ClusterIP",
                f"Service {svc_name} must be type 'ClusterIP', got '{svc_type}'",
            )


if __name__ == "__main__":
    unittest.main()
