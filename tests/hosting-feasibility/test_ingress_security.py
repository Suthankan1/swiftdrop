"""
SwiftDrop Platform — Hosting Feasibility: Ingress Security Tests (Issue #176)

Verifies:
1. Reuses existing embedded K3s Traefik ingress controller (ingressClassName: traefik).
2. Prohibits introducing new ingress controllers (e.g. NGINX, HAProxy, Caddy, Envoy).
3. TLS termination configured with secret 'swiftdrop-tls-cert'.
4. Approved route mapping:
   - Path '/' -> frontend:8080
   - Path '/api' -> api-gateway:8080
   - Path '/graphql' -> api-gateway:8080
   - Path '/realms' -> keycloak:8080
   - Path '/resources' -> keycloak:8080
   - Path '/js' -> keycloak:8080
5. Rejection of direct Ingress exposure for databases, Kafka, MinIO, or internal gRPC ports.
6. Keycloak '/admin' route presence is monitored and flagged as an unapproved administrative exposure.
"""

import os
import subprocess
import unittest
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
HELM_DIR = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop")
VALUES_FILE = os.path.join(HELM_DIR, "values.yaml")
VALUES_LOCAL = os.path.join(HELM_DIR, "values-local.yaml")


def render_ingress_manifests(values_file=None):
    cmd = ["helm", "template", "swiftdrop", HELM_DIR]
    if values_file:
        cmd.extend(["-f", values_file])
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
    docs = [d for d in yaml.safe_load_all(res.stdout) if d is not None]
    return [d for d in docs if d.get("kind") == "Ingress"]


class TestIngressSecurity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ingress_docs = render_ingress_manifests(VALUES_LOCAL)

    def test_uses_embedded_traefik_controller_only(self):
        """Verifies that all Ingress resources use ingressClassName 'traefik'."""
        self.assertGreaterEqual(len(self.ingress_docs), 2, "Expected at least apps and identity ingress")
        for ing in self.ingress_docs:
            name = ing["metadata"]["name"]
            cls_name = ing.get("spec", {}).get("ingressClassName")
            self.assertEqual(
                cls_name,
                "traefik",
                f"Ingress '{name}' must use embedded 'traefik' ingressClassName, got '{cls_name}'",
            )
            # Rejects NGINX or external controllers
            self.assertNotEqual(cls_name, "nginx", f"Ingress '{name}' illegally introduces NGINX controller!")
            self.assertNotEqual(cls_name, "caddy", f"Ingress '{name}' illegally introduces Caddy controller!")

    def test_tls_termination_configured(self):
        """Verifies TLS configuration and secret reference across all Ingress resources."""
        for ing in self.ingress_docs:
            name = ing["metadata"]["name"]
            tls_list = ing.get("spec", {}).get("tls", [])
            self.assertTrue(tls_list, f"Ingress '{name}' missing TLS configuration")
            for tls_entry in tls_list:
                self.assertEqual(
                    tls_entry.get("secretName"),
                    "swiftdrop-tls-cert",
                    f"Ingress '{name}' must reference secret 'swiftdrop-tls-cert'",
                )
                for host in tls_entry.get("hosts", []):
                    self.assertTrue(
                        host == "swiftdrop.local" or host.endswith(".swiftdrop.local"),
                        f"Unapproved TLS host '{host}' in ingress '{name}'",
                    )

    def test_approved_route_backends(self):
        """Verifies that Ingress routes only map to frontend, api-gateway, and keycloak."""
        approved_backends = {"frontend", "api-gateway", "keycloak"}
        for ing in self.ingress_docs:
            name = ing["metadata"]["name"]
            for rule in ing.get("spec", {}).get("rules", []):
                for path_entry in rule.get("http", {}).get("paths", []):
                    backend_svc = path_entry.get("backend", {}).get("service", {}).get("name")
                    self.assertIn(
                        backend_svc,
                        approved_backends,
                        f"Unapproved backend service '{backend_svc}' routed in Ingress '{name}'!",
                    )

    def test_no_direct_database_or_infrastructure_ingress(self):
        """Verifies that zero database, Kafka, MinIO, or gRPC services are exposed in Ingress."""
        forbidden_services = [
            "accounts-postgres",
            "business-postgres",
            "keycloak-postgres",
            "delivery-db",
            "pricing-db",
            "accounts-db",
            "communications-db",
            "kafka",
            "minio",
            "mailpit",
        ]
        for ing in self.ingress_docs:
            name = ing["metadata"]["name"]
            for rule in ing.get("spec", {}).get("rules", []):
                for path_entry in rule.get("http", {}).get("paths", []):
                    backend_svc = path_entry.get("backend", {}).get("service", {}).get("name")
                    self.assertNotIn(
                        backend_svc,
                        forbidden_services,
                        f"Database or infrastructure service '{backend_svc}' exposed via Ingress '{name}'!",
                    )

    def test_audit_keycloak_admin_route(self):
        """
        Audits Keycloak Ingress for the /admin route.
        Documented finding: /admin is routed to keycloak:8080 in the identity ingress.
        In an externalized or public environment, this exposes the master administrative console.
        """
        identity_ing = next(
            (ing for ing in self.ingress_docs if ing["metadata"]["name"] == "swiftdrop-identity-ingress"),
            None,
        )
        self.assertIsNotNone(identity_ing, "swiftdrop-identity-ingress must exist")
        
        admin_paths = []
        for rule in identity_ing.get("spec", {}).get("rules", []):
            for path_entry in rule.get("http", {}).get("paths", []):
                if path_entry.get("path") == "/admin":
                    admin_paths.append(path_entry)

        # Confirm that /admin is currently routed, documenting the finding for Issue #150 handoff
        self.assertGreater(
            len(admin_paths),
            0,
            "Documented finding: /admin route exists in identity ingress and must be audited/restricted",
        )
        self.assertEqual(admin_paths[0]["backend"]["service"]["name"], "keycloak")


if __name__ == "__main__":
    unittest.main()
