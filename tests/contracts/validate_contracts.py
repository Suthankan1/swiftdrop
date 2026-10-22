#!/usr/bin/env python3
"""
SwiftDrop Contract Validation and Backward Compatibility Test Suite
===================================================================
Enforces machine-readable contract invariants across:
1. OpenAPI 3.0.3 (contracts/rest/)
2. GraphQL SDL (contracts/graphql/schema.graphql)
3. Kafka Domain Events JSON Schema 2020-12 (contracts/events/)
4. gRPC / Protocol Buffers (contracts/grpc/) - Tracks Issue #92 handoff

Supports backward compatibility checking against a git base reference.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml
import jsonschema
import graphql

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
CONTRACTS_DIR = ROOT_DIR / "contracts"


class ContractValidationError(Exception):
    pass


def get_git_file_content(ref: str, relative_path: str) -> Optional[str]:
    """Retrieve file content from git at a given ref."""
    try:
        result = subprocess.run(
            ["git", "show", f"{ref}:{relative_path}"],
            cwd=str(ROOT_DIR),
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout
    except subprocess.CalledProcessError:
        return None


# ==============================================================================
# 1. OpenAPI Validation & Compatibility
# ==============================================================================

def resolve_refs(spec: dict, all_specs: Dict[str, dict]) -> List[str]:
    """Verify all internal and external $ref targets resolve cleanly."""
    errors = []

    def check_node(node: Any, current_spec_name: str, path: str):
        if isinstance(node, dict):
            for k, v in node.items():
                if k == "$ref":
                    ref_str = str(v)
                    try:
                        if ref_str.startswith("#/"):
                            # Local ref
                            target = all_specs[current_spec_name]
                            for seg in ref_str.lstrip("#/").split("/"):
                                target = target[seg]
                        elif "#/" in ref_str:
                            # External ref (e.g. problem-detail.yaml#/components/schemas/ProblemDetail)
                            ext_file, local_path = ref_str.split("#/")
                            if ext_file not in all_specs:
                                errors.append(f"{path}: External ref file '{ext_file}' not found")
                                continue
                            target = all_specs[ext_file]
                            for seg in local_path.split("/"):
                                target = target[seg]
                        else:
                            errors.append(f"{path}: Unsupported ref format '{ref_str}'")
                    except KeyError as e:
                        errors.append(f"{path}: Unresolved $ref '{ref_str}' (missing key: {e})")
                else:
                    check_node(v, current_spec_name, f"{path}.{k}")
        elif isinstance(node, list):
            for idx, item in enumerate(node):
                check_node(item, current_spec_name, f"{path}[{idx}]")

    for name, s in all_specs.items():
        check_node(s, name, name)
    return errors


def validate_openapi_contracts() -> Dict[str, Any]:
    """Validate OpenAPI structure and references."""
    rest_dir = CONTRACTS_DIR / "rest"
    specs = {}
    for yaml_path in sorted(rest_dir.glob("*.yaml")):
        content = yaml_path.read_text(encoding="utf-8")
        parsed = yaml.safe_load(content)
        if not isinstance(parsed, dict):
            raise ContractValidationError(f"Invalid YAML root in {yaml_path.name}")
        if "openapi" not in parsed:
            raise ContractValidationError(f"Missing 'openapi' field in {yaml_path.name}")
        specs[yaml_path.name] = parsed

    ref_errors = resolve_refs(specs, specs)
    if ref_errors:
        raise ContractValidationError(f"OpenAPI $ref resolution errors:\n" + "\n".join(ref_errors))

    return {
        "status": "VALID",
        "specs_count": len(specs),
        "specs": list(specs.keys()),
    }


def check_openapi_compatibility(base_ref: str) -> List[str]:
    """Check for breaking changes in OpenAPI specs relative to git base_ref."""
    breaking_changes = []
    rest_dir = CONTRACTS_DIR / "rest"

    for yaml_path in sorted(rest_dir.glob("*.yaml")):
        rel_path = f"contracts/rest/{yaml_path.name}"
        base_content = get_git_file_content(base_ref, rel_path)
        if not base_content:
            continue  # New file in PR, no breaking change against base

        base_spec = yaml.safe_load(base_content)
        head_spec = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))

        base_paths = base_spec.get("paths", {})
        head_paths = head_spec.get("paths", {})

        # 1. Check for removed endpoints
        for path, path_item in base_paths.items():
            if path not in head_paths:
                breaking_changes.append(f"{yaml_path.name}: Endpoint '{path}' was removed")
                continue

            head_path_item = head_paths[path]
            http_methods = ["get", "post", "put", "delete", "patch", "options", "head"]
            for method in http_methods:
                if method in path_item and method not in head_path_item:
                    breaking_changes.append(f"{yaml_path.name}: Method {method.upper()} '{path}' was removed")
                    continue

                if method in path_item and method in head_path_item:
                    base_op = path_item[method]
                    head_op = head_path_item[method]

                    # Check for newly required query/header parameters
                    base_params = {p.get("name"): p for p in base_op.get("parameters", []) if isinstance(p, dict)}
                    head_params = {p.get("name"): p for p in head_op.get("parameters", []) if isinstance(p, dict)}

                    for p_name, h_param in head_params.items():
                        if h_param.get("required") is True:
                            if p_name not in base_params or base_params[p_name].get("required") is not True:
                                if "default" not in h_param.get("schema", {}):
                                    breaking_changes.append(
                                        f"{yaml_path.name}: {method.upper()} '{path}' added new required parameter '{p_name}' without default"
                                    )

                    # Check for removed success response codes
                    base_responses = base_op.get("responses", {})
                    head_responses = head_op.get("responses", {})
                    for code in ["200", "201", "204"]:
                        if code in base_responses and code not in head_responses:
                            breaking_changes.append(
                                f"{yaml_path.name}: {method.upper()} '{path}' removed success response {code}"
                            )

    return breaking_changes


# ==============================================================================
# 2. GraphQL SDL Validation & Compatibility
# ==============================================================================

def validate_graphql_sdl() -> Dict[str, Any]:
    """Validate GraphQL SDL syntax and read-only invariants."""
    sdl_path = CONTRACTS_DIR / "graphql" / "schema.graphql"
    if not sdl_path.exists():
        raise ContractValidationError(f"GraphQL schema not found at {sdl_path}")

    sdl_text = sdl_path.read_text(encoding="utf-8")
    try:
        schema = graphql.build_schema(sdl_text)
    except Exception as e:
        raise ContractValidationError(f"GraphQL SDL syntax error: {e}")

    errors = graphql.validate_schema(schema)
    if errors:
        raise ContractValidationError(f"GraphQL schema validation errors: {[str(e) for e in errors]}")

    # Enforce Read-Only constraint
    if schema.mutation_type is not None:
        raise ContractValidationError("VIOLATION: GraphQL schema contains Mutation types! SwiftDrop GraphQL API must be strictly READ-ONLY.")

    if schema.subscription_type is not None:
        raise ContractValidationError("VIOLATION: GraphQL schema contains Subscription types! SwiftDrop uses SSE for live updates, not GraphQL subscriptions.")

    query_fields = list(schema.query_type.fields.keys()) if schema.query_type else []

    return {
        "status": "VALID",
        "query_fields": query_fields,
        "is_read_only": True,
    }


def check_graphql_compatibility(base_ref: str) -> List[str]:
    """Check for breaking changes in GraphQL schema relative to git base_ref."""
    breaking_changes = []
    rel_path = "contracts/graphql/schema.graphql"
    base_sdl = get_git_file_content(base_ref, rel_path)
    if not base_sdl:
        return breaking_changes

    head_sdl = (CONTRACTS_DIR / "graphql" / "schema.graphql").read_text(encoding="utf-8")

    try:
        base_schema = graphql.build_schema(base_sdl)
        head_schema = graphql.build_schema(head_sdl)
    except Exception as e:
        breaking_changes.append(f"Failed to parse GraphQL schemas for diff: {e}")
        return breaking_changes

    # 1. Check Query fields
    base_queries = base_schema.query_type.fields if base_schema.query_type else {}
    head_queries = head_schema.query_type.fields if head_schema.query_type else {}

    for q_name, base_field in base_queries.items():
        if q_name not in head_queries:
            breaking_changes.append(f"GraphQL Query field '{q_name}' was removed")
            continue

        head_field = head_queries[q_name]
        # Check newly required arguments without defaults
        base_args = base_field.args
        head_args = head_field.args
        for arg_name, h_arg in head_args.items():
            if isinstance(h_arg.type, graphql.GraphQLNonNull) and arg_name not in base_args:
                if h_arg.default_value is None:
                    breaking_changes.append(
                        f"GraphQL Query '{q_name}' added non-nullable argument '{arg_name}' without default"
                    )

    # 2. Check Object Types & Fields
    for type_name, base_type in base_schema.type_map.items():
        if type_name.startswith("__"):
            continue
        if type_name not in head_schema.type_map:
            breaking_changes.append(f"GraphQL Type '{type_name}' was removed")
            continue

        head_type = head_schema.type_map[type_name]
        if isinstance(base_type, graphql.GraphQLObjectType) and isinstance(head_type, graphql.GraphQLObjectType):
            for field_name in base_type.fields:
                if field_name not in head_type.fields:
                    breaking_changes.append(f"GraphQL Field '{type_name}.{field_name}' was removed")

        if isinstance(base_type, graphql.GraphQLEnumType) and isinstance(head_type, graphql.GraphQLEnumType):
            for val in base_type.values:
                if val not in head_type.values:
                    breaking_changes.append(f"GraphQL Enum value '{type_name}.{val}' was removed")

    return breaking_changes


# ==============================================================================
# 3. Kafka Event Schema Validation & Invariants
# ==============================================================================

def validate_kafka_event_schemas() -> Dict[str, Any]:
    """Validate JSON schema 2020-12 and example domain events."""
    events_dir = CONTRACTS_DIR / "events"
    schema_file = events_dir / "parcel.status.changed.v1.schema.json"
    example_file = events_dir / "parcel.status.changed.v1.example.json"

    if not schema_file.exists():
        raise ContractValidationError(f"Event schema not found: {schema_file}")
    if not example_file.exists():
        raise ContractValidationError(f"Event example not found: {example_file}")

    schema = json.loads(schema_file.read_text(encoding="utf-8"))
    example = json.loads(example_file.read_text(encoding="utf-8"))

    # 1. Validate meta-schema
    validator_cls = jsonschema.validators.validator_for(schema)
    validator_cls.check_schema(schema)
    validator = validator_cls(schema, format_checker=validator_cls.FORMAT_CHECKER)

    # 2. Validate example against schema
    validator.validate(instance=example)

    # 3. Verify schema invariants
    if schema.get("additionalProperties") is not False:
        raise ContractValidationError("Event envelope schema MUST have 'additionalProperties: false'")

    payload_prop = schema.get("properties", {}).get("payload", {})
    if payload_prop.get("additionalProperties") is not False:
        raise ContractValidationError("Event payload schema MUST have 'additionalProperties: false' to prevent private customer data leakage")

    # 4. Negative test cases: prove schema rejects invalid payloads
    negative_tests = [
        ("Forbidden private customer data injection", {**example, "payload": {**example["payload"], "recipientPhone": "+94771234567"}}),
        ("Invalid status enum value", {**example, "payload": {**example["payload"], "toStatus": "UNKNOWN_TELEPORT"}}),
        ("Missing required aggregateVersion", {k: v for k, v in example.items() if k != "aggregateVersion"}),
        ("Invalid UUID format for aggregateId", {**example, "aggregateId": "not-a-uuid"}),
        ("Negative aggregateVersion", {**example, "aggregateVersion": -5}),
    ]

    for desc, bad_payload in negative_tests:
        try:
            validator.validate(instance=bad_payload)
            raise ContractValidationError(f"Negative test failed: Schema unexpectedly accepted invalid payload: {desc}")
        except jsonschema.ValidationError:
            pass  # Expected rejection

    return {
        "status": "VALID",
        "schema_id": schema.get("$id"),
        "negative_tests_passed": len(negative_tests),
    }


def check_kafka_compatibility(base_ref: str) -> List[str]:
    """Check for breaking changes in event schema relative to git base_ref."""
    breaking_changes = []
    rel_path = "contracts/events/parcel.status.changed.v1.schema.json"
    base_content = get_git_file_content(base_ref, rel_path)
    if not base_content:
        return breaking_changes

    base_schema = json.loads(base_content)
    head_schema = json.loads((CONTRACTS_DIR / "events" / "parcel.status.changed.v1.schema.json").read_text(encoding="utf-8"))

    # Required fields in envelope must not be removed
    base_req = set(base_schema.get("required", []))
    head_req = set(head_schema.get("required", []))
    removed_req = base_req - head_req
    if removed_req:
        breaking_changes.append(f"Event envelope removed required fields: {removed_req}")

    # Check status enum compatibility
    base_statuses = set(base_schema.get("properties", {}).get("payload", {}).get("properties", {}).get("toStatus", {}).get("enum", []))
    head_statuses = set(head_schema.get("properties", {}).get("payload", {}).get("properties", {}).get("toStatus", {}).get("enum", []))
    removed_statuses = base_statuses - head_statuses
    if removed_statuses:
        breaking_changes.append(f"Event payload removed status enum values: {removed_statuses}")

    return breaking_changes


# ==============================================================================
# 4. Protobuf Gate & Issue #92 Tracking
# ==============================================================================

def inspect_protobuf_gate() -> Dict[str, Any]:
    """Inspect gRPC directory and evaluate Protobuf activation status."""
    grpc_dir = CONTRACTS_DIR / "grpc"
    proto_files = list(grpc_dir.glob("**/*.proto"))
    readme_file = grpc_dir / "README.md"

    if proto_files:
        return {
            "status": "PROTO_FILES_PRESENT",
            "proto_files": [f.name for f in proto_files],
            "message": f"Found {len(proto_files)} .proto file(s). Active protoc/buf linting required.",
        }
    else:
        # Documented dependency gap per Section 11 & Issue #92
        has_readme = readme_file.exists()
        return {
            "status": "DEPENDENCY_PENDING_ISSUE_92",
            "proto_files": [],
            "readme_handoff_present": has_readme,
            "message": (
                "No .proto files exist in contracts/grpc/. Specification handoff frozen in "
                "contracts/grpc/README.md. Protobuf generation and contract testing are assigned to "
                "@Sinthuha-n under GitHub Issue #92. CI protobuf checks are marked as DEPENDENCY_PENDING."
            ),
        }


# ==============================================================================
# Main Runner
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(description="SwiftDrop Contract Validation & Compatibility Gate")
    parser.add_argument("--mode", choices=["all", "openapi", "graphql", "kafka", "protobuf"], default="all")
    parser.add_argument("--check-compatibility", action="store_true", help="Compare against base git reference")
    parser.add_argument("--base-ref", default="origin/test", help="Git base reference for compatibility diff (default: origin/test)")
    parser.add_argument("--output-json", help="Path to write JSON summary output")
    args = parser.parse_args()

    results: Dict[str, Any] = {"summary": "PASS", "modes": {}, "breaking_changes": []}
    has_failure = False

    print("================================================================================")
    print("                SWIFTDROP CONTRACT COMPATIBILITY & VALIDATION                   ")
    print("================================================================================")

    # 1. OpenAPI
    if args.mode in ("all", "openapi"):
        print("\n[GATE 1] Validating OpenAPI 3.0.3 REST Contracts...")
        try:
            res = validate_openapi_contracts()
            results["modes"]["openapi"] = res
            print(f"  ✓ OpenAPI structure and $ref integrity valid across {res['specs_count']} specifications.")
        except Exception as e:
            results["modes"]["openapi"] = {"status": "FAIL", "error": str(e)}
            print(f"  ✗ OpenAPI validation failed: {e}")
            has_failure = True

        if args.check_compatibility:
            print(f"  - Checking OpenAPI backward compatibility against {args.base_ref}...")
            breaks = check_openapi_compatibility(args.base_ref)
            if breaks:
                results["breaking_changes"].extend(breaks)
                for b in breaks:
                    print(f"    ✗ BREAKING CHANGE: {b}")
                has_failure = True
            else:
                print(f"    ✓ No breaking changes detected against {args.base_ref}.")

    # 2. GraphQL SDL
    if args.mode in ("all", "graphql"):
        print("\n[GATE 2] Validating GraphQL SDL Contracts...")
        try:
            res = validate_graphql_sdl()
            results["modes"]["graphql"] = res
            print(f"  ✓ GraphQL SDL valid ({len(res['query_fields'])} Query fields). Read-only invariant enforced.")
        except Exception as e:
            results["modes"]["graphql"] = {"status": "FAIL", "error": str(e)}
            print(f"  ✗ GraphQL validation failed: {e}")
            has_failure = True

        if args.check_compatibility:
            print(f"  - Checking GraphQL backward compatibility against {args.base_ref}...")
            breaks = check_graphql_compatibility(args.base_ref)
            if breaks:
                results["breaking_changes"].extend(breaks)
                for b in breaks:
                    print(f"    ✗ BREAKING CHANGE: {b}")
                has_failure = True
            else:
                print(f"    ✓ No breaking changes detected against {args.base_ref}.")

    # 3. Kafka Event Schema
    if args.mode in ("all", "kafka"):
        print("\n[GATE 3] Validating Kafka Domain Event Schemas...")
        try:
            res = validate_kafka_event_schemas()
            results["modes"]["kafka"] = res
            print(f"  ✓ Kafka JSON Schema 2020-12 valid. All {res['negative_tests_passed']} negative tests rejected as expected.")
        except Exception as e:
            results["modes"]["kafka"] = {"status": "FAIL", "error": str(e)}
            print(f"  ✗ Kafka event schema validation failed: {e}")
            has_failure = True

        if args.check_compatibility:
            print(f"  - Checking Kafka event schema compatibility against {args.base_ref}...")
            breaks = check_kafka_compatibility(args.base_ref)
            if breaks:
                results["breaking_changes"].extend(breaks)
                for b in breaks:
                    print(f"    ✗ BREAKING CHANGE: {b}")
                has_failure = True
            else:
                print(f"    ✓ No breaking changes detected against {args.base_ref}.")

    # 4. Protobuf Gate
    if args.mode in ("all", "protobuf"):
        print("\n[GATE 4] Inspecting Protobuf / gRPC Gate...")
        proto_info = inspect_protobuf_gate()
        results["modes"]["protobuf"] = proto_info
        if proto_info["status"] == "DEPENDENCY_PENDING_ISSUE_92":
            print(f"  ℹ {proto_info['message']}")
        else:
            print(f"  ✓ Protobuf files detected: {proto_info['proto_files']}")

    print("\n================================================================================")
    if has_failure:
        results["summary"] = "FAIL"
        print("RESULT: CONTRACT VALIDATION FAILED")
        if args.output_json:
            Path(args.output_json).write_text(json.dumps(results, indent=2), encoding="utf-8")
        sys.exit(1)
    else:
        results["summary"] = "PASS"
        print("RESULT: ALL CONTRACT VALIDATIONS & COMPATIBILITY CHECKS PASSED")
        if args.output_json:
            Path(args.output_json).write_text(json.dumps(results, indent=2), encoding="utf-8")
        sys.exit(0)


if __name__ == "__main__":
    main()
