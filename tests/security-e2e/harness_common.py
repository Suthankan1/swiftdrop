"""
SwiftDrop Platform — Security E2E Test Harness Common Infrastructure (Task 018)
Shared utilities for authentic Keycloak OIDC PKCE flows, synthetic JWT generation,
Keycloak admin management, and standardized security test matrix reporting.
"""

from __future__ import annotations

import base64
import html.parser
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
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
IDENTITY_DIR = REPO_ROOT / "platform" / "identity"
REDIRECT_URI = "http://localhost:5173/auth/callback"


class HarnessError(Exception):
    """Raised when an infrastructure or environment assertion fails."""
    pass


@dataclass
class Response:
    status: int
    headers: Dict[str, str]
    body: str
    url: str

    def json(self) -> Dict[str, Any]:
        try:
            return json.loads(self.body)
        except Exception:
            return {}


class FormParser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.forms: List[Dict[str, Any]] = []
        self._current_form: Optional[Dict[str, Any]] = None

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]):
        attr_map = {k.lower(): (v or "") for k, v in attrs}
        if tag.lower() == "form":
            self._current_form = {
                "action": attr_map.get("action", ""),
                "method": attr_map.get("method", "get").upper(),
                "id": attr_map.get("id", ""),
                "fields": {},
            }
            self.forms.append(self._current_form)
        elif tag.lower() == "input" and self._current_form is not None:
            name = attr_map.get("name")
            if name:
                self._current_form["fields"][name] = attr_map.get("value", "")

    def handle_endtag(self, tag: str):
        if tag.lower() == "form":
            self._current_form = None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        return True


class Session:
    """HTTP Client with cookie jar and redirect handling."""

    def __init__(self, origin: str):
        self.origin = origin.rstrip("/")
        self.cookies = http.cookiejar.CookieJar(policy=LoopbackCookiePolicy())
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies),
            NoRedirect(),
        )

    def request(self, url: str, data: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None, method: Optional[str] = None) -> Response:
        req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        try:
            resp = self.opener.open(req, timeout=15)
            status = resp.code
            resp_headers = {k: v for k, v in resp.headers.items()}
            body = resp.read().decode("utf-8", "replace")
            resp_url = resp.url
        except urllib.error.HTTPError as err:
            status = err.code
            resp_headers = {k: v for k, v in err.headers.items()}
            body = err.read().decode("utf-8", "replace")
            resp_url = err.url
        except Exception as err:
            raise HarnessError(f"HTTP connection failed to {url}: {err}") from err
        return Response(status, resp_headers, body, resp_url)

    def follow(self, response: Response, max_redirects: int = 15) -> Response:
        curr = response
        for _ in range(max_redirects):
            if curr.status not in (301, 302, 303, 307, 308):
                return curr
            target = urllib.parse.urljoin(curr.url, curr.headers.get("Location", ""))
            if target.startswith(REDIRECT_URI):
                return Response(curr.status, curr.headers, "", target)
            curr = self.request(target)
        raise HarnessError("Exceeded redirect limit")

    def get(self, url: str) -> Response:
        return self.follow(self.request(url))

    def post(self, url: str, data: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None) -> Response:
        return self.follow(self.request(url, data=data, headers=headers, method="POST"))

    def submit_form(self, page: Response, form_id: str, fields: Dict[str, str]) -> Response:
        parser = FormParser()
        parser.feed(page.body)
        target_forms = [f for f in parser.forms if f.get("id") == form_id]
        if not target_forms:
            raise HarnessError(f"Form '{form_id}' not found in response from {page.url}")
        form = target_forms[0]
        action = urllib.parse.urljoin(page.url, form["action"])
        payload_dict = dict(form["fields"])
        payload_dict.update(fields)
        encoded_data = urllib.parse.urlencode(payload_dict).encode("utf-8")
        return self.follow(self.request(action, data=encoded_data, headers={"Content-Type": "application/x-www-form-urlencoded"}, method=form["method"]))


def generate_secure_password(length: int = 16) -> str:
    """Generate a random test password satisfying Keycloak policy without static literals."""
    special = "!@#$%^&*"
    chars = [
        secrets.choice(string.ascii_uppercase),
        secrets.choice(string.ascii_lowercase),
        secrets.choice(string.digits),
        secrets.choice(special),
    ]
    all_chars = string.ascii_letters + string.digits + special
    chars.extend(secrets.choice(all_chars) for _ in range(length - len(chars)))
    secrets.SystemRandom().shuffle(chars)
    return "".join(chars)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


