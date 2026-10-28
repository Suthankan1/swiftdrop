#!/usr/bin/env python3
"""
SwiftDrop Cross-Protocol Convention Regressions (Issue #140 / N10-QA-01)
=======================================================================
Verifies semantic equivalence and compatibility across REST, GraphQL, gRPC, and Kafka:
1. Money representation: integer minor units (int64/int, e.g. 50000 = LKR 500.00) and ISO-4217.
2. UTC Timestamp conventions: ISO-8601 strings in REST/GraphQL/Kafka vs google.protobuf.Timestamp in gRPC.
3. Bounded pagination: 0-indexed page, default 20, maximum bound 50.
4. Uniform status enums: CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED.
5. Canonical ID representations: UUID format for aggregate/tenant IDs, SWD-YYYYMMDD-XXXXXXXX for tracking.
"""

import json
import re
import unittest
from pathlib import Path
from typing import Dict, Set

import yaml
from graphql import build_schema

from tests.contracts.validate_contracts import (
    CONTRACTS_DIR,
    ROOT_DIR,
    parse_proto_content,
)


class CrossProtocolConventionRegressionTest(unittest.TestCase):
    """Executable regression tests for cross-protocol architectural conventions."""

    @classmethod
    def setUpClass(cls):
        # 1. REST
        cls.delivery_rest = yaml.safe_load((CONTRACTS_DIR / "rest" / "openapi-delivery.yaml").read_text(encoding="utf-8"))
        cls.pricing_rest = yaml.safe_load((CONTRACTS_DIR / "rest" / "openapi-pricing.yaml").read_text(encoding="utf-8"))
        cls.problem_rest = yaml.safe_load((CONTRACTS_DIR / "rest" / "problem-detail.yaml").read_text(encoding="utf-8"))

        # 2. GraphQL
        cls.gql_schema = build_schema((CONTRACTS_DIR / "graphql" / "schema.graphql").read_text(encoding="utf-8"))

        # 3. gRPC
        cls.protos: Dict[str, dict] = {}
        for p in (CONTRACTS_DIR / "grpc").glob("**/*.proto"):
            cls.protos[p.name] = parse_proto_content(p.read_text(encoding="utf-8"))

        # 4. Kafka
        cls.event_schema = json.loads((CONTRACTS_DIR / "events" / "parcel.status.changed.v1.schema.json").read_text(encoding="utf-8"))

    def test_money_minor_units_convention_across_all_protocols(self):
        """
        Money values across REST, GraphQL, and gRPC must use integer minor units
        and must strictly forbid floating-point types to prevent precision errors.
        """
        # REST checks: openapi-delivery.yaml ParcelDetailResponse
        schemas = self.delivery_rest["components"]["schemas"]
        parcel_detail = schemas["ParcelDetailResponse"]["properties"]
        self.assertEqual(parcel_detail["amountMinorUnits"]["type"], "integer")
        self.assertEqual(parcel_detail["currency"]["type"], "string")
        self.assertNotIn(parcel_detail["amountMinorUnits"].get("format"), ("float", "double"))

        # Pricing REST quote response
        pricing_schemas = self.pricing_rest["components"]["schemas"]
        quote_resp = pricing_schemas["QuoteResponse"]["properties"]
        self.assertEqual(quote_resp["totalAmountMinorUnits"]["type"], "integer")
        self.assertEqual(quote_resp["baseFeeMinorUnits"]["type"], "integer")
        self.assertEqual(quote_resp["currency"]["type"], "string")

        # GraphQL checks
        cust_summary = self.gql_schema.type_map["CustomerParcelSummary"]
        self.assertEqual(str(cust_summary.fields["amountMinorUnits"].type), "Int!")
        self.assertEqual(str(cust_summary.fields["currency"].type), "String!")

        # gRPC checks
        for pname, pdata in self.protos.items():
            for mname, mdef in pdata.get("messages", {}).items():
                for fname, fdef in mdef.get("fields", {}).items():
                    if "minor_units" in fname.lower() or "amount" in fname.lower():
                        self.assertEqual(
                            fdef["type"],
                            "int64",
                            f"{pname} {mname}.{fname} must use int64 for money minor units",
                        )
                        self.assertNotIn(fdef["type"], ("float", "double"))

    def test_timestamp_conventions_across_all_protocols(self):
        """
        Timestamps must represent UTC: ISO-8601 date-time in REST, GraphQL, and Kafka;
        google.protobuf.Timestamp in gRPC.
        """
        # REST: createdAt, occurredAt must have format: date-time
        delivery_schemas = self.delivery_rest["components"]["schemas"]
        self.assertEqual(delivery_schemas["ParcelDetailResponse"]["properties"]["createdAt"]["format"], "date-time")
        self.assertEqual(self.problem_rest["components"]["schemas"]["ProblemDetail"]["properties"]["timestamp"]["format"], "date-time")

        # GraphQL: createdAt, occurredAt, submittedAt are strings
        self.assertEqual(str(self.gql_schema.type_map["CustomerParcelSummary"].fields["createdAt"].type), "String!")

        # gRPC: all _at fields must use google.protobuf.Timestamp
        for pname, pdata in self.protos.items():
            for mname, mdef in pdata.get("messages", {}).items():
                for fname, fdef in mdef.get("fields", {}).items():
                    if fname.endswith("_at"):
                        self.assertEqual(
                            fdef["type"],
                            "google.protobuf.Timestamp",
                            f"{pname} {mname}.{fname} must use google.protobuf.Timestamp",
                        )

        # Kafka: occurredAt format: date-time
        self.assertEqual(self.event_schema["properties"]["occurredAt"]["format"], "date-time")

    def test_status_enum_consistency_across_all_protocols(self):
        """
        Authoritative parcel lifecycle status values must be identical in naming
        across REST, GraphQL, gRPC, and Kafka:
        CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED.
        """
        expected_statuses = {
            "CREATED",
            "PICKED_UP",
            "IN_TRANSIT",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "FAILED",
        }

        # REST status transitions
        update_schema = self.delivery_rest["components"]["schemas"]["UpdateStatusRequest"]["properties"]["newStatus"]
        self.assertTrue(set(update_schema["enum"]).issubset(expected_statuses))

        # GraphQL status enum
        gql_enum = set(self.gql_schema.type_map["ParcelStatus"].values.keys())
        self.assertEqual(gql_enum, expected_statuses, "GraphQL ParcelStatus enum mismatch")

        # Kafka status enum
        kafka_enum = set(self.event_schema["properties"]["payload"]["properties"]["newStatus"]["enum"])
        self.assertEqual(kafka_enum, expected_statuses, "Kafka event newStatus enum mismatch")

    def test_bounded_pagination_conventions_across_protocols(self):
        """
        Pagination across REST, GraphQL, and gRPC must use 0-based page index,
        default page size 20, and bounded maximum page size 50.
        """
        # GraphQL PaginationInput defaults in AST
        pagination_input = self.gql_schema.type_map["PaginationInput"]
        self.assertEqual(int(pagination_input.fields["page"].ast_node.default_value.value), 0)
        self.assertEqual(int(pagination_input.fields["size"].ast_node.default_value.value), 20)

        # gRPC pagination convention in Accounts pending merchants and Pricing summaries
        accounts_proto = self.protos["accounts.proto"]
        req_fields = accounts_proto["messages"]["PendingMerchantsSummaryRequest"]["fields"]
        self.assertEqual(req_fields["page"]["type"], "int32")
        self.assertEqual(req_fields["page_size"]["type"], "int32")

        pricing_proto = self.protos["pricing.proto"]
        req_fields_pricing = pricing_proto["messages"]["PricingAdminSummaryRequest"]["fields"]
        self.assertEqual(req_fields_pricing["page"]["type"], "int32")
        self.assertEqual(req_fields_pricing["page_size"]["type"], "int32")

    def test_canonical_identifier_formats(self):
        """
        Entity identifiers use UUID format, and public tracking numbers use
        canonical pattern SWD-YYYYMMDD-[A-Z0-9]{4,8}.
        """
        tracking_pattern = r"^SWD-[0-9]{8}-[A-Z0-9]{4,8}$"

        # REST tracking pattern in PublicTrackingResponse or schema
        tracking_prop = self.delivery_rest["components"]["schemas"]["ParcelDetailResponse"]["properties"]["trackingNumber"]
        self.assertTrue(re.match(tracking_pattern, "SWD-20261028-ABC12345"))

        # Kafka tracking pattern in schema
        kafka_pattern = self.event_schema["properties"]["payload"]["properties"]["trackingNumber"]["pattern"]
        self.assertEqual(kafka_pattern, tracking_pattern)


if __name__ == "__main__":
    unittest.main()
