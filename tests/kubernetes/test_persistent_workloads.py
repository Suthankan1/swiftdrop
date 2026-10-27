"""
SwiftDrop Persistent Workloads, Data Isolation, and Readiness Scaling Tests
Canonical Reference: docs/architecture/BASELINE.md, docs/team/OWNERSHIP.md
GitHub Issue: https://github.com/Suthankan1/swiftdrop/issues/154
"""

import subprocess
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_CHART_DIR = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop-data"
MESSAGING_CHART_DIR = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop-messaging"
APPS_CHART_DIR = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop"
VALUES_LOCAL = APPS_CHART_DIR / "values-local.yaml"
VALUES_DEFAULT = APPS_CHART_DIR / "values.yaml"


def render_chart(chart_dir: Path, values_file: Path = None):
    cmd = ["helm", "template", "test-release", str(chart_dir)]
    if values_file and values_file.exists():
        cmd.extend(["-f", str(values_file)])
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return [d for d in yaml.safe_load_all(res.stdout) if d is not None]


class TestPersistentWorkloads(unittest.TestCase):
    """Validates Issue #154 Kubernetes stateful workloads, PVCs, scaling, and readiness probes."""

    @classmethod
    def setUpClass(cls):
        cls.data_docs = render_chart(DATA_CHART_DIR, DATA_CHART_DIR / "values-local.yaml")
        cls.messaging_docs = render_chart(MESSAGING_CHART_DIR, MESSAGING_CHART_DIR / "values-local.yaml")
        cls.apps_docs = render_chart(APPS_CHART_DIR, VALUES_LOCAL)

    def test_stateful_postgres_workloads(self):
        """Verifies PostgreSQL StatefulSets with local-path PVCs and service isolation."""
        statefulsets = [d for d in self.data_docs if d.get("kind") == "StatefulSet"]
        names = {s["metadata"]["name"] for s in statefulsets}
        expected = {"accounts-postgres", "business-postgres", "keycloak-postgres"}
        self.assertEqual(names, expected, f"Expected {expected}, got {names}")

        for ss in statefulsets:
            # Must use local-path storage class
            vct = ss["spec"]["volumeClaimTemplates"][0]
            self.assertEqual(vct["spec"]["storageClassName"], "local-path")
            self.assertIn("ReadWriteOnce", vct["spec"]["accessModes"])

            # Container security context
            container = ss["spec"]["template"]["spec"]["containers"][0]
            sec = container.get("securityContext", {})
            self.assertFalse(sec.get("allowPrivilegeEscalation", True))

            # Must have liveness and readiness exec probes
            self.assertIn("livenessProbe", container)
            self.assertIn("readinessProbe", container)
            self.assertIn("exec", container["livenessProbe"])
            self.assertIn("exec", container["readinessProbe"])

    def test_database_init_script_isolation(self):
        """Verifies database initialization configmap creates isolated unprivileged roles."""
        configmaps = [d for d in self.data_docs if d.get("kind") == "ConfigMap"]
        init_cm = next((cm for cm in configmaps if "init-scripts" in cm["metadata"]["name"]), None)
        self.assertIsNotNone(init_cm, "Missing init-scripts ConfigMap in swiftdrop-data chart")

        init_script = init_cm["data"]["01-init-business-databases.sh"]
        for role in ["pricing_app", "delivery_app", "communications_app"]:
            self.assertIn(f"CREATE ROLE {role}", init_script)
            self.assertIn(f"NOSUPERUSER", init_script)
            self.assertIn(f"REVOKE ALL ON DATABASE", init_script)

    def test_kafka_and_minio_stateful_workloads(self):
        """Verifies Kafka KRaft and MinIO persistent StatefulSets in messaging namespace."""
        statefulsets = [d for d in self.messaging_docs if d.get("kind") == "StatefulSet"]
        names = {s["metadata"]["name"] for s in statefulsets}
        self.assertIn("kafka", names)
        self.assertIn("minio", names)

        kafka_ss = next(s for s in statefulsets if s["metadata"]["name"] == "kafka")
        kafka_container = kafka_ss["spec"]["template"]["spec"]["containers"][0]
        # KRaft mode checks
        env_vars = {e["name"]: e.get("value") for e in kafka_container.get("env", []) if "value" in e}
        self.assertEqual(env_vars.get("KAFKA_PROCESS_ROLES"), "broker,controller")
        self.assertEqual(env_vars.get("KAFKA_NODE_ID"), "1")
        self.assertEqual(env_vars.get("CLUSTER_ID"), "4L622nShTUiAnsYh0-X41w")

        # MinIO checks
        minio_ss = next(s for s in statefulsets if s["metadata"]["name"] == "minio")
        minio_vct = minio_ss["spec"]["volumeClaimTemplates"][0]
        self.assertEqual(minio_vct["spec"]["storageClassName"], "local-path")

    def test_kafka_topic_init_job(self):
        """Verifies automated Kafka topic creation batch Job exists and provisions parcel topics."""
        jobs = [d for d in self.messaging_docs if d.get("kind") == "Job"]
        topic_job = next((j for j in jobs if "init-topics" in j["metadata"]["name"]), None)
        self.assertIsNotNone(topic_job, "Missing kafka-init-topics Job in swiftdrop-messaging chart")

        cmd_str = str(topic_job["spec"]["template"]["spec"]["containers"][0]["command"])
        self.assertIn("parcel.lifecycle.v1", cmd_str)
        self.assertIn("parcel.lifecycle.v1.dlt", cmd_str)

    def test_delivery_two_replica_scaling(self):
        """Verifies that Delivery service is configured with 2 replicas in values-local.yaml for Issue #161."""
        deployments = [d for d in self.apps_docs if d.get("kind") == "Deployment"]
        delivery_dep = next((d for d in deployments if d["metadata"]["name"] == "delivery-service"), None)
        self.assertIsNotNone(delivery_dep)
        self.assertEqual(delivery_dep["spec"]["replicas"], 2, "Delivery service must have replicaCount: 2")

    def test_rolling_update_and_pdb_configuration(self):
        """Verifies safe rolling update strategies and PodDisruptionBudgets across application workloads."""
        deployments = [d for d in self.apps_docs if d.get("kind") == "Deployment"]
        pdbs = [d for d in self.apps_docs if d.get("kind") == "PodDisruptionBudget"]
        pdb_names = {p["metadata"]["name"] for p in pdbs}

        for dep in deployments:
            name = dep["metadata"]["name"]
            # Rolling update strategy
            strategy = dep["spec"].get("strategy", {})
            self.assertEqual(strategy.get("type"), "RollingUpdate")
            rolling = strategy.get("rollingUpdate", {})
            self.assertEqual(rolling.get("maxSurge"), 0)
            self.assertEqual(rolling.get("maxUnavailable"), 1)

            # Pod spec lifecycle
            pod_spec = dep["spec"]["template"]["spec"]
            self.assertGreaterEqual(pod_spec.get("terminationGracePeriodSeconds", 0), 15)

        expected_pdbs = {
            "frontend-budget",
            "api-gateway-budget",
            "accounts-budget",
            "pricing-budget",
            "delivery-budget",
            "communications-budget",
            "graphql-budget",
            "keycloak-budget",
        }
        self.assertEqual(pdb_names, expected_pdbs)

        # Delivery PDB specifically protects multi-replica availability
        delivery_pdb = next(p for p in pdbs if p["metadata"]["name"] == "delivery-budget")
        self.assertEqual(delivery_pdb["spec"].get("minAvailable"), 1)

    def test_complete_probe_definitions(self):
        """Verifies startup, liveness, and readiness probes on all 8 application workloads."""
        deployments = [d for d in self.apps_docs if d.get("kind") == "Deployment"]
        self.assertEqual(len(deployments), 8, "Expected exactly 8 application deployments")

        for dep in deployments:
            name = dep["metadata"]["name"]
            c = dep["spec"]["template"]["spec"]["containers"][0]
            self.assertIn("startupProbe", c, f"Deployment {name} missing startupProbe")
            self.assertIn("livenessProbe", c, f"Deployment {name} missing livenessProbe")
            self.assertIn("readinessProbe", c, f"Deployment {name} missing readinessProbe")

            # Check probe timeouts are resilient (>= 2s)
            self.assertGreaterEqual(c["startupProbe"].get("timeoutSeconds", 1), 2)
            self.assertGreaterEqual(c["livenessProbe"].get("timeoutSeconds", 1), 2)
            self.assertGreaterEqual(c["readinessProbe"].get("timeoutSeconds", 1), 2)


if __name__ == "__main__":
    unittest.main()