class SyntheticJwtBuilder:
    """Constructs arbitrary RS256 signed or unsigned JWTs for negative security testing."""

    def __init__(self):
        self.untrusted_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def build_jwt(
        self,
        issuer: Optional[str] = "http://localhost:8090/realms/devarom",
        audience: Optional[str] = "devarom-api",
        subject: Optional[str] = "synthetic-sub",
        roles: Optional[List[str]] = None,
        issued_at: Optional[int] = None,
        not_before: Optional[int] = None,
        expiration: Optional[int] = None,
        signing_key: Optional[rsa.RSAPrivateKey] = None,
        custom_claims: Optional[Dict[str, Any]] = None,
        unsigned: bool = False,
        kid: str = "untrusted-test-key",
    ) -> str:
        now = int(time.time())
        claims: Dict[str, Any] = {}
        if issuer is not None:
            claims["iss"] = issuer
        if audience is not None:
            claims["aud"] = audience
        if subject is not None:
            claims["sub"] = subject
        claims["iat"] = issued_at if issued_at is not None else (now - 60)
        claims["exp"] = expiration if expiration is not None else (now + 300)
        if not_before is not None:
            claims["nbf"] = not_before
        if roles is not None:
            claims["roles"] = roles
            claims["realm_access"] = {"roles": roles}
        if custom_claims:
            claims.update(custom_claims)

        header = {"alg": "none" if unsigned else "RS256", "typ": "JWT", "kid": kid}
        hdr_b64 = b64url(json.dumps(header).encode("utf-8"))
        claims_b64 = b64url(json.dumps(claims).encode("utf-8"))
        signing_input = f"{hdr_b64}.{claims_b64}".encode("ascii")

        if unsigned:
            return f"{hdr_b64}.{claims_b64}."

        key = signing_key or self.untrusted_key
        signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
        sig_b64 = b64url(signature)
        return f"{hdr_b64}.{claims_b64}.{sig_b64}"


