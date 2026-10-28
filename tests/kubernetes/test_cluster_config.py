"""
SwiftDrop Local Kubernetes Cluster Configuration Tests
Canonical Reference: docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/175
"""

import os
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CLUSTER_YAML_PATH = REPO_ROOT / "platform" / "kubernetes" / "k3d" / "cluster.yaml"
NAMESPACES_YAML_PATH = REPO_ROOT / "platform" / "kubernetes" / "k3d" / "namespaces.yaml"
AZURE_POLICY_PATH = REPO_ROOT / "platform" / "security" / "azure-allowlist-policy.yaml"


class TestClusterConfig(unittest.TestCase):
    """Validates declarative k3d/K3s configuration and management boundaries."""

    @classmethod
    def setUpClass(cls):
        with open(CLUSTER_YAML_PATH, "r", encoding="utf-8") as f:
            cls.cluster_cfg = yaml.safe_load(f)

        with open(NAMESPACES_YAML_PATH, "r", encoding="utf-8") as f:
            cls.namespaces = list(yaml.safe_load_all(f))

        with open(AZURE_POLICY_PATH, "r", encoding="utf-8") as f:
            cls.azure_policy = yaml.safe_load(f)

    def test_cluster_schema_and_version(self):
        """Verifies k3d.io/v1alpha5 Simple configuration schema."""
        self.assertEqual(self.cluster_cfg.get("apiVersion"), "k3d.io/v1alpha5")
        self.assertEqual(self.cluster_cfg.get("kind"), "Simple")

    def test_cluster_metadata_name(self):
        """Verifies cluster identity is strictly swiftdrop-local."""
        metadata = self.cluster_cfg.get("metadata", {})
        self.assertEqual(metadata.get("name"), "swiftdrop-local")

    def test_pinned_k3s_image(self):
        """Verifies pinned k3s container image without latest tag."""
        image = self.cluster_cfg.get("image", "")
        self.assertTrue(image.startswith("docker.io/rancher/k3s:v1.") or image.startswith("rancher/k3s:v1."))
        self.assertNotIn("latest", image)
        self.assertIn("1.31.", image)

    def test_single_server_node_topology(self):
        """Verifies single server node resource-conscious topology on single physical host."""
        self.assertEqual(self.cluster_cfg.get("servers"), 1)
        self.assertEqual(self.cluster_cfg.get("agents", 0), 0)

    def test_loopback_only_kube_api(self):
        """Verifies Kubernetes API is bound strictly to loopback (127.0.0.1:16443)."""
        kube_api = self.cluster_cfg.get("kubeAPI", {})
        self.assertEqual(kube_api.get("hostIP"), "127.0.0.1")
        self.assertEqual(str(kube_api.get("hostPort")), "16443")
        self.assertNotEqual(kube_api.get("hostIP"), "0.0.0.0")

    def test_loopback_only_ingress_ports(self):
        """Verifies ingress ports are bound strictly to 127.0.0.1 to avoid LAN/public exposure."""
        ports = self.cluster_cfg.get("ports", [])
        self.assertTrue(len(ports) >= 2, "Expected at least HTTP and HTTPS ingress ports")
        for port_entry in ports:
            port_str = port_entry.get("port", "")
            self.assertTrue(
                port_str.startswith("127.0.0.1:"),
                f"Ingress port '{port_str}' must be explicitly bound to 127.0.0.1"
            )
            self.assertNotIn("0.0.0.0", port_str)

    def test_kubeconfig_context_switch_disabled(self):
        """Verifies automatic switching of user's active context is disabled."""
        kubeconfig_opts = self.cluster_cfg.get("options", {}).get("kubeconfig", {})
        self.assertFalse(
            kubeconfig_opts.get("switchCurrentContext", True),
            "k3d must not automatically hijack or overwrite the active kube context"
        )

    def test_storage_volume_mount(self):
        """Verifies host volume mount into K3s local-path provisioner storage root."""
        volumes = self.cluster_cfg.get("volumes", [])
        self.assertTrue(len(volumes) >= 1)
        storage_mount_found = False
        for vol in volumes:
            vol_str = vol.get("volume", "")
            if "/var/lib/rancher/k3s/storage" in vol_str:
                storage_mount_found = True
                self.assertIn(".swiftdrop/k3d/storage", vol_str)
        self.assertTrue(storage_mount_found, "Missing mount for /var/lib/rancher/k3s/storage")

    def test_namespaces_yaml_declarations(self):
        """Verifies all required management namespaces and boundary labels exist."""
        expected_namespaces = {
            "swiftdrop-system": "platform-system",
            "swiftdrop-apps": "microservices",
            "swiftdrop-data": "persistence",
            "swiftdrop-identity": "identity-idp",
            "swiftdrop-messaging": "async-events",
        }
        found_namespaces = {}
        for doc in self.namespaces:
            if doc and doc.get("kind") == "Namespace":
                ns_name = doc.get("metadata", {}).get("name")
                labels = doc.get("metadata", {}).get("labels", {})
                self.assertEqual(labels.get("app.kubernetes.io/part-of"), "swiftdrop")
                found_namespaces[ns_name] = labels.get("swiftdrop.io/boundary")

        for expected_name, expected_boundary in expected_namespaces.items():
            self.assertIn(expected_name, found_namespaces)
            self.assertEqual(found_namespaces[expected_name], expected_boundary)

    def test_azure_allowlist_policy_alignment(self):
        """Verifies alignment with strict $0 hosting policy (ADR-0011)."""
        local_target = self.azure_policy.get("local", {}).get("target")
        local_authorized = self.azure_policy.get("local", {}).get("authorized")
        self.assertEqual(local_target, "k3d-k3s")
        self.assertTrue(local_authorized)

        azure_deploy = self.azure_policy.get("azure", {}).get("deployment_authorized")
        self.assertFalse(azure_deploy)
        allowed_resources = self.azure_policy.get("azure", {}).get("allowed_resources", [])
        self.assertEqual(allowed_resources, [])


if __name__ == "__main__":
    unittest.main()
