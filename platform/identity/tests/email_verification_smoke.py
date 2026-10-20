#!/usr/bin/env python3
"""Explicit local native-flow smoke test. Creates only disposable dv47a-* identities.

Requires an already-running stack and the email gate applied by the operator.
Never prints credentials, email links, action tokens, OAuth codes, or JWTs.
"""

import argparse
import base64
import copy
from dataclasses import dataclass
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import string
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


IDENTITY_DIR = Path(__file__).resolve().parents[1]
STATE_FILE = IDENTITY_DIR / ".local/email-verification-smoke-state.json"
REDIRECT_URI = "http://localhost:5173/auth/callback"
PRIVILEGED = {"MERCHANT", "COURIER", "DISPATCHER", "ADMIN"}


class SmokeFailure(Exception):
    """Messages contain only fixed, non-sensitive diagnostic labels."""


def check(condition, label):
    if not condition:
        raise SmokeFailure(label)


def report(label):
    print(label, flush=True)


def decode_claims(token):
    return json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))


def generate_test_password(length=32):
    """Generate an unpredictable, cryptographically secure synthetic test password.

    Guarantees uppercase, lowercase, numeric, and special character requirements
    without any predictable prefix or hardcoded credential components.
    """
    if length < 24:
        raise ValueError("Password length must be at least 24 characters")
    special_chars = "!@#$%&*-_=+"
    mandatory = [
        secrets.choice(string.ascii_uppercase),
        secrets.choice(string.ascii_uppercase),
        secrets.choice(string.ascii_lowercase),
        secrets.choice(string.ascii_lowercase),
        secrets.choice(string.digits),
        secrets.choice(string.digits),
        secrets.choice(special_chars),
        secrets.choice(special_chars),
    ]
    all_chars = string.ascii_letters + string.digits + special_chars
    remaining = [secrets.choice(all_chars) for _ in range(length - len(mandatory))]
    password_chars = mandatory + remaining
    secrets.SystemRandom().shuffle(password_chars)
    return "".join(password_chars)


class Page(HTMLParser):
    def __init__(self, body):
        super().__init__(convert_charrefs=True)
        self.forms, self.links, self.current = [], [], None
        self.feed(body)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self.current = {**attrs, "fields": {}}
            self.forms.append(self.current)
        elif tag == "input" and self.current is not None and attrs.get("name"):
            if attrs.get("type") not in ("submit", "button", "checkbox") and "disabled" not in attrs:
                self.current["fields"][attrs["name"]] = attrs.get("value", "")
        elif tag == "a" and attrs.get("href"):
            self.links.append(attrs)

    def handle_endtag(self, tag):
        if tag == "form":
            self.current = None


@dataclass
class Response:
    status: int
    headers: object
    body: str
    url: str


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        return True


def clone_cookies(jar):
    new_jar = http.cookiejar.CookieJar(policy=LoopbackCookiePolicy())
    for cookie in jar:
        new_jar.set_cookie(copy.copy(cookie))
    return new_jar


class Session:
    def __init__(self, keycloak_url, cookies=None):
        self.origin = keycloak_url.rstrip("/")
        self.cookies = cookies if cookies is not None else http.cookiejar.CookieJar(policy=LoopbackCookiePolicy())
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())

    def clone(self):
        return Session(self.origin, clone_cookies(self.cookies))

    def request(self, url, data=None, headers=None, method=None):
        request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        try:
            result = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            result = error
        except (urllib.error.URLError, TimeoutError, OSError):
            raise SmokeFailure("HTTP connection failed; inspect local service availability") from None
        return Response(result.code, result.headers, result.read().decode("utf-8", "replace"), url)

    def follow(self, response):
        for _ in range(15):
            if response.status not in (301, 302, 303, 307, 308):
                return response
            target = urllib.parse.urljoin(response.url, response.headers["Location"])
            if target.startswith(REDIRECT_URI + "?"):
                return Response(response.status, response.headers, "", target)
            check(target.startswith(self.origin + "/"), "Unexpected external authentication redirect")
            response = self.request(target)
        raise SmokeFailure("Authentication exceeded the redirect limit")

    def get(self, url):
        return self.follow(self.request(url))

    def form(self, response, form_id, fields):
        forms = [form for form in Page(response.body).forms if form.get("id") == form_id]
        check(len(forms) == 1, "Expected native form missing: " + form_id)
        form = forms[0]
        target = urllib.parse.urljoin(response.url, form["action"])
        check(target.startswith(self.origin + "/"), "Native form action is outside Keycloak")
        payload = urllib.parse.urlencode({**form["fields"], **fields}).encode()
        return self.follow(self.request(target, data=payload))


