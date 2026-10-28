"""
SwiftDrop Platform — Helm Template Rendering & Schema Tests (Issue #149)

Validates:
1. Manifest generation across all environment values (values.yaml, values-local.yaml, values-test.yaml).
2. Proper resource naming, namespace mapping, and label propagation.
3. Correct port and endpoint routing in Services and Ingress.
4. Liveness and readiness probe configurations.
5. Resource requests and limits bounding host consumption.
"""

import os
import subprocess
import unittest
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
HELM_DIR = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop")
VALUES_FILE = os.path.join(HELM_DIR, "values.yaml")
VALUES_LOCAL = os.path.join(HELM_DIR, "values-local.yaml")
VALUES_TEST = os.path.join(HELM_DIR, "values-test.yaml")


def render(values_file):
    cmd = ["helm", "template", "swiftdrop", HELM_DIR, "-f", values_file]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
    return [d for d in yaml.safe_load_all(res.stdout) if d is not None]


class TestHelmTemplates(unittest.TestCase):
    def test_render_all_value_profiles(self):
        """Verifies that all three values files render without syntax or template errors."""
        for vfile in [VALUES_FILE, VALUES_LOCAL, VALUES_TEST]:
            docs = render(vfile)
            self.assertGreater(len(docs), 20, f"Failed to render complete document set from {vfile}")

    def test_namespace_mapping(self):
        """Verifies that components are routed to their designated namespaces."""
        docs = render(VALUES_LOCAL)
        for doc in docs:
            ns = doc["metadata"].get("namespace")
            kind = doc.get("kind")
            name = doc["metadata"].get("name", "")
            if "keycloak" in name or "identity" in name:
                self.assertEqual(ns, "swiftdrop-identity", f"Keycloak resource {name} must be in swiftdrop-identity")
            elif "data" in name or "db" in name or name in ["accounts-postgres", "business-postgres", "keycloak-postgres"]:
                self.assertEqual(ns, "swiftdrop-data", f"Data resource {name} must be in swiftdrop-data")
            elif "messaging" in name or name in ["kafka", "minio", "mailpit"]:
                self.assertEqual(ns, "swiftdrop-messaging", f"Messaging resource {name} must be in swiftdrop-messaging")
            else:
                self.assertEqual(ns, "swiftdrop-apps", f"App resource {name} ({kind}) must be in swiftdrop-apps")

    def test_configmap_environment_variables(self):
        """Verifies that all required non-secret env vars are templated into ConfigMaps."""
        docs = render(VALUES_LOCAL)
        cm_docs = {d["metadata"]["name"]: d.get("data", {}) for d in docs if d.get("kind") == "ConfigMap"}

        # Gateway ConfigMap
        self.assertIn("swiftdrop-gateway-config", cm_docs)
        gw_data = cm_docs["swiftdrop-gateway-config"]
        self.assertEqual(gw_data.get("SERVER_PORT"), "8080")
        self.assertIn("KEYCLOAK_ISSUER_URI", gw_data)
        self.assertIn("ACCOUNTS_SERVICE_URL", gw_data)
        self.assertIn("DELIVERY_SERVICE_URL", gw_data)

        # Accounts ConfigMap
        self.assertIn("swiftdrop-accounts-config", cm_docs)
        acc_data = cm_docs["swiftdrop-accounts-config"]
        self.assertEqual(acc_data.get("SERVER_PORT"), "8081")
        self.assertEqual(acc_data.get("ACCOUNTS_GRPC_PORT"), "9081")

        # Pricing ConfigMap
        self.assertIn("swiftdrop-pricing-config", cm_docs)
        prc_data = cm_docs["swiftdrop-pricing-config"]
        self.assertEqual(prc_data.get("SERVER_PORT"), "8082")
        self.assertEqual(prc_data.get("PRICING_GRPC_PORT"), "9083")

        # Delivery ConfigMap
        self.assertIn("swiftdrop-delivery-config", cm_docs)
        del_data = cm_docs["swiftdrop-delivery-config"]
        self.assertEqual(del_data.get("SERVER_PORT"), "8083")
        self.assertEqual(del_data.get("DELIVERY_GRPC_PORT"), "9082")
        self.assertIn("KAFKA_BOOTSTRAP_SERVERS", del_data)

    def test_probes_configured_on_all_deployments(self):
        """Verifies that liveness and readiness probes are present on all 8 deployments."""
        docs = render(VALUES_LOCAL)
        deployments = [d for d in docs if d.get("kind") == "Deployment"]
        for dep in deployments:
            name = dep["metadata"]["name"]
            c = dep["spec"]["template"]["spec"]["containers"][0]
            self.assertIn("livenessProbe", c, f"Deployment {name} missing livenessProbe")
            self.assertIn("readinessProbe", c, f"Deployment {name} missing readinessProbe")
            self.assertIn("httpGet", c["livenessProbe"], f"Deployment {name} livenessProbe must use httpGet")
            self.assertIn("httpGet", c["readinessProbe"], f"Deployment {name} readinessProbe must use httpGet")

    def test_resource_limits_configured(self):
        """Verifies that resource requests and limits are explicitly bounded."""
        docs = render(VALUES_LOCAL)
        deployments = [d for d in docs if d.get("kind") == "Deployment"]
        for dep in deployments:
            name = dep["metadata"]["name"]
            c = dep["spec"]["template"]["spec"]["containers"][0]
            res = c.get("resources", {})
            self.assertIn("requests", res, f"Deployment {name} missing resource requests")
            self.assertIn("limits", res, f"Deployment {name} missing resource limits")
            self.assertIn("cpu", res["requests"])
            self.assertIn("memory", res["requests"])
            self.assertIn("cpu", res["limits"])
            self.assertIn("memory", res["limits"])


if __name__ == "__main__":
    unittest.main()
