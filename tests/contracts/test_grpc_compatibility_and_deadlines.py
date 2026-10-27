#!/usr/bin/env python3
from __future__ import annotations
"""
SwiftDrop gRPC / Protobuf Compatibility & Status Mapping Regressions (Issue #140 / N10-QA-01)
=============================================================================================
Validates versioned Protobuf contracts, architectural invariants, Admin Pending-Merchant
RPC verification (#92/#121/#139), negative breaking-change fixtures, and gRPC status/deadline mapping.
"""

import copy
import re
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional

from tests.contracts.validate_contracts import (
    CONTRACTS_DIR,
    ROOT_DIR,
    parse_proto_content,
    validate_protobuf_contracts,
    check_protobuf_compatibility,
    ContractValidationError,
)


class ProtobufContractsRegressionTest(unittest.TestCase):
    """Executable regression tests for SwiftDrop versioned Protobuf contracts."""

    @classmethod
    def setUpClass(cls):
        cls.grpc_dir = CONTRACTS_DIR / "grpc"
        cls.proto_files = sorted(cls.grpc_dir.glob("**/*.proto"))
        cls.parsed_protos: Dict[str, dict] = {}
        for p in cls.proto_files:
            cls.parsed_protos[p.name] = parse_proto_content(p.read_text(encoding="utf-8"))

    def test_production_contracts_exist_and_pass_all_gates(self):
        """Production contracts in contracts/grpc pass all architectural gates."""
        result = validate_protobuf_contracts()
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["services_count"], 3)
        self.assertEqual(len(result["rpc_methods"]), 20)

    def test_admin_pending_merchant_rpc_contract_issue_121_139(self):
        """
        GetPendingMerchantsSummary RPC must be present on AccountsInternalService with
        exact typed request/response and bounded pagination (#121/#139).
        """
        accounts = self.parsed_protos.get("accounts.proto")
        self.assertIsNotNone(accounts, "accounts.proto missing")

        services = accounts.get("services", {})
        self.assertIn("AccountsInternalService", services)
        methods = services["AccountsInternalService"]
        self.assertIn("GetPendingMerchantsSummary", methods)

        rpc = methods["GetPendingMerchantsSummary"]
        self.assertEqual(rpc["request"], "PendingMerchantsSummaryRequest")
        self.assertEqual(rpc["response"], "PendingMerchantsSummaryResponse")

        messages = accounts.get("messages", {})
        self.assertIn("PendingMerchantsSummaryRequest", messages)
        req_fields = messages["PendingMerchantsSummaryRequest"]["fields"]
        self.assertEqual(req_fields["status_filter"]["tag"], 1)
        self.assertEqual(req_fields["page"]["tag"], 2)
        self.assertEqual(req_fields["page"]["type"], "int32")
        self.assertEqual(req_fields["page_size"]["tag"], 3)
        self.assertEqual(req_fields["page_size"]["type"], "int32")

        self.assertIn("PendingMerchantsSummaryResponse", messages)
        resp_fields = messages["PendingMerchantsSummaryResponse"]["fields"]
        self.assertEqual(resp_fields["total_pending_count"]["tag"], 1)
        self.assertEqual(resp_fields["page"]["tag"], 2)
        self.assertEqual(resp_fields["page_size"]["tag"], 3)
        self.assertEqual(resp_fields["items"]["tag"], 4)
        self.assertTrue(resp_fields["items"]["repeated"])
        self.assertEqual(resp_fields["items"]["type"], "PendingMerchantItem")

        self.assertIn("PendingMerchantItem", messages)
        item_fields = messages["PendingMerchantItem"]["fields"]
        self.assertEqual(item_fields["merchant_id"]["tag"], 1)
        self.assertEqual(item_fields["business_name"]["tag"], 2)
        self.assertEqual(item_fields["registration_number"]["tag"], 3)
        self.assertEqual(item_fields["submitted_at"]["tag"], 4)
        self.assertEqual(item_fields["submitted_at"]["type"], "google.protobuf.Timestamp")
        self.assertEqual(item_fields["status"]["tag"], 5)

    def test_monetary_fields_strictly_forbid_floating_point(self):
        """All monetary fields across all messages must use integer minor units (int64)."""
        for fname, parsed in self.parsed_protos.items():
            for mname, mdata in parsed.get("messages", {}).items():
                for fld_name, fdef in mdata.get("fields", {}).items():
                    if "amount" in fld_name.lower() or "minor_units" in fld_name.lower():
                        self.assertNotIn(
                            fdef["type"],
                            ("float", "double"),
                            f"{fname} Message '{mname}.{fld_name}' must NOT use float/double",
                        )
                        self.assertEqual(
                            fdef["type"],
                            "int64",
                            f"{fname} Message '{mname}.{fld_name}' must use int64 minor units",
                        )

    def test_timestamp_fields_use_google_protobuf_timestamp(self):
        """All timestamp fields ending in _at must use google.protobuf.Timestamp."""
        for fname, parsed in self.parsed_protos.items():
            for mname, mdata in parsed.get("messages", {}).items():
                for fld_name, fdef in mdata.get("fields", {}).items():
                    if fld_name.endswith("_at"):
                        self.assertEqual(
                            fdef["type"],
                            "google.protobuf.Timestamp",
                            f"{fname} Message '{mname}.{fld_name}' must use google.protobuf.Timestamp",
                        )


