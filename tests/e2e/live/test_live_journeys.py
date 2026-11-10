"""Opt-in live journeys against a RUNNING local stack (audit 104).

The other suites run in-process, so they cannot see failures that only appear when the real services, Keycloak,
Kafka and the gateway run together. These tests drive the stack through the gateway exactly as the browser does.

Run (stack started with platform/compose/start.sh):
    SWIFTDROP_LIVE_E2E=1 python3 -m pytest tests/e2e/live -q

Safety: loopback URLs only; throwaway users with random passwords are created for the run and deleted afterwards;
the frontend client's password grant is switched on only for the run and restored to its previous value.
Admin credentials are read from platform/compose/.env (KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD) and never printed.
"""

import json
import os
import secrets
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
GW = os.environ.get("SWIFTDROP_GATEWAY_URL", "http://localhost:8080").rstrip("/")
KC = os.environ.get("SWIFTDROP_KEYCLOAK_URL", "http://localhost:8090").rstrip("/")
REALM = "swiftdrop"
CLIENT = "swiftdrop-frontend"
ENABLED = os.environ.get("SWIFTDROP_LIVE_E2E") == "1"

for url in (GW, KC):
    host = urllib.parse.urlsplit(url).hostname
    if host not in ("localhost", "127.0.0.1", "::1"):
        raise SystemExit("Live e2e only runs against loopback URLs")

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, url, token=None, body=None, form=None, headers=None, raw=False):
    hdrs = dict(headers or {})
    data = None
    if token:
        hdrs["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        with _OPENER.open(req, timeout=30) as res:
            status, payload, loc = res.status, res.read(), res.headers.get("Location", "")
    except urllib.error.HTTPError as err:
        status, payload, loc = err.code, err.read(), ""
    if raw:
        return status, payload, loc
    try:
        return status, json.loads(payload) if payload else None, loc
    except ValueError:
        return status, None, loc


def read_env():
    values = {}
    env = REPO / "platform" / "compose" / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip().strip("'\"")
    values.update(os.environ)
    return values


@unittest.skipUnless(ENABLED, "set SWIFTDROP_LIVE_E2E=1 with the stack running to run live journeys")
class LiveJourneys(unittest.TestCase):
    """Customer books and tracks; dispatcher assigns; courier delivers with proof; receipts and events check out."""

    @classmethod
    def setUpClass(cls):
        env = read_env()
        status, body, _ = call("POST", f"{KC}/realms/master/protocol/openid-connect/token", form={
            "grant_type": "password", "client_id": "admin-cli",
            "username": env.get("KEYCLOAK_ADMIN_USER", ""), "password": env.get("KEYCLOAK_ADMIN_PASSWORD", "")})
        if status != 200:
            raise unittest.SkipTest("Keycloak admin sign-in failed; is the stack running with platform/compose/.env?")
        cls.admin_kc = body["access_token"]
        cls.realm = f"{KC}/admin/realms/{REALM}"
        cls.client = call("GET", f"{cls.realm}/clients?clientId={CLIENT}", cls.admin_kc)[1][0]
        cls.previous_grant = bool(cls.client.get("directAccessGrantsEnabled"))
        call("PUT", f"{cls.realm}/clients/{cls.client['id']}", cls.admin_kc, dict(cls.client, directAccessGrantsEnabled=True))
        cls.users = {}
        tag = secrets.token_hex(3)
        for name, role in [("customer", "CUSTOMER"), ("courier", "COURIER"), ("dispatcher", "DISPATCHER"), ("admin", "ADMIN")]:
            password = "Live-" + secrets.token_urlsafe(16)
            username = f"live_{name}_{tag}"
            _, _, location = call("POST", f"{cls.realm}/users", cls.admin_kc, {
                "username": username, "email": f"{username}@example.test", "firstName": "Live", "lastName": name,
                "enabled": True, "emailVerified": True,
                "credentials": [{"type": "password", "value": password, "temporary": False}]})
            uid = location.rsplit("/", 1)[-1]
            descriptor = call("GET", f"{cls.realm}/roles/{role}", cls.admin_kc)[1]
            call("POST", f"{cls.realm}/users/{uid}/role-mappings/realm", cls.admin_kc, [descriptor])
            cls.users[name] = {"username": username, "password": password, "sub": uid}
        cls.tokens = {n: cls.sign_in(n) for n in cls.users}
        for n in cls.users:
            me = call("GET", f"{GW}/api/v1/users/me", cls.tokens[n])[1] or {}
            cls.users[n]["profileId"] = me.get("id")
        for n, role in [("admin", "ADMIN"), ("courier", "COURIER"), ("dispatcher", "DISPATCHER")]:
            call("POST", f"{GW}/api/v1/admin/reconciliation/staff/provision", cls.tokens["admin"], {
                "keycloakSub": cls.users[n]["sub"], "staffId": f"LIVE-{n}-{tag}", "role": role, "department": "OPS"})
        cls.tokens = {n: cls.sign_in(n) for n in cls.users}

    @classmethod
    def tearDownClass(cls):
        # Accounts has no hard delete, so retire what the run created before removing the Keycloak users:
        # staff profiles become INACTIVE and account profiles lose sign-in, so they never show up as live staff.
        users = getattr(cls, "users", {})
        admin_token = getattr(cls, "tokens", {}).get("admin")
        if admin_token:
            for name in ("courier", "dispatcher", "customer", "admin"):
                user = users.get(name, {})
                if name != "customer":
                    call("POST", f"{GW}/api/v1/admin/reconciliation/staff/{user.get('sub')}/deactivate", admin_token)
                if user.get("profileId") and name != "admin":
                    call("POST", f"{GW}/api/v1/admin/users/{user['profileId']}/disable", admin_token)
        for user in users.values():
            call("DELETE", f"{cls.realm}/users/{user['sub']}", cls.admin_kc)
        if hasattr(cls, "client"):
            call("PUT", f"{cls.realm}/clients/{cls.client['id']}", cls.admin_kc,
                 dict(cls.client, directAccessGrantsEnabled=cls.previous_grant))

    @classmethod
    def sign_in(cls, name):
        user = cls.users[name]
        status, body, _ = call("POST", f"{KC}/realms/{REALM}/protocol/openid-connect/token", form={
            "grant_type": "password", "client_id": CLIENT, "username": user["username"],
            "password": user["password"], "scope": "openid"})
        assert status == 200, f"sign-in failed for {name}: HTTP {status}"
        return body["access_token"]

    def version(self, parcel_id):
        return call("GET", f"{GW}/api/v1/parcels/{parcel_id}", self.tokens["dispatcher"])[1]["version"]

    def test_full_delivery_journey(self):
        t = self.tokens
        zones = call("GET", f"{GW}/api/v1/zones")[1]["zones"]
        self.assertTrue(zones, "no active zones")
        quote = call("POST", f"{GW}/api/v1/quotes", t["customer"], {
            "pickupZone": zones[0]["zoneCode"], "dropoffZone": zones[-1]["zoneCode"],
            "weightKg": 2, "lengthCm": 20, "widthCm": 15, "heightCm": 10})
        self.assertEqual(quote[0], 201, quote)
        status, parcel, _ = call("POST", f"{GW}/api/v1/parcels", t["customer"], {
            "quoteId": quote[1]["quoteId"], "pickupAddress": "1 Live St", "dropoffAddress": "2 Test Rd",
            "recipientName": "Live Recipient", "recipientPhone": "+94770000000"},
            headers={"Idempotency-Key": str(uuid.uuid4())})
        self.assertEqual(status, 201, parcel)
        pid, tn = parcel["parcelId"], parcel["trackingNumber"]

        # Dispatcher sees it in the queue with real zones, then assigns the courier.
        q = "query Q($p: PaginationInput) { dispatcherDashboard(pagination: $p) { unassignedParcels { trackingNumber pickupZone } } }"
        dash = call("POST", f"{GW}/graphql", t["dispatcher"], {"query": q, "variables": {"p": {"page": 0, "size": 50}}})[1]
        self.assertFalse(dash.get("errors"), dash)
        self.assertIn(tn, [p["trackingNumber"] for p in dash["data"]["dispatcherDashboard"]["unassignedParcels"]])
        assign = call("POST", f"{GW}/api/v1/assignments", t["dispatcher"], {
            "parcelId": pid, "courierSub": self.users["courier"]["sub"], "expectedVersion": self.version(pid)})
        self.assertEqual(assign[0], 200, assign)

        for stage in ("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY"):
            res = call("PATCH", f"{GW}/api/v1/parcels/{pid}/status", t["courier"],
                       {"newStatus": stage, "expectedVersion": self.version(pid)})
            self.assertEqual(res[0], 200, (stage, res))

        png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000001e221bc330000000049454e44ae426082")
        boundary = "----live" + secrets.token_hex(6)
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"recipientName\"\r\n\r\nLive Recipient\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\nContent-Type: image/png\r\n\r\n").encode() \
            + png + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(f"{GW}/api/v1/parcels/{pid}/proof", data=body, method="POST", headers={
            "Authorization": "Bearer " + t["courier"], "Content-Type": "multipart/form-data; boundary=" + boundary})
        with _OPENER.open(req, timeout=30) as res:
            proof = json.loads(res.read())
        done = call("PATCH", f"{GW}/api/v1/parcels/{pid}/status", t["courier"],
                    {"newStatus": "DELIVERED", "expectedVersion": self.version(pid), "proofId": proof["proofId"]})
        self.assertEqual(done[0], 200, done)

        public = call("GET", f"{GW}/api/v1/tracking/{tn}")
        self.assertEqual(public[1]["currentStatus"], "DELIVERED")
        self.assertNotIn("Live St", json.dumps(public[1]), "public tracking must not expose addresses")
        receipt = call("GET", f"{GW}/api/v1/parcels/{pid}/receipt", t["customer"], raw=True)
        self.assertEqual(receipt[0], 200)
        self.assertTrue(receipt[1].startswith(b"%PDF"))
        history = call("GET", f"{GW}/api/v1/parcels/{pid}/history", t["customer"])[1]["items"]
        self.assertEqual({h["newStatus"] for h in history} >= {"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"}, True)

    def test_access_rules_hold_on_the_real_stack(self):
        t = self.tokens
        self.assertEqual(call("GET", f"{GW}/api/v1/parcels?page=0&size=5")[0], 401)
        self.assertEqual(call("GET", f"{GW}/api/v1/admin/users", t["customer"])[0], 403)
        self.assertEqual(call("POST", f"{GW}/api/v1/assignments", t["customer"], {})[0], 403)
        self.assertEqual(call("GET", f"{GW}/api/v1/assistant/status")[0], 200)


if __name__ == "__main__":
    unittest.main()
