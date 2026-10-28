"""
In-process SIMULATED SwiftDrop edge used ONLY to self-validate the capacity harness (Issue #165).

It is never acceptance evidence: reports produced against it are labelled SIMULATED and the
overall verdict is NOT_ACCEPTANCE_EVIDENCE. Fault flags let tests prove the harness detects
cross-tenant leaks, PII exposure, N+1 fan-out, dropped SSE events and server errors.
"""

from __future__ import annotations

import json
import re
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List, Optional, Tuple

from .fixtures import BoundParcel, Dataset, SSE_MAX_PER_PARCEL, SSE_MAX_PER_USER, TERMINAL, plan_sse_subscribers
from .safety import PUBLIC_SSE_KEYS

TRACKING_RE = re.compile(r"^SWD-[0-9]{8}-[A-Z0-9]{4,8}$")
GRPC_CALLS = {"customerDashboard": 1, "merchantDashboard": 2, "dispatcherDashboard": 5}


class _Server(ThreadingHTTPServer):
    # The default backlog of 5 overflows under concurrent clients and adds SYN-retry stalls
    # (~500ms on Windows) that would masquerade as tail latency in self-check output.
    request_queue_size = 128


@dataclass
class Faults:
    leak_cross_tenant: bool = False
    leak_pii: bool = False
    n_plus_one: bool = False
    drop_sse_events: bool = False
    leak_sse_events: bool = False
    accept_oversized_page: bool = False
    error_every: int = 0
    latency_ms: float = 0.0
    tracking_burst: int = 0          # >0 enables a token bucket on public tracking (429 + Retry-After)
    heartbeat_s: float = 1.0


@dataclass
class _ParcelState:
    status: str
    version: int
    listeners: List["_Listener"] = field(default_factory=list)


class _Listener:
    def __init__(self) -> None:
        self.events: List[Tuple[str, dict]] = []
        self.cond = threading.Condition()

    def push(self, event_id: str, payload: dict) -> None:
        with self.cond:
            self.events.append((event_id, payload))
            self.cond.notify_all()