class Smoke:
    def __init__(self, args):
        self.args = args
        self.realm_url = args.keycloak_url + "/realms/devarom"
        self.admin_url = args.keycloak_url + "/admin/realms/devarom"
        self.http = Session(args.keycloak_url)
        self.run_id = secrets.token_hex(6)
        self.users, self.message_ids = [], set()
        self.admin_token = None

    def admin(self, path, method="GET", data=None):
        if not self.admin_token:
            self.authenticate_admin()
        headers = {"Authorization": "Bearer " + self.admin_token}
        if data is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(data).encode()
        response = self.http.request(self.admin_url + path, data, headers, method)
        if response.status == 401:
            self.authenticate_admin()
            headers["Authorization"] = "Bearer " + self.admin_token
            response = self.http.request(self.admin_url + path, data, headers, method)
        check(response.status in (200, 201, 204), "Keycloak admin operation failed")
        return json.loads(response.body) if response.body else None

    def authenticate_admin(self):
        values = {}
        for line in (IDENTITY_DIR / ".env").read_text().splitlines():
            match = re.match(r"^(?:export\s+)?([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
            if match:
                value = match[2]
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                    value = value[1:-1]
                values[match[1]] = value
        username = os.environ.get("KC_BOOTSTRAP_ADMIN_USERNAME", values.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"))
        password = os.environ.get("KC_BOOTSTRAP_ADMIN_PASSWORD", values.get("KC_BOOTSTRAP_ADMIN_PASSWORD", ""))
        check(bool(password), "Local bootstrap admin password is unavailable")
        payload = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password",
                                          "username": username, "password": password}).encode()
        response = self.http.request(self.args.keycloak_url + "/realms/master/protocol/openid-connect/token", payload)
        check(response.status == 200, "Local admin authentication failed")
        self.admin_token = json.loads(response.body)["access_token"]

    def assert_runtime_gate(self):
        realm = self.admin("")
        check(realm.get("verifyEmail") is True, "Apply the runtime verification gate before running this test")
        check(realm.get("actionTokenGeneratedByUserLifespan") == 300, "Runtime user action expiry must remain300 seconds")
        check(realm.get("smtpServer", {}).get("host") == "mailpit", "Runtime SMTP must point to local Mailpit")
        actions = self.admin("/authentication/required-actions")
        verify = next((action for action in actions if action["alias"] == "VERIFY_EMAIL"), {})
        check(verify.get("enabled") is True and verify.get("defaultAction") is False,
              "Runtime native verification action is not configured safely")
        check(verify.get("config", {}).get("emailResendCooldownSeconds") == "60",
              "Apply the runtime60-second native resend cooldown before running")
        check(any(action["alias"] == "UPDATE_PASSWORD" and action["enabled"] for action in actions),
              "Native password required action must remain enabled")
        report("PASS: active native gate, expiry, required actions, and resend cooldown")

    def authorization_url(self, verifier, state, registration=False, prompt=None):
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        params = {"client_id": "devarom-frontend", "response_type": "code", "redirect_uri": REDIRECT_URI,
                  "scope": "openid profile email", "code_challenge": challenge, "code_challenge_method": "S256",
                  "state": state, "nonce": secrets.token_urlsafe(20)}
        if prompt:
            params["prompt"] = prompt
        path = "/protocol/openid-connect/registrations" if registration else "/protocol/openid-connect/auth"
        return self.realm_url + path + "?" + urllib.parse.urlencode(params)

    def user_record(self, user):
        records = self.admin("/users?" + urllib.parse.urlencode({"username": user["username"], "exact": "true"}))
        check(len(records) == 1 and records[0]["email"] == user["email"], "Synthetic identity binding did not match")
        user["id"] = records[0]["id"]
        return records[0]

    def assert_roles(self, user):
        roles = self.admin("/users/" + user["id"] + "/role-mappings/realm/composite")
        names = {role["name"] for role in roles}
        check("CUSTOMER" in names and not names & PRIVILEGED, "Synthetic registration gained an unauthorized role")

    def register(self, label):
        user = {"username": "dv47a-" + self.run_id + "-" + label,
                "password": generate_test_password(32),
                "verifier": secrets.token_urlsafe(48), "state": secrets.token_urlsafe(24)}
        user["email"] = user["username"] + "@devarom.test"
        self.users.append(user)
        user["session"] = Session(self.args.keycloak_url)
        page = user["session"].get(self.authorization_url(user["verifier"], user["state"], registration=True))
        fields = {"username": user["username"], "email": user["email"], "firstName": "PhaseA",
                  "lastName": "Synthetic", "password": user["password"], "password-confirm": user["password"],
                  "roles": "ADMIN", "realmRoles": "ADMIN", "user.attributes.roles": "ADMIN"}
        page = user["session"].form(page, "kc-register-form", fields)
        check("code" not in urllib.parse.parse_qs(urllib.parse.urlsplit(page.url).query),
              "Registration bypassed the verification gate")
        check("css/devarom.css" in page.body and 'class="devarom-brand-title">SwiftDrop' in page.body,
              "Native verification page is missing SwiftDrop branding")
        check(self.user_record(user).get("emailVerified") is False, "New registration was unexpectedly verified")
        self.assert_roles(user)
        user["pending_page"] = page
        user["link"] = self.wait_for_email(user)
        user["expires"] = decode_claims(urllib.parse.parse_qs(urllib.parse.urlsplit(user["link"]).query)["key"][0])["exp"]
        report("PASS: synthetic registration, real Mailpit message, gate and role-injection rejection")
        return user

    def messages(self, user):
        url = self.args.mailpit_url + "/api/v1/search?" + urllib.parse.urlencode({"query": "to:" + user["email"]})
        response = self.http.request(url)
        check(response.status == 200, "Mailpit search failed")
        messages = json.loads(response.body).get("messages", [])
        ids = [message["ID"] for message in messages]
        self.message_ids.update(ids)
        return ids

    def wait_for_email(self, user, minimum=1):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            ids = self.messages(user)
            if len(ids) >= minimum:
                response = self.http.request(self.args.mailpit_url + "/api/v1/message/" + ids[0])
                check(response.status == 200, "Mailpit message retrieval failed")
                message = json.loads(response.body)
                body = message.get("HTML", "") + "\n" + message.get("Text", "")
                check(user["password"] not in body, "Verification email leaked the synthetic password")
                links = [link["href"] for link in Page(body).links]
                links += re.findall(r'https?://[^\s<>"\']+', message.get("Text", ""))
                candidates = [link for link in links if link.startswith(self.realm_url + "/login-actions/action-token?")]
                check(bool(candidates), "Mailpit email did not contain a native verification link")
                return candidates[0]
            time.sleep(0.5)
        raise SmokeFailure("No verification email arrived in local Mailpit")

    def check_pending(self, user):
        session = user["session"].clone()
        response = session.get(self.authorization_url(secrets.token_urlsafe(48), secrets.token_urlsafe(24), prompt="none"))
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(response.url).query)
        check("code" not in params and params.get("error", [None])[0] in
              ("login_required", "interaction_required", "account_selection_required"),
              "Pending identity unexpectedly obtained a silent authentication code")
        check(self.user_record(user).get("emailVerified") is False, "Pending identity changed verification state")
        report("PASS: pending identity cannot silently obtain an authenticated code")

    def check_tampered(self, user):
        parts = urllib.parse.urlsplit(user["link"])
        query = urllib.parse.parse_qs(parts.query)
        token = query["key"][0]
        at = len(token) - 10
        query["key"] = [token[:at] + ("A" if token[at] != "A" else "B") + token[at + 1:]]
        link = urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(query, doseq=True)))
        response = Session(self.args.keycloak_url).get(link)
        check(response.status == 400 and "css/devarom.css" in response.body,
              "Forged verification link did not fail on the branded native error page")
        check(self.user_record(user).get("emailVerified") is False, "Forged link changed verification state")
        report("PASS: forged native link rejected without verifying the identity")

    def check_resend(self, user):
        links = [link["href"] for link in Page(user["pending_page"].body).links
                 if "/login-actions/required-action" in link["href"]]
        check(bool(links), "Native required-action resend link is missing")
        initial_count = len(self.messages(user))
        cooldown_page = user["session"].get(links[0])
        check(len(self.messages(user)) == initial_count, "Native resend cooldown failed to suppress immediate delivery")
        report("PASS: immediate resend is suppressed by the native cooldown; waiting 60 seconds")
        resend_links = [link["href"] for link in Page(cooldown_page.body).links
                        if "/login-actions/required-action" in link["href"]]
        check(bool(resend_links), "Refreshed native required-action resend link is missing")
        self.wait_until(time.time() + 61, "native resend cooldown")
        user["pending_page"] = user["session"].get(resend_links[0])
        user["link"] = self.wait_for_email(user, initial_count + 1)
        report("PASS: native resend delivers a new real email after the cooldown")

    def complete(self, user):
        session = user["session"]
        page = session.get(user["link"])
        # A fresh auth session may require native confirmation before setting the flag.
        if not self.user_record(user).get("emailVerified"):
            links = [link["href"] for link in Page(page.body).links if "/login-actions/action-token?" in link["href"]]
            check(bool(links), "Native verification confirmation link is missing")
            page = session.get(links[0])
        check(self.user_record(user).get("emailVerified") is True, "Native link did not verify its bound identity")
        forms = Page(page.body).forms
        if any(form.get("id") == "kc-passwd-update-form" for form in forms):
            page = session.form(page, "kc-passwd-update-form", {"password-new": user["password"],
                                "password-confirm": user["password"], "login": "Submit"})
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(page.url).query)
        check(params.get("state", [None])[0] == user["state"] and bool(params.get("code")),
              "Verified native flow did not return its bound PKCE authorization code")
        payload = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "devarom-frontend",
                    "code": params["code"][0], "redirect_uri": REDIRECT_URI, "code_verifier": user["verifier"]}).encode()
        response = session.request(self.realm_url + "/protocol/openid-connect/token", payload)
        check(response.status == 200, "PKCE authorization code exchange failed")
        user["tokens"] = json.loads(response.body)
        claims = decode_claims(user["tokens"]["access_token"])
        check(claims["sub"] == user["id"] and claims.get("email_verified") is True,
              "Authenticated token is not bound to the verified identity")
        check("CUSTOMER" in claims.get("roles", []) and not PRIVILEGED & set(claims.get("roles", [])),
              "Verified token gained unauthorized application roles")
        self.assert_roles(user)
        report("PASS: native link, deferred password, PKCE exchange, verified subject and CUSTOMER-only token")

    def check_wrong_account(self, authenticated, pending):
        response = authenticated["session"].get(pending["link"])
        check(response.status == 400 and "different user" in response.body.lower(),
              "Wrong-account SSO context did not receive the native rejection")
        check(self.user_record(pending).get("emailVerified") is False,
              "Wrong-account SSO context verified the pending identity")
        self.assert_roles(authenticated)
        report("PASS: wrong-account SSO context cannot verify the pending identity")

    def check_replay(self, verified, pending):
        response = verified["session"].get(verified["link"])
        check("code" not in urllib.parse.parse_qs(urllib.parse.urlsplit(response.url).query),
              "Repeated verification link unexpectedly issued a new authorization code")
        check(self.user_record(verified).get("emailVerified") is True
              and self.user_record(pending).get("emailVerified") is False,
              "Repeated link changed another identity's verification state")
        self.assert_roles(verified)
        report("PASS: repeated link preserves identity binding without a new login code")

    def check_profile_and_jwt(self, user):
        profiles = []
        for base_url in (self.args.gateway_url, self.args.accounts_url, self.args.gateway_url):
            response = self.http.request(base_url + "/api/v1/users/me", headers={
                "Authorization": "Bearer " + user["tokens"]["access_token"]})
            check(response.status == 200, "Verified JWT failed Gateway or Accounts profile access")
            profile = json.loads(response.body)
            check(profile.get("keycloakSub") == user["id"] and profile.get("email") == user["email"],
                  "Persisted Accounts profile is not bound to the verified identity")
            check("CUSTOMER" in profile.get("roles", []) and not PRIVILEGED & set(profile.get("roles", [])),
                  "Persisted profile reports unauthorized application roles")
            profiles.append(profile)
        check(len({profile["id"] for profile in profiles}) == 1, "Repeated profile access did not retain the persisted ID")
        for base_url in (self.args.gateway_url, self.args.accounts_url):
            response = self.http.request(base_url + "/api/v1/users/me", headers={"Authorization": "Bearer not.a.valid.jwt"})
            check(response.status == 401, "Gateway or Accounts accepted an invalid JWT")
        report("PASS: Gateway + Accounts verified profile, stable persisted ID, and independent invalid-JWT rejection")

    def check_logout(self, user):
        query = urllib.parse.urlencode({"id_token_hint": user["tokens"]["id_token"],
                                        "post_logout_redirect_uri": "http://localhost:5173/"})
        # Stop at the SPA redirect; no browser state is simulated or claimed here.
        response = user["session"].request(self.realm_url + "/protocol/openid-connect/logout?" + query)
        check(response.status in (302, 303) and response.headers.get("Location", "").startswith("http://localhost:5173/"),
              "Native logout failed to return to the approved SPA origin")
        page = user["session"].get(self.authorization_url(secrets.token_urlsafe(48), secrets.token_urlsafe(24), prompt="none"))
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(page.url).query)
        check("code" not in params and params.get("error", [None])[0] == "login_required",
              "Native logout did not clear the SSO session")
        report("PASS: native logout clears SSO and preserves the approved redirect")

    def wait_until(self, deadline, label):
        while time.time() < deadline:
            remaining = max(0, deadline - time.time())
            report("WAIT: " + label + "; " + str(int(remaining)) + " seconds remaining")
            time.sleep(min(30, remaining))

    def check_expired(self, user):
        self.wait_until(user["expires"] + 2, "native verification expiry")
        response = user["session"].get(user["link"])
        check(response.status == 400 and "css/devarom.css" in response.body,
              "Expired verification link did not fail on the native branded error page")
        check(self.user_record(user).get("emailVerified") is False, "Expired verification link verified the identity")
        report("PASS: naturally expired native link rejected after its unchanged300-second lifespan")

    def save(self):
        STATE_FILE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        users = [{key: user[key] for key in ("id", "username", "email", "password") if key in user}
                 for user in self.users]
        state = {"run_id": self.run_id, "users": users, "message_ids": sorted(self.message_ids)}
        descriptor = os.open(STATE_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.chmod(STATE_FILE, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump(state, handle)
        report("Synthetic identities retained; private browser handoff state saved in ignored identity .local directory")

    def cleanup(self):
        self.authenticate_admin()  # Admin access tokens can expire during the natural-expiry test.
        for user in self.users:
            check(user["username"].startswith("dv47a-" + self.run_id + "-")
                  and user["email"] == user["username"] + "@devarom.test", "Refusing cleanup of a non-test identity")
            records = self.admin("/users?" + urllib.parse.urlencode({"username": user["username"], "exact": "true"}))
            if not records:
                continue
            check(len(records) == 1 and records[0]["email"] == user["email"], "Cleanup identity binding did not match")
            self.admin("/users/" + records[0]["id"], method="DELETE")
            self.messages(user)
        if self.message_ids:
            response = self.http.request(self.args.mailpit_url + "/api/v1/messages", method="DELETE",
                        headers={"Content-Type": "application/json"}, data=json.dumps({"IDs": sorted(self.message_ids)}).encode())
            check(response.status in (200, 204), "Synthetic Mailpit message cleanup failed")
        report("CLEANUP: only this run's Keycloak identities and Mailpit messages removed")
        report("NOTE: synthetic Accounts profiles remain; no service deletion API exists")

    def run(self):
        self.authenticate_admin()
        self.assert_runtime_gate()
        if self.args.cleanup:
            state = json.loads(STATE_FILE.read_text())
            self.run_id, self.users, self.message_ids = state["run_id"], state["users"], set(state["message_ids"])
            self.cleanup()
            STATE_FILE.unlink()
            return
        try:
            expired = self.register("expiry") if self.args.include_expiry else None
            primary = self.register("primary")
            self.check_pending(primary)
            self.check_tampered(primary)
            self.check_resend(primary)
            self.complete(primary)
            pending = self.register("secondary")
            self.check_wrong_account(primary, pending)
            self.check_replay(primary, pending)
            self.check_profile_and_jwt(primary)
            self.complete(pending)
            self.check_logout(primary)
            if expired:
                self.check_expired(expired)
            else:
                report("NOT RUN: natural expiry test; enable --include-expiry for the unchanged300-second wait")
            report("HTTP SMOKE PASSED; React browser rendering and SMTP outage recovery require separate checks")
        finally:
            if self.args.keep:
                self.save()
            else:
                self.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name, default in (("keycloak", "http://localhost:8090"), ("mailpit", "http://localhost:8025"),
                          ("gateway", "http://localhost:8080"), ("accounts", "http://localhost:8081")):
        parser.add_argument("--" + name + "-url", default=default)
    parser.add_argument("--include-expiry", action="store_true", help="wait for natural300-second link expiry")
    parser.add_argument("--keep", action="store_true", help="retain only synthetic accounts for browser checks")
    parser.add_argument("--cleanup", action="store_true", help="clean up the last private --keep handoff")
    args = parser.parse_args()
    for name in ("keycloak", "mailpit", "gateway", "accounts"):
        value = getattr(args, name + "_url").rstrip("/")
        parsed = urllib.parse.urlsplit(value)
        check(parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1")
              and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment,
              "Smoke test endpoints must be local HTTP origins")
        setattr(args, name + "_url", value)
    check(not (args.keep and args.cleanup), "Choose either --keep or --cleanup")
    check(args.cleanup or not STATE_FILE.exists(), "Clean the previous retained synthetic run before starting another")
    Smoke(args).run()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        report("STOPPED: smoke test interrupted; inspect the private state before retrying")
        sys.exit(130)
    except SmokeFailure as error:
        report("FAIL: " + str(error))
        sys.exit(1)
    except Exception as error:
        # Never expose raw HTTP error bodies, response URLs, or locals in a traceback.
        report("FAIL: unexpected " + type(error).__name__ + "; raw details suppressed to protect credentials")
        sys.exit(1)
