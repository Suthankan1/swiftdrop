"""
Capacity workloads for dashboards, public tracking, authorized SSE and security probes (Issue #165).

Every number produced here comes from a request actually sent by this process. Workloads
that cannot run (missing binding entries, metrics endpoints or drivers) report None /
NOT_EXECUTED instead of a default value.
"""

from __future__ import annotations

import json
import math
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Protocol, Sequence, Tuple

from . import transport
from .fixtures import BoundParcel, Dataset, Parcel, plan_sse_subscribers
from .metrics import WorkloadStats, latency_summary, linear_slope_per_min, metric_sum, parse_prometheus_text
from .safety import public_sse_event_violations, public_tracking_violations

CUSTOMER_QUERY = (
    "query CapacityCustomer { customerDashboard { userId activeDeliveriesCount completedDeliveriesCount "
    "recentParcels { parcelId trackingNumber currentStatus amountMinorUnits currency recipientCity createdAt updatedAt } } }")
MERCHANT_QUERY = (
    "query CapacityMerchant { merchantDashboard { merchantId businessName status totalOrdersCount activeOrdersCount "
    "deliveredOrdersCount failedOrdersCount recentOrders { parcelId trackingNumber currentStatus weightKg "
    "feeMinorUnits currency createdAt lastStatusUpdate } } }")
DISPATCHER_QUERY = (
    "query CapacityDispatcher($page: Int, $size: Int) { dispatcherDashboard(pagination: {page: $page, size: $size}) { "
    "unassignedParcels { parcelId trackingNumber pickupZone dropoffZone weightKg sizeCategory createdAt version } "
    "pageInfo { currentPage pageSize totalElements totalPages hasNext } "
    "availableCouriers { courierSub fullName activeAssignmentsCount maxCapacity eligible } } }")
ROOT_QUERIES = {"customerDashboard": CUSTOMER_QUERY, "merchantDashboard": MERCHANT_QUERY,
                "dispatcherDashboard": DISPATCHER_QUERY}
FORWARD_TRANSITIONS = {"CREATED": None, "PICKED_UP": "IN_TRANSIT", "IN_TRANSIT": "OUT_FOR_DELIVERY"}
GRPC_CLIENT_COUNTER = "swiftdrop_grpc_client_requests_total"


class Directory(Protocol):
    """Resolves fixture aliases to environment ids and tokens (live binding or self-check stub)."""
    environment_id: str
    unknown_tracking_number: str
    status_drivers: List[Dict[str, str]]

    def token(self, actor_alias: str) -> str: ...
    def negative_token(self, kind: str) -> Optional[str]: ...
    def parcel(self, alias: str) -> BoundParcel: ...
    def secrets(self) -> List[str]: ...


@dataclass
class Targets:
    graphql_url: str
    api_base_url: str
    tracking_base_url: str
    graphql_metrics_url: Optional[str] = None
    delivery_metrics_url: Optional[str] = None
    timeout_s: float = 10.0


@dataclass
class SecurityLedger:
    isolation_violations: int = 0
    sse_leak_violations: int = 0
    pii_violations: int = 0
    negative_probe_failures: int = 0
    findings: List[str] = field(default_factory=list)
    probes: List[Dict] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def add(self, counter: str, finding: str) -> None:
        with self._lock:
            setattr(self, counter, getattr(self, counter) + 1)
            if len(self.findings) < 200:
                self.findings.append(finding)

    def as_dict(self) -> Dict:
        return {"isolation_violations": self.isolation_violations,
                "sse_leak_violations": self.sse_leak_violations,
                "pii_violations": self.pii_violations,
                "negative_probe_failures": self.negative_probe_failures,
                "findings": list(self.findings), "probes": list(self.probes)}