class StubSwiftDrop:
    def __init__(self, dataset: Dataset, faults: Optional[Faults] = None):
        self.dataset = dataset
        self.faults = faults or Faults()
        self.lock = threading.Lock()
        self.parcels = {p.parcel_id: p for p in dataset.parcels}
        self.by_tracking = {p.tracking_number: p for p in dataset.parcels}
        self.state = {p.parcel_id: _ParcelState(p.status, p.version) for p in dataset.parcels}
        self.grpc_calls = 0
        self.requests = 0
        self.sse_user: Dict[str, int] = {}
        self.sse_parcel: Dict[str, int] = {}
        self.sse_active = 0
        self.tracking_tokens = float(self.faults.tracking_burst)
        self.stopping = threading.Event()
        self.event_seq = 0
        handler = self._handler()
        self.server = _Server(("127.0.0.1", 0), handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    # ---- lifecycle ----------------------------------------------------------------------
    @property
    def base_url(self) -> str:
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    def __enter__(self) -> "StubSwiftDrop":
        self.thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self.stopping.set()
        with self.lock:
            for st in self.state.values():
                for listener in st.listeners:
                    with listener.cond:
                        listener.cond.notify_all()
        self.server.shutdown()
        self.server.server_close()

    # ---- directory for the harness ------------------------------------------------------
    def directory(self, sse_connections: int = 20) -> "StubDirectory":
        return StubDirectory(self, sse_connections)

    # ---- domain helpers -----------------------------------------------------------------
    def actor_for(self, auth: Optional[str]) -> Tuple[Optional[str], int]:
        if not auth or not auth.startswith("Bearer "):
            return None, 401
        token = auth[len("Bearer "):]
        if token.startswith("stub.") and token[5:] in self.dataset.actors:
            return token[5:], 200
        return None, 401

    def transition(self, parcel_id: str, new_status: str, expected_version: int) -> int:
        with self.lock:
            st = self.state[parcel_id]
            if st.version != expected_version:
                return 409
            old = st.status
            st.status, st.version = new_status, st.version + 1
            self.event_seq += 1
            parcel = self.parcels[parcel_id]
            payload = {"eventId": "00000000-0000-4000-8000-%012d" % self.event_seq, "parcelId": parcel_id,
                       "trackingNumber": parcel.tracking_number, "aggregateVersion": st.version,
                       "oldStatus": old, "newStatus": new_status, "occurredAt": "2026-11-06T10:00:00Z",
                       "cityOrZone": parcel.destination_city}
            targets = list(st.listeners)
            if self.faults.leak_sse_events:
                other = next((s for pid, s in self.state.items() if pid != parcel_id and s.listeners), None)
                targets += other.listeners if other else []
        if not self.faults.drop_sse_events:
            for listener in targets:
                listener.push(payload["eventId"], payload)
        return 200

    def _handler(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args) -> None:  # keep test output clean; never log headers
                pass

            def _send(self, status: int, payload, headers: Optional[Dict[str, str]] = None) -> None:
                body = json.dumps(payload).encode() if payload is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def _common(self) -> bool:
                with stub.lock:
                    stub.requests += 1
                    n = stub.requests
                if stub.faults.latency_ms:
                    time.sleep(stub.faults.latency_ms / 1000.0)
                if stub.faults.error_every and n % stub.faults.error_every == 0:
                    self._send(500, {"title": "Internal Server Error"})
                    return False
                return True

            # ---- routes ------------------------------------------------------------------
            def do_POST(self) -> None:
                if self.path != "/graphql":
                    return self._send(404, None)
                length = int(self.headers.get("Content-Length", 0))
                request = json.loads(self.rfile.read(length) or b"{}")
                if not self._common():
                    return
                actor_alias, code = stub.actor_for(self.headers.get("Authorization"))
                if actor_alias is None:
                    return self._send(code, {"error": "unauthorized"})
                self._send(200, stub.graphql(actor_alias, request.get("query", ""), request.get("variables") or {}))

            def do_GET(self) -> None:
                path = self.path.split("?")[0]
                if path == "/metrics/graphql":
                    return self._text('swiftdrop_grpc_client_requests_total{service="delivery"} %d\n' % stub.grpc_calls)
                if path == "/metrics/delivery":
                    return self._text("swiftdrop_outbox_pending 0\nswiftdrop_outbox_oldest_age_seconds 0\n"
                                      "swiftdrop_sse_connections_active %d\n" % stub.sse_active)
                m = re.fullmatch(r"/api/v1/tracking/([^/]+)/events", path)
                if m:
                    parcel = stub.by_tracking.get(m.group(1))
                    if parcel is None:
                        return self._send(404, {"title": "Not Found"})
                    return self._stream("public", parcel.parcel_id, public=True)
                m = re.fullmatch(r"/api/v1/tracking/([^/]+)", path)
                if m:
                    if not self._common():
                        return
                    return self._tracking(m.group(1))
                m = re.fullmatch(r"/api/v1/parcels/([0-9a-f-]{36})(/events)?", path)
                if not m:
                    return self._send(404, None)
                actor_alias, code = stub.actor_for(self.headers.get("Authorization"))
                if actor_alias is None:
                    return self._send(401, {"title": "Unauthorized"})
                parcel = stub.parcels.get(m.group(1))
                if parcel is None:
                    return self._send(404, None)
                if not stub.dataset.can_stream(actor_alias, parcel):
                    return self._send(403, {"title": "Forbidden"})
                if m.group(2):
                    return self._stream(actor_alias, parcel.parcel_id)
                st = stub.state[parcel.parcel_id]
                self._send(200, {"parcelId": parcel.parcel_id, "trackingNumber": parcel.tracking_number,
                                 "currentStatus": st.status, "version": st.version})

            def do_PATCH(self) -> None:
                m = re.fullmatch(r"/api/v1/parcels/([0-9a-f-]{36})/status", self.path)
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                actor_alias, _ = stub.actor_for(self.headers.get("Authorization"))
                parcel = stub.parcels.get(m.group(1)) if m else None
                if actor_alias is None or parcel is None or parcel.courier_alias != actor_alias:
                    return self._send(403, {"title": "Forbidden"})
                code = stub.transition(parcel.parcel_id, body["newStatus"], int(body["expectedVersion"]))
                self._send(code, {"parcelId": parcel.parcel_id})

            def _text(self, text: str) -> None:
                body = text.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _tracking(self, reference: str) -> None:
                if not TRACKING_RE.match(reference):
                    return self._send(400, {"title": "Invalid tracking reference"})
                if stub.faults.tracking_burst:
                    with stub.lock:
                        if stub.tracking_tokens < 1:
                            return self._send(429, {"title": "Too Many Requests"}, {"Retry-After": "1"})
                        stub.tracking_tokens -= 1
                parcel = stub.by_tracking.get(reference)
                if parcel is None:
                    return self._send(404, {"title": "Not Found"})
                st = stub.state[parcel.parcel_id]
                payload = {"trackingNumber": parcel.tracking_number, "currentStatus": st.status,
                           "originCity": parcel.origin_city, "destinationCity": parcel.destination_city,
                           "timeline": [{"status": "CREATED", "occurredAt": "2026-11-06T08:00:00Z",
                                         "cityOrZone": parcel.origin_city}]}
                if stub.faults.leak_pii:
                    payload["recipientPhone"] = "+94 77 123 4567"
                self._send(200, payload)

            def _chunk(self, text: str) -> None:
                data = text.encode()
                self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
                self.wfile.flush()

            def _stream(self, actor_alias: str, parcel_id: str, public: bool = False) -> None:
                with stub.lock:
                    if stub.sse_user.get(actor_alias, 0) >= SSE_MAX_PER_USER or stub.sse_parcel.get(parcel_id, 0) >= SSE_MAX_PER_PARCEL:
                        return self._send(429, {"title": "SSE limit"})
                    stub.sse_user[actor_alias] = stub.sse_user.get(actor_alias, 0) + 1
                    stub.sse_parcel[parcel_id] = stub.sse_parcel.get(parcel_id, 0) + 1
                    stub.sse_active += 1
                    listener = _Listener()
                    stub.state[parcel_id].listeners.append(listener)
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                sent = 0
                try:
                    self._chunk(":keepalive\n\n")
                    last_beat = time.monotonic()
                    while not stub.stopping.is_set():
                        with listener.cond:
                            if len(listener.events) == sent:
                                listener.cond.wait(timeout=0.2)
                            pending = listener.events[sent:]
                            sent = len(listener.events)
                        for event_id, payload in pending:
                            if public and not stub.faults.leak_pii:
                                payload = {k: v for k, v in payload.items() if k in PUBLIC_SSE_KEYS}
                            self._chunk("id:%s\nevent:parcel.status.changed\ndata:%s\n\n" % (event_id, json.dumps(payload)))
                        if time.monotonic() - last_beat >= stub.faults.heartbeat_s:
                            self._chunk(":keepalive\n\n")
                            last_beat = time.monotonic()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                    pass
                finally:
                    with stub.lock:
                        stub.state[parcel_id].listeners.remove(listener)
                        stub.sse_user[actor_alias] -= 1
                        stub.sse_parcel[parcel_id] -= 1
                        stub.sse_active -= 1
                    self.close_connection = True

        return Handler

    # ---- GraphQL simulation -------------------------------------------------------------
    def graphql(self, actor_alias: str, query: str, variables: dict) -> dict:
        actor = self.dataset.actors[actor_alias]
        if len(re.findall(r"\ba\d+\s*:", query)) > 99:
            return {"errors": [{"message": "too complex", "extensions": {"classification": "ValidationError"}}], "data": None}
        root = next((r for r in GRPC_CALLS if r in query), None)
        allowed = {"customerDashboard": ("CUSTOMER",), "merchantDashboard": ("MERCHANT",),
                   "dispatcherDashboard": ("DISPATCHER", "ADMIN")}
        if root is None or actor.role not in allowed[root] or (root == "merchantDashboard" and not actor.approved):
            return {"errors": [{"message": "denied", "extensions": {"classification": "FORBIDDEN"}}], "data": None}
        visible = self.dataset.visible_to(actor_alias)
        if root == "customerDashboard":
            with self.lock:
                self.grpc_calls += GRPC_CALLS[root]
            items = visible[:20]
            if self.faults.leak_cross_tenant:
                items = items + [next(p for p in self.dataset.parcels if p.owner_alias != actor_alias)]
            return {"data": {root: {"userId": actor.subject,
                                    "activeDeliveriesCount": sum(1 for p in visible if p.status not in TERMINAL),
                                    "completedDeliveriesCount": sum(1 for p in visible if p.status in TERMINAL),
                                    "recentParcels": [{"parcelId": p.parcel_id, "trackingNumber": p.tracking_number,
                                                       "currentStatus": p.status} for p in items]}}}
        if root == "merchantDashboard":
            with self.lock:
                self.grpc_calls += GRPC_CALLS[root]
            return {"data": {root: {"merchantId": actor.tenant, "totalOrdersCount": len(visible),
                                    "recentOrders": [{"parcelId": p.parcel_id, "trackingNumber": p.tracking_number}
                                                     for p in visible[:20]]}}}
        page, size = int(variables.get("page", 0)), int(variables.get("size", 20))
        if page < 0 or size < 1 or (size > 50 and not self.faults.accept_oversized_page):
            return {"errors": [{"message": "Invalid pagination", "extensions": {"classification": "BAD_REQUEST"}}], "data": None}
        with self.lock:
            self.grpc_calls += GRPC_CALLS[root] + (size if self.faults.n_plus_one else 0)
        items = visible[page * size:(page + 1) * size]
        total = len(visible)
        return {"data": {root: {
            "unassignedParcels": [{"parcelId": p.parcel_id, "trackingNumber": p.tracking_number,
                                   "pickupZone": p.pickup_zone, "dropoffZone": p.dropoff_zone} for p in items],
            "pageInfo": {"currentPage": page, "pageSize": size, "totalElements": total,
                         "totalPages": -(-total // size) if total else 0, "hasNext": (page + 1) * size < total},
            "availableCouriers": []}}}


class StubDirectory:
    environment_id = "in-process-stub"
    unknown_tracking_number = "SWD-20261106-ZZZZZZ"

    def __init__(self, stub: StubSwiftDrop, sse_connections: int = 20):
        self._ds = stub.dataset
        subscribed = {p.alias: p for _, p in plan_sse_subscribers(self._ds, sse_connections)}
        drivable = [p for p in subscribed.values() if p.status in ("PICKED_UP", "IN_TRANSIT")]
        self.status_drivers = [{"parcel": p.alias, "actor": p.courier_alias} for p in drivable[:6]]

    def token(self, actor_alias: str) -> str:
        return "stub." + actor_alias

    def negative_token(self, kind: str) -> Optional[str]:
        return {"expired": "stub-expired.customer-01", "invalid_signature": "forged.customer-01"}.get(kind)

    def parcel(self, alias: str) -> BoundParcel:
        p = next(x for x in self._ds.parcels if x.alias == alias)
        return BoundParcel(alias, p.parcel_id, p.tracking_number)

    def secrets(self) -> List[str]:
        return [self.token(a) for a in self._ds.actors]
