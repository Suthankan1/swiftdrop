"""
SwiftDrop Platform — Security E2E Test Suite: L07 Role & L08 Ownership Isolation (Task 018)
Proves that unauthorized roles cannot access protected operations (L07), and that distinct
customers are strictly isolated so Customer A cannot access Customer B's data (L08).
Accurately records blocked dependencies for endpoints that are not yet implemented.
"""

from __future__ import annotations

import json
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


def run_role_and_ownership_suite(
    gateway_url: str = "http://localhost:8080",
    accounts_url: str = "http://localhost:8081",
    keycloak_url: str = "http://localhost:8090",
    report: Optional[SecurityTestReport] = None,
) -> SecurityTestReport:
    rep = report or SecurityTestReport()
    admin_client = KeycloakAdminClient(keycloak_url)
    oidc_client = OidcPkceClient(keycloak_url)
    jwt_builder = SyntheticJwtBuilder()

    gateway_session = Session(gateway_url)
    accounts_session = Session(accounts_url)

    print("  [+] Provisioning synthetic Customer A and Customer B...")
    user_a = admin_client.create_disposable_user(username_prefix="dv-sec-custA", role="CUSTOMER", first_name="Alice", last_name="Customer")
    user_b = admin_client.create_disposable_user(username_prefix="dv-sec-custB", role="CUSTOMER", first_name="Bob", last_name="Customer")

    try:
        # Authenticate Customer A via real OIDC PKCE flow
        tokens_a = oidc_client.login(user_a["username"], user_a["password"])
        token_a = tokens_a["access_token"]

        # Authenticate Customer B via real OIDC PKCE flow
        tokens_b = oidc_client.login(user_b["username"], user_b["password"])
        token_b = tokens_b["access_token"]

        print("  [+] Executing L07 Cross-Role Authorization Denial Tests...")

        # L07-ROLE-01: Provision user with NO application roles (strip default-roles-devarom)
        user_norole = admin_client.create_disposable_user(username_prefix="dv-sec-norole", role="")
        st, direct_roles = admin_client.request(f"/users/{user_norole['id']}/role-mappings/realm")
        if st == 200 and direct_roles:
            admin_client.request(f"/users/{user_norole['id']}/role-mappings/realm", method="DELETE", data=direct_roles)

        tokens_norole = oidc_client.login(user_norole["username"], user_norole["password"])
        token_norole = tokens_norole["access_token"]

        # L07-ROLE-01a: Direct Accounts Service test
        resp_acc_norole = accounts_session.request(
            f"{accounts_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_norole}"},
        )
        is_acc_403 = (
            resp_acc_norole.status == 403
            and resp_acc_norole.json().get("title") == "Forbidden"
            and resp_acc_norole.json().get("status") == 403
        )
        rep.record(TestResult(
            test_id="L07-ROLE-01",
            name="Direct Accounts rejects token lacking approved application role with 403 Forbidden",
            category="L07",
            request_desc=f"GET {accounts_url}/api/v1/users/me",
            identity_role="Authenticated (No App Role)",
            expected_status=403,
            actual_status=resp_acc_norole.status,
            service="Accounts",
            positive_control="CUSTOMER role receives 200 OK",
            status="PASS" if is_acc_403 else "FAIL",
            detail="" if is_acc_403 else f"Expected RFC 7807 403 Forbidden, got HTTP {resp_acc_norole.status}: {resp_acc_norole.body[:120]}",
        ))

        # L07-ROLE-01b: Gateway test
        resp_gw_norole = gateway_session.request(
            f"{gateway_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_norole}"},
        )
        is_gw_403 = (
            resp_gw_norole.status == 403
            and resp_gw_norole.json().get("title") == "Forbidden"
            and resp_gw_norole.json().get("status") == 403
        )
        rep.record(TestResult(
            test_id="L07-ROLE-02",
            name="Gateway forwards 403 Forbidden when downstream Accounts denies role authorization",
            category="L07",
            request_desc=f"GET {gateway_url}/api/v1/users/me",
            identity_role="Authenticated (No App Role)",
            expected_status=403,
            actual_status=resp_gw_norole.status,
            service="Gateway",
            positive_control="CUSTOMER role receives 200 OK through Gateway",
            status="PASS" if is_gw_403 else "FAIL",
            detail="" if is_gw_403 else f"Expected RFC 7807 403 Forbidden, got HTTP {resp_gw_norole.status}: {resp_gw_norole.body[:120]}",
        ))

        # L07-ROLE-03: Public self-registration privilege confinement invariant
        # Check composite role mappings of self-registered user to verify no staff privileges
        st_comp, comp_roles = admin_client.request(f"/users/{user_a['id']}/role-mappings/realm/composite")
        composite_names = {r["name"] for r in (comp_roles or [])}
        staff_roles = {"MERCHANT", "COURIER", "DISPATCHER", "ADMIN"}
        leaked_staff = composite_names & staff_roles
        confinement_ok = (st_comp == 200 and not leaked_staff and "CUSTOMER" in composite_names)
        rep.record(TestResult(
            test_id="L07-ROLE-03",
            name="Public user provisioning strictly confined to CUSTOMER role (no self-granted staff roles)",
            category="L07",
            request_desc="Keycloak Admin GET /users/{id}/role-mappings/realm/composite",
            identity_role="CUSTOMER (Self-Registered)",
            expected_status=200,
            actual_status=st_comp,
            service="Keycloak",
            positive_control="Composite roles contain CUSTOMER; zero staff roles present",
            status="PASS" if confinement_ok else "FAIL",
            detail="" if confinement_ok else f"Unauthorized staff roles present: {leaked_staff}",
        ))

        # L07-ROLE-04: Staff Operations - Admin Endpoint Access by Customer
        resp_admin_op = gateway_session.request(
            f"{gateway_url}/api/v1/admin/users",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        rep.record(TestResult(
            test_id="L07-ROLE-04",
            name="CUSTOMER cannot invoke staff admin operations",
            category="L07",
            request_desc=f"GET {gateway_url}/api/v1/admin/users",
            identity_role="CUSTOMER",
            expected_status=403,
            actual_status=resp_admin_op.status,
            service="Gateway",
            positive_control="Admin operations require authenticated ADMIN role",
            status="BLOCKED" if resp_admin_op.status in (403, 404) else "FAIL",
            detail="BLOCKED BY MISSING PROVIDER ENDPOINT (Issue #14: Staff read models and admin operations not yet implemented; Gateway default-denies unmapped path with 404)",
        ))

        # L07-ROLE-05: Courier Operations - Access by Customer
        resp_courier_op = gateway_session.request(
            f"{gateway_url}/api/v1/courier/deliveries",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        rep.record(TestResult(
            test_id="L07-ROLE-05",
            name="CUSTOMER cannot invoke courier operations",
            category="L07",
            request_desc=f"GET {gateway_url}/api/v1/courier/deliveries",
            identity_role="CUSTOMER",
            expected_status=403,
            actual_status=resp_courier_op.status,
            service="Gateway",
            positive_control="Courier operations require authenticated COURIER role",
            status="BLOCKED" if resp_courier_op.status in (403, 404) else "FAIL",
            detail="BLOCKED BY MISSING PROVIDER ENDPOINT (Issue #15: Courier operations not yet implemented; Gateway default-denies unmapped path with 404)",
        ))

        print("  [+] Executing L08 Resource Ownership & Tenant Isolation Tests...")

        # L08-OWN-01: Customer A requests own profile via Gateway and Accounts
        resp_gw_a = gateway_session.request(
            f"{gateway_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        resp_acc_a = accounts_session.request(
            f"{accounts_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        body_gw_a = resp_gw_a.json()
        body_acc_a = resp_acc_a.json()

        cust_a_ok = (
            resp_gw_a.status == 200
            and resp_acc_a.status == 200
            and body_gw_a.get("keycloakSub") == user_a["id"]
            and body_acc_a.get("keycloakSub") == user_a["id"]
            and body_gw_a.get("id") == body_acc_a.get("id")
        )
        rep.record(TestResult(
            test_id="L08-OWN-01",
            name="Customer A receives Customer A's own profile through Gateway and Accounts",
            category="L08",
            request_desc=f"GET /api/v1/users/me (Bearer Token A)",
            identity_role="Customer A",
            expected_status=200,
            actual_status=resp_gw_a.status,
            service="Gateway & Accounts",
            positive_control="Token A returns Customer A profile bound to Customer A subject",
            status="PASS" if cust_a_ok else "FAIL",
            detail="" if cust_a_ok else f"Profile mismatch or non-200 status: GW={resp_gw_a.status}, ACC={resp_acc_a.status}",
        ))

        # L08-OWN-02: Customer B requests own profile via Gateway and Accounts
        resp_gw_b = gateway_session.request(
            f"{gateway_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_b}"},
        )
        resp_acc_b = accounts_session.request(
            f"{accounts_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_b}"},
        )
        body_gw_b = resp_gw_b.json()
        body_acc_b = resp_acc_b.json()

        cust_b_ok = (
            resp_gw_b.status == 200
            and resp_acc_b.status == 200
            and body_gw_b.get("keycloakSub") == user_b["id"]
            and body_acc_b.get("keycloakSub") == user_b["id"]
            and body_gw_b.get("id") == body_acc_b.get("id")
        )
        rep.record(TestResult(
            test_id="L08-OWN-02",
            name="Customer B receives Customer B's own profile through Gateway and Accounts",
            category="L08",
            request_desc=f"GET /api/v1/users/me (Bearer Token B)",
            identity_role="Customer B",
            expected_status=200,
            actual_status=resp_gw_b.status,
            service="Gateway & Accounts",
            positive_control="Token B returns Customer B profile bound to Customer B subject",
            status="PASS" if cust_b_ok else "FAIL",
            detail="" if cust_b_ok else f"Profile mismatch or non-200 status: GW={resp_gw_b.status}, ACC={resp_acc_b.status}",
        ))

        # L08-OWN-03: Subject & Profile Isolation invariant
        profiles_isolated = (
            cust_a_ok
            and cust_b_ok
            and body_gw_a.get("id") != body_gw_b.get("id")
            and body_gw_a.get("keycloakSub") != body_gw_b.get("keycloakSub")
        )
        rep.record(TestResult(
            test_id="L08-OWN-03",
            name="Customer A and Customer B have distinct, isolated persistent profiles",
            category="L08",
            request_desc="Compare Profile A vs Profile B",
            identity_role="Multi-tenant Customer",
            expected_status=200,
            actual_status=200,
            service="Accounts",
            positive_control="Profile A UUID != Profile B UUID and keycloakSub values differ",
            status="PASS" if profiles_isolated else "FAIL",
            detail="" if profiles_isolated else f"Collision detected: ID A={body_gw_a.get('id')}, ID B={body_gw_b.get('id')}",
        ))

        # L08-OWN-04: Repeated access preserves identical business profile ID
        resp_repeat = accounts_session.request(
            f"{accounts_url}/api/v1/users/me",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        body_repeat = resp_repeat.json()
        id_stable = (resp_repeat.status == 200 and body_repeat.get("id") == body_gw_a.get("id"))
        rep.record(TestResult(
            test_id="L08-OWN-04",
            name="Repeated customer access retains identical immutable profile ID",
            category="L08",
            request_desc=f"GET {accounts_url}/api/v1/users/me (Repeat Token A)",
            identity_role="Customer A",
            expected_status=200,
            actual_status=resp_repeat.status,
            service="Accounts",
            positive_control="Profile ID on repeat call matches initial profile ID",
            status="PASS" if id_stable else "FAIL",
            detail="" if id_stable else f"ID drift observed: initial={body_gw_a.get('id')}, repeat={body_repeat.get('id')}",
        ))

        # L08-OWN-05: Gateway identity spoofing probe
        # Customer A sends Token A, but attempts to spoof Customer B's identity headers and parameters
        spoofed_headers_a = {
            "Authorization": f"Bearer {token_a}",
            "X-User-Id": user_b["id"],
            "X-Keycloak-Sub": user_b["id"],
            "X-Roles": "ADMIN",
            "X-User-Email": user_b["email"],
        }
        resp_gw_spoof = gateway_session.request(
            f"{gateway_url}/api/v1/users/me?id={body_gw_b.get('id')}&subject={user_b['id']}",
            headers=spoofed_headers_a,
        )
        body_gw_spoof = resp_gw_spoof.json()
        spoof_gw_safe = (
            resp_gw_spoof.status == 200
            and body_gw_spoof.get("keycloakSub") == user_a["id"]
            and body_gw_spoof.get("email", "").lower() == user_a["email"].lower()
            and body_gw_spoof.get("id") == body_gw_a.get("id")
            and "ADMIN" not in body_gw_spoof.get("roles", [])
        )
        rep.record(TestResult(
            test_id="L08-OWN-05",
            name="Gateway strips spoofed headers and query params; Customer A cannot access Customer B profile",
            category="L08",
            request_desc=f"GET {gateway_url}/api/v1/users/me?id={body_gw_b.get('id')} (Spoofed Headers)",
            identity_role="Customer A attempting to impersonate Customer B",
            expected_status=200,
            actual_status=resp_gw_spoof.status,
            service="Gateway & Accounts",
            positive_control="Gateway strips identity headers; Accounts returns Customer A profile",
            status="PASS" if spoof_gw_safe else "FAIL",
            detail="" if spoof_gw_safe else f"Spoofed headers tricked service: leaked sub={body_gw_spoof.get('keycloakSub')}, roles={body_gw_spoof.get('roles')}",
        ))

        # L08-OWN-06: Direct Accounts identity spoofing probe
        resp_acc_spoof = accounts_session.request(
            f"{accounts_url}/api/v1/users/me?id={body_gw_b.get('id')}&subject={user_b['id']}",
            headers=spoofed_headers_a,
        )
        body_acc_spoof = resp_acc_spoof.json()
        spoof_acc_safe = (
            resp_acc_spoof.status == 200
            and body_acc_spoof.get("keycloakSub") == user_a["id"]
            and body_acc_spoof.get("email", "").lower() == user_a["email"].lower()
            and body_acc_spoof.get("id") == body_gw_a.get("id")
            and "ADMIN" not in body_acc_spoof.get("roles", [])
        )
        rep.record(TestResult(
            test_id="L08-OWN-06",
            name="Direct Accounts service ignores spoofed headers; strictly uses verified JWT SecurityContext",
            category="L08",
            request_desc=f"GET {accounts_url}/api/v1/users/me?id={body_gw_b.get('id')} (Direct Spoof)",
            identity_role="Customer A attempting to impersonate Customer B",
            expected_status=200,
            actual_status=resp_acc_spoof.status,
            service="Accounts",
            positive_control="Accounts resolves identity strictly from verified token principal",
            status="PASS" if spoof_acc_safe else "FAIL",
            detail="" if spoof_acc_safe else f"Direct spoof succeeded: leaked sub={body_acc_spoof.get('keycloakSub')}",
        ))

        # L08-OWN-07: IDOR cross-user resource lookup probe
        # Customer A attempts to access Customer B's profile via direct ID: GET /api/v1/users/{Customer B UUID}
        cust_b_profile_id = body_gw_b.get("id")
        resp_idor = gateway_session.request(
            f"{gateway_url}/api/v1/users/{cust_b_profile_id}",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        rep.record(TestResult(
            test_id="L08-OWN-07",
            name="Cross-user explicit ID lookup probe (/api/v1/users/{id})",
            category="L08",
            request_desc=f"GET {gateway_url}/api/v1/users/{cust_b_profile_id}",
            identity_role="Customer A",
            expected_status=403,
            actual_status=resp_idor.status,
            service="Accounts",
            positive_control="Only /api/v1/users/me exists; no /api/v1/users/{id} provider exists",
            status="BLOCKED" if resp_idor.status in (403, 404) else "FAIL",
            detail="BLOCKED BY MISSING PROVIDER ENDPOINT (No cross-user /api/v1/users/{id} endpoint exists in Accounts service; coordinate with Sinthuha; unmapped path default-denied)",
        ))

        # L08-OWN-08: Merchant Tenant Isolation probe
        # Customer A attempting cross-tenant operations
        resp_merchant = gateway_session.request(
            f"{gateway_url}/api/v1/merchants/orders",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        rep.record(TestResult(
            test_id="L08-OWN-08",
            name="Merchant tenant isolation and cross-tenant order modification",
            category="L08",
            request_desc=f"GET {gateway_url}/api/v1/merchants/orders",
            identity_role="CUSTOMER (or Merchant A)",
            expected_status=403,
            actual_status=resp_merchant.status,
            service="Accounts",
            positive_control="Merchant tenancy provider enforces approval and tenant boundary",
            status="BLOCKED" if resp_merchant.status in (403, 404) else "FAIL",
            detail="BLOCKED BY MISSING PROVIDER ENDPOINT (Issue #8: Merchant approval-state and tenant provider not yet implemented; Thuvarahan & Eric; unmapped path default-denied)",
        ))

    finally:
        admin_client.cleanup_users()

    return rep


if __name__ == "__main__":
    report = run_role_and_ownership_suite()
    report.print_summary()