def _graphql_outcome(result: transport.HttpResult) -> Tuple[bool, str, Optional[dict]]:
    """Classify a GraphQL HTTP result into (ok, failure_kind, data)."""
    if result.status == 0:
        return False, result.error.split(":")[0] or "transport", None
    if result.status == 429:
        return False, "rate_limited", None
    if result.status != 200:
        return False, "http_%d" % result.status, None
    try:
        body = result.json()
    except ValueError:
        return False, "invalid_json", None
    if body.get("errors"):
        code = ((body["errors"][0].get("extensions") or {}).get("classification")
                or (body["errors"][0].get("extensions") or {}).get("code") or "error")
        return False, "graphql_%s" % str(code).lower(), None
    return True, "", body.get("data")


class CapacityRunner:
    def __init__(self, dataset: Dataset, directory: Directory, targets: Targets, ledger: Optional[SecurityLedger] = None):
        self.dataset = dataset
        self.directory = directory
        self.targets = targets
        self.ledger = ledger or SecurityLedger()

    # ---- helpers ------------------------------------------------------------------------
    def _allowed_tracking(self, actor_alias: str) -> set:
        return {self.directory.parcel(p.alias).tracking_number for p in self.dataset.visible_to(actor_alias)}

    def _fixture_tracking(self) -> Dict[str, Parcel]:
        return {self.directory.parcel(p.alias).tracking_number: p for p in self.dataset.parcels}

    def _check_isolation(self, root: str, actor_alias: str, data: dict, page: int = 0, size: int = 20) -> None:
        actor = self.dataset.actors[actor_alias]
        allowed = self._allowed_tracking(actor_alias)
        dashboard = (data or {}).get(root) or {}
        if root == "customerDashboard":
            items = dashboard.get("recentParcels") or []
        elif root == "merchantDashboard":
            items = dashboard.get("recentOrders") or []
        else:
            items = dashboard.get("unassignedParcels") or []
        fixture = self._fixture_tracking()
        for item in items:
            tn = item.get("trackingNumber")
            if root == "dispatcherDashboard":
                # Zone scope is the invariant; live statuses may legitimately move during the run.
                zones = {item.get("pickupZone"), item.get("dropoffZone")}
                if actor.zone and actor.zone not in zones:
                    self.ledger.add("isolation_violations", "%s saw out-of-zone parcel %s" % (actor_alias, tn))
            elif tn not in allowed:
                owner = fixture[tn].owner_alias if tn in fixture else "non-fixture"
                self.ledger.add("isolation_violations", "%s (%s) saw parcel owned by %s" % (actor_alias, root, owner))
        if root == "dispatcherDashboard":
            info = dashboard.get("pageInfo") or {}
            total = info.get("totalElements", 0)
            if (len(items) > size or info.get("currentPage") != page or info.get("pageSize") != size
                    or info.get("totalPages") != (math.ceil(total / size) if total else 0)):
                self.ledger.add("isolation_violations", "%s pagination contract inconsistent at page %d" % (actor_alias, page))

    # ---- dashboards ---------------------------------------------------------------------
    def run_dashboard(self, root: str, actors: Sequence[str], clients: int, iterations: int,
                      page_sizes: Sequence[int] = (20,)) -> Dict:
        stats = WorkloadStats(root)
        query = ROOT_QUERIES[root]
        tokens = {a: self.directory.token(a) for a in actors}

        def client(index: int) -> None:
            actor = actors[index % len(actors)]
            for it in range(iterations):
                variables = None
                page, size = 0, 20
                if root == "dispatcherDashboard":
                    size = page_sizes[it % len(page_sizes)]
                    pages = max(1, self.dataset.expected_pages(actor, size))
                    page = (index + it) % (pages + 1)          # includes one page past the end
                    variables = {"page": page, "size": size}
                result = transport.graphql(self.targets.graphql_url, query, tokens[actor], self.targets.timeout_s, variables)
                ok, kind, data = _graphql_outcome(result)
                if ok:
                    self._check_isolation(root, actor, data, page, size)
                stats.record(ok, result.elapsed_ms, kind, len(result.body))

        _run_parallel(clients, client)
        out = stats.as_dict()
        out.update({"clients": clients, "iterations_per_client": iterations, "actors": list(actors)})
        return out

    # ---- public tracking ----------------------------------------------------------------
    def run_public_tracking(self, clients: int, iterations: int) -> Dict:
        stats = WorkloadStats("tracking_public")
        parcels = self.dataset.parcels
        private = [self.dataset.actors[a].subject for a in self.dataset.actors]

        def client(index: int) -> None:
            for it in range(iterations):
                fixture = parcels[(index * iterations + it) % len(parcels)]
                bound = self.directory.parcel(fixture.alias)
                result = transport.request("GET", "%s/api/v1/tracking/%s" % (self.targets.tracking_base_url, bound.tracking_number),
                                           {"Accept": "application/json"}, timeout=self.targets.timeout_s)
                if result.status == 200:
                    try:
                        payload = result.json()
                    except ValueError:
                        stats.record(False, result.elapsed_ms, "invalid_json", len(result.body))
                        continue
                    violations = public_tracking_violations(payload, private + [bound.parcel_id])
                    for v in violations:
                        self.ledger.add("pii_violations", "public tracking %s: %s" % (fixture.alias, v))
                    stats.record(not violations, result.elapsed_ms, "pii" if violations else "", len(result.body))
                elif result.status == 429:
                    stats.record(False, result.elapsed_ms, "rate_limited", len(result.body))
                else:
                    stats.record(False, result.elapsed_ms, result.error or "http_%d" % result.status, len(result.body))

        _run_parallel(clients, client)
        out = stats.as_dict()
        out.update({"clients": clients, "iterations_per_client": iterations})
        return out

    def run_rate_limit_probe(self, burst: int) -> Dict:
        """Sequential burst from one client; documents edge abuse-control behaviour (not an error budget)."""
        bound = self.directory.parcel(self.dataset.parcels[0].alias)
        statuses: List[int] = []
        retry_after_present = 0
        for _ in range(burst):
            result = transport.request("GET", "%s/api/v1/tracking/%s" % (self.targets.tracking_base_url, bound.tracking_number),
                                       timeout=self.targets.timeout_s)
            statuses.append(result.status)
            if result.status == 429 and "retry-after" in result.headers:
                retry_after_present += 1
        limited = [i for i, s in enumerate(statuses) if s == 429]
        return {"burst_requests": burst, "ok": statuses.count(200), "rate_limited": len(limited),
                "first_rate_limited_index": limited[0] if limited else None,
                "retry_after_header_on_429": retry_after_present,
                "other_statuses": sorted({s for s in statuses if s not in (200, 429)})}

    # ---- security probes ----------------------------------------------------------------
    def run_security_probes(self) -> List[Dict]:
        ds, d, t = self.dataset, self.directory, self.targets
        customers = [a.alias for a in ds.by_role("CUSTOMER") if ds.visible_to(a.alias)]
        merchants = ds.merchant_tenants()
        probes: List[Tuple[str, str, Callable[[], transport.HttpResult], Callable[[transport.HttpResult], bool]]] = []

        def gql(root: str, token: Optional[str], variables: Optional[dict] = None, query: Optional[str] = None):
            return lambda: transport.graphql(t.graphql_url, query or ROOT_QUERIES[root], token, t.timeout_s, variables)

        unauthenticated = lambda r: r.status == 401
        def denied(r: transport.HttpResult) -> bool:
            if r.status in (401, 403):
                return True
            if r.status != 200:
                return False
            body = json.loads(r.body.decode() or "{}")
            data = body.get("data")
            return bool(body.get("errors")) and (data is None or all(v is None for v in data.values()))
        denied_or_missing = lambda r: r.status in (401, 403, 404)

        probes.append(("graphql.no_token", "HTTP 401", gql("customerDashboard", None), unauthenticated))
        probes.append(("graphql.malformed_token", "HTTP 401", gql("customerDashboard", "capacity-malformed-token"), unauthenticated))
        for kind in ("expired", "invalid_signature"):
            token = d.negative_token(kind)
            if token:
                probes.append(("graphql.%s_token" % kind, "HTTP 401", gql("customerDashboard", token), unauthenticated))
            else:
                self.ledger.probes.append({"id": "graphql.%s_token" % kind, "result": "NOT_EXECUTED",
                                           "reason": "binding has no %s token file" % kind})
        probes.append(("graphql.customer_on_merchant_dashboard", "denied", gql("merchantDashboard", d.token(customers[0])), denied))
        probes.append(("graphql.customer_on_dispatcher_dashboard", "denied", gql("dispatcherDashboard", d.token(customers[0])), denied))
        probes.append(("graphql.merchant_on_customer_dashboard", "denied", gql("customerDashboard", d.token(merchants[0])), denied))
        probes.append(("graphql.merchant_on_dispatcher_dashboard", "denied", gql("dispatcherDashboard", d.token(merchants[0])), denied))
        if "merchant-pending" in ds.actors:
            probes.append(("graphql.pending_merchant_dashboard", "denied", gql("merchantDashboard", d.token("merchant-pending")), denied))
        dispatcher = ds.by_role("DISPATCHER")[0].alias
        for label, variables in (("size_51", {"page": 0, "size": 51}), ("size_0", {"page": 0, "size": 0}),
                                 ("page_negative", {"page": -1, "size": 20})):
            probes.append(("graphql.dispatcher_pagination_%s" % label, "rejected", gql("dispatcherDashboard", d.token(dispatcher), variables), denied))
        aliases = " ".join("a%d: activeDeliveriesCount" % i for i in range(101))
        probes.append(("graphql.complexity_alias_attack_101", "rejected",
                       gql("customerDashboard", d.token(customers[0]), query="query Attack { customerDashboard { %s } }" % aliases), denied))

        other = next(p for p in ds.parcels if p.owner_alias != customers[0] and p.owner_kind == "customer")
        other_bound = d.parcel(other.alias)
        sse_url = "%s/api/v1/parcels/%s/events" % (t.api_base_url, other_bound.parcel_id)
        probes.append(("sse.no_token", "HTTP 401", lambda: _sse_status(sse_url, None, t.timeout_s), unauthenticated))
        probes.append(("sse.cross_customer_stream", "HTTP 403/404", lambda: _sse_status(sse_url, d.token(customers[0]), t.timeout_s), denied_or_missing))
        merchant_other = next(p for p in ds.parcels if p.merchant_alias and p.merchant_alias != merchants[0])
        m_url = "%s/api/v1/parcels/%s/events" % (t.api_base_url, d.parcel(merchant_other.alias).parcel_id)
        probes.append(("sse.cross_tenant_merchant_stream", "HTTP 403/404", lambda: _sse_status(m_url, d.token(merchants[0]), t.timeout_s), denied_or_missing))
        probes.append(("rest.cross_customer_parcel_read", "HTTP 403/404",
                       lambda: transport.request("GET", "%s/api/v1/parcels/%s" % (t.api_base_url, other_bound.parcel_id),
                                                 {"Authorization": "Bearer " + d.token(customers[0])}, timeout=t.timeout_s),
                       denied_or_missing))
        probes.append(("tracking.invalid_format", "HTTP 400",
                       lambda: transport.request("GET", "%s/api/v1/tracking/not-a-reference" % t.tracking_base_url, timeout=t.timeout_s),
                       lambda r: r.status == 400))
        probes.append(("tracking.unknown_reference", "HTTP 404",
                       lambda: transport.request("GET", "%s/api/v1/tracking/%s" % (t.tracking_base_url, d.unknown_tracking_number), timeout=t.timeout_s),
                       lambda r: r.status == 404))

        for probe_id, expectation, call, predicate in probes:
            result = call()
            try:
                passed = predicate(result)
            except ValueError:
                passed = False
            record = {"id": probe_id, "expected": expectation, "observed_status": result.status,
                      "result": "PASS" if passed else "FAIL"}
            self.ledger.probes.append(record)
            if not passed:
                self.ledger.add("negative_probe_failures", "%s expected %s observed HTTP %s" % (probe_id, expectation, result.status))
        return self.ledger.probes

    def max_accepted_page_size(self) -> Optional[int]:
        dispatcher = self.dataset.by_role("DISPATCHER")[0].alias
        accepted = None
        for size in (50, 51):
            r = transport.graphql(self.targets.graphql_url, DISPATCHER_QUERY, self.directory.token(dispatcher),
                                  self.targets.timeout_s, {"page": 0, "size": size})
            ok, _, _ = _graphql_outcome(r)
            if ok:
                accepted = size
            elif r.status == 0:
                return None
        return accepted

    # ---- gRPC fan-out (live, requires metrics endpoint) ---------------------------------
    def measure_grpc_calls_per_query(self, samples: int = 5) -> Dict:
        if not self.targets.graphql_metrics_url:
            return {"status": "UNAVAILABLE", "reason": "no --graphql-metrics-url; GraphQL service exposes health only"}

        def counter() -> Optional[float]:
            r = transport.request("GET", self.targets.graphql_metrics_url, timeout=self.targets.timeout_s)
            return metric_sum(parse_prometheus_text(r.body.decode("utf-8", "replace")), GRPC_CLIENT_COUNTER) if r.status == 200 else None

        plans = [("customerDashboard", self.dataset.by_role("CUSTOMER")[0].alias, None),
                 ("merchantDashboard", self.dataset.merchant_tenants()[0], None),
                 ("dispatcherDashboard", self.dataset.by_role("DISPATCHER")[0].alias, {"page": 0, "size": 1}),
                 ("dispatcherDashboard@50", self.dataset.by_role("DISPATCHER")[0].alias, {"page": 0, "size": 50})]
        out: Dict = {"status": "MEASURED", "calls_per_query": {}, "samples_per_query": samples}
        for label, actor, variables in plans:
            root = label.split("@")[0]
            before = counter()
            for _ in range(samples):
                transport.graphql(self.targets.graphql_url, ROOT_QUERIES[root], self.directory.token(actor), self.targets.timeout_s, variables)
            after = counter()
            if before is None or after is None:
                return {"status": "UNAVAILABLE", "reason": "metrics endpoint did not expose %s" % GRPC_CLIENT_COUNTER}
            out["calls_per_query"][label] = round((after - before) / samples, 3)
        small = out["calls_per_query"]["dispatcherDashboard"]
        large = out["calls_per_query"].pop("dispatcherDashboard@50")
        out["dispatcher_calls_page_size_1"] = small
        out["dispatcher_calls_page_size_50"] = large
        out["n_plus_one_detected"] = int(large > small)
        out["note"] = "Counter deltas assume no concurrent traffic; this phase runs before concurrent load."
        return out

    # ---- SSE ----------------------------------------------------------------------------
    def run_sse(self, connections: int, duration_s: float, disconnect_every: int = 4, disconnect_at_s: float = 5.0,
                reconnect_attempts: int = 3, settle_s: float = 2.0, driver_interval_s: float = 2.0,
                public_streams: int = 3) -> Dict:
        plan = plan_sse_subscribers(self.dataset, connections)
        t = self.targets
        lock = threading.Lock()
        state = {"active": 0, "peak": 0}
        public = {"planned": 0, "connected": 0, "events": 0, "errors": []}
        private_values = [a.subject for a in self.dataset.actors.values()]
        published: Dict[Tuple[str, int], float] = {}
        recovery_ms: List[float] = []
        connect_ms: List[float] = []
        delivery_ms: List[float] = []
        results: List[Dict] = []
        start = time.monotonic()
        deadline = start + duration_s

        def adjust(delta: int) -> None:
            with lock:
                state["active"] += delta
                state["peak"] = max(state["peak"], state["active"])

        def rest_state(parcel_id: str, token: str) -> Optional[Tuple[str, int]]:
            r = transport.request("GET", "%s/api/v1/parcels/%s" % (t.api_base_url, parcel_id),
                                  {"Authorization": "Bearer " + token, "Accept": "application/json"}, timeout=t.timeout_s)
            if r.status != 200:
                return None
            body = r.json()
            return body.get("currentStatus"), int(body.get("version", 0))

        def subscriber(index: int) -> None:
            actor, fixture = plan[index]
            bound = self.directory.parcel(fixture.alias)
            token = self.directory.token(actor)
            url = "%s/api/v1/parcels/%s/events" % (t.api_base_url, bound.parcel_id)
            rec = {"actor": actor, "parcel": fixture.alias, "connected": False, "events": 0, "heartbeats": 0,
                   "duplicates": 0, "out_of_order": 0, "superseded": 0, "version_gaps": 0, "disconnects": 0,
                   "recovered": 0, "recovery_failures": 0, "errors": [], "max_gap_s": 0.0}
            seen_ids: set = set()
            known_version = last_streamed_version = 0
            planned_disconnect = disconnect_every > 0 and index % disconnect_every == 0
            # Subscribe first, then read canonical REST state: a transition between the two is then
            # either in the REST snapshot or buffered on the stream (applied idempotently by version).
            stream = transport.SseStream(url, token, t.timeout_s)
            if not stream.open():
                rec["errors"].append(stream.error or "http_%d" % stream.status)
            else:
                known = rest_state(bound.parcel_id, token)
                known_version = known[1] if known else 0
            while stream.status == 200:
                rec["connected"] = True
                with lock:
                    connect_ms.append(stream.connect_ms)
                adjust(+1)
                last_frame = time.monotonic()
                cut_at = start + disconnect_at_s if planned_disconnect and rec["disconnects"] == 0 else deadline
                for frame in stream.frames(min(cut_at, deadline)):
                    now = time.monotonic()
                    rec["max_gap_s"] = max(rec["max_gap_s"], now - last_frame)
                    last_frame = now
                    if frame.comment:
                        rec["heartbeats"] += 1
                        continue
                    if frame.event != "parcel.status.changed":
                        continue
                    try:
                        event = json.loads(frame.data)
                    except ValueError:
                        rec["errors"].append("invalid_event_json")
                        continue
                    if event.get("parcelId") != bound.parcel_id or event.get("trackingNumber") != bound.tracking_number:
                        self.ledger.add("sse_leak_violations", "%s stream for %s received event for another parcel" % (actor, fixture.alias))
                        continue
                    if frame.id in seen_ids:
                        rec["duplicates"] += 1
                        continue
                    seen_ids.add(frame.id)
                    rec["events"] += 1
                    version = int(event.get("aggregateVersion", 0))
                    if version < last_streamed_version:
                        rec["out_of_order"] += 1          # stream regressed below an event it already sent
                    elif version <= known_version:
                        rec["superseded"] += 1            # already covered by REST catch-up; benign
                    elif version > known_version + 1 and known_version:
                        rec["version_gaps"] += 1
                    last_streamed_version = max(last_streamed_version, version)
                    known_version = max(known_version, version)
                    with lock:
                        sent = published.get((bound.parcel_id, version))
                        if sent is not None:
                            delivery_ms.append((now - sent) * 1000.0)
                stream.close()
                adjust(-1)
                if time.monotonic() >= deadline:
                    break
                if stream.error:                       # unplanned drop (EOF/reset/read timeout)
                    rec["errors"].append(stream.error)
                rec["disconnects"] += 1
                recovery_start = time.monotonic()
                stream = transport.SseStream(url, token, t.timeout_s)
                for attempt in range(reconnect_attempts):
                    stream = transport.SseStream(url, token, t.timeout_s)
                    if stream.open():
                        # DEC-SSE-03: authoritative REST catch-up before consuming fresh events.
                        canonical = rest_state(bound.parcel_id, token)
                        if canonical is not None:
                            known_version = max(known_version, canonical[1])
                            break
                        stream.close()
                        stream.status = 0
                    time.sleep(min(0.25 * (2 ** attempt), 2.0))
                if stream.status != 200:
                    rec["recovery_failures"] += 1
                    break
                rec["recovered"] += 1
                with lock:
                    recovery_ms.append((time.monotonic() - recovery_start) * 1000.0)
            rec["final_known_version"] = known_version
            with lock:
                results.append(rec)

        driver_stats = {"configured": len(self.directory.status_drivers), "transitions_ok": 0, "transitions_failed": 0}

        def driver() -> None:
            while time.monotonic() < deadline - settle_s - 1:
                for entry in self.directory.status_drivers:
                    bound = self.directory.parcel(entry["parcel"])
                    token = self.directory.token(entry["actor"])
                    current = rest_state(bound.parcel_id, token)
                    nxt = FORWARD_TRANSITIONS.get(current[0]) if current else None
                    if not nxt:
                        continue
                    body = json.dumps({"newStatus": nxt, "expectedVersion": current[1],
                                       "note": "capacity-run synthetic transition"}).encode()
                    key = (bound.parcel_id, current[1] + 1)
                    with lock:
                        published[key] = time.monotonic()      # before sending: the event may beat the response
                    r = transport.request("PATCH", "%s/api/v1/parcels/%s/status" % (t.api_base_url, bound.parcel_id),
                                          {"Authorization": "Bearer " + token, "Content-Type": "application/json"},
                                          body, t.timeout_s)
                    with lock:
                        if r.status in (200, 201):
                            driver_stats["transitions_ok"] += 1
                        else:
                            driver_stats["transitions_failed"] += 1
                            published.pop(key, None)
                time.sleep(driver_interval_s)

        def public_subscriber(bound: BoundParcel) -> None:
            """Anonymous redacted stream (IF-SSE-01); every event must pass the public allowlist."""
            stream = transport.SseStream("%s/api/v1/tracking/%s/events" % (t.tracking_base_url, bound.tracking_number),
                                         None, t.timeout_s)
            if not stream.open():
                with lock:
                    public["errors"].append(stream.error or "http_%d" % stream.status)
                return
            with lock:
                public["connected"] += 1
            for frame in stream.frames(deadline):
                if frame.comment or frame.event != "parcel.status.changed":
                    continue
                try:
                    event = json.loads(frame.data)
                except ValueError:
                    continue
                with lock:
                    public["events"] += 1
                if event.get("trackingNumber") != bound.tracking_number:
                    self.ledger.add("sse_leak_violations", "public stream %s received another parcel's event" % bound.alias)
                for v in public_sse_event_violations(event, private_values + [bound.parcel_id]):
                    self.ledger.add("pii_violations", "public SSE %s: %s" % (bound.alias, v))
            stream.close()

        driven = [self.directory.parcel(d["parcel"]) for d in self.directory.status_drivers]
        public_targets = (driven or [self.directory.parcel(p.alias) for _, p in plan])[:public_streams]
        public["planned"] = len(public_targets)

        threads = [threading.Thread(target=subscriber, args=(i,), daemon=True) for i in range(len(plan))]
        threads += [threading.Thread(target=public_subscriber, args=(b,), daemon=True) for b in public_targets]
        if self.directory.status_drivers:
            threads.append(threading.Thread(target=driver, daemon=True))
        for th in threads:
            th.start()
        for th in threads:
            th.join(timeout=duration_s + 30)

        time.sleep(settle_s)
        mismatches = 0
        for rec in results:
            fixture = next(p for a, p in plan if p.alias == rec["parcel"] and a == rec["actor"])
            canonical = rest_state(self.directory.parcel(fixture.alias).parcel_id, self.directory.token(rec["actor"]))
            rec["rest_final_version"] = canonical[1] if canonical else None
            if canonical is None or canonical[1] != rec["final_known_version"]:
                mismatches += 1
        total = lambda key: sum(r[key] for r in results)
        return {
            "planned_connections": len(plan),
            "distinct_actors": len({a for a, _ in plan}),
            "distinct_parcels": len({p.alias for _, p in plan}),
            "connected_subscribers": sum(1 for r in results if r["connected"]),
            "peak_concurrent": state["peak"],
            "connect": latency_summary(connect_ms),
            "events_received": total("events"),
            "heartbeats_received": total("heartbeats"),
            "duplicate_events": total("duplicates"),
            "out_of_order_events": total("out_of_order"),
            "superseded_by_rest_events": total("superseded"),
            "version_gaps_while_connected": total("version_gaps"),
            "disconnects": total("disconnects"),
            "recoveries": total("recovered"),
            "recovery_failures": total("recovery_failures"),
            "recovery": latency_summary(recovery_ms),
            "event_delivery_from_command_start": latency_summary(delivery_ms),
            "reconciliation_mismatches": mismatches,
            "max_frame_gap_s": round(max((r["max_gap_s"] for r in results), default=0.0), 3) if results else None,
            "status_drivers": driver_stats,
            "public_streams": {"planned": public["planned"], "connected": public["connected"],
                               "events_received": public["events"], "errors": sorted(set(public["errors"]))},
            "connection_errors": sorted({e for r in results for e in r["errors"]}),
            "duration_s": duration_s,
        }

    # ---- backlog ------------------------------------------------------------------------
    def start_backlog_sampler(self, interval_s: float) -> "BacklogSampler":
        sampler = BacklogSampler(self.targets.delivery_metrics_url, interval_s, self.targets.timeout_s)
        sampler.start()
        return sampler


