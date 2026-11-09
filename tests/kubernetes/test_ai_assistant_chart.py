"""AI assistant chart wiring: opt-in, least-privilege network paths, optional key Secret, hardened pod."""

import os
import subprocess
import unittest

import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
HELM_DIR = os.path.join(REPO_ROOT, "platform/kubernetes/helm/swiftdrop")


def render(*sets):
    cmd = ["helm", "template", "swiftdrop", HELM_DIR]
    for s in sets:
        cmd += ["--set", s]
    out = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True).stdout
    return [d for d in yaml.safe_load_all(out) if d]


def find(docs, kind, name):
    return next((d for d in docs if d["kind"] == kind and d["metadata"]["name"] == name), None)


def peers(policy_rules, key):
    return {
        rule_peer.get("podSelector", {}).get("matchLabels", {}).get("app.kubernetes.io/name")
        for rule in policy_rules for rule_peer in rule.get(key, [])
    }


class TestAiAssistantChart(unittest.TestCase):
    def test_disabled_by_default_renders_nothing(self):
        text = yaml.safe_dump_all(render())
        self.assertNotIn("ai-assistant", text)
        self.assertNotIn("AI_ASSISTANT_URL", text)

    def test_enabled_renders_hardened_workload_with_optional_key(self):
        docs = render("aiAssistant.enabled=true")
        for kind, name in [("Deployment", "ai-assistant"), ("Service", "ai-assistant"),
                           ("ServiceAccount", "swiftdrop-ai-assistant-sa"), ("PodDisruptionBudget", "ai-assistant-budget"),
                           ("ConfigMap", "swiftdrop-ai-assistant-config"), ("NetworkPolicy", "allow-ai-assistant-traffic")]:
            self.assertIsNotNone(find(docs, kind, name), f"missing {kind}/{name}")
        pod = find(docs, "Deployment", "ai-assistant")["spec"]["template"]["spec"]
        container = pod["containers"][0]
        self.assertTrue(container["securityContext"]["readOnlyRootFilesystem"])
        self.assertFalse(container["securityContext"]["allowPrivilegeEscalation"])
        key = container["env"][0]
        self.assertEqual(key["name"], "GEMINI_API_KEY")
        self.assertTrue(key["valueFrom"]["secretKeyRef"]["optional"])
        config = find(docs, "ConfigMap", "swiftdrop-ai-assistant-config")["data"]
        self.assertNotIn("GEMINI_API_KEY", config, "the key must come from a Secret, never a ConfigMap")
        self.assertEqual(find(docs, "ConfigMap", "swiftdrop-gateway-config")["data"]["AI_ASSISTANT_URL"],
                         "http://ai-assistant.swiftdrop-apps.svc.cluster.local:8086")

    def test_network_paths_are_least_privilege(self):
        docs = render("aiAssistant.enabled=true")
        policy = find(docs, "NetworkPolicy", "allow-ai-assistant-traffic")["spec"]
        self.assertEqual(peers(policy["ingress"], "from"), {"api-gateway"})
        self.assertEqual(peers(policy["egress"], "to") - {None}, {"pricing-service", "delivery-service", "keycloak"})
        internet = [r for r in policy["egress"] if any("ipBlock" in p for p in r["to"])]
        self.assertEqual(len(internet), 1)
        self.assertEqual([p["port"] for p in internet[0]["ports"]], [443])
        self.assertIn("10.0.0.0/8", internet[0]["to"][0]["ipBlock"]["except"])
        for name in ("allow-pricing-traffic", "allow-delivery-traffic"):
            self.assertIn("ai-assistant", peers(find(docs, "NetworkPolicy", name)["spec"]["ingress"], "from"))
        self.assertIn("ai-assistant", peers(find(docs, "NetworkPolicy", "allow-gateway-egress")["spec"]["egress"], "to"))

    def test_disabled_keeps_existing_policies_unchanged(self):
        docs = render()
        for name in ("allow-pricing-traffic", "allow-delivery-traffic"):
            self.assertNotIn("ai-assistant", peers(find(docs, "NetworkPolicy", name)["spec"]["ingress"], "from"))


if __name__ == "__main__":
    unittest.main()
