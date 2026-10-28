"""
SwiftDrop capacity-budget runner (Issue #165 / N10-QA-08).

Modes
  plan        Write the deterministic fixture manifest and a binding template. No network.
  self-check  Run every workload against the in-process SIMULATED stub to validate the harness.
              Never acceptance evidence.
  measure     Run against an authorized non-production environment with an operator binding.
              Verdict is at best BLOCKED_PENDING_APPROVAL while budgets are unapproved.
  acceptance  As measure, but refuses to send any load unless every budget is APPROVED and the
              approval pins the current budget digest.

Exit codes: 0 pass/self-check clean, 1 budget or invariant failure, 2 blocked (approval/evidence/safety).
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    __package__ = "tests.capacity"

from tests.capacity import budgets as budget_mod
from tests.capacity import transport
from tests.capacity.fixtures import Binding, BindingError, BoundParcel, build_dataset, load_binding, write_manifest
from tests.capacity.safety import UnsafeTargetError, assert_safe_environment_id, assert_safe_target, find_secret_leaks, scrub
from tests.capacity.workloads import CapacityRunner, SecurityLedger, Targets

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT_DIR = REPO_ROOT / "build" / "reports" / "capacity"
GRAPHQL_SRC = REPO_ROOT / "services" / "graphql-query-service" / "src" / "main" / "java" / "com" / "swiftdrop" / "graphql"

EXIT_OK, EXIT_FAIL, EXIT_BLOCKED = 0, 1, 2


class LiveDirectory:
    """Directory backed by an operator binding; token values are cached in memory only."""

    def __init__(self, binding: Binding):
        self._b = binding
        self._cache: Dict[str, str] = {}
        self.environment_id = binding.environment_id
        self.unknown_tracking_number = binding.unknown_tracking_number
        self.status_drivers = binding.status_drivers

    def token(self, actor_alias: str) -> str:
        if actor_alias not in self._cache:
            self._cache[actor_alias] = self._b.token(actor_alias)
        return self._cache[actor_alias]

    def negative_token(self, kind: str) -> Optional[str]:
        return self._b.negative_token(kind)

    def parcel(self, alias: str) -> BoundParcel:
        return self._b.parcels[alias]

    def secrets(self) -> List[str]:
        values = list(self._cache.values())
        for kind in self._b.negative_token_files:
            token = self._b.negative_token(kind)
            if token:
                values.append(token)
        return values


# ---- provenance and static inspection -----------------------------------------------------

def git_provenance() -> Dict:
    def git(*args: str) -> Optional[str]:
        try:
            return subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, timeout=10,
                                  check=True).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None
    status = git("status", "--porcelain")
    return {"harness_sha": git("rev-parse", "HEAD"), "harness_branch": git("rev-parse", "--abbrev-ref", "HEAD"),
            "harness_worktree_dirty": bool(status) if status is not None else None}


def static_graphql_limits() -> Dict:
    """Configured limits read from the checked-out source (evidence class STATIC_SOURCE)."""
    def find(path: Path, pattern: str) -> Optional[int]:
        try:
            m = re.search(pattern, path.read_text(encoding="utf-8"))
        except OSError:
            return None
        return int(m.group(1)) if m else None
    return {
        "configured_max_depth": find(GRAPHQL_SRC / "config" / "GraphqlRuntimeConfig.java", r"MaxQueryDepthInstrumentation\((\d+)\)"),
        "configured_max_complexity": find(GRAPHQL_SRC / "config" / "GraphqlRuntimeConfig.java", r"MaxQueryComplexityInstrumentation\((\d+)"),
        "configured_max_page_size": find(GRAPHQL_SRC / "limits" / "PaginationPolicy.java", r"MAX_SIZE\s*=\s*(\d+)"),
        "client_deadline_ms": find(GRAPHQL_SRC / "client" / "AuthenticatedReadClients.java", r"\},\s*(\d+)\);"),
    }


def load_generator_info() -> Dict:
    return {"platform": platform.platform(), "python": platform.python_version(), "cpu_count": os.cpu_count(),
            "hostname_recorded": False}


# ---- orchestration ------------------------------------------------------------------------

def execute(runner: CapacityRunner, args: argparse.Namespace, live: bool) -> Dict:
    ds = runner.dataset
    started = time.monotonic()
    cpu_started = time.process_time()
    m: Dict = {"workloads": {}, "dataset": ds.counts(), "evidence": {}}
    ev = "MEASURED_LIVE" if live else "SIMULATED"

    # Serial phase first: counter deltas are only meaningful without concurrent traffic.
    grpc = runner.measure_grpc_calls_per_query(samples=args.grpc_samples)
    static = static_graphql_limits()
    m["grpc"] = dict(grpc)
    m["grpc"]["client_deadline_ms"] = static["client_deadline_ms"]
    m["evidence"]["grpc.calls_per_query"] = ev if grpc.get("status") == "MEASURED" else "UNAVAILABLE"
    m["evidence"]["grpc.client_deadline_ms"] = "STATIC_SOURCE"

    runner.run_security_probes()
    m["graphql"] = {"configured_max_depth": static["configured_max_depth"],
                    "configured_max_complexity": static["configured_max_complexity"],
                    "configured_max_page_size": static["configured_max_page_size"],
                    "max_accepted_page_size": runner.max_accepted_page_size()}
    m["evidence"]["graphql.configured_*"] = "STATIC_SOURCE"
    m["evidence"]["graphql.max_accepted_page_size"] = ev

    sampler = runner.start_backlog_sampler(args.backlog_interval)
    customers = [a.alias for a in ds.by_role("CUSTOMER")]
    dispatchers = [a.alias for a in ds.by_role("DISPATCHER")] + [a.alias for a in ds.by_role("ADMIN")]
    m["workloads"]["dashboard_customer"] = runner.run_dashboard("customerDashboard", customers, args.clients, args.iterations)
    m["workloads"]["dashboard_merchant"] = runner.run_dashboard("merchantDashboard", ds.merchant_tenants(), args.clients, args.iterations)
    m["workloads"]["dashboard_dispatcher"] = runner.run_dashboard("dispatcherDashboard", dispatchers, args.clients,
                                                                  args.iterations, page_sizes=(1, 20, 50))
    m["workloads"]["tracking_public"] = runner.run_public_tracking(args.clients, args.iterations)
    m["workloads"]["sse_authorized"] = runner.run_sse(args.sse_connections, args.sse_duration,
                                                      disconnect_every=args.sse_disconnect_every,
                                                      disconnect_at_s=args.sse_disconnect_at)
    m["workloads"]["tracking_rate_limit_probe"] = runner.run_rate_limit_probe(args.rate_limit_burst)
    m["backlog"] = sampler.stop()
    for name in m["workloads"]:
        m["evidence"]["workloads." + name] = ev
    m["evidence"]["backlog"] = ev if m["backlog"].get("status") == "MEASURED" else "UNAVAILABLE"
    m["security"] = runner.ledger.as_dict()
    m["evidence"]["security"] = ev

    dashboards = [m["workloads"][k] for k in ("dashboard_customer", "dashboard_merchant", "dashboard_dispatcher", "tracking_public")]
    rates = [w["error_rate"] for w in dashboards if w["error_rate"] is not None]
    maxima = [w["latency"]["max_ms"] for w in dashboards if w["latency"]["max_ms"] is not None]
    m["aggregate"] = {"max_error_rate": max(rates) if rates else None, "max_latency_ms": max(maxima) if maxima else None}
    m["run"] = {"dashboard_concurrency": args.clients, "duration_s": round(time.monotonic() - started, 3),
                "load_generator_cpu_s": round(time.process_time() - cpu_started, 3)}
    return m


def reproduction(args: argparse.Namespace) -> List[str]:
    base = "python3 tests/capacity/run_capacity_budgets.py"
    return [
        "python3 -m unittest discover -s tests/capacity -p \"test_*.py\" -v",
        "%s --mode plan" % base,
        "%s --mode self-check" % base,
        "%s --mode %s --environment-id <non-prod-id> --api-base-url <gateway-url> --binding <binding.json> "
        "--clients %d --iterations %d --sse-connections %d --sse-duration %s [--graphql-metrics-url ...] "
        "[--delivery-metrics-url ...] [--resource-file ...] [--target-revision <deployed-sha>]"
        % (base, args.mode if args.mode in ("measure", "acceptance") else "measure",
           args.clients, args.iterations, args.sse_connections, args.sse_duration),
        "(cd services/graphql-query-service && ./mvnw -B -Dsurefire.failIfNoSpecifiedTests=false "
        "-Dtest=DashboardGrpcFanoutCapacityTest test)",
    ]


def acceptance_input_blockers(args: argparse.Namespace) -> List[str]:
    blockers = []
    if not args.target_revision or not args.target_revision.strip():
        blockers.append("--target-revision is required for acceptance evidence")
    if not args.resource_file:
        blockers.append("--resource-file is required for acceptance evidence")
    if args.sse_disconnect_every < 1:
        blockers.append("acceptance requires at least one planned SSE disconnect")
    if args.sse_disconnect_at <= 0 or args.sse_disconnect_at >= args.sse_duration:
        blockers.append("--sse-disconnect-at must be positive and less than --sse-duration")
    return blockers


def acceptance_measurement_blockers(measurements: Dict) -> List[str]:
    sse = measurements.get("workloads", {}).get("sse_authorized", {})
    drivers = sse.get("status_drivers", {})
    blockers = []
    if drivers.get("configured", 0) < 1:
        blockers.append("no SSE status driver was configured")
    if drivers.get("transitions_ok", 0) < 1:
        blockers.append("no authorized status transition was measured")
    if sse.get("events_received", 0) < 1:
        blockers.append("no authorized SSE status event was observed")
    if sse.get("disconnects", 0) < 1 or sse.get("recoveries", 0) < 1:
        blockers.append("SSE disconnect and recovery evidence is incomplete")
    return blockers


def write_report(report: Dict, out_dir: Path, secrets: List[str]) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, indent=2, sort_keys=False, default=str)
    leaks = find_secret_leaks(text, secrets)
    if leaks:
        report["secret_scan"] = {"status": "FAIL", "findings": len(leaks)}
        text = scrub(json.dumps(report, indent=2, default=str), secrets)
    json_path = out_dir / ("capacity-report-%s.json" % report["mode"])
    json_path.write_text(text, encoding="utf-8")
    (out_dir / ("capacity-report-%s.md" % report["mode"])).write_text(scrub(render_markdown(report), secrets), encoding="utf-8")
    return json_path


def _fmt(v) -> str:
    return "—" if v is None else str(v)


def render_markdown(r: Dict) -> str:
    m = r.get("measurements", {})
    lines = ["# SwiftDrop capacity report — %s" % r["mode"], "",
             "- Overall verdict: **%s**" % r["evaluation"]["overall"],
             "- Environment: `%s` (evidence: %s)" % (r["environment"]["environment_id"], r["environment"]["evidence_class"]),
             "- Harness SHA: `%s` (dirty: %s); target revision: `%s`" % (
                 r["provenance"]["harness_sha"], r["provenance"]["harness_worktree_dirty"], r["environment"]["target_revision"]),
             "- Budget set: `%s` digest `%s` (approval: %s)" % (r["budgets"]["revision"], r["budgets"]["digest"][:16], r["budgets"]["approval_status"]),
             "- Generated: %s" % r["generated_at"], ""]
    if r["evaluation"]["reasons"]:
        lines += ["## Verdict reasons", ""] + ["- %s" % x for x in r["evaluation"]["reasons"][:40]] + [""]
    lines += ["## Workloads", "", "| Workload | Requests | OK | Failed | 429 | Timeouts | Error rate | p50 ms | p95 ms | p99 ms | Max ms |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, w in m.get("workloads", {}).items():
        if "latency" in w:
            L = w["latency"]
            lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                name, w["total_requests"], w["successful_requests"], w["failed_requests"], w["rate_limited"], w["timeouts"],
                _fmt(w["error_rate"]), _fmt(L["p50_ms"]), _fmt(L["p95_ms"]), _fmt(L["p99_ms"]), _fmt(L["max_ms"])))
    sse = m.get("workloads", {}).get("sse_authorized")
    if sse:
        lines += ["", "## Authorized SSE", ""] + ["- %s: %s" % (k, _fmt(v)) for k, v in sse.items()]
    lines += ["", "## Budgets and invariants", "", "| Id | Status | Comparator | Threshold | Measured | Verdict |", "|---|---|---|---|---|---|"]
    for v in r["evaluation"]["verdicts"]:
        lines.append("| %s | %s | %s | %s | %s | **%s** |" % (v["budget_id"], v["status"], v["comparator"], _fmt(v["threshold"]),
                                                             _fmt(v["measured"]), v["verdict"]))
    lines += ["", "## Evidence classes", ""] + ["- `%s`: %s" % (k, v) for k, v in m.get("evidence", {}).items()]
    probes = m.get("security", {}).get("probes", [])
    if probes:
        lines += ["", "## Security probes", "", "| Probe | Expected | Observed | Result |", "|---|---|---|---|"]
        lines += ["| %s | %s | %s | %s |" % (p["id"], p.get("expected", ""), p.get("observed_status", p.get("reason", "")), p["result"]) for p in probes]
    lines += ["", "## Reproduction", ""] + ["```", *r["reproduction"], "```", ""]
    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--mode", choices=("plan", "self-check", "measure", "acceptance"), default="self-check")
    p.add_argument("--budgets", type=Path, default=budget_mod.DEFAULT_BUDGET_FILE)
    p.add_argument("--parcels", type=int, default=120)
    p.add_argument("--environment-id")
    p.add_argument("--api-base-url", help="Gateway base URL serving REST, SSE and /graphql")
    p.add_argument("--graphql-url", help="defaults to <api-base-url>/graphql")
    p.add_argument("--tracking-base-url", help="defaults to --api-base-url (gateway rate limits apply)")
    p.add_argument("--graphql-metrics-url")
    p.add_argument("--delivery-metrics-url")
    p.add_argument("--binding", type=Path)
    p.add_argument("--allow-host", action="append", default=[])
    p.add_argument("--confirm-non-production", action="store_true")
    p.add_argument("--target-revision", default=None, help="deployed image/commit SHA reported by the operator")
    p.add_argument("--resource-file", type=Path, help="operator-supplied JSON of runtime resource allocations/usage")
    p.add_argument("--clients", type=int, default=10)
    p.add_argument("--iterations", type=int, default=20)
    p.add_argument("--sse-connections", type=int, default=20)
    p.add_argument("--sse-duration", type=float, default=60.0)
    p.add_argument("--sse-disconnect-every", type=int, default=4)
    p.add_argument("--sse-disconnect-at", type=float, default=10.0)
    p.add_argument("--rate-limit-burst", type=int, default=20)
    p.add_argument("--grpc-samples", type=int, default=5)
    p.add_argument("--backlog-interval", type=float, default=5.0)
    p.add_argument("--timeout", type=float, default=10.0)
    p.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    budget_set = budget_mod.load_budgets(args.budgets)
    dataset = build_dataset(args.parcels)
    if args.sse_connections < 20:
        print("BLOCKED: issue #165 requires at least 20 simultaneous authorized SSE connections", file=sys.stderr)
        return EXIT_BLOCKED

    if args.mode == "plan":
        args.report_dir.mkdir(parents=True, exist_ok=True)
        write_manifest(dataset, args.report_dir / "fixture-manifest.json")
        template = {"environment_id": "<non-production id>", "fixture_digest": dataset.fixture_digest,
                    "unknown_tracking_number": "SWD-20261106-ZZZZZZ",
                    "actors": {a: {"token_file": "<path outside repo>"} for a in sorted(dataset.actors)},
                    "negative_tokens": {"expired": "<path>", "invalid_signature": "<path>"},
                    "parcels": {p.alias: {"parcel_id": "<server id>", "tracking_number": "<server tracking number>",
                                          "seed_status": p.status, "owner": p.owner_alias, "pickup_zone": p.pickup_zone}
                                for p in dataset.parcels},
                    "status_drivers": [{"parcel": "<alias in PICKED_UP/IN_TRANSIT>", "actor": "<assigned courier alias>"}]}
        (args.report_dir / "binding.template.json").write_text(json.dumps(template, indent=2), encoding="utf-8")
        print("Fixture digest %s; %s" % (dataset.fixture_digest, json.dumps(dataset.counts())))
        print("Wrote %s" % args.report_dir)
        return EXIT_OK

    live = args.mode in ("measure", "acceptance")
    if args.mode == "acceptance":
        blockers = budget_set.acceptance_blockers()
        blockers.extend(acceptance_input_blockers(args))
        if blockers:
            print("BLOCKED - acceptance run refused before sending load:", file=sys.stderr)
            for b in blockers:
                print("  - " + b, file=sys.stderr)
            return EXIT_BLOCKED

    resources = {"status": "UNAVAILABLE", "reason": "no --resource-file supplied by operator"}
    if args.resource_file:
        try:
            resource_data = json.loads(args.resource_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print("BLOCKED - resource snapshot is unreadable (%s)" % type(exc).__name__, file=sys.stderr)
            return EXIT_BLOCKED
        if not isinstance(resource_data, dict) or not resource_data:
            print("BLOCKED - resource snapshot must be a non-empty JSON object", file=sys.stderr)
            return EXIT_BLOCKED
        resources = {"status": "OPERATOR_SUPPLIED", "data": resource_data}

    if live:
        try:
            if not args.environment_id or not args.api_base_url or not args.binding:
                raise UnsafeTargetError("--environment-id, --api-base-url and --binding are required for live modes")
            assert_safe_environment_id(args.environment_id)
            urls = [args.api_base_url, args.graphql_url, args.tracking_base_url, args.graphql_metrics_url, args.delivery_metrics_url]
            for url in filter(None, urls):
                assert_safe_target(url, args.allow_host, args.confirm_non_production)
            binding = load_binding(args.binding, dataset)
            if binding.environment_id != args.environment_id:
                raise BindingError("binding environment %r does not match --environment-id" % binding.environment_id)
            if args.mode == "acceptance" and not binding.status_drivers:
                raise BindingError("acceptance requires at least one assigned-courier status driver")
            health = transport.request("GET", args.api_base_url.rstrip("/") + "/actuator/health", timeout=args.timeout)
            if health.status == 0:
                raise UnsafeTargetError("environment unreachable at %s (%s)" % (args.api_base_url, health.error))
        except (UnsafeTargetError, BindingError) as exc:
            print("BLOCKED - %s" % exc, file=sys.stderr)
            return EXIT_BLOCKED
        directory = LiveDirectory(binding)
        base = args.api_base_url.rstrip("/")
        targets = Targets(args.graphql_url or base + "/graphql", base, (args.tracking_base_url or base).rstrip("/"),
                          args.graphql_metrics_url, args.delivery_metrics_url, args.timeout)
        measurements = execute(CapacityRunner(dataset, directory, targets, SecurityLedger()), args, live=True)
        env = {"environment_id": args.environment_id, "evidence_class": "MEASURED_LIVE", "target_revision": args.target_revision}
    else:
        from tests.capacity.stub_server import StubSwiftDrop
        with StubSwiftDrop(dataset) as stub:
            directory = stub.directory(args.sse_connections)
            base = stub.base_url
            targets = Targets(base + "/graphql", base, base, base + "/metrics/graphql", base + "/metrics/delivery", args.timeout)
            measurements = execute(CapacityRunner(dataset, directory, targets, SecurityLedger()), args, live=False)
        env = {"environment_id": "in-process-stub", "evidence_class": "SIMULATED",
               "target_revision": None, "note": "Harness self-validation only; not capacity evidence."}

    evaluation = budget_mod.evaluate(budget_set, measurements, evidence_is_live=live, acceptance_mode=args.mode == "acceptance")
    if args.mode == "acceptance":
        missing_evidence = acceptance_measurement_blockers(measurements)
        if missing_evidence:
            if evaluation.overall != budget_mod.OVERALL_FAIL:
                evaluation.overall = budget_mod.OVERALL_BLOCKED_EVIDENCE
            evaluation.reasons.extend(missing_evidence)
    report = {
        "report_schema": 1, "issue": "#165", "mode": args.mode,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "provenance": git_provenance(), "environment": env, "load_generator": load_generator_info(),
        "runtime_resources": resources,
        "budgets": {"file": str(args.budgets.relative_to(REPO_ROOT)) if REPO_ROOT in args.budgets.resolve().parents else str(args.budgets),
                    "revision": budget_set.raw["revision"], "digest": budget_set.digest(),
                    "approval_status": budget_set.approval.get("status"), "pending": budget_set.pending_ids()},
        "dataset": {"seed": dataset.seed, "fixture_digest": dataset.fixture_digest, **dataset.counts()},
        "measurements": measurements, "evaluation": evaluation.as_dict(),
        "reproduction": reproduction(args),
    }
    path = write_report(report, args.report_dir, directory.secrets())
    print("Overall verdict: %s" % evaluation.overall)
    for reason in evaluation.reasons[:20]:
        print("  - " + reason)
    print("Report: %s" % path)
    if report.get("secret_scan", {}).get("status") == "FAIL":
        return EXIT_FAIL
    if evaluation.overall == budget_mod.OVERALL_FAIL:
        return EXIT_FAIL
    if evaluation.overall in (budget_mod.OVERALL_PASS, budget_mod.OVERALL_NOT_ACCEPTANCE):
        return EXIT_OK
    return EXIT_BLOCKED


if __name__ == "__main__":
    sys.exit(main())