class BacklogSampler(threading.Thread):
    SERIES = ("swiftdrop_outbox_pending", "swiftdrop_outbox_oldest_age_seconds", "swiftdrop_sse_connections_active")

    def __init__(self, url: Optional[str], interval_s: float, timeout_s: float):
        super().__init__(daemon=True)
        self.url, self.interval_s, self.timeout_s = url, interval_s, timeout_s
        self.samples: Dict[str, List[Tuple[float, float]]] = {s: [] for s in self.SERIES}
        self._stop = threading.Event()
        self._t0 = time.monotonic()
        self.errors = 0

    def run(self) -> None:
        if not self.url:
            return
        while not self._stop.is_set():
            r = transport.request("GET", self.url, timeout=self.timeout_s)
            if r.status == 200:
                values = parse_prometheus_text(r.body.decode("utf-8", "replace"))
                for name in self.SERIES:
                    v = metric_sum(values, name)
                    if v is not None:
                        self.samples[name].append((time.monotonic() - self._t0, v))
            else:
                self.errors += 1
            self._stop.wait(self.interval_s)

    def stop(self) -> Dict:
        self._stop.set()
        if self.is_alive():
            self.join(timeout=self.timeout_s + 1)
        if not self.url:
            return {"status": "UNAVAILABLE", "reason": "no --delivery-metrics-url supplied"}
        out: Dict = {"status": "MEASURED" if any(self.samples.values()) else "UNAVAILABLE", "scrape_errors": self.errors}
        for name, points in self.samples.items():
            key = name.replace("swiftdrop_", "")
            out[key] = {"samples": len(points), "first": points[0][1] if points else None,
                        "last": points[-1][1] if points else None,
                        "max": max((v for _, v in points), default=None),
                        "slope_per_min": linear_slope_per_min(points)}
        return out


def _sse_status(url: str, token: Optional[str], timeout_s: float) -> transport.HttpResult:
    stream = transport.SseStream(url, token, timeout_s)
    start = time.perf_counter()
    stream.open()
    stream.close()
    return transport.HttpResult(stream.status, b"", (time.perf_counter() - start) * 1000.0, error=stream.error)


def _run_parallel(clients: int, fn: Callable[[int], None]) -> None:
    with ThreadPoolExecutor(max_workers=clients) as pool:
        for future in [pool.submit(fn, i) for i in range(clients)]:
            future.result()