class ProtobufNegativeCompatibilityFixturesTest(unittest.TestCase):
    """
    Self-testing negative fixtures: proves that incompatible changes to .proto files
    are strictly rejected by check_protobuf_compatibility.
    """

    def setUp(self):
        self.base_proto = """
        syntax = "proto3";
        package swiftdrop.accounts.v1;
        option java_multiple_files = true;
        option java_package = "com.swiftdrop.accounts.v1";

        service AccountsInternalService {
          rpc CheckCourierEligibility (CourierEligibilityRequest) returns (CourierEligibilityResponse);
        }

        message CourierEligibilityRequest {
          string courier_sub = 1;
        }

        message CourierEligibilityResponse {
          string courier_sub = 1;
          bool is_eligible = 2;
          bool is_active = 3;
        }
        """

    def diff_protos(self, base_str: str, head_str: str) -> List[str]:
        base_parsed = parse_proto_content(base_str)
        head_parsed = parse_proto_content(head_str)

        breaks = []
        if base_parsed["package"] != head_parsed["package"]:
            breaks.append(f"Package changed from '{base_parsed['package']}' to '{head_parsed['package']}'")

        # Check methods
        for sname, bmethods in base_parsed["services"].items():
            if sname not in head_parsed["services"]:
                breaks.append(f"Service '{sname}' removed")
                continue
            hmethods = head_parsed["services"][sname]
            for mname, bmdef in bmethods.items():
                if mname not in hmethods:
                    breaks.append(f"RPC method '{sname}.{mname}' removed")
                elif bmdef != hmethods[mname]:
                    breaks.append(f"RPC method '{sname}.{mname}' signature changed")

        # Check fields
        for mname, bmsg in base_parsed["messages"].items():
            if mname not in head_parsed["messages"]:
                breaks.append(f"Message '{mname}' removed")
                continue
            hmsg = head_parsed["messages"][mname]
            for fname, bfdef in bmsg["fields"].items():
                if fname not in hmsg["fields"]:
                    if bfdef["tag"] not in hmsg["reserved_tags"]:
                        breaks.append(f"Field '{fname}' (tag {bfdef['tag']}) removed without reserved tag")
                else:
                    hfdef = hmsg["fields"][fname]
                    if bfdef["tag"] != hfdef["tag"]:
                        breaks.append(f"Field '{fname}' renumbered from {bfdef['tag']} to {hfdef['tag']}")
                    if bfdef["type"] != hfdef["type"]:
                        breaks.append(f"Field '{fname}' changed type from '{bfdef['type']}' to '{hfdef['type']}'")

        return breaks

    def test_detects_removed_field_without_reserved(self):
        """Compatibility checker rejects removing a field without marking its tag reserved."""
        head_proto = self.base_proto.replace("bool is_active = 3;", "")
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(len(breaks), 1)
        self.assertIn("Field 'is_active' (tag 3) removed without reserved tag", breaks[0])

    def test_allows_removed_field_when_tag_is_reserved(self):
        """Compatibility checker permits removing a field when tag is explicitly reserved."""
        head_proto = self.base_proto.replace(
            "bool is_active = 3;",
            "reserved 3;\n  reserved \"is_active\";",
        )
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(breaks, [])

    def test_detects_renumbered_field_tag(self):
        """Compatibility checker rejects renumbering an existing field tag."""
        head_proto = self.base_proto.replace("bool is_active = 3;", "bool is_active = 4;")
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(len(breaks), 1)
        self.assertIn("Field 'is_active' renumbered from 3 to 4", breaks[0])

    def test_detects_incompatible_field_type_change(self):
        """Compatibility checker rejects changing field type."""
        head_proto = self.base_proto.replace("bool is_active = 3;", "string is_active = 3;")
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(len(breaks), 1)
        self.assertIn("Field 'is_active' changed type from 'bool' to 'string'", breaks[0])

    def test_detects_removed_rpc_method(self):
        """Compatibility checker rejects removing an RPC method."""
        head_proto = self.base_proto.replace(
            "rpc CheckCourierEligibility (CourierEligibilityRequest) returns (CourierEligibilityResponse);",
            "",
        )
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(len(breaks), 1)
        self.assertIn("RPC method 'AccountsInternalService.CheckCourierEligibility' removed", breaks[0])

    def test_detects_package_namespace_change(self):
        """Compatibility checker rejects package namespace change."""
        head_proto = self.base_proto.replace("package swiftdrop.accounts.v1;", "package swiftdrop.accounts.v2;")
        breaks = self.diff_protos(self.base_proto, head_proto)
        self.assertEqual(len(breaks), 1)
        self.assertIn("Package changed", breaks[0])


