"""Opt-in synthetic Merchant probe. Never prints credentials or changes realm grant settings.

Uses existing PKCE helpers, owner-supplied ignored Compose configuration, and an
official cloudflared executable for a disposable HTTPS receiver. Shared records
are retired through supported APIs; existing users and orders are never touched.
"""
from __future__ import annotations

import argparse
import base64
from functools import partial
import hashlib
import hmac
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tests/security-e2e"))
import harness_common as harness  # noqa: E402
from harness_common import KeycloakAdminClient, OidcPkceClient  # noqa: E402

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class ProbeSession(harness.Session):
    """Bounded loopback client tolerating shared development database latency."""
    def request(self, url, data=None, headers=None, method=None):
        request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                return harness.Response(response.status, dict(response.headers), response.read().decode(), response.url)
        except urllib.error.HTTPError as error:
            return harness.Response(error.code, dict(error.headers), error.read().decode(), error.url)
        except Exception as error:
            path = urllib.parse.urlsplit(url).path
            raise harness.HarnessError(f"Loopback request failed: {method or 'GET'} {path} {type(error).__name__}") from None


harness.Session = ProbeSession


def gateway_call(gateway_url, method, path, token=None, body=None, form=None, headers=None, raw=False):
    hdr = dict(headers or {})
    data = None
    if token:
        hdr["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdr["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        hdr["Content-Type"] = "application/json"
    req = urllib.request.Request(path if path.startswith("http") else gateway_url + path, data=data, headers=hdr, method=method)
    try:
        with opener.open(req, timeout=45) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    if raw:
        return status, content
    try:
        return status, json.loads(content) if content else {}
    except ValueError:
        return status, {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--cloudflared", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--run", action="store_true", help="Explicitly authorize disposable fixtures and receiver")
    parser.add_argument("--browser", action="store_true", help="Also run explicitly authorized headless Playwright acceptance")
    endpoints = parser.add_mutually_exclusive_group()
    endpoints.add_argument("--isolated-local", action="store_true",
                           help="Use disposable Compose ports 18080, 18090 and 15173")
    endpoints.add_argument("--shared-development", action="store_true",
                           help="Explicitly select the shared development stack")
    args = parser.parse_args()
    if not args.run:
        parser.error("Use --run only against the authorized development stack")
    if not args.isolated_local and not args.shared_development:
        parser.error("Select --isolated-local or --shared-development explicitly")
    gateway_url = "http://localhost:18080" if args.isolated_local else "http://localhost:8080"
    keycloak_url = "http://localhost:18090" if args.isolated_local else "http://localhost:8090"
    frontend_url = "http://localhost:15173" if args.isolated_local else "http://localhost:5173"
    call = partial(gateway_call, gateway_url)
    if not args.cloudflared.is_file():
        parser.error("Provide an existing official cloudflared executable")
    if args.browser and importlib.util.find_spec("playwright") is None:
        parser.error("Install Playwright before enabling browser acceptance")
    os.environ["NO_PROXY"] = "localhost,127.0.0.1,::1"
    args.report_dir.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("compose_config", ROOT / "platform/compose/supabase.py")
    config = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(config)
    env = config.read_env(args.env_file)
    # Existing admin helper refreshes its own short-lived token from these names.
    os.environ["KC_BOOTSTRAP_ADMIN_USERNAME"] = env.get("KEYCLOAK_ADMIN_USER") or env.get("KC_BOOTSTRAP_ADMIN_USERNAME") or "admin"
    os.environ["KC_BOOTSTRAP_ADMIN_PASSWORD"] = env.get("KEYCLOAK_ADMIN_PASSWORD") or env.get("KC_BOOTSTRAP_ADMIN_PASSWORD") or ""
    results = []
    step = "development admin authentication"
    fixtures = {}
    run_tag = uuid.uuid4().hex[:8]
    refreshes = {}

    class TokenStore(dict):
        def __getitem__(self, name):
            value = super().__getitem__(name)
            claims = json.loads(base64.urlsafe_b64decode(value.split('.')[1] + '=='))
            if claims['exp'] <= time.time() + 20:
                data = require(call("POST", keycloak_url + "/realms/swiftdrop/protocol/openid-connect/token", form={
                    "grant_type": "refresh_token", "client_id": "swiftdrop-frontend", "refresh_token": refreshes[name],
                }), (200,), "fixture session refresh")
                value = data["access_token"]
                self[name] = value
                refreshes[name] = data["refresh_token"]
            return value

    tokens = TokenStore()
    key = None
    subscription = None
    merchant = None
    merchants = []
    parcels = []
    tunnel = None
    server = None
    browser = None
    admin = KeycloakAdminClient(keycloak_url)
    receiver = {"secret": None, "events": [], "failed_once": False, "exhaust_parcel": None}
    started = time.monotonic()

    def write_report():
        args.report_dir.mkdir(parents=True, exist_ok=True)
        report = {
            "runTag": run_tag, "results": results,
            "fixtureSubjects": {name: user["id"] for name, user in fixtures.items()},
            "fixtureProfileIds": {name: user.get("profileId") for name, user in fixtures.items()},
            "fixtureParcels": parcels, "verifiedWebhookRequests": len(receiver["events"]),
            "verifiedWebhookEvents": receiver["events"],
        }
        pending = args.report_dir / "merchant-live-probe.json.tmp"
        pending.write_text(json.dumps(report, indent=2), encoding="utf-8")
        pending.replace(args.report_dir / "merchant-live-probe.json")

    def passed(label):
        results.append({"step": label, "status": "Passed", "elapsedSeconds": round(time.monotonic() - started, 2)})
        write_report()
        print("PASS: " + label, flush=True)

    def require(result, statuses, label):
        if result[0] not in statuses:
            raise RuntimeError(label + " HTTP " + str(result[0]))
        return result[1]

    def book(body, headers, label):
        # Every retry carries the original quote, body and idempotency key.
        for attempt in range(3):
            response = call("POST", "/api/v1/merchant/parcels", body=body, headers=headers)
            if response[0] not in (502, 503, 504) or attempt == 2:
                return require(response, (200, 201), label)
            results.append({"step": "idempotent booking recovery", "status": "Observed", "http": response[0], "attempt": attempt + 1})
            write_report()
            time.sleep(1 + attempt)

    class Receiver(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            size = int(self.headers.get("Content-Length", "0"))
            if self.path != "/merchant-probe" or size > 65536:
                self.send_error(404)
                return
            body = self.rfile.read(size)
            signature = self.headers.get("X-SwiftDrop-Signature", "")
            match = re.fullmatch(r"t=(\d+),\s*v1=([0-9a-fA-F]{64})", signature)
            valid = False
            if match and receiver["secret"]:
                digest = hmac.new(receiver["secret"].encode(), match[1].encode() + b"." + body, hashlib.sha256).hexdigest()
                valid = abs(time.time() - int(match[1])) <= 300 and hmac.compare_digest(digest, match[2])
            code = 401
            if valid:
                event = json.loads(body)
                try:
                    uuid.UUID(event.get("eventId", ""))
                except (ValueError, TypeError):
                    self.send_error(422)
                    return
                payload = event.get("payload") or {}
                force_failure = payload.get("parcelId") == receiver["exhaust_parcel"] and payload.get("newStatus") == "FAILED"
                code = 503 if force_failure or not receiver["failed_once"] else 200
                receiver["failed_once"] = True
                receiver["events"].append({"eventId": event.get("eventId"), "parcelId": payload.get("parcelId"),
                    "merchantId": payload.get("merchantId"), "newStatus": payload.get("newStatus"),
                    "status": code, "verified": True})
            self.send_response(code)
            self.end_headers()
            self.wfile.write(b"ok")

    try:
        data = require(call("POST", keycloak_url + "/realms/master/protocol/openid-connect/token", form={
            "client_id": "admin-cli", "grant_type": "password",
            "username": env.get("KEYCLOAK_ADMIN_USER") or env.get("KC_BOOTSTRAP_ADMIN_USERNAME") or "admin",
            "password": env.get("KEYCLOAK_ADMIN_PASSWORD") or env.get("KC_BOOTSTRAP_ADMIN_PASSWORD") or "",
        }), (200,), step)
        admin.admin_token = data["access_token"]
        passed(step)
        # The helper otherwise inherits workstation proxy settings for loopback traffic.
        admin.http.opener.add_handler(urllib.request.ProxyHandler({}))
        pkce = OidcPkceClient(keycloak_url)

        def login_fixture(user):
            try:
                return pkce.login(user["username"], user["password"])
            except harness.HarnessError:
                results.append({"step": "fixture PKCE bounded recovery", "status": "Observed", "attempt": 1})
                write_report()
                time.sleep(2)
                return pkce.login(user["username"], user["password"])

        step = "disposable identities and PKCE authentication"
        for name, role in [("admin", "ADMIN"), ("dispatcher", "DISPATCHER"), ("courier", "COURIER"), ("merchant", "CUSTOMER"), ("other", "CUSTOMER")]:
            step = "create disposable " + name + " identity"
            user = admin.create_disposable_user(username_prefix="merchant-qa-" + run_tag + "-" + name, role=role)
            fixtures[name] = user
            step = "disposable " + name + " PKCE authentication"
            session = login_fixture(user)
            tokens[name] = session["access_token"]
            refreshes[name] = session["refresh_token"]
            profile = require(call("GET", "/api/v1/users/me", tokens[name]), (200,), step)
            user["profileId"] = profile["id"]
            passed(step)
        step = "staff fixture provisioning through authorized API"
        for name, role in [("admin", "ADMIN"), ("dispatcher", "DISPATCHER"), ("courier", "COURIER")]:
            require(call("POST", "/api/v1/admin/reconciliation/staff/provision", tokens["admin"], {
                "keycloakSub": fixtures[name]["id"], "staffId": "MQA-" + uuid.uuid4().hex[:12],
                "role": role, "department": "QA",
            }), (200,), step)
        passed(step)
        step = "pending merchant enrollment and approval restriction"
        merchant = require(call("POST", "/api/v1/merchants", tokens["merchant"], {
            "businessName": "Synthetic Merchant Acceptance", "registrationNumber": "MQA-" + uuid.uuid4().hex,
        }), (201,), step)["id"]
        merchants.append(merchant)
        require(call("POST", f"/api/v1/merchants/{merchant}/api-keys", tokens["merchant"], {
            "name": "Pending negative", "scopes": ["parcels:create"],
        }), (403,), step)
        passed(step)
        step = "admin approval and refreshed merchant access"
        require(call("POST", f"/api/v1/merchants/{merchant}/approve", tokens["admin"], {}), (200,), step)
        user = fixtures["merchant"]
        session = login_fixture(user)
        tokens["merchant"] = session["access_token"]
        refreshes["merchant"] = session["refresh_token"]
        passed(step)
        step = "second approved merchant fixture"
        other_merchant = require(call("POST", "/api/v1/merchants", tokens["other"], {
            "businessName": "Synthetic Isolation Merchant", "registrationNumber": "MQB-" + uuid.uuid4().hex,
        }), (201,), step)["id"]
        merchants.append(other_merchant)
        require(call("POST", f"/api/v1/merchants/{other_merchant}/approve", tokens["admin"], {}), (200,), step)
        other_session = login_fixture(fixtures["other"])
        tokens["other"] = other_session["access_token"]
        refreshes["other"] = other_session["refresh_token"]
        passed(step)
        step = "machine API key issuance and tenant isolation"
        key_data = require(call("POST", f"/api/v1/merchants/{merchant}/api-keys", tokens["merchant"], {
            "name": "Disposable Merchant QA", "scopes": ["parcels:create", "parcels:read"], "expiresInDays": 1,
        }), (201,), step)
        key = key_data["keyId"]
        machine = {"X-API-Key": key_data["plaintextKey"]}
        require(call("GET", f"/api/v1/merchants/{merchant}/api-keys", tokens["other"]), (403, 404), step)
        passed(step)
        step = "controlled public HTTPS receiver"
        # Exercise bounded recovery on a safe read before any subscription mutation.
        # Cold service-token acquisition can consume the existing gRPC deadline.
        for attempt in range(3):
            readiness = call("GET", "/api/v1/merchants/webhooks", tokens["merchant"])
            if readiness[0] not in (502, 503, 504) or attempt == 2:
                require(readiness, (200,), "webhook eligibility readiness")
                break
            results.append({"step": "webhook eligibility read recovery", "status": "Observed", "http": readiness[0], "attempt": attempt + 1})
            time.sleep(1 + attempt)
        server = ThreadingHTTPServer(("127.0.0.1", 8769), Receiver)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        log_path = Path(tempfile.gettempdir()) / ("merchant-tunnel-" + uuid.uuid4().hex + ".log")
        with log_path.open("w") as log:
            tunnel = subprocess.Popen([str(args.cloudflared), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8769", "--protocol", "http2"],
                stdout=log, stderr=log, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                env={k: v for k, v in os.environ.items() if k not in {"KC_BOOTSTRAP_ADMIN_USERNAME", "KC_BOOTSTRAP_ADMIN_PASSWORD"}})
        public = None
        for _ in range(60):
            match = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", log_path.read_text())
            if match:
                public = match[0] + "/merchant-probe"
                break
            if tunnel.poll() is not None:
                break
            time.sleep(1)
        if not public:
            raise RuntimeError("Temporary HTTPS receiver unavailable")
        created = require(call("POST", "/api/v1/merchants/webhooks", tokens["merchant"], {
            "httpsUrl": public, "allowedEventTypes": ["parcel.status.changed.v1"],
        }), (201,), step)
        subscription = created["subscriptionId"]
        receiver["secret"] = created["hmacSigningSecret"]
        passed(step)
        step = "machine credential booking, idempotency and merchant dashboard"
        zones = require(call("GET", "/api/v1/zones"), (200,), step)["zones"]
        quote = require(call("POST", "/api/v1/quotes", body={
            "pickupZone": zones[0]["zoneCode"], "dropoffZone": zones[0]["zoneCode"], "weightKg": 1,
            "lengthCm": 10, "widthCm": 10, "heightCm": 10,
        }), (201, 200), step)
        booking = {"quoteId": quote["quoteId"], "pickupAddress": "Synthetic QA pickup", "dropoffAddress": "Synthetic QA destination",
            "recipientName": "Synthetic Recipient", "recipientPhone": "+94770000000", "merchantReference": "MQA-" + uuid.uuid4().hex}
        headers = {**machine, "Idempotency-Key": str(uuid.uuid4())}
        parcel = book(booking, headers, step)
        parcels.append({"parcelId": parcel["parcelId"], "trackingNumber": parcel["trackingNumber"]})
        write_report()
        replay = book(booking, headers, step)
        if replay["parcelId"] != parcel["parcelId"]:
            raise RuntimeError("Idempotent replay returned a different parcel")
        pid = parcel["parcelId"]
        require(call("GET", f"/api/v1/parcels/{pid}", tokens["other"]), (403, 404), "cross-merchant parcel denial")
        require(call("GET", f"/api/v1/merchants/webhooks/{subscription}", tokens["other"]), (403, 404), "cross-merchant webhook denial")
        for attempt in range(3):
            dash = require(call("POST", "/graphql", tokens["merchant"], {"query": "{merchantDashboard{recentOrders{parcelId trackingNumber currentStatus lastStatusUpdate}}}"}), (200,), step)
            dashboard = (dash.get("data") or {}).get("merchantDashboard") or {}
            if pid in [row["parcelId"] for row in dashboard.get("recentOrders", [])]:
                break
            if attempt == 2:
                raise RuntimeError("Booked order missing from merchant dashboard")
            results.append({"step": "dashboard read recovery", "status": "Observed", "attempt": attempt + 1})
            time.sleep(1 + attempt)
        passed(step)
        observed = []
        if args.browser:
            step = "browser PKCE login and live Merchant tracking subscription"
            from merchant_browser_probe import MerchantBrowserProbe
            browser = MerchantBrowserProbe(parcel, args.report_dir / "browser", frontend_url)
            browser.login(fixtures["merchant"]["username"], fixtures["merchant"]["password"])
            passed(step)
        stream = {"response": None}

        def read_stream():
            try:
                request = urllib.request.Request(gateway_url + f"/api/v1/parcels/{pid}/events", headers={
                    "Authorization": "Bearer " + tokens["merchant"], "Accept": "text/event-stream",
                })
                with opener.open(request, timeout=60) as response:
                    stream["response"] = response
                    for line in response:
                        if line.startswith(b"data:"):
                            event = json.loads(line[5:].strip())
                            observed.append(event)
                            if event.get("newStatus") == "DELIVERED":
                                return
            except Exception:
                pass  # Failure is asserted below, not mistaken for a successful stream.

        threading.Thread(target=read_stream, daemon=True).start()
        step = "dispatcher assignment and legitimate courier status transition"
        current = require(call("GET", f"/api/v1/parcels/{pid}", tokens["merchant"]), (200,), step)
        if not current.get("updatedAt"):
            raise RuntimeError("Development Delivery response lacks authoritative updatedAt")
        require(call("POST", "/api/v1/assignments", tokens["dispatcher"], {
            "parcelId": pid, "courierSub": fixtures["courier"]["id"], "expectedVersion": current["version"],
        }), (200, 201), step)
        for stage in ["PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY"]:
            current = require(call("GET", f"/api/v1/parcels/{pid}", tokens["merchant"]), (200,), step)
            require(call("PATCH", f"/api/v1/parcels/{pid}/status", tokens["courier"], {
                "newStatus": stage, "expectedVersion": current["version"],
            }), (200,), step)
        passed(step)
        step = "proof upload, delivered terminal state and downloadable PDF contents"
        png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000001e221bc330000000049454e44ae426082")
        boundary = "----merchantqa" + uuid.uuid4().hex
        proof_body = (f'--{boundary}\r\nContent-Disposition: form-data; name="recipientName"\r\n\r\nSynthetic Recipient\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
        request = urllib.request.Request(gateway_url + f"/api/v1/parcels/{pid}/proof", data=proof_body, method="POST", headers={
            "Authorization": "Bearer " + tokens["courier"], "Content-Type": "multipart/form-data; boundary=" + boundary,
        })
        with opener.open(request, timeout=45) as response:
            proof = json.loads(response.read())
        current = require(call("GET", f"/api/v1/parcels/{pid}", tokens["merchant"]), (200,), step)
        require(call("PATCH", f"/api/v1/parcels/{pid}/status", tokens["courier"], {
            "newStatus": "DELIVERED", "expectedVersion": current["version"], "proofId": proof["proofId"],
        }), (200,), step)
        pdf = require(call("GET", f"/api/v1/parcels/{pid}/receipt", tokens["merchant"], raw=True), (200,), step)
        if not pdf.startswith(b"%PDF-"):
            raise RuntimeError("Receipt did not contain a real PDF")
        from pypdf import PdfReader
        text = "\n".join(page.extract_text() for page in PdfReader(io.BytesIO(pdf)).pages)
        if parcel["trackingNumber"] not in text or f'{parcel["amountMinorUnits"] / 100:.2f}' not in text.replace(',', ''):
            raise RuntimeError("Receipt reference or immutable fee mismatch")
        (args.report_dir / "synthetic-merchant-receipt.pdf").write_bytes(pdf)
        public_tracking = require(call("GET", "/api/v1/tracking/" + parcel["trackingNumber"]), (200,), step)
        if public_tracking["currentStatus"] != "DELIVERED" or "Synthetic QA" in json.dumps(public_tracking):
            raise RuntimeError("Public tracking status or privacy mismatch")
        passed(step)
        current = require(call("GET", f"/api/v1/parcels/{pid}", tokens["merchant"]), (200,), step)
        if current.get("updatedAt") == current["createdAt"]:
            raise RuntimeError("Delivered parcel retained its booking timestamp as update")
        step = "actual authenticated SSE status event"
        for _ in range(20):
            if any(event.get("parcelId") == pid and event.get("newStatus") == "DELIVERED" for event in observed):
                break
            time.sleep(1)
        else:
            raise RuntimeError("No delivered status event reached the authenticated SSE client")
        passed(step)
        if browser:
            step = "actual courier transition rendered in Merchant UI and browser PDF download"
            browser.delivered()
            passed(step)
        step = "Kafka webhook delivery, HMAC verification and receiver-failure retry"
        for _ in range(90):
            events = receiver["events"]
            failed = {event["eventId"] for event in events if event["status"] == 503}
            successful = {event["eventId"] for event in events if event["status"] == 200}
            if failed & successful:
                break
            time.sleep(2)
        else:
            raise RuntimeError("No verified retry of the failed webhook event observed")
        passed(step)
        step = "failed-delivery terminal path"
        quote2 = require(call("POST", "/api/v1/quotes", body={
            "pickupZone": zones[0]["zoneCode"], "dropoffZone": zones[0]["zoneCode"], "weightKg": 1,
            "lengthCm": 10, "widthCm": 10, "heightCm": 10,
        }), (200, 201), step)
        failed_parcel = book({**booking,
            "quoteId": quote2["quoteId"], "merchantReference": "MQF-" + uuid.uuid4().hex,
        }, {**machine, "Idempotency-Key": str(uuid.uuid4())}, step)
        failed_id = failed_parcel["parcelId"]
        if browser:
            receiver["exhaust_parcel"] = failed_id
        parcels.append({"parcelId": failed_id, "trackingNumber": failed_parcel["trackingNumber"]})
        current = require(call("GET", f"/api/v1/parcels/{failed_id}", tokens["merchant"]), (200,), step)
        require(call("POST", "/api/v1/assignments", tokens["dispatcher"], {
            "parcelId": failed_id, "courierSub": fixtures["courier"]["id"], "expectedVersion": current["version"],
        }), (200, 201), step)
        for stage in ["PICKED_UP", "FAILED"]:
            current = require(call("GET", f"/api/v1/parcels/{failed_id}", tokens["merchant"]), (200,), step)
            require(call("PATCH", f"/api/v1/parcels/{failed_id}/status", tokens["courier"], {
                "newStatus": stage, "expectedVersion": current["version"],
                **({"reason": "PARCEL_DAMAGED"} if stage == "FAILED" else {}),
            }), (200,), step)
        passed(step)
        step = "revoked machine credential rejection"
        require(call("DELETE", f"/api/v1/merchants/{merchant}/api-keys/{key}", tokens["merchant"]), (204,), step)
        key = None
        require(call("POST", "/api/v1/merchant/parcels", body=booking, headers=headers), (401, 403), step)
        passed(step)
        if browser:
            step = "browser responsive screens, dashboard controls and CSV downloads/import"
            browser.finish(zones[0]["zoneCode"])
            passed(step)
            step = "persistent receiver failure reaches visible webhook retry exhaustion"
            for _ in range(90):
                history = require(call("GET", f"/api/v1/merchants/webhooks/{subscription}/deliveries?size=100", tokens["merchant"]), (200,), step)
                exhausted = [row for row in history["deliveries"] if row["parcelId"] == failed_id and row["status"] == "EXHAUSTED"]
                if exhausted:
                    results.append({"step": "exhausted webhook evidence", "status": "Observed", "attemptCount": exhausted[0]["attemptCount"]})
                    break
                time.sleep(2)
            else:
                raise RuntimeError("Persistent receiver failure did not reach exhausted delivery state")
            if any(event["merchantId"] != merchant for event in receiver["events"]):
                raise RuntimeError("Webhook payload had an unexpected merchant association")
            passed(step)
            step = "deactivated subscription excludes subsequent synthetic status events"
            require(call("DELETE", f"/api/v1/merchants/webhooks/{subscription}", tokens["merchant"]), (204,), step)
            subscription = None
            owned = require(call("GET", "/api/v1/parcels?size=100", tokens["merchant"]), (200,), step)
            imported = next(parcel for parcel in owned["items"] if parcel["currentStatus"] == "CREATED")
            imported_id = imported["parcelId"]
            parcels.append({"parcelId": imported_id, "trackingNumber": imported["trackingNumber"]})
            require(call("POST", "/api/v1/assignments", tokens["dispatcher"], {
                "parcelId": imported_id, "courierSub": fixtures["courier"]["id"], "expectedVersion": imported["version"],
            }), (200, 201), step)
            imported = require(call("GET", f"/api/v1/parcels/{imported_id}", tokens["merchant"]), (200,), step)
            transition = require(call("PATCH", f"/api/v1/parcels/{imported_id}/status", tokens["courier"], {
                "newStatus": "PICKED_UP", "expectedVersion": imported["version"],
            }), (200,), step)
            if transition["currentStatus"] != "PICKED_UP":
                raise RuntimeError("Post-deactivation pickup did not complete")
            time.sleep(20)
            if any(event["parcelId"] == imported_id and event["newStatus"] == transition["currentStatus"]
                   for event in receiver["events"]):
                raise RuntimeError("Deactivated subscription received the subsequent pickup event")
            passed(step)
        else:
            results.append({"step": "browser UI delivery and responsive screenshots", "status": "Blocked", "reason": "Headless browser checks were not enabled"})
    except Exception as error:
        # Never include helper exception strings: some contain raw authentication responses.
        reason = str(error) if type(error) is RuntimeError else type(error).__name__
        if type(error).__name__ == "HarnessError":
            codes = re.findall(r"HTTP (\d{3})", str(error))
            if codes: reason += " HTTP " + codes[0]
            elif str(error).startswith("Loopback request failed:"): reason = str(error)
            elif "authorization code" in str(error): reason = "PKCE login did not return an authorization code"
            elif "Form '" in str(error): reason = "Expected login form was unavailable"
        results.append({"step": step, "status": "Blocked", "reason": reason})
        if browser:
            try:
                browser.diagnostics()
            except Exception:
                pass
        write_report()
        print("BLOCKED: " + step + " (" + reason + ")", flush=True)
    finally:
        def cleanup(label, action):
            try:
                status, _ = action()
                results.append({"step": label, "status": "Passed" if status in (200, 204, 404) else "Blocked", "http": status})
            except Exception as error:
                results.append({"step": label, "status": "Blocked", "reason": type(error).__name__})
            write_report()
            print("CLEANUP: " + label + " " + results[-1]["status"], flush=True)

        if browser:
            try:
                browser.close()
                status, owned = call("GET", "/api/v1/parcels?size=100", tokens["merchant"])
                if status == 200:
                    known = {parcel["parcelId"] for parcel in parcels}
                    for parcel in owned["items"]:
                        if parcel["parcelId"] not in known:
                            parcels.append({"parcelId": parcel["parcelId"], "trackingNumber": parcel["trackingNumber"]})
                else:
                    results.append({"step": "discover browser-created synthetic parcels", "status": "Blocked", "http": status})
            except Exception as error:
                results.append({"step": "close browser and discover synthetic parcels", "status": "Blocked", "reason": type(error).__name__})

        if subscription and tokens.get("merchant"):
            cleanup("deactivate disposable webhook", lambda: call("DELETE", f"/api/v1/merchants/webhooks/{subscription}", tokens["merchant"]))
        if key and merchant and tokens.get("merchant"):
            cleanup("revoke disposable API key", lambda: call("DELETE", f"/api/v1/merchants/{merchant}/api-keys/{key}", tokens["merchant"]))
        if tokens.get("admin"):
            for parcel_record in parcels:
                try:
                    parcel_id = parcel_record["parcelId"]
                    status, current = call("GET", f"/api/v1/parcels/{parcel_id}", tokens["admin"])
                    if status != 200:
                        raise RuntimeError("Fixture parcel could not be inspected")
                    if current["currentStatus"] not in ("DELIVERED", "FAILED"):
                        reasons = {"CREATED": "CANCELLED_BY_SENDER", "PICKED_UP": "FORCE_MAJEURE",
                                   "IN_TRANSIT": "FORCE_MAJEURE", "OUT_FOR_DELIVERY": "RECIPIENT_UNAVAILABLE"}
                        cleanup("retire unfinished synthetic parcel", lambda: call("PATCH", f"/api/v1/parcels/{parcel_id}/status", tokens["admin"], {
                            "newStatus": "FAILED", "expectedVersion": current["version"],
                            "reason": reasons[current["currentStatus"]], "note": "Synthetic acceptance probe cleanup",
                        }))
                except Exception as error:
                    results.append({"step": "retire unfinished synthetic parcel", "status": "Blocked", "reason": type(error).__name__})
            for retired_merchant in merchants:
                try:
                    status, profile = call("GET", f"/api/v1/merchants/{retired_merchant}", tokens["admin"])
                    action = "suspend" if profile.get("status") == "APPROVED" else "reject"
                    cleanup("retire synthetic merchant", lambda: call("POST", f"/api/v1/merchants/{retired_merchant}/{action}", tokens["admin"], {"reason": "Synthetic acceptance probe complete"}))
                except Exception:
                    results.append({"step": "retire synthetic merchant", "status": "Blocked"})
            for name in ["courier", "dispatcher"]:
                if name in fixtures:
                    cleanup("deactivate " + name + " fixture", lambda name=name: call("POST", f"/api/v1/admin/reconciliation/staff/{fixtures[name]['id']}/deactivate", tokens["admin"]))
            for name, user in fixtures.items():
                if name != "admin" and user.get("profileId"):
                    cleanup("disable " + name + " profile", lambda user=user: call("POST", f"/api/v1/admin/users/{user['profileId']}/disable", tokens["admin"]))
            # Retire the caller last; self-disable is intentionally forbidden by the API.
            if "admin" in fixtures:
                cleanup("deactivate admin fixture", lambda: call("POST", f"/api/v1/admin/reconciliation/staff/{fixtures['admin']['id']}/deactivate", tokens["admin"]))
        # Also find users whose creation succeeded but whose response was lost.
        try:
            status, discovered = admin.request("/users?search=merchant-qa-" + run_tag + "&max=100")
            if status == 200:
                known = {user["id"] for user in admin.disposable_users}
                for user in discovered:
                    if user.get("username", "").startswith("merchant-qa-" + run_tag + "-") and user.get("email", "").endswith("@swiftdrop.test") and user["id"] not in known:
                        admin.disposable_users.append(user)
        except Exception:
            results.append({"step": "discover uncertain fixture creation", "status": "Blocked"})
        for user in admin.disposable_users:
            cleanup("remove disposable identity", lambda user=user: admin.request(f"/users/{user['id']}", method="DELETE"))
        if tunnel:
            tunnel.terminate()
            tunnel.wait(timeout=15)
        if server:
            server.shutdown()
        write_report()
    return 0 if not any(row["status"] == "Blocked" for row in results) else 1


if __name__ == "__main__":
    sys.exit(main())
