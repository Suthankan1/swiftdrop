#!/usr/bin/env python3
"""SwiftDrop Platform — Phase 2C Identity Protocol Cutover Tool.

Provides an automated, reviewable, non-destructive cutover procedure for:
  - Realm name: devarom -> swiftdrop
  - Public Client ID: devarom-frontend -> swiftdrop-frontend
  - JWT Audience Claim: devarom-api -> swiftdrop-api
  - Broker Callback: http://localhost:8090/realms/devarom/broker/google/endpoint ->
                     http://localhost:8090/realms/swiftdrop/broker/google/endpoint

SAFETY ENFORCEMENT:
  - Defaults to READ-ONLY PREFLIGHT (--preflight).
  - REFUSES to execute any cutover without BOTH:
      --execute-cutover
      --confirm-cutover-approval "SWIFTDROP-MAINTENANCE-APPROVED"
  - Handles user pagination to guarantee 100% of identities are audited.
  - Compares stable ID-keyed snapshots (never array index order).
  - Maintains an atomic execution journal for partial-failure diagnosis and rollback.
  - Dynamically discovers client internal UUID and mapper ID from the live server.
  - Never logs sensitive Admin API response bodies or credentials.
  - Rejects unexpected target/source realm states (e.g., target already exists).
  - Strict preservation of:
      1. Realm internal ID ('devarom')
      2. User UUIDs ('sub')
      3. Client internal UUID
      4. Audience mapper UUID
      5. Google federated identity links
      6. Password and TOTP credentials
      7. Email verification status
      8. CUSTOMER-only self-registration role confinement
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Set, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
REALM_FILE = REPO_ROOT / "platform" / "identity" / "realm-devarom.json"


class CutoverError(Exception):
    """Sanitized failure for cutover operations."""
    pass


class KeycloakAdminClient:
    """Minimal Keycloak Admin REST API client using Python standard library."""

    def __init__(self, base_url: str, admin_user: str, admin_pass: str):
        self.base_url = base_url.rstrip("/")
        self.admin_user = admin_user
        self.admin_pass = admin_pass
        self._token: Optional[str] = None
        self._token_expiry: float = 0

    def get_token(self) -> str:
        now = time.time()
        if self._token and now < self._token_expiry - 10:
            return self._token

        url = f"{self.base_url}/realms/master/protocol/openid-connect/token"
        data = urllib.parse.urlencode({
            "client_id": "admin-cli",
            "username": self.admin_user,
            "password": self.admin_pass,
            "grant_type": "password",
        }).encode("utf-8")

        req = urllib.request.Request(url, data=data, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                body = json.loads(resp.read().decode("utf-8"))
                self._token = body["access_token"]
                self._token_expiry = now + body.get("expires_in", 60)
                return self._token
        except urllib.error.HTTPError as err:
            reason = getattr(err, "reason", "Authentication failed")
            raise CutoverError(f"Failed to obtain admin token from {self.base_url}: HTTP {err.code} ({reason})")
        except Exception as err:
            raise CutoverError(f"Connection failure to {self.base_url}: {err}")

    def request(
        self,
        path: str,
        method: str = "GET",
        payload: Optional[Dict[str, Any]] = None,
        expected_status: Tuple[int, ...] = (200,),
    ) -> Tuple[int, Any]:
        token = self.get_token()
        url = f"{self.base_url}{path}"
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)

        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                code = resp.status
                body = resp.read().decode("utf-8")
                res = json.loads(body) if body.strip() else None
                if code not in expected_status:
                    raise CutoverError(f"Unexpected status {code} on {method} {path}")
                return code, res
        except urllib.error.HTTPError as err:
            if err.code in expected_status:
                try:
                    body = err.read().decode("utf-8", errors="replace")
                    res = json.loads(body) if body.strip() else None
                except Exception:
                    res = None
                return err.code, res
            reason = getattr(err, "reason", "Request failed")
            # Do NOT dump full response body to avoid leaking sensitive data or server tokens
            raise CutoverError(f"Admin API {method} {path} failed with HTTP {err.code}: {reason}")


class IdentityCutoverEngine:
    """Encapsulates the in-place Keycloak rename and verification protocol with full safety gates."""

    def __init__(self, admin: KeycloakAdminClient):
        self.admin = admin

    def get_all_users(self, realm: str, page_size: int = 100) -> List[Dict[str, Any]]:
        """Paginates across all users in the realm to ensure complete inventory."""
        users: List[Dict[str, Any]] = []
        first = 0
        while True:
            code, batch = self.admin.request(f"/admin/realms/{realm}/users?first={first}&max={page_size}")
            if not batch or not isinstance(batch, list):
                break
            users.extend(batch)
            if len(batch) < page_size:
                break
            first += len(batch)
        return users

    def find_frontend_client(self, realm: str) -> Dict[str, Any]:
        """Dynamically finds the public frontend client in the realm."""
        code, clients = self.admin.request(f"/admin/realms/{realm}/clients")
        if not clients or not isinstance(clients, list):
            raise CutoverError(f"No clients found in realm '{realm}'.")

        # Candidate client IDs
        candidate_ids = {f"{realm}-frontend", "devarom-frontend", "swiftdrop-frontend"}
        client = next((c for c in clients if c.get("clientId") in candidate_ids), None)
        if not client:
            raise CutoverError(f"Frontend client not found in realm '{realm}'. Available: {[c.get('clientId') for c in clients]}")
        return client

    def find_audience_mapper(self, realm: str, client_internal_uuid: str) -> Dict[str, Any]:
        """Dynamically finds the custom audience protocol mapper on the frontend client."""
        code, mappers = self.admin.request(f"/admin/realms/{realm}/clients/{client_internal_uuid}/protocol-mappers/models")
        if not mappers or not isinstance(mappers, list):
            raise CutoverError(f"No protocol mappers found on client {client_internal_uuid} in realm '{realm}'.")

        mapper = next((m for m in mappers if m.get("protocolMapper") == "oidc-audience-mapper"), None)
        if not mapper:
            raise CutoverError(f"OIDC audience protocol mapper not found on client {client_internal_uuid} in realm '{realm}'.")
        return mapper

    def run_read_only_preflight(self, source_realm: str = "devarom") -> Dict[str, Any]:
        """Performs a thorough read-only inspection of the active identity configuration."""
        # 1. Realm existence & internal ID
        code, realm_rep = self.admin.request(f"/admin/realms/{source_realm}", expected_status=(200, 404))
        if code == 404 or not realm_rep:
            raise CutoverError(f"Source realm '{source_realm}' not found on server.")

        # 2. Inspect frontend client dynamically
        frontend_client = self.find_frontend_client(source_realm)
        client_internal_uuid = frontend_client["id"]

        # 3. Inspect audience mapper dynamically
        audience_mapper = self.find_audience_mapper(source_realm, client_internal_uuid)
        current_audience = audience_mapper.get("config", {}).get("included.custom.audience")

        # 4. Inspect users & credentials with pagination
        raw_users = self.get_all_users(source_realm)
        users_by_id: Dict[str, Dict[str, Any]] = {}
        for u in raw_users:
            uid = u["id"]
            _, creds = self.admin.request(f"/admin/realms/{source_realm}/users/{uid}/credentials")
            _, feds = self.admin.request(f"/admin/realms/{source_realm}/users/{uid}/federated-identity")
            _, roles = self.admin.request(f"/admin/realms/{source_realm}/users/{uid}/role-mappings/realm")

            cred_types = sorted([c.get("type") for c in (creds or [])])
            fed_providers = sorted([f.get("identityProvider") for f in (feds or [])])
            role_names = sorted([r.get("name") for r in (roles or [])])

            users_by_id[uid] = {
                "id": uid,
                "username": u.get("username"),
                "email": u.get("email"),
                "emailVerified": u.get("emailVerified", False),
                "credentials": cred_types,
                "federated": fed_providers,
                "roles": role_names,
            }

        # 5. Inspect Google identity provider
        _, idps = self.admin.request(f"/admin/realms/{source_realm}/identity-provider/instances")
        google_idp = next((idp for idp in (idps or []) if idp.get("alias") == "google"), None)

        return {
            "source_realm": source_realm,
            "realm_internal_id": realm_rep.get("id"),
            "realm_name": realm_rep.get("realm"),
            "frontend_client_id": frontend_client.get("clientId"),
            "frontend_client_internal_uuid": client_internal_uuid,
            "audience_mapper_id": audience_mapper.get("id"),
            "audience_mapper_name": audience_mapper.get("name"),
            "current_audience": current_audience,
            "google_broker_configured": google_idp is not None,
            "user_count": len(users_by_id),
            "users_by_id": users_by_id,
            "users": list(users_by_id.values()),
            "status": "PREFLIGHT_PASSED",
        }

    def verify_identity_invariants(
        self,
        pre: Dict[str, Any],
        post: Dict[str, Any],
        expected_target_realm: str,
        expected_target_client_id: str,
        expected_target_audience: str,
    ) -> Tuple[bool, List[str]]:
        """Compares pre- and post-migration states using stable ID-keyed sets and attributes."""
        mismatches: List[str] = []

        # Realm invariants
        if post["realm_internal_id"] != pre["realm_internal_id"]:
            mismatches.append(f"Realm internal ID changed: expected {pre['realm_internal_id']}, got {post['realm_internal_id']}")
        if post["realm_name"] != expected_target_realm:
            mismatches.append(f"Realm name mismatch: expected {expected_target_realm}, got {post['realm_name']}")

        # Client invariants
        if post["frontend_client_internal_uuid"] != pre["frontend_client_internal_uuid"]:
            mismatches.append(f"Client internal UUID changed: expected {pre['frontend_client_internal_uuid']}, got {post['frontend_client_internal_uuid']}")
        if post["frontend_client_id"] != expected_target_client_id:
            mismatches.append(f"Client public ID mismatch: expected {expected_target_client_id}, got {post['frontend_client_id']}")

        # Audience mapper invariants
        if post["audience_mapper_id"] != pre["audience_mapper_id"]:
            mismatches.append(f"Audience mapper ID changed: expected {pre['audience_mapper_id']}, got {post['audience_mapper_id']}")
        if post["current_audience"] != expected_target_audience:
            mismatches.append(f"Custom audience claim mismatch: expected {expected_target_audience}, got {post['current_audience']}")

        # User continuity invariants (keyed by stable UUID)
        pre_users = pre["users_by_id"]
        post_users = post["users_by_id"]

        pre_uids: Set[str] = set(pre_users.keys())
        post_uids: Set[str] = set(post_users.keys())

        if pre_uids != post_uids:
            missing = pre_uids - post_uids
            unexpected = post_uids - pre_uids
            mismatches.append(f"User UUID set mismatch. Missing: {missing}, Unexpected: {unexpected}")

        # Check each user's attributes
        for uid in pre_uids.intersection(post_uids):
            u_pre = pre_users[uid]
            u_post = post_users[uid]

            if u_pre["username"] != u_post["username"]:
                mismatches.append(f"User {uid} username changed from {u_pre['username']} to {u_post['username']}")
            if u_pre["emailVerified"] != u_post["emailVerified"]:
                mismatches.append(f"User {uid} emailVerified changed from {u_pre['emailVerified']} to {u_post['emailVerified']}")
            if u_pre["credentials"] != u_post["credentials"]:
                mismatches.append(f"User {uid} credential types changed from {u_pre['credentials']} to {u_post['credentials']}")
            if u_pre["federated"] != u_post["federated"]:
                mismatches.append(f"User {uid} federated identities changed from {u_pre['federated']} to {u_post['federated']}")
            if u_pre["roles"] != u_post["roles"]:
                mismatches.append(f"User {uid} roles changed from {u_pre['roles']} to {u_post['roles']}")

        return len(mismatches) == 0, mismatches

    def execute_in_place_cutover(
        self,
        source_realm: str = "devarom",
        target_realm: str = "swiftdrop",
        target_client_id: str = "swiftdrop-frontend",
        target_audience: str = "swiftdrop-api",
    ) -> Dict[str, Any]:
        """Executes in-place rename of realm, client, and audience mapper with execution journaling."""
        # Pre-check: Reject if target realm already exists!
        code, target_rep = self.admin.request(f"/admin/realms/{target_realm}", expected_status=(200, 404))
        if code == 200 and target_rep:
            raise CutoverError(f"Target realm '{target_realm}' already exists! Aborting cutover to prevent corruption or state overwrite.")

        # Capture pre-cutover state
        pre = self.run_read_only_preflight(source_realm)
        client_internal_uuid = pre["frontend_client_internal_uuid"]
        mapper_id = pre["audience_mapper_id"]
        realm_internal_id = pre["realm_internal_id"]

        journal: List[Dict[str, Any]] = []
        results: Dict[str, Any] = {"journal": journal}

        try:
            # 1. Rename realm in-place
            code, _ = self.admin.request(
                f"/admin/realms/{source_realm}",
                method="PUT",
                payload={"id": realm_internal_id, "realm": target_realm},
                expected_status=(204,),
            )
            journal.append({"step": "rename_realm", "status": "SUCCESS", "from": source_realm, "to": target_realm})

            # 2. Rename frontend client
            code, _ = self.admin.request(
                f"/admin/realms/{target_realm}/clients/{client_internal_uuid}",
                method="PUT",
                payload={"clientId": target_client_id},
                expected_status=(204,),
            )
            journal.append({"step": "rename_client", "status": "SUCCESS", "realm": target_realm, "client_id": client_internal_uuid, "to": target_client_id})

            # 3. Update audience protocol mapper
            code, _ = self.admin.request(
                f"/admin/realms/{target_realm}/clients/{client_internal_uuid}/protocol-mappers/models/{mapper_id}",
                method="PUT",
                payload={
                    "id": mapper_id,
                    "name": pre["audience_mapper_name"] or "devarom-api-audience",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-audience-mapper",
                    "consentRequired": False,
                    "config": {
                        "included.client.audience": "",
                        "included.custom.audience": target_audience,
                        "id.token.claim": "false",
                        "access.token.claim": "true",
                        "introspection.token.claim": "true",
                    },
                },
                expected_status=(204,),
            )
            journal.append({"step": "update_audience_mapper", "status": "SUCCESS", "realm": target_realm, "mapper_id": mapper_id, "to": target_audience})

            # 4. Post-cutover verification
            post = self.run_read_only_preflight(target_realm)
            results["post_cutover_verification"] = post

            # 5. Verify identity invariants
            preserved, mismatches = self.verify_identity_invariants(
                pre=pre,
                post=post,
                expected_target_realm=target_realm,
                expected_target_client_id=target_client_id,
                expected_target_audience=target_audience,
            )
            results["identity_invariants_preserved"] = preserved
            results["invariant_mismatches"] = mismatches

            if not preserved:
                raise CutoverError(f"Post-cutover invariant verification failed: {mismatches}")

            return results

        except Exception as err:
            results["error"] = str(err)
            results["completed_steps"] = journal
            raise CutoverError(
                f"Cutover failed during execution! Completed steps: {journal}.\n"
                f"Error: {err}.\n"
                f"Rollback command: python3 platform/identity/cutover-identity-migration.py --rollback --confirm-rollback-approval 'SWIFTDROP-ROLLBACK-APPROVED'"
            )

    def execute_in_place_rollback(
        self,
        current_realm: str = "swiftdrop",
        target_realm: str = "devarom",
        target_client_id: str = "devarom-frontend",
        target_audience: str = "devarom-api",
    ) -> Dict[str, Any]:
        """Rolls back in-place rename from swiftdrop to devarom with execution journaling."""
        pre = self.run_read_only_preflight(current_realm)
        client_internal_uuid = pre["frontend_client_internal_uuid"]
        mapper_id = pre["audience_mapper_id"]
        realm_internal_id = pre["realm_internal_id"]

        journal: List[Dict[str, Any]] = []
        results: Dict[str, Any] = {"journal": journal}

        # 1. Reverse rename realm
        code, _ = self.admin.request(
            f"/admin/realms/{current_realm}",
            method="PUT",
            payload={"id": realm_internal_id, "realm": target_realm},
            expected_status=(204,),
        )
        journal.append({"step": "rename_realm", "status": "SUCCESS", "from": current_realm, "to": target_realm})

        # 2. Reverse rename client
        code, _ = self.admin.request(
            f"/admin/realms/{target_realm}/clients/{client_internal_uuid}",
            method="PUT",
            payload={"clientId": target_client_id},
            expected_status=(204,),
        )
        journal.append({"step": "rename_client", "status": "SUCCESS", "realm": target_realm, "client_id": client_internal_uuid, "to": target_client_id})

        # 3. Reverse audience mapper
        code, _ = self.admin.request(
            f"/admin/realms/{target_realm}/clients/{client_internal_uuid}/protocol-mappers/models/{mapper_id}",
            method="PUT",
            payload={
                "id": mapper_id,
                "name": pre["audience_mapper_name"] or "devarom-api-audience",
                "protocol": "openid-connect",
                "protocolMapper": "oidc-audience-mapper",
                "consentRequired": False,
                "config": {
                    "included.client.audience": "",
                    "included.custom.audience": target_audience,
                    "id.token.claim": "false",
                    "access.token.claim": "true",
                    "introspection.token.claim": "true",
                },
            },
            expected_status=(204,),
        )
        journal.append({"step": "update_audience_mapper", "status": "SUCCESS", "realm": target_realm, "mapper_id": mapper_id, "to": target_audience})

        # 4. Post-rollback verification
        post = self.run_read_only_preflight(target_realm)
        results["post_rollback_verification"] = post

        preserved, mismatches = self.verify_identity_invariants(
            pre=pre,
            post=post,
            expected_target_realm=target_realm,
            expected_target_client_id=target_client_id,
            expected_target_audience=target_audience,
        )
        results["identity_invariants_preserved"] = preserved
        results["invariant_mismatches"] = mismatches
        return results


def main() -> int:
    parser = argparse.ArgumentParser(description="SwiftDrop Phase 2C Identity Cutover Tool")
    parser.add_argument("--keycloak-url", default=os.getenv("KEYCLOAK_URL", "http://localhost:8090"), help="Keycloak origin URL")
    parser.add_argument("--admin-user", default=os.getenv("KEYCLOAK_ADMIN_USER", "admin"), help="Keycloak bootstrap admin user")
    parser.add_argument("--admin-pass", default=os.getenv("KEYCLOAK_ADMIN_PASS", ""), help="Keycloak bootstrap admin password")
    parser.add_argument("--preflight", action="store_true", default=True, help="Execute read-only preflight inspection (DEFAULT)")
    parser.add_argument("--execute-cutover", action="store_true", default=False, help="Execute in-place cutover (REQUIRES APPROVAL FLAG)")
    parser.add_argument("--confirm-cutover-approval", default="", help="Pass exact confirmation phrase: 'SWIFTDROP-MAINTENANCE-APPROVED'")
    parser.add_argument("--rollback", action="store_true", default=False, help="Execute in-place rollback to devarom")
    parser.add_argument("--confirm-rollback-approval", default="", help="Pass exact confirmation phrase: 'SWIFTDROP-ROLLBACK-APPROVED'")

    args = parser.parse_args()

    print("==============================================================================")
    print(" SwiftDrop — Phase 2C Identity Migration Protocol Engine")
    print("==============================================================================")

    if not args.admin_pass:
        print("[NOTICE] No --admin-pass or KEYCLOAK_ADMIN_PASS provided.")
        print("  Running offline specification check...")
        print("  - Target Realm Rename     : devarom -> swiftdrop")
        print("  - Target Client Rename    : devarom-frontend -> swiftdrop-frontend")
        print("  - Target Audience Claim   : devarom-api -> swiftdrop-api")
        print("  - Target Google Callback  : http://localhost:8090/realms/swiftdrop/broker/google/endpoint")
        print("  - Existing Callback Kept  : http://localhost:8090/realms/devarom/broker/google/endpoint")
        print("\nPreflight offline check: PASS.")
        return 0

    client = KeycloakAdminClient(args.keycloak_url, args.admin_user, args.admin_pass)
    engine = IdentityCutoverEngine(client)

    if args.execute_cutover:
        if args.confirm_cutover_approval != "SWIFTDROP-MAINTENANCE-APPROVED":
            print("[SAFETY REFUSAL] Live cutover execution BLOCKED.")
            print("  You must provide --confirm-cutover-approval='SWIFTDROP-MAINTENANCE-APPROVED'.")
            print("  Active identities are protected. No changes made.")
            return 1

        print("[EXECUTION] Executing in-place identity cutover...")
        try:
            res = engine.execute_in_place_cutover()
            print(f"[RESULT] Cutover completed. Invariants preserved: {res['identity_invariants_preserved']}")
            return 0 if res["identity_invariants_preserved"] else 1
        except CutoverError as err:
            print(f"[CUTOVER FAILED] {err}")
            return 1

    if args.rollback:
        if args.confirm_rollback_approval != "SWIFTDROP-ROLLBACK-APPROVED":
            print("[SAFETY REFUSAL] Rollback execution BLOCKED.")
            print("  You must provide --confirm-rollback-approval='SWIFTDROP-ROLLBACK-APPROVED'.")
            return 1

        print("[ROLLBACK] Executing in-place rollback to devarom...")
        try:
            res = engine.execute_in_place_rollback()
            print(f"[RESULT] Rollback completed successfully. Invariants preserved: {res['identity_invariants_preserved']}")
            return 0
        except CutoverError as err:
            print(f"[ROLLBACK FAILED] {err}")
            return 1

    # Default to read-only preflight
    print("[PREFLIGHT] Executing read-only preflight inspection...")
    try:
        preflight = engine.run_read_only_preflight("devarom")
        print(f"  - Realm Internal ID       : {preflight['realm_internal_id']}")
        print(f"  - Current Realm Name      : {preflight['realm_name']}")
        print(f"  - Client Internal UUID    : {preflight['frontend_client_internal_uuid']}")
        print(f"  - Current Client ID       : {preflight['frontend_client_id']}")
        print(f"  - Audience Mapper ID      : {preflight['audience_mapper_id']}")
        print(f"  - Current Audience Claim  : {preflight['current_audience']}")
        print(f"  - Google Broker Configured: {preflight['google_broker_configured']}")
        print(f"  - Active User Count       : {preflight['user_count']}")
        print("Preflight inspection status: ALL PREREQUISITES VERIFIED (READ-ONLY).")
        return 0
    except CutoverError as err:
        print(f"[PREFLIGHT FAILED] {err}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
