#!/usr/bin/env python3
"""
SwiftDrop Executable GraphQL Contract Regressions (Issue #140 / N10-QA-01)
==========================================================================
Validates GraphQL SDL invariants, strictly read-only execution, authorized query
resolution, public tracking decision verification (#119), and negative security gates.
"""

import unittest
from pathlib import Path
from typing import Any, Dict, Optional

import graphql
from graphql import build_schema, graphql_sync, validate, parse

from tests.contracts.validate_contracts import (
    CONTRACTS_DIR,
    ContractValidationError,
    validate_graphql_sdl,
)


class GraphqlContractRegressionTest(unittest.TestCase):
    """Regression tests for SwiftDrop read-only GraphQL Query API."""

    @classmethod
    def setUpClass(cls):
        cls.sdl_path = CONTRACTS_DIR / "graphql" / "schema.graphql"
        cls.sdl_text = cls.sdl_path.read_text(encoding="utf-8")
        cls.schema = build_schema(cls.sdl_text)

    def test_schema_syntax_and_read_only_invariants(self):
        """Schema must parse cleanly and define strictly read-only query root."""
        self.assertIsNotNone(self.schema.query_type)
        self.assertIsNone(self.schema.mutation_type, "GraphQL schema must NOT have Mutation types")
        self.assertIsNone(self.schema.subscription_type, "GraphQL schema must NOT have Subscription types")

    def test_query_root_fields_match_approved_composite_dashboards(self):
        """Approved query root fields must be present with expected types."""
        expected_fields = {
            "customerDashboard",
            "merchantDashboard",
            "courierDashboard",
            "dispatcherDashboard",
            "adminDashboard",
            "deliveryStats",
        }
        actual_fields = set(self.schema.query_type.fields.keys())
        self.assertEqual(actual_fields, expected_fields, f"Mismatch in approved query fields: {actual_fields ^ expected_fields}")

    def test_verified_public_graphql_schema_decision_issue_119(self):
        """
        Public tracking is REST-owned (#115/#133); publicTracking MUST NOT exist
        as a query root in the active GraphQL schema per approved Issue #119 decision.
        """
        query_fields = self.schema.query_type.fields
        self.assertNotIn(
            "publicTracking",
            query_fields,
            "VIOLATION: Query.publicTracking must NOT be exposed in released GraphQL schema per Issue #119 decision",
        )

    def test_pagination_and_money_types(self):
        """Pagination and money minor units must use integer representation."""
        page_info = self.schema.type_map.get("PageInfo")
        self.assertIsNotNone(page_info)
        for field in ("currentPage", "pageSize", "totalElements", "totalPages"):
            self.assertEqual(str(page_info.fields[field].type), "Int!")

        customer_summary = self.schema.type_map.get("CustomerParcelSummary")
        self.assertIsNotNone(customer_summary)
        self.assertEqual(str(customer_summary.fields["amountMinorUnits"].type), "Int!")
        self.assertEqual(str(customer_summary.fields["currency"].type), "String!")


