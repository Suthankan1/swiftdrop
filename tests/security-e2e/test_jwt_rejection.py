"""
Devarom Platform — Security E2E Test Suite: L06 JWT Authentication Rejection (Task 018)
Proves that missing, malformed, untrusted, expired, and misconfigured JWTs are strictly
rejected with HTTP 401 at both Spring Cloud Gateway (8080) and Accounts Service (8081).
"""

from __future__ import annotations

import time
import urllib.request
import urllib.error
from typing import Dict, Any, Optional

from harness_common import (
    Session,
    SyntheticJwtBuilder,
    KeycloakAdminClient,
    OidcPkceClient,
    TestResult,
    SecurityTestReport,
    HarnessError,
)


def run_jwt_rejection_suite(
    gateway_url: str = "http://localhost:8080",
    accounts_url: str = "http://localhost:8081",
    keycloak_url: str = "http://localhost:8090",
    report: Optional[SecurityTestReport] = None,
) -> SecurityTestReport:
    rep = report or SecurityTestReport()
    jwt_builder = SyntheticJwtBuilder()
    admin_client = KeycloakAdminClient(keycloak_url)
    oidc_client = OidcPkceClient(keycloak_url)

    gateway_session = Session(gateway_url)
    accounts_session = Session(accounts_url)

    # 1. Obtain a positive control token using authentic interactive OIDC PKCE flow
    print("  [+] Provisioning temporary positive control customer...")
    pos_user = admin_client.create_disposable_user(username_prefix="dv-sec-pos", role="CUSTOMER")
    try:
        tokens = oidc_client.login(pos_user["username"], pos_user["password"])
        valid_access_token = tokens["access_token"]
    except Exception as err:
        admin_client.cleanup_users()
        raise HarnessError(f"Failed to acquire positive control token via OIDC PKCE: {err}") from err

    # Helper function to execute and assert
    def assert_request(
        test_id: str,
        name: str,
        category: str,
        service: str,
        url: str,
        headers: Dict[str, str],
        expected_status: int,
        identity_role: str,
        method: str = "GET",
        positive_control: str = "Valid Keycloak PKCE Token returns 200 OK",
        check_problem_detail: bool = True,
    ):
        session = gateway_session if service == "Gateway" else accounts_session
        resp = session.request(url, headers=headers, method=method)
        status_match = (resp.status == expected_status)
        detail = ""

        if check_problem_detail and expected_status in (401, 403):
            body = resp.json()
            is_problem = (body.get("status") == expected_status and "title" in body)
            if not is_problem:
                detail = f"Expected RFC 7807 ProblemDetail, got status={resp.status}, body={resp.body[:120]}"
                status_match = False

        if status_match:
            outcome = "PASS"
        else:
            outcome = "FAIL"
            detail = f"Expected HTTP {expected_status}, got HTTP {resp.status}"

        rep.record(TestResult(
            test_id=test_id,
            name=name,
            category=category,
            request_desc=f"{method} {url}",
            identity_role=identity_role,
            expected_status=expected_status,
            actual_status=resp.status,
            service=service,
            positive_control=positive_control,
            status=outcome,
            detail=detail,
        ))

    try:
        print("  [+] Executing Gateway (8080) JWT Rejection Tests...")

        # L06-GW-01: Missing token
        assert_request(
            "L06-GW-01", "Missing bearer token rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {}, 401, "Anonymous",
        )

        # L06-GW-02: Malformed token
        assert_request(
            "L06-GW-02", "Malformed bearer token rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": "Bearer not.a.valid.jwt"}, 401, "Malformed",
        )

        # L06-GW-03: Unsigned token
        unsigned_jwt = jwt_builder.build_jwt(unsigned=True)
        assert_request(
            "L06-GW-03", "Unsigned JWT (alg=none) rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {unsigned_jwt}"}, 401, "Unsigned",
        )

        # L06-GW-04: Untrusted signature
        untrusted_jwt = jwt_builder.build_jwt()
        assert_request(
            "L06-GW-04", "Untrusted key signature rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {untrusted_jwt}"}, 401, "Untrusted Signer",
        )

        # L06-GW-05: Wrong issuer
        wrong_iss_jwt = jwt_builder.build_jwt(issuer="http://attacker.evil.com/realms/evil")
        assert_request(
            "L06-GW-05", "Wrong issuer JWT rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {wrong_iss_jwt}"}, 401, "Foreign Issuer",
        )

        # L06-GW-06: Wrong audience
        wrong_aud_jwt = jwt_builder.build_jwt(audience="wrong-client-api")
        assert_request(
            "L06-GW-06", "Wrong audience JWT rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {wrong_aud_jwt}"}, 401, "Wrong Audience",
        )

        # L06-GW-07: Expired token
        now = int(time.time())
        expired_jwt = jwt_builder.build_jwt(issued_at=now - 600, expiration=now - 300)
        assert_request(
            "L06-GW-07", "Expired JWT rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {expired_jwt}"}, 401, "Expired",
        )

        # L06-GW-08: Not-yet-valid token
        future_jwt = jwt_builder.build_jwt(not_before=now + 600, expiration=now + 1200)
        assert_request(
            "L06-GW-08", "Not-yet-valid JWT (nbf in future) rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {future_jwt}"}, 401, "Not Yet Valid",
        )

        # L06-GW-09: Missing subject
        no_sub_jwt = jwt_builder.build_jwt(subject=None)
        assert_request(
            "L06-GW-09", "Missing subject JWT rejected", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {no_sub_jwt}"}, 401, "Missing Subject",
        )

        # L06-GW-10: Public health accessible without auth
        assert_request(
            "L06-GW-10", "Public actuator health accessible without auth", "Gateway", "Gateway",
            f"{gateway_url}/actuator/health", {}, 200, "Anonymous",
            check_problem_detail=False,
        )

        # L06-GW-11: Unmapped protected route requires auth before routing
        assert_request(
            "L06-GW-11", "Unmapped protected route requires auth", "Gateway", "Gateway",
            f"{gateway_url}/api/v1/unmapped-endpoint", {}, 401, "Anonymous",
        )

        # L06-GW-12: CORS preflight from allowed origin
        cors_headers = {
            "Origin": "http://localhost:5173",
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization,Content-Type",
        }
        cors_resp = gateway_session.request(f"{gateway_url}/api/v1/users/me", headers=cors_headers, method="OPTIONS")
        cors_allowed = cors_resp.status == 200 and cors_resp.headers.get("Access-Control-Allow-Origin") == "http://localhost:5173"
        rep.record(TestResult(
            test_id="L06-GW-12",
            name="CORS preflight permits approved frontend origin",
            category="Gateway",
            request_desc=f"OPTIONS {gateway_url}/api/v1/users/me",
            identity_role="Anonymous (CORS Preflight)",
            expected_status=200,
            actual_status=cors_resp.status,
            service="Gateway",
            positive_control="Origin http://localhost:5173 returns permissive headers",
            status="PASS" if cors_allowed else "FAIL",
            detail="" if cors_allowed else f"CORS headers missing or origin mismatch: {cors_resp.headers}",
        ))

        # L06-GW-13: CORS preflight from disallowed origin
        bad_cors_headers = {
            "Origin": "http://attacker.evil.com",
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization",
        }
        bad_cors_resp = gateway_session.request(f"{gateway_url}/api/v1/users/me", headers=bad_cors_headers, method="OPTIONS")
        bad_cors_rejected = "Access-Control-Allow-Origin" not in bad_cors_resp.headers
        rep.record(TestResult(
            test_id="L06-GW-13",
            name="CORS preflight rejects disallowed attacker origin",
            category="Gateway",
            request_desc=f"OPTIONS {gateway_url}/api/v1/users/me",
            identity_role="Anonymous (CORS Attacker)",
            expected_status=200,
            actual_status=bad_cors_resp.status,
            service="Gateway",
            positive_control="Disallowed origin does not receive Access-Control-Allow-Origin",
            status="PASS" if bad_cors_rejected else "FAIL",
            detail="" if bad_cors_rejected else f"Attacker origin permitted in header: {bad_cors_resp.headers.get('Access-Control-Allow-Origin')}",
        ))

        # L06-GW-14: Positive control through Gateway
        assert_request(
            "L06-GW-14", "Valid Keycloak PKCE token accepted through Gateway", "L06", "Gateway",
            f"{gateway_url}/api/v1/users/me", {"Authorization": f"Bearer {valid_access_token}"}, 200, "CUSTOMER",
            check_problem_detail=False,
        )

        print("  [+] Executing Direct Accounts Service (8081) JWT Rejection Tests...")

        # L06-ACC-01: Direct missing token
        assert_request(
            "L06-ACC-01", "Direct missing bearer token rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {}, 401, "Anonymous",
        )

        # L06-ACC-02: Direct malformed token
        assert_request(
            "L06-ACC-02", "Direct malformed bearer token rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": "Bearer malformed.jwt.token"}, 401, "Malformed",
        )

        # L06-ACC-03: Direct unsigned token
        assert_request(
            "L06-ACC-03", "Direct unsigned JWT (alg=none) rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {unsigned_jwt}"}, 401, "Unsigned",
        )

        # L06-ACC-04: Direct untrusted signature
        assert_request(
            "L06-ACC-04", "Direct untrusted key signature rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {untrusted_jwt}"}, 401, "Untrusted Signer",
        )

        # L06-ACC-05: Direct wrong issuer
        assert_request(
            "L06-ACC-05", "Direct wrong issuer JWT rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {wrong_iss_jwt}"}, 401, "Foreign Issuer",
        )

        # L06-ACC-06: Direct wrong audience
        assert_request(
            "L06-ACC-06", "Direct wrong audience JWT rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {wrong_aud_jwt}"}, 401, "Wrong Audience",
        )

        # L06-ACC-07: Direct expired token
        assert_request(
            "L06-ACC-07", "Direct expired JWT rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {expired_jwt}"}, 401, "Expired",
        )

        # L06-ACC-08: Direct not-yet-valid token
        assert_request(
            "L06-ACC-08", "Direct not-yet-valid JWT rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {future_jwt}"}, 401, "Not Yet Valid",
        )

        # L06-ACC-09: Direct missing subject
        assert_request(
            "L06-ACC-09", "Direct missing subject JWT rejected", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {no_sub_jwt}"}, 401, "Missing Subject",
        )

        # L06-ACC-10: Basic auth disabled
        basic_resp = accounts_session.request(
            f"{accounts_url}/api/v1/users/me",
            headers={"Authorization": "Basic dGVzdDp0ZXN0"},
        )
        basic_ok = (basic_resp.status == 401 and "WWW-Authenticate" not in basic_resp.headers)
        rep.record(TestResult(
            test_id="L06-ACC-10",
            name="Direct Basic Auth disabled (no WWW-Authenticate challenge)",
            category="L06",
            request_desc=f"GET {accounts_url}/api/v1/users/me (Basic)",
            identity_role="Anonymous (Basic)",
            expected_status=401,
            actual_status=basic_resp.status,
            service="Accounts",
            positive_control="Basic authentication fails and never prompts browser modal",
            status="PASS" if basic_ok else "FAIL",
            detail="" if basic_ok else f"Unexpected Basic header: {basic_resp.headers.get('WWW-Authenticate')}",
        ))

        # L06-ACC-11: Unexposed Actuator routes rejected
        assert_request(
            "L06-ACC-11", "Unexposed actuator (/actuator/env) rejected", "Accounts", "Accounts",
            f"{accounts_url}/actuator/env", {}, 401, "Anonymous",
        )

        # L06-ACC-12: Public actuator health accessible
        assert_request(
            "L06-ACC-12", "Public actuator health accessible without auth", "Accounts", "Accounts",
            f"{accounts_url}/actuator/health", {}, 200, "Anonymous",
            check_problem_detail=False,
        )

        # L06-ACC-13: Direct positive control on Accounts Service
        assert_request(
            "L06-ACC-13", "Direct valid Keycloak PKCE token accepted by Accounts", "L06", "Accounts",
            f"{accounts_url}/api/v1/users/me", {"Authorization": f"Bearer {valid_access_token}"}, 200, "CUSTOMER",
            check_problem_detail=False,
        )

    finally:
        admin_client.cleanup_users()

    return rep


if __name__ == "__main__":
    report = run_jwt_rejection_suite()
    report.print_summary()
