"""Static monitoring query contract tests for Issue #166.

These checks deliberately do not claim live Prometheus, Alertmanager, Grafana, or
Mailpit verification.  They protect the query definitions that can be verified
from source and defer runtime evaluation to promtool/a real operator environment.
"""

import json
import os
import re
import shutil
import subprocess
import unittest

import yaml


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
MONITORING_ROOT = os.path.join(
    REPO_ROOT, "platform", "kubernetes", "helm", "swiftdrop-monitoring"
)
RULES_PATH = os.path.join(MONITORING_ROOT, "files", "alerts", "swiftdrop-alerts.yaml")
DASHBOARDS_PATH = os.path.join(MONITORING_ROOT, "files", "dashboards")
RUNBOOK_PATH = os.path.join(
    REPO_ROOT, "docs", "observability", "N10_P14_MONITORING_OPERATIONS.md"
)


EXPECTED_ALERTS = {
    "ServiceReadinessFailed",
    "MonitoringTargetScrapeMissing",
    "OutboxRelayOldestAgeExceeded",
    "OutboxRelayBacklogAccumulation",
    "OutboxDeadLetterEventsAccumulated",
    "WebhookTerminalExhaustionSpike",
    "NotificationPendingBacklogAgeExceeded",
    "KafkaConsumerFailureSpike",
    "GrpcServerDeadlineExceededSpike",
    "ApplicationPodCrashLooping",
    "MonitoringDiskSpaceLow",
    "ReleaseCandidateReadinessRegression",
}

EXPECTED_DASHBOARD_UIDS = {
    "swiftdrop-communications",
    "swiftdrop-delivery-outbox",
    "swiftdrop-grpc",
    "swiftdrop-release-monitoring",
    "swiftdrop-service-health",
    "swiftdrop-sse",
}

REQUIRED_ANNOTATIONS = {
    "summary",
    "description",
    "affected_component",
    "operational_impact",
    "suggested_investigation",
    "runbook_url",
    "expected_recovery",
}

KNOWN_QUERY_METRICS = {
    "ALERTS",
    "probe_duration_seconds",
    "probe_success",
    "up",
    "swiftdrop_outbox_pending",
    "swiftdrop_outbox_failed",
    "swiftdrop_outbox_dead_letter",
    "swiftdrop_outbox_oldest_age_seconds",
    "swiftdrop_outbox_publish_total",
    "swiftdrop_webhook_deliveries",
    "swiftdrop_webhook_exhausted_total",
    "swiftdrop_webhook_attempts_total",
    "swiftdrop_notification_jobs",
    "swiftdrop_notification_pending_age_seconds",
    "swiftdrop_kafka_consumer_processed_total",
    "swiftdrop_grpc_server_requests_total",
    "swiftdrop_grpc_server_errors_total",
    "swiftdrop_grpc_client_requests_total",
    "swiftdrop_grpc_client_duration_seconds_sum",
    "swiftdrop_grpc_client_duration_seconds_count",
    "swiftdrop_sse_connections_active",
    "swiftdrop_sse_connections_accepted_total",
    "swiftdrop_sse_connections_closed_total",
    "swiftdrop_sse_connections_rejected_total",
    # These two providers are absent from the current chart and are explicitly
    # recorded as blockers in N10_P14A_MONITORING_QUERY_VERIFICATION.md.
    "kube_pod_container_status_restarts_total",
    "node_filesystem_avail_bytes",
    "node_filesystem_size_bytes",
}


def github_anchor(heading):
    """Return the anchor form used by GitHub for the headings in this runbook."""
    anchor = heading.strip().lower().replace(" ", "-")
    return re.sub(r"[^a-z0-9_-]", "", anchor)