class GraphqlExecutionAndSecurityTest(unittest.TestCase):
    """
    Executes actual GraphQL queries against the schema with mock authorization context.
    Verifies authorized access, role-scoping, and negative rejection of mutations and unauth calls.
    """

    @classmethod
    def setUpClass(cls):
        cls.sdl_path = CONTRACTS_DIR / "graphql" / "schema.graphql"
        cls.schema = build_schema(cls.sdl_path.read_text(encoding="utf-8"))

    def resolve(self, query: str, context: Optional[dict] = None) -> dict:
        """Execute a query against reference resolvers enforcing security context."""
        context = context or {}

        def customer_dashboard(*args, **kwargs):
            if "CUSTOMER" not in context.get("roles", []):
                raise PermissionError("Role CUSTOMER required")
            return {
                "userId": context.get("sub", "cust-123"),
                "activeDeliveriesCount": 2,
                "completedDeliveriesCount": 10,
                "recentParcels": [
                    {
                        "parcelId": "p-1",
                        "trackingNumber": "SWD-20261028-A1B2C3D4",
                        "currentStatus": "CREATED",
                        "amountMinorUnits": 50000,
                        "currency": "LKR",
                        "createdAt": "2026-10-28T10:00:00Z",
                    }
                ],
            }

        def admin_dashboard(*args, **kwargs):
            if "ADMIN" not in context.get("roles", []):
                raise PermissionError("Role ADMIN required")
            return {
                "pendingMerchantsCount": 3,
                "totalParcelsCount": 1500,
                "activeParcelsCount": 42,
                "terminalSuccessRate": 98.5,
                "recentPendingMerchants": [
                    {
                        "merchantId": "m-101",
                        "businessName": "Ceylon Express",
                        "registrationNumber": "PV-9988",
                        "submittedAt": "2026-10-28T08:00:00Z",
                    }
                ],
            }

        def delivery_stats(*args, **kwargs):
            if not any(r in ("DISPATCHER", "ADMIN") for r in context.get("roles", [])):
                raise PermissionError("Role DISPATCHER or ADMIN required")
            return {
                "totalCount": 100,
                "createdCount": 10,
                "inTransitCount": 15,
                "deliveredCount": 70,
                "failedCount": 5,
                "averageDeliveryHours": 4.25,
            }

        root_value = {
            "customerDashboard": customer_dashboard,
            "adminDashboard": admin_dashboard,
            "deliveryStats": delivery_stats,
        }

        result = graphql_sync(self.schema, query, root_value=root_value, context_value=context)
        return {
            "data": result.data,
            "errors": [str(e) for e in result.errors] if result.errors else [],
        }

    def test_authorized_customer_dashboard_query(self):
        """Customer with CUSTOMER role successfully queries customerDashboard."""
        query = """
        query GetCustomerDash {
            customerDashboard {
                userId
                activeDeliveriesCount
                completedDeliveriesCount
                recentParcels {
                    parcelId
                    trackingNumber
                    currentStatus
                    amountMinorUnits
                    currency
                }
            }
        }
        """
        context = {"sub": "customer-alice", "roles": ["CUSTOMER"]}
        res = self.resolve(query, context)
        self.assertEqual(res["errors"], [])
        self.assertIsNotNone(res["data"]["customerDashboard"])
        self.assertEqual(res["data"]["customerDashboard"]["userId"], "customer-alice")
        self.assertEqual(res["data"]["customerDashboard"]["recentParcels"][0]["amountMinorUnits"], 50000)

    def test_authorized_admin_dashboard_query(self):
        """Admin with ADMIN role successfully queries adminDashboard with pending merchants."""
        query = """
        query GetAdminDash {
            adminDashboard {
                pendingMerchantsCount
                totalParcelsCount
                terminalSuccessRate
                recentPendingMerchants {
                    merchantId
                    businessName
                    registrationNumber
                    submittedAt
                }
            }
        }
        """
        context = {"sub": "admin-bob", "roles": ["ADMIN"]}
        res = self.resolve(query, context)
        self.assertEqual(res["errors"], [])
        dash = res["data"]["adminDashboard"]
        self.assertEqual(dash["pendingMerchantsCount"], 3)
        self.assertEqual(dash["recentPendingMerchants"][0]["businessName"], "Ceylon Express")
        self.assertEqual(dash["recentPendingMerchants"][0]["submittedAt"], "2026-10-28T08:00:00Z")

    def test_authorized_delivery_stats_query(self):
        """Dispatcher queries deliveryStats with filter input."""
        query = """
        query GetStats($filter: DeliveryStatsFilterInput) {
            deliveryStats(filter: $filter) {
                totalCount
                deliveredCount
                failedCount
                averageDeliveryHours
            }
        }
        """
        context = {"sub": "dispatcher-kamal", "roles": ["DISPATCHER"]}
        res = self.resolve(query, context)
        self.assertEqual(res["errors"], [])
        stats = res["data"]["deliveryStats"]
        self.assertEqual(stats["totalCount"], 100)
        self.assertEqual(stats["deliveredCount"], 70)
        self.assertAlmostEqual(stats["averageDeliveryHours"], 4.25)

    def test_customer_denied_admin_dashboard(self):
        """Customer attempting to query adminDashboard is denied with error."""
        query = "query { adminDashboard { pendingMerchantsCount } }"
        context = {"sub": "customer-alice", "roles": ["CUSTOMER"]}
        res = self.resolve(query, context)
        self.assertTrue(len(res["errors"]) > 0)
        self.assertTrue(any("Role ADMIN required" in e for e in res["errors"]))

    def test_anonymous_caller_denied_all_private_dashboards(self):
        """Unauthenticated caller without roles is denied."""
        query = "query { customerDashboard { userId } }"
        res = self.resolve(query, context={})
        self.assertTrue(len(res["errors"]) > 0)
        self.assertTrue(any("Role CUSTOMER required" in e for e in res["errors"]))

    def test_mutation_syntax_rejected_at_validation(self):
        """GraphQL mutations must be rejected because schema defines zero mutations."""
        mutation = "mutation { cancelParcel(parcelId: \"123\") { success } }"
        doc = parse(mutation)
        errors = validate(self.schema, doc)
        self.assertTrue(len(errors) > 0)
        self.assertTrue(any("The mutation operation is not supported by the schema" in str(e) for e in errors))

    def test_subscription_syntax_rejected_at_validation(self):
        """GraphQL subscriptions must be rejected because schema defines zero subscriptions."""
        subscription = "subscription { parcelStatusLive(trackingNumber: \"SWD-123\") { status } }"
        doc = parse(subscription)
        errors = validate(self.schema, doc)
        self.assertTrue(len(errors) > 0)
        self.assertTrue(any("The subscription operation is not supported by the schema" in str(e) for e in errors))


if __name__ == "__main__":
    unittest.main()
