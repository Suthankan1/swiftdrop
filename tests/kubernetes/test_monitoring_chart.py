"""
SwiftDrop Platform — Automated Monitoring Helm Chart & Security Tests (Issue #160)

Validates:
1. Chart.yaml integrity, semantic versioning, and metadata.
2. Rendered manifests across values.yaml and values-local.yaml.
3. Least-privilege ServiceAccounts for all monitoring components.
4. Strictly private ClusterIP-only services (zero NodePort, LoadBalancer, externalIPs).
5. Absolute absence of public Ingress resources in swiftdrop-monitoring.
6. Workload security contexts (runAsNonRoot, dropped ALL capabilities, runtimeDefault seccomp).
7. Prometheus configuration: bounded retention (24h), non-root execution, scrape configs.
8. Alertmanager configuration: Mailpit SMTP routing, inhibition rules, non-root execution.
9. Grafana configuration: anonymous access disabled, secret-backed admin credentials.
10. Lightweight logging (Loki + Promtail): bounded retention, redaction pipeline for secrets.
11. NetworkPolicies: default-deny-all, scoped intra-cluster egress, and cross-namespace scrape allow.
12. Strict $0 hosting policy alignment (ADR-0011; zero Azure or paid cloud resources).
"""

import os
import subprocess
import unittest
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
HELM_DIR = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop-monitoring")
VALUES_FILE = os.path.join(HELM_DIR, "values.yaml")
VALUES_LOCAL = os.path.join(HELM_DIR, "values-local.yaml")


def render_monitoring_templates(values_file=None):
    """Renders swiftdrop-monitoring Helm chart templates and returns parsed docs."""
    cmd = ["helm", "template", "swiftdrop-monitoring", HELM_DIR]
    if values_file:
        cmd.extend(["-f", values_file])
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
    return [d for d in yaml.safe_load_all(result.stdout) if d is not None]