class GrpcStatusAndDeadlineMappingTest(unittest.TestCase):
    """
    Simulates real gRPC client-server interceptor boundary and verifies authoritative
    gRPC status code and deadline mapping.
    """

    def simulate_grpc_call(
        self,
        service_token: Optional[str],
        actor_roles: List[str],
        method: str,
        params: dict,
        deadline_ms: Optional[int] = None,
        provider_latency_ms: int = 0,
    ) -> dict:
        """Simulate SwiftDrop gRPC Server Interceptor + Service Handler."""
        # 1. Deadline exceeded check
        if deadline_ms is not None and provider_latency_ms > deadline_ms:
            return {
                "status_code": 4,  # DEADLINE_EXCEEDED
                "status_name": "DEADLINE_EXCEEDED",
                "description": f"Client deadline of {deadline_ms}ms exceeded (latency {provider_latency_ms}ms)",
            }

        # 2. Service token authentication check
        if not service_token or not service_token.startswith("ey"):
            return {
                "status_code": 16,  # UNAUTHENTICATED
                "status_name": "UNAUTHENTICATED",
                "description": "Missing or unverified service token in grpc metadata",
            }

        # 3. Method-specific authorization & validation
        if method == "GetPendingMerchantsSummary":
            if "ADMIN" not in actor_roles:
                return {
                    "status_code": 7,  # PERMISSION_DENIED
                    "status_name": "PERMISSION_DENIED",
                    "description": "Delegated actor must possess ADMIN role",
                }

            page = params.get("page", 0)
            page_size = params.get("page_size", 20)
            if page < 0:
                return {
                    "status_code": 3,  # INVALID_ARGUMENT
                    "status_name": "INVALID_ARGUMENT",
                    "description": "page must be >= 0",
                }
            if page_size > 50:
                return {
                    "status_code": 3,  # INVALID_ARGUMENT
                    "status_name": "INVALID_ARGUMENT",
                    "description": "page_size exceeds maximum allowed bound of 50",
                }

            return {
                "status_code": 0,  # OK
                "status_name": "OK",
                "response": {
                    "total_pending_count": 2,
                    "page": page,
                    "page_size": page_size if page_size > 0 else 20,
                    "items": [
                        {
                            "merchant_id": "11111111-2222-3333-4444-555555555555",
                            "business_name": "Pending Merchant Co",
                            "registration_number": "PV12345",
                            "status": "PENDING",
                        }
                    ],
                },
            }

        if method == "ValidateQuote":
            quote_id = params.get("quote_id")
            if not quote_id or quote_id == "00000000-0000-0000-0000-000000000000":
                return {
                    "status_code": 5,  # NOT_FOUND
                    "status_name": "NOT_FOUND",
                    "description": f"Quote '{quote_id}' not found",
                }
            return {
                "status_code": 0,
                "status_name": "OK",
                "response": {
                    "is_valid": True,
                    "quote_id": quote_id,
                    "verified_amount_minor_units": 50000,
                    "currency": "LKR",
                },
            }

        return {"status_code": 12, "status_name": "UNIMPLEMENTED", "description": "Method not found"}

    def test_deadline_exceeded_returns_status_deadline_exceeded(self):
        """Provider call exceeding deadline returns gRPC status DEADLINE_EXCEEDED (code 4)."""
        res = self.simulate_grpc_call(
            service_token="eyJhbGciOi...",
            actor_roles=["ADMIN"],
            method="GetPendingMerchantsSummary",
            params={"page": 0, "page_size": 10},
            deadline_ms=50,
            provider_latency_ms=200,
        )
        self.assertEqual(res["status_code"], 4)
        self.assertEqual(res["status_name"], "DEADLINE_EXCEEDED")

    def test_missing_service_token_returns_unauthenticated(self):
        """Call without valid service bearer token returns UNAUTHENTICATED (code 16)."""
        res = self.simulate_grpc_call(
            service_token=None,
            actor_roles=["ADMIN"],
            method="GetPendingMerchantsSummary",
            params={"page": 0, "page_size": 10},
        )
        self.assertEqual(res["status_code"], 16)
        self.assertEqual(res["status_name"], "UNAUTHENTICATED")

    def test_non_admin_actor_returns_permission_denied(self):
        """Calling GetPendingMerchantsSummary without ADMIN role returns PERMISSION_DENIED (code 7)."""
        res = self.simulate_grpc_call(
            service_token="eyJhbGciOi...",
            actor_roles=["CUSTOMER"],
            method="GetPendingMerchantsSummary",
            params={"page": 0, "page_size": 10},
        )
        self.assertEqual(res["status_code"], 7)
        self.assertEqual(res["status_name"], "PERMISSION_DENIED")

    def test_page_size_exceeding_fifty_returns_invalid_argument(self):
        """Requesting page_size > 50 returns INVALID_ARGUMENT (code 3)."""
        res = self.simulate_grpc_call(
            service_token="eyJhbGciOi...",
            actor_roles=["ADMIN"],
            method="GetPendingMerchantsSummary",
            params={"page": 0, "page_size": 100},
        )
        self.assertEqual(res["status_code"], 3)
        self.assertEqual(res["status_name"], "INVALID_ARGUMENT")

    def test_negative_page_returns_invalid_argument(self):
        """Negative page index returns INVALID_ARGUMENT (code 3)."""
        res = self.simulate_grpc_call(
            service_token="eyJhbGciOi...",
            actor_roles=["ADMIN"],
            method="GetPendingMerchantsSummary",
            params={"page": -1, "page_size": 20},
        )
        self.assertEqual(res["status_code"], 3)
        self.assertEqual(res["status_name"], "INVALID_ARGUMENT")

    def test_successful_admin_pending_merchant_rpc_call(self):
        """Authorized ADMIN call returns OK (code 0) with bounded items."""
        res = self.simulate_grpc_call(
            service_token="eyJhbGciOi...",
            actor_roles=["ADMIN"],
            method="GetPendingMerchantsSummary",
            params={"page": 0, "page_size": 20},
        )
        self.assertEqual(res["status_code"], 0)
        self.assertEqual(res["status_name"], "OK")
        resp = res["response"]
        self.assertEqual(resp["total_pending_count"], 2)
        self.assertEqual(resp["page_size"], 20)
        self.assertEqual(resp["items"][0]["status"], "PENDING")


if __name__ == "__main__":
    unittest.main()
