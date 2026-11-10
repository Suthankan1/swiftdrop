#!/usr/bin/env python3
"""Opt-in seed of one demo account per SwiftDrop role on a LOCAL stack (Python 3.9+).

Creates (or resets) these Keycloak users in the swiftdrop realm:
  demo.customer, demo.merchant, demo.courier, demo.dispatcher, demo.admin   (@swiftdrop.test)

and puts each one in a usable state through the real APIs:
  * every user signs in once so Accounts creates its profile;
  * demo.admin is given ADMIN in Keycloak (the bootstrap step) and a staff profile;
  * demo.courier and demo.dispatcher are provisioned by demo.admin through Accounts;
  * demo.merchant registers "Demo Store" and demo.admin approves it.

Safety:
  * Nothing runs without --yes. Only loopback URLs are accepted (no credentials leave this machine).
  * Passwords are generated per run and written only to platform/identity/.local/demo-accounts.json
    (mode 0600, git-ignored). They are never printed.
  * Signing in from a script needs the password grant on the frontend client. It is switched on
    for the duration of the run and restored to its previous value afterwards, even on failure.

Usage:
  python3 platform/identity/seed-demo-accounts.py --yes
  (reads KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD from platform/compose/.env or the environment)
"""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

DIRECTORY = Path(__file__).resolve().parent
ENV_FILE = DIRECTORY.parent / "compose" / ".env"
OUTPUT = DIRECTORY / ".local" / "demo-accounts.json"
FRONTEND_CLIENT = "swiftdrop-frontend"
DOMAIN = "swiftdrop.test"
ROLES = ("customer", "merchant", "courier", "dispatcher", "admin")


class SeedError(Exception):
    """A sanitized operator-facing failure."""


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                values[key] = value.strip().strip("'\"")
    values.update(os.environ)
    return values


def loopback(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1") \
            or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query:
        raise SeedError("Only an HTTP(S) loopback origin is allowed: " + parsed.scheme + "://" + str(parsed.hostname))
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def call(method, url, token=None, body=None, form=None, accept=(200, 201, 204)):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=20) as response:
            status, raw, location = response.status, response.read(), response.headers.get("Location", "")
    except urllib.error.HTTPError as error:
        status, raw, location = error.code, error.read(), ""
        error.close()
    except (urllib.error.URLError, TimeoutError, OSError):
        raise SeedError(f"{method} {urllib.parse.urlsplit(url).path} failed: service unreachable") from None
    if status not in accept:
        raise SeedError(f"{method} {urllib.parse.urlsplit(url).path} returned HTTP {status}")
    try:
        payload = json.loads(raw) if raw else None
    except ValueError:
        payload = None
    return status, payload, location


class Seeder:
    def __init__(self, keycloak, gateway, realm, settings):
        self.kc, self.gw, self.realm = keycloak, gateway, realm
        self.admin_api = f"{keycloak}/admin/realms/{realm}"
        user, password = settings.get("KEYCLOAK_ADMIN_USER", ""), settings.get("KEYCLOAK_ADMIN_PASSWORD", "")
        if not user or not password:
            raise SeedError("KEYCLOAK_ADMIN_USER and KEYCLOAK_ADMIN_PASSWORD are required.")
        _, body, _ = call("POST", f"{keycloak}/realms/master/protocol/openid-connect/token",
                          form={"grant_type": "password", "client_id": "admin-cli", "username": user, "password": password})
        self.admin = body["access_token"]

    def ensure_user(self, name, password):
        username = "demo." + name
        _, found, _ = call("GET", f"{self.admin_api}/users?exact=true&username={username}", self.admin)
        if found:
            uid = found[0]["id"]
        else:
            _, _, location = call("POST", f"{self.admin_api}/users", self.admin, {
                "username": username, "email": f"{username}@{DOMAIN}", "firstName": "Demo",
                "lastName": name.capitalize(), "enabled": True, "emailVerified": True, "requiredActions": []})
            uid = location.rsplit("/", 1)[-1]
        call("PUT", f"{self.admin_api}/users/{uid}", self.admin, {"enabled": True, "requiredActions": []})
        call("PUT", f"{self.admin_api}/users/{uid}/reset-password", self.admin,
             {"type": "password", "value": password, "temporary": False})
        return username, uid

    def grant_realm_role(self, uid, role):
        _, descriptor, _ = call("GET", f"{self.admin_api}/roles/{role}", self.admin)
        call("POST", f"{self.admin_api}/users/{uid}/role-mappings/realm", self.admin, [descriptor])

    def frontend_client(self):
        _, clients, _ = call("GET", f"{self.admin_api}/clients?clientId={FRONTEND_CLIENT}", self.admin)
        if not clients:
            raise SeedError(f"Client {FRONTEND_CLIENT} not found in realm {self.realm}.")
        return clients[0]

    def set_password_grant(self, client, enabled):
        client = dict(client, directAccessGrantsEnabled=enabled)
        call("PUT", f"{self.admin_api}/clients/{client['id']}", self.admin, client)

    def sign_in(self, username, password):
        _, body, _ = call("POST", f"{self.kc}/realms/{self.realm}/protocol/openid-connect/token", form={
            "grant_type": "password", "client_id": FRONTEND_CLIENT, "username": username,
            "password": password, "scope": "openid"})
        return body["access_token"]

    def api(self, method, path, token, body=None, accept=(200, 201, 204)):
        return call(method, f"{self.gw}/api/v1{path}", token, body, accept=accept)[1]