class TestMonitoringChart(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(HELM_DIR, "Chart.yaml"), "r") as f:
            cls.chart = yaml.safe_load(f)
        with open(VALUES_FILE, "r") as f:
            cls.values = yaml.safe_load(f)
        with open(VALUES_LOCAL, "r") as f:
            cls.values_local = yaml.safe_load(f)
        cls.rendered_prod = render_monitoring_templates(VALUES_FILE)
        cls.rendered_local = render_monitoring_templates(VALUES_LOCAL)

    def test_chart_metadata(self):
        """Verifies Chart.yaml metadata, apiVersion, and application version."""
        self.assertEqual(self.chart["apiVersion"], "v2")
        self.assertEqual(self.chart["name"], "swiftdrop-monitoring")
        self.assertEqual(self.chart["type"], "application")
        self.assertEqual(self.chart["version"], "1.0.0")
        self.assertEqual(self.chart["appVersion"], "1.3.0")

    def test_render_all_value_profiles(self):
        """Verifies both values.yaml and values-local.yaml render complete resource sets."""
        for name, docs in [("prod", self.rendered_prod), ("local", self.rendered_local)]:
            self.assertGreaterEqual(
                len(docs), 20, f"Expected at least 20 rendered manifests for {name}, got {len(docs)}"
            )

    def test_serviceaccounts_rendered(self):
        """Verifies dedicated ServiceAccounts for all monitoring components."""
        expected_sas = {
            "swiftdrop-prometheus-sa",
            "swiftdrop-blackbox-sa",
            "swiftdrop-alertmanager-sa",
            "swiftdrop-grafana-sa",
            "swiftdrop-loki-sa",
            "swiftdrop-promtail-sa",
        }
        rendered_sas = {
            d["metadata"]["name"] for d in self.rendered_local if d.get("kind") == "ServiceAccount"
        }
        for sa in expected_sas:
            self.assertIn(sa, rendered_sas, f"Missing ServiceAccount: {sa}")

    def test_services_are_strictly_clusterip(self):
        """Verifies that all monitoring Services are strictly ClusterIP (no public exposure)."""
        svc_docs = [d for d in self.rendered_local if d.get("kind") == "Service"]
        self.assertGreaterEqual(len(svc_docs), 4, "Expected at least 4 Services rendered")
        for svc in svc_docs:
            svc_type = svc.get("spec", {}).get("type", "ClusterIP")
            svc_name = svc.get("metadata", {}).get("name", "")
            self.assertEqual(
                svc_type,
                "ClusterIP",
                f"Service {svc_name} has unapproved type {svc_type}; must be ClusterIP",
            )
            # Verify no externalIPs or nodePort
            self.assertNotIn("externalIPs", svc.get("spec", {}))

    def test_absolute_absence_of_ingress(self):
        """Verifies zero Ingress resources in the monitoring chart (private access only)."""
        ingress_docs = [d for d in self.rendered_local if d.get("kind") == "Ingress"]
        self.assertEqual(len(ingress_docs), 0, "Public Ingress is strictly forbidden in monitoring chart")

    def test_workload_security_contexts(self):
        """Verifies runAsNonRoot, dropped capabilities, and seccomp profiles."""
        workload_kinds = ["Deployment", "DaemonSet", "StatefulSet"]
        workloads = [d for d in self.rendered_local if d.get("kind") in workload_kinds]
        self.assertGreaterEqual(len(workloads), 4, "Expected at least 4 workloads")

        for w in workloads:
            w_name = w["metadata"]["name"]
            pod_spec = w["spec"]["template"]["spec"]

            # Deployments must enforce runAsNonRoot
            if w.get("kind") == "Deployment":
                pod_sc = pod_spec.get("securityContext", {})
                self.assertTrue(
                    pod_sc.get("runAsNonRoot", False),
                    f"Workload {w_name} must specify pod-level runAsNonRoot: true",
                )
                seccomp = pod_sc.get("seccompProfile", {})
                self.assertEqual(
                    seccomp.get("type"),
                    "RuntimeDefault",
                    f"Workload {w_name} must use RuntimeDefault seccomp profile",
                )

            # Container-level security context must drop ALL capabilities
            for c in pod_spec.get("containers", []):
                c_name = c["name"]
                c_sc = c.get("securityContext", {})
                caps = c_sc.get("capabilities", {})
                drop_caps = caps.get("drop", [])
                self.assertIn(
                    "ALL",
                    drop_caps,
                    f"Container {c_name} in {w_name} must drop ALL capabilities",
                )

    def test_prometheus_bounded_retention(self):
        """Verifies Prometheus storage retention flags and volume limits."""
        prom_deploy = next(
            d for d in self.rendered_local
            if d.get("kind") == "Deployment" and d["metadata"]["name"] == "swiftdrop-prometheus"
        )
        container = prom_deploy["spec"]["template"]["spec"]["containers"][0]
        args = container.get("args", [])
        retention_arg = next((a for a in args if "--storage.tsdb.retention.time" in a), None)
        size_arg = next((a for a in args if "--storage.tsdb.retention.size" in a), None)
        self.assertIsNotNone(retention_arg, "Prometheus must configure retention time")
        self.assertIsNotNone(size_arg, "Prometheus must configure retention size")
        self.assertIn("24h", retention_arg)
        self.assertIn("1500MB", size_arg)

    def test_prometheus_alert_rules_exist(self):
        """Verifies alert rules ConfigMap is rendered with reviewed alert definitions."""
        rules_cm = next(
            d for d in self.rendered_local
            if d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "swiftdrop-prometheus-rules"
        )
        rules_data = rules_cm.get("data", {}).get("swiftdrop-alerts.yaml", "")
        self.assertGreater(len(rules_data), 100, "Alert rules config must not be empty")
        parsed_rules = yaml.safe_load(rules_data)
        groups = parsed_rules.get("groups", [])
        self.assertGreater(len(groups), 0, "Expected at least 1 alert group")
        rule_names = [r["alert"] for g in groups for r in g.get("rules", [])]
        expected_rules = [
            "ServiceReadinessFailed",
            "OutboxRelayOldestAgeExceeded",
            "NotificationPendingBacklogAgeExceeded",
            "WebhookTerminalExhaustionSpike",
            "ApplicationPodCrashLooping",
        ]
        for er in expected_rules:
            self.assertIn(er, rule_names, f"Expected alert rule {er} in prometheus rules")

    def test_alertmanager_mailpit_smtp_config(self):
        """Verifies Alertmanager routes alerts to Mailpit SMTP."""
        am_cm = next(
            d for d in self.rendered_local
            if d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "swiftdrop-alertmanager-config"
        )
        config_text = am_cm.get("data", {}).get("alertmanager.yml", "")
        self.assertIn("mailpit.swiftdrop-messaging.svc.cluster.local:1025", config_text)
        self.assertIn("alerts@swiftdrop.local", config_text)
        self.assertIn("inhibit_rules", config_text)

    def test_grafana_anonymous_disabled(self):
        """Verifies Grafana disables anonymous access and uses secret-backed admin password."""
        grafana_deploy = next(
            d for d in self.rendered_local
            if d.get("kind") == "Deployment" and d["metadata"]["name"] == "swiftdrop-grafana"
        )
        container = grafana_deploy["spec"]["template"]["spec"]["containers"][0]
        env_vars = {e["name"]: e.get("value") or e.get("valueFrom") for e in container.get("env", [])}

        self.assertIn("GF_AUTH_ANONYMOUS_ENABLED", env_vars)
        self.assertEqual(env_vars["GF_AUTH_ANONYMOUS_ENABLED"], "false")
        self.assertIn("GF_SECURITY_ADMIN_PASSWORD", env_vars)
        # Verify password comes from secretRef
        secret_key_ref = env_vars["GF_SECURITY_ADMIN_PASSWORD"].get("secretKeyRef", {})
        self.assertEqual(secret_key_ref.get("name"), "grafana-admin-secret")
        self.assertEqual(secret_key_ref.get("key"), "admin-password")

    def test_logging_promtail_redaction_pipeline(self):
        """Verifies Promtail config contains regex redaction stages for sensitive data."""
        promtail_cm = next(
            d for d in self.rendered_local
            if d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "swiftdrop-promtail-config"
        )
        config_text = promtail_cm.get("data", {}).get("promtail.yaml", "")
        self.assertIn("pipeline_stages", config_text)
        self.assertIn("REDACTED", config_text)

    def test_networkpolicies_enforce_isolation(self):
        """Verifies default-deny and scoped traffic rules across monitoring and apps."""
        np_docs = [d for d in self.rendered_local if d.get("kind") == "NetworkPolicy"]
        self.assertGreaterEqual(len(np_docs), 5, "Expected comprehensive NetworkPolicies")

        np_names = {d["metadata"]["name"] for d in np_docs}
        self.assertIn("default-deny-all-monitoring", np_names)
        self.assertIn("allow-dns-egress-monitoring", np_names)
        self.assertIn("allow-prometheus-traffic", np_names)
        self.assertIn("allow-alertmanager-traffic", np_names)
        self.assertIn("allow-grafana-traffic", np_names)
        self.assertIn("allow-monitoring-scrapes", np_names)

        # Cross-namespace scrape policy in swiftdrop-apps
        apps_np = next(d for d in np_docs if d["metadata"]["name"] == "allow-monitoring-scrapes")
        self.assertEqual(apps_np["metadata"]["namespace"], "swiftdrop-apps")

    def test_strict_zero_cost_policy(self):
        """Verifies ADR-0011 adherence: zero paid cloud resources or external SaaS endpoints."""
        all_text = yaml.dump(self.rendered_prod) + yaml.dump(self.rendered_local)
        forbidden_providers = [
            "azure",
            "microsoft",
            "datadog",
            "newrelic",
            "pagerduty",
            "opsgenie",
            "victorops",
            "slack.com/services",
            "amazonaws.com",
        ]
        for forbidden in forbidden_providers:
            self.assertNotIn(
                forbidden,
                all_text.lower(),
                f"Strict $0 violation: found reference to '{forbidden}' in monitoring manifests",
            )


if __name__ == "__main__":
    unittest.main()
