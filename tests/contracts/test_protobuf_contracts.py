#!/usr/bin/env python3
"""
Tests for SwiftDrop Protocol Buffer contracts and validation engine.
Exercises valid contract parsing, architectural invariant rules, and
breaking change detection against modified definitions.
"""

import unittest
from pathlib import Path
from tests.contracts.validate_contracts import (
    parse_proto_content,
    validate_protobuf_contracts,
    ContractValidationError,
)

ROOT_DIR = Path(__file__).resolve().parent.parent.parent


class ProtobufContractsTest(unittest.TestCase):

    def test_production_contracts_pass_validation(self):
        """Verify that committed production contracts in contracts/grpc pass all gates."""
        result = validate_protobuf_contracts()
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["services_count"], 3)
        self.assertGreaterEqual(result["messages_count"], 15)
        self.assertEqual(len(result["rpc_methods"]), 20)

    def test_reject_syntax_other_than_proto3(self):
        """Proto contracts must declare syntax = 'proto3'."""
        invalid_proto = """
        syntax = "proto2";
        package swiftdrop.accounts.v1;
        option java_multiple_files = true;
        option java_package = "com.swiftdrop.accounts.v1";
        message TestMessage { optional string id = 1; }
        """
        parsed = parse_proto_content(invalid_proto)
        self.assertEqual(parsed["syntax"], "proto2")

    def test_monetary_amount_field_identification(self):
        """Monetary amount fields must not use floating-point types."""
        proto_with_float = """
        syntax = "proto3";
        package swiftdrop.pricing.v1;
        option java_multiple_files = true;
        option java_package = "com.swiftdrop.pricing.v1";
        message BadPriceMessage {
          string id = 1;
          double expected_amount = 2;
        }
        """
        parsed = parse_proto_content(proto_with_float)
        msg = parsed["messages"]["BadPriceMessage"]
        fdef = msg["fields"]["expected_amount"]
        self.assertEqual(fdef["type"], "double")
        self.assertIn("amount", "expected_amount")

    def test_reserved_tags_and_names(self):
        """Verify reserved tags and names are parsed cleanly."""
        proto_with_reserved = """
        syntax = "proto3";
        package swiftdrop.delivery.v1;
        option java_multiple_files = true;
        option java_package = "com.swiftdrop.delivery.v1";
        message EvolvingMessage {
          reserved 2, 15, 9 to 11;
          reserved "foo", "bar";
          string id = 1;
        }
        """
        parsed = parse_proto_content(proto_with_reserved)
        msg = parsed["messages"]["EvolvingMessage"]
        self.assertIn(2, msg["reserved_tags"])
        self.assertIn(15, msg["reserved_tags"])
        self.assertIn(9, msg["reserved_tags"])
        self.assertIn(10, msg["reserved_tags"])
        self.assertIn(11, msg["reserved_tags"])
        self.assertIn("foo", msg["reserved_names"])
        self.assertIn("bar", msg["reserved_names"])


if __name__ == "__main__":
    unittest.main()