class TestMonitoringQuerySemantics(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(RULES_PATH, encoding="utf-8") as rules_file:
            cls.rules_document = yaml.safe_load(rules_file)
        cls.rules = {
            rule["alert"]: rule
            for group in cls.rules_document["groups"]
            for rule in group.get("rules", [])
        }

        cls.dashboards = {}
        for filename in os.listdir(DASHBOARDS_PATH):
            if filename.endswith(".json"):
                with open(os.path.join(DASHBOARDS_PATH, filename), encoding="utf-8") as dashboard_file:
                    dashboard = json.load(dashboard_file)
                cls.dashboards[dashboard["uid"]] = dashboard

        with open(RUNBOOK_PATH, encoding="utf-8") as runbook_file:
            cls.runbook_text = runbook_file.read()
        cls.runbook_anchors = {
            github_anchor(match.group(1))
            for match in re.finditer(r"^#{1,6}\s+(.+?)\s*$", cls.runbook_text, re.MULTILINE)
        }

    def test_all_twelve_alerts_are_present_once(self):
        self.assertEqual(set(self.rules), EXPECTED_ALERTS)
        self.assertEqual(len(self.rules), 12)

    def test_alerts_have_actionable_metadata_and_bounded_severity(self):
        for name, rule in self.rules.items():
            self.assertIn(rule["labels"]["severity"], {"warning", "critical"}, name)
            self.assertTrue(rule["labels"].get("owner"), name)
            self.assertTrue(rule.get("for"), name)
            self.assertTrue(REQUIRED_ANNOTATIONS.issubset(rule["annotations"]), name)

    def test_runbook_links_resolve_to_real_headings(self):
        prefix = "docs/observability/N10_P14_MONITORING_OPERATIONS.md#"
        for name, rule in self.rules.items():
            url = rule["annotations"]["runbook_url"]
            self.assertTrue(url.startswith(prefix), name)
            self.assertIn(url.removeprefix(prefix), self.runbook_anchors, name)

    def test_counter_queries_use_rate_and_gauges_do_not(self):
        rate_alerts = {
            "WebhookTerminalExhaustionSpike",
            "KafkaConsumerFailureSpike",
            "GrpcServerDeadlineExceededSpike",
            "ApplicationPodCrashLooping",
        }
        gauge_alerts = EXPECTED_ALERTS - rate_alerts
        for name in rate_alerts:
            self.assertIn("rate(", self.rules[name]["expr"], name)
        for name in gauge_alerts:
            self.assertNotIn("rate(", self.rules[name]["expr"], name)

    def test_thresholds_and_for_durations_are_explicit_contracts(self):
        expected = {
            "ServiceReadinessFailed": ('probe_success{job="swiftdrop-readiness"} == 0', "30s"),
            "MonitoringTargetScrapeMissing": ("up == 0", "1m"),
            "OutboxRelayOldestAgeExceeded": ("swiftdrop_outbox_oldest_age_seconds > 60", "2m"),
            "OutboxRelayBacklogAccumulation": ("swiftdrop_outbox_pending > 100", "3m"),
            "OutboxDeadLetterEventsAccumulated": ("swiftdrop_outbox_dead_letter > 0", "2m"),
            "WebhookTerminalExhaustionSpike": ("rate(swiftdrop_webhook_exhausted_total[5m]) > 5", "2m"),
            "NotificationPendingBacklogAgeExceeded": ("swiftdrop_notification_pending_age_seconds > 120", "3m"),
            "KafkaConsumerFailureSpike": ('rate(swiftdrop_kafka_consumer_processed_total{status="failure"}[5m]) > 0', "2m"),
            "GrpcServerDeadlineExceededSpike": ('rate(swiftdrop_grpc_server_errors_total{status="DEADLINE_EXCEEDED"}[5m]) > 2', "2m"),
            "ApplicationPodCrashLooping": ('rate(kube_pod_container_status_restarts_total{namespace=~"swiftdrop-.*"}[5m]) > 0.05', "3m"),
            "MonitoringDiskSpaceLow": ('(node_filesystem_avail_bytes{mountpoint="/prometheus"} / node_filesystem_size_bytes{mountpoint="/prometheus"}) < 0.15', "2m"),
            "ReleaseCandidateReadinessRegression": ('(min(probe_success{job="swiftdrop-readiness"}) or vector(0)) == 0', "1m"),
        }
        for name, (expression, duration) in expected.items():
            self.assertEqual(self.rules[name]["expr"], expression, name)
            self.assertEqual(self.rules[name]["for"], duration, name)

    def test_kafka_failure_selector_matches_implemented_label_value(self):
        source_path = os.path.join(
            REPO_ROOT,
            "services",
            "communications-service",
            "src",
            "main",
            "java",
            "com",
            "swiftdrop",
            "communicationsservice",
            "consumer",
            "ParcelLifecycleKafkaConsumer.java",
        )
        with open(source_path, encoding="utf-8") as source_file:
            source = source_file.read()
        self.assertIn('recordKafkaConsumerProcessed("communications.notifications.v1", "failure")', source)
        self.assertIn('status="failure"', self.rules["KafkaConsumerFailureSpike"]["expr"])

    def test_release_alert_and_summary_panels_are_missing_data_safe(self):
        self.assertIn("or vector(0)", self.rules["ReleaseCandidateReadinessRegression"]["expr"])
        for uid, title in (
            ("swiftdrop-release-monitoring", "Release Acceptance Gate (All Services Ready)"),
            ("swiftdrop-service-health", "Overall Core Service Health"),
        ):
            panel = next(panel for panel in self.dashboards[uid]["panels"] if panel["title"] == title)
            self.assertIn("or vector(0)", panel["targets"][0]["expr"])

    def test_all_six_dashboard_uids_and_prometheus_datasources_are_stable(self):
        self.assertEqual(set(self.dashboards), EXPECTED_DASHBOARD_UIDS)
        for uid, dashboard in self.dashboards.items():
            for panel in dashboard["panels"]:
                self.assertEqual(panel["datasource"]["type"], "prometheus", f"{uid}: {panel['title']}")
                self.assertEqual(
                    panel["datasource"]["uid"],
                    "swiftdrop-prometheus",
                    f"{uid}: {panel['title']}",
                )
                for target in panel.get("targets", []):
                    self.assertTrue(target.get("expr", "").strip(), f"{uid}: {panel['title']}")

    def test_every_rule_and_panel_uses_an_inventory_metric(self):
        expressions = {
            f"alert:{name}": rule["expr"] for name, rule in self.rules.items()
        }
        expressions.update(
            {
                f"{uid}:{panel['title']}:{target['refId']}": target["expr"]
                for uid, dashboard in self.dashboards.items()
                for panel in dashboard["panels"]
                for target in panel.get("targets", [])
            }
        )
        for query_name, expression in expressions.items():
            matched = {
                metric
                for metric in KNOWN_QUERY_METRICS
                if re.search(rf"(?<![A-Za-z0-9_:]){re.escape(metric)}(?![A-Za-z0-9_:])", expression)
            }
            self.assertTrue(matched, f"{query_name} has no metric in the verified inventory: {expression}")

    def test_grpc_latency_uses_exported_timer_sum_and_count(self):
        panel = next(
            panel
            for panel in self.dashboards["swiftdrop-grpc"]["panels"]
            if panel["title"] == "Client Call Latency (seconds)"
        )
        expression = panel["targets"][0]["expr"]
        self.assertIn("swiftdrop_grpc_client_duration_seconds_sum", expression)
        self.assertIn("swiftdrop_grpc_client_duration_seconds_count", expression)
        self.assertIn("rate(", expression)
        self.assertEqual(panel["fieldConfig"]["defaults"]["unit"], "s")

    def test_dashboard_rate_units_are_compatible(self):
        rate_units = {"ops", "reqps", "errps", "cps"}
        for uid, dashboard in self.dashboards.items():
            for panel in dashboard["panels"]:
                expression = " ".join(target["expr"] for target in panel.get("targets", []))
                unit = panel.get("fieldConfig", {}).get("defaults", {}).get("unit")
                if "rate(" in expression and "duration_seconds" not in expression:
                    self.assertIn(unit, rate_units, f"{uid}: {panel['title']}")

    def test_queries_do_not_use_sensitive_or_high_cardinality_labels(self):
        forbidden = {"parcel_id", "event_id", "tenant_id", "actor_sub", "email", "token"}
        query_text = "\n".join(rule["expr"] for rule in self.rules.values())
        query_text += "\n" + "\n".join(
            target["expr"]
            for dashboard in self.dashboards.values()
            for panel in dashboard["panels"]
            for target in panel.get("targets", [])
        )
        for label in forbidden:
            self.assertNotIn(label, query_text.lower())

    @unittest.skipUnless(shutil.which("promtool"), "promtool is not installed in this environment")
    def test_promtool_parses_alert_rules(self):
        result = subprocess.run(
            ["promtool", "check", "rules", RULES_PATH],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