class KeycloakAdminClient:
    """Keycloak Admin REST API client for synthetic identity provisioning and verification."""

    def __init__(self, keycloak_url: str = "http://localhost:8090"):
        self.keycloak_url = keycloak_url.rstrip("/")
        self.realm_admin_url = f"{self.keycloak_url}/admin/realms/devarom"
        self.http = Session(self.keycloak_url)
        self.admin_token: Optional[str] = None
        self.disposable_users: List[Dict[str, Any]] = []

    def authenticate_admin(self):
        env_vars: Dict[str, str] = {}
        env_path = IDENTITY_DIR / ".env"
        if env_path.exists():
            for line in env_path.read_text().splitlines():
                m = re.match(r"^(?:export\s+)?([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
                if m:
                    val = m[2].strip()
                    if len(val) >= 2 and val[0] == val[-1] and val[0] in ("'", '"'):
                        val = val[1:-1]
                    env_vars[m[1]] = val
        username = os.environ.get("KC_BOOTSTRAP_ADMIN_USERNAME", env_vars.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"))
        password = os.environ.get("KC_BOOTSTRAP_ADMIN_PASSWORD", env_vars.get("KC_BOOTSTRAP_ADMIN_PASSWORD", ""))
        if not password:
            raise HarnessError("Keycloak admin password not found in local environment or platform/identity/.env")

        payload = urllib.parse.urlencode({
            "client_id": "admin-cli",
            "grant_type": "password",
            "username": username,
            "password": password,
        }).encode("utf-8")
        resp = self.http.request(
            f"{self.keycloak_url}/realms/master/protocol/openid-connect/token",
            data=payload,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        if resp.status != 200:
            raise HarnessError(f"Admin authentication failed: HTTP {resp.status} - {resp.body}")
        self.admin_token = json.loads(resp.body)["access_token"]

    def request(self, path: str, method: str = "GET", data: Optional[Dict[str, Any]] = None) -> Tuple[int, Any]:
        if not self.admin_token:
            self.authenticate_admin()
        headers = {"Authorization": f"Bearer {self.admin_token}"}
        encoded = None
        if data is not None:
            headers["Content-Type"] = "application/json"
            encoded = json.dumps(data).encode("utf-8")
        resp = self.http.request(f"{self.realm_admin_url}{path}", data=encoded, headers=headers, method=method)
        if resp.status == 401:
            self.authenticate_admin()
            headers["Authorization"] = f"Bearer {self.admin_token}"
            resp = self.http.request(f"{self.realm_admin_url}{path}", data=encoded, headers=headers, method=method)
        parsed_body = resp.json() if resp.body else None
        return resp.status, parsed_body

    def create_disposable_user(
        self,
        username_prefix: str = "dv-sec",
        role: str = "CUSTOMER",
        email_verified: bool = True,
        first_name: str = "Synthetic",
        last_name: str = "TestUser",
    ) -> Dict[str, Any]:
        username = f"{username_prefix}-{secrets.token_hex(4)}"
        email = f"{username}@devarom.test"
        password = generate_secure_password()
        payload = {
            "username": username,
            "email": email,
            "enabled": True,
            "emailVerified": email_verified,
            "firstName": first_name,
            "lastName": last_name,
            "credentials": [{"type": "password", "value": password, "temporary": False}],
            "requiredActions": [],
        }
        status, _ = self.request("/users", method="POST", data=payload)
        if status not in (200, 201):
            raise HarnessError(f"Failed to create disposable user '{username}': HTTP {status}")

        status, users = self.request(f"/users?username={username}&exact=true")
        if status != 200 or not users:
            raise HarnessError(f"Failed to retrieve created user '{username}'")
        user_id = users[0]["id"]

        # Assign specified realm role if not default CUSTOMER
        if role and role != "CUSTOMER":
            # fetch role representation
            st, realm_role = self.request(f"/roles/{role}")
            if st == 200 and realm_role:
                self.request(f"/users/{user_id}/role-mappings/realm", method="POST", data=[realm_role])

        user_info = {
            "id": user_id,
            "username": username,
            "email": email,
            "password": password,
            "role": role,
            "fullName": f"{first_name} {last_name}",
        }
        self.disposable_users.append(user_info)
        return user_info

    def cleanup_users(self):
        for u in self.disposable_users:
            try:
                self.request(f"/users/{u['id']}", method="DELETE")
            except Exception:
                pass
        self.disposable_users.clear()


class OidcPkceClient:
    """Performs authentic interactive Authorization Code + S256 PKCE authentication."""

    def __init__(self, keycloak_url: str = "http://localhost:8090"):
        self.keycloak_url = keycloak_url.rstrip("/")
        self.realm_url = f"{self.keycloak_url}/realms/devarom"

    def login(self, username: str, password: str) -> Dict[str, Any]:
        session = Session(self.keycloak_url)
        verifier = secrets.token_urlsafe(48)
        challenge = b64url(hashes.Hash(hashes.SHA256()).finalize() if False else \
            (lambda h: (h.update(verifier.encode("ascii")), h.finalize())[1])(hashes.Hash(hashes.SHA256())))
        state = secrets.token_urlsafe(16)
        nonce = secrets.token_urlsafe(16)

        auth_url = (
            f"{self.realm_url}/protocol/openid-connect/auth?"
            + urllib.parse.urlencode({
                "client_id": "devarom-frontend",
                "response_type": "code",
                "scope": "openid email profile",
                "redirect_uri": REDIRECT_URI,
                "state": state,
                "nonce": nonce,
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            })
        )
        login_page = session.get(auth_url)
        if login_page.status != 200:
            raise HarnessError(f"Keycloak login page failed: HTTP {login_page.status}")

        submit_resp = session.submit_form(
            login_page,
            "kc-form-login",
            {"username": username, "password": password},
        )
        target_url = submit_resp.url
        params = urllib.parse.parse_qs(urllib.parse.urlsplit(target_url).query)
        if "code" not in params:
            raise HarnessError(f"Login failed to return authorization code. Current URL: {target_url}")

        code = params["code"][0]
        token_payload = urllib.parse.urlencode({
            "grant_type": "authorization_code",
            "client_id": "devarom-frontend",
            "code": code,
            "redirect_uri": REDIRECT_URI,
            "code_verifier": verifier,
        }).encode("utf-8")

        token_resp = session.request(
            f"{self.realm_url}/protocol/openid-connect/token",
            data=token_payload,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        if token_resp.status != 200:
            raise HarnessError(f"PKCE token exchange failed: HTTP {token_resp.status} - {token_resp.body}")

        tokens = json.loads(token_resp.body)
        return tokens


@dataclass
class TestResult:
    test_id: str
    name: str
    category: str  # L06, L07, L08, Gateway, Regression
    request_desc: str
    identity_role: str
    expected_status: int
    actual_status: int
    service: str  # Gateway, Accounts, Keycloak
    positive_control: str
    status: str  # PASS, FAIL, BLOCKED
    detail: str = ""


class SecurityTestReport:
    """Collects test results and formats matrices for markdown/console output."""

    def __init__(self):
        self.results: List[TestResult] = []

    def record(self, result: TestResult):
        self.results.append(result)

    def print_summary(self):
        passed = sum(1 for r in self.results if r.status == "PASS")
        failed = sum(1 for r in self.results if r.status == "FAIL")
        blocked = sum(1 for r in self.results if r.status == "BLOCKED")
        total = len(self.results)

        print("\n" + "=" * 95)
        print(" SWIFTDROP SECURITY E2E TEST EXECUTION MATRIX")
        print("=" * 95)
        print(f"{'ID':<6} {'Category':<8} {'Service':<10} {'Exp':<5} {'Act':<5} {'Status':<8} {'Test Description'}")
        print("-" * 95)
        for r in self.results:
            status_str = f"\033[92m{r.status}\033[0m" if r.status == "PASS" else (
                f"\033[93m{r.status}\033[0m" if r.status == "BLOCKED" else f"\033[91m{r.status}\033[0m"
            )
            print(f"{r.test_id:<6} {r.category:<8} {r.service:<10} {r.expected_status:<5} {r.actual_status:<5} {status_str:<18} {r.name}")
            if r.detail:
                print(f"       -> {r.detail}")

        print("=" * 95)
        print(f"TOTAL: {total} | PASSED: {passed} | FAILED: {failed} | BLOCKED: {blocked}")
        print("=" * 95 + "\n")