def write_output(accounts):
    OUTPUT.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if OUTPUT.is_symlink():
        raise SeedError("Refusing to write through a symbolic link.")
    descriptor = os.open(OUTPUT, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(accounts, handle, indent=2)
    os.chmod(OUTPUT, 0o600)


def seed(seeder):
    passwords = {name: "Demo-" + secrets.token_urlsafe(15) for name in ROLES}
    users = {name: seeder.ensure_user(name, passwords[name]) for name in ROLES}
    seeder.grant_realm_role(users["admin"][1], "ADMIN")  # bootstrap: the first admin cannot be provisioned by an admin

    client = seeder.frontend_client()
    previous = bool(client.get("directAccessGrantsEnabled"))
    seeder.set_password_grant(client, True)
    try:
        tokens = {name: seeder.sign_in(users[name][0], passwords[name]) for name in ROLES}
        for name in ROLES:  # first sign-in creates the Accounts profile
            seeder.api("GET", "/users/me", tokens[name])
        admin = tokens["admin"]
        for name, role in (("admin", "ADMIN"), ("courier", "COURIER"), ("dispatcher", "DISPATCHER")):
            seeder.api("POST", "/admin/reconciliation/staff/provision", admin, {
                "keycloakSub": users[name][1], "staffId": "DEMO-" + name.upper(), "role": role,
                "department": "OPERATIONS", "assignedZone": "ZONE_1"}, accept=(200, 201, 409))
        merchant = seeder.api("GET", "/merchants/me", tokens["merchant"], accept=(200, 404))
        if not merchant or not merchant.get("id"):
            merchant = seeder.api("POST", "/merchants", tokens["merchant"], {
                "businessName": "Demo Store", "registrationNumber": "DEMO-" + secrets.token_hex(4).upper()})
        detail = seeder.api("GET", f"/admin/merchants/{merchant['id']}", admin)
        if detail.get("status") == "PENDING":
            seeder.api("POST", f"/merchants/{merchant['id']}/approve", admin,
                       {"reviewToken": detail.get("reviewToken"), "reason": "Demo seed"})
    finally:
        seeder.set_password_grant(seeder.frontend_client(), previous)

    write_output({name: {"username": users[name][0], "email": f"{users[name][0]}@{DOMAIN}",
                         "password": passwords[name]} for name in ROLES})
    return [users[name][0] for name in ROLES]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--yes", action="store_true", help="confirm seeding demo accounts on this local stack")
    args = parser.parse_args(argv)
    if not args.yes:
        parser.print_help()
        print("\nNothing changed. Re-run with --yes to seed demo accounts.", file=sys.stderr)
        return 2
    settings = read_env(ENV_FILE)
    try:
        keycloak = loopback(settings.get("SEED_KEYCLOAK_URL", "http://localhost:" + settings.get("KEYCLOAK_PORT", "8090")))
        gateway = loopback(settings.get("SEED_GATEWAY_URL", "http://localhost:" + settings.get("GATEWAY_PORT", "8080")))
        names = seed(Seeder(keycloak, gateway, settings.get("KEYCLOAK_REALM", "swiftdrop"), settings))
    except SeedError as error:
        print(f"Seeding failed: {error}", file=sys.stderr)
        return 1
    print("Seeded demo accounts: " + ", ".join(names))
    print(f"Passwords were written to {OUTPUT} (mode 0600, git-ignored).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
