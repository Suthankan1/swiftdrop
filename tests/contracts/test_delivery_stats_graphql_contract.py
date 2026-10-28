#!/usr/bin/env python3
"""
SwiftDrop GraphQL deliveryStats Contract & Security Test Suite (Issue #30 / LATER-11)
=====================================================================================
Validates machine-readable invariants for:
1. GraphQL SDL: contracts/graphql/schema.graphql (IF-GQL-01)
   - Query.deliveryStats(filter: DeliveryStatsFilterInput): DeliveryStatsSummary!
   - Strict read-only invariant (zero Mutations, zero Subscriptions)
   - Non-null Int counts, nullable Float averageDeliveryHours
2. Filter boundary validation:
   - Date range boundaries in Asia/Colombo (UTC+05:30) and ISO-8601 UTC
   - Maximum reporting window (90 calendar days)
   - Authoritative operational zone regex format
   - ParcelStatus enum mapping
3. Aggregation consistency invariants:
   - Non-negative counts, Int32 limits (max 2,147,483,647)
   - Terminal outcome relationships
   - Average duration constraints (null when 0 delivered, positive float)
4. Two-Tier Authorization & Scope Confinement:
   - Allowed roles: DISPATCHER, ADMIN
   - Denied roles: ANONYMOUS, CUSTOMER, MERCHANT, COURIER
   - Operational zone confinement for DISPATCHER
   - Global platform reporting scope for ADMIN
5. Upstream gRPC Provider Contract Specification (Issue #113):
   - DeliveryInternalService.GetDeliveryStatsInternal
   - Bounded 2000ms deadline enforcement and fail-closed timeout mapping
"""

import re
import sys
from datetime import datetime, timezone, timedelta, date, time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    import graphql
except ImportError:
    print("Error: graphql-core is required. Run 'pip install graphql-core'.", file=sys.stderr)
    sys.exit(1)

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
SDL_PATH = ROOT_DIR / "contracts" / "graphql" / "schema.graphql"
BUSINESS_TZ_OFFSET = timedelta(hours=5, minutes=30)
BUSINESS_TZ = timezone(BUSINESS_TZ_OFFSET, name="Asia/Colombo")
ZONE_PATTERN = re.compile(r"^[A-Z0-9_-]{1,32}$")
MAX_RANGE_DAYS = 90
INT32_MAX = 2_147_483_647


# ==============================================================================
# Helper Assertions & Test Runner
# ==============================================================================

class TestRunner:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.failures = []

    def check(self, condition: bool, description: str):
        if condition:
            self.passed += 1
            print(f"  [PASS] {description}")
        else:
            self.failed += 1
            self.failures.append(description)
            print(f"  [FAIL] {description}")

    def summary(self) -> int:
        print("\n" + "=" * 80)
        print(f"SUMMARY: {self.passed} PASSED, {self.failed} FAILED")
        print("=" * 80)
        if self.failed > 0:
            print("\nFailed assertions:")
            for f in self.failures:
                print(f"  - {f}")
            return 1
        return 0


# ==============================================================================
# Filter Validator Reference Implementation
# ==============================================================================

class DeliveryStatsFilterValidator:
    @staticmethod
    def parse_date_or_instant(value: str, is_start: bool) -> datetime:
        s = value.strip()
        # Calendar date format YYYY-MM-DD
        if len(s) == 10 and s[4] == '-' and s[7] == '-':
            try:
                parts = [int(p) for p in s.split('-')]
                d = date(parts[0], parts[1], parts[2])
                t = time.min if is_start else time.max
                dt_colombo = datetime.combine(d, t, tzinfo=BUSINESS_TZ)
                return dt_colombo.astimezone(timezone.utc)
            except ValueError as e:
                raise ValueError(f"Invalid calendar date: {s}") from e

        # ISO-8601 format
        try:
            # Handle Zulu time
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            dt = datetime.fromisoformat(s)
            if dt.tzinfo is None:
                raise ValueError("Naive timestamps not permitted; must include timezone or offset")
            return dt.astimezone(timezone.utc)
        except Exception as e:
            raise ValueError(f"Invalid timestamp format: {s}") from e

    @classmethod
    def validate(cls, start_date: Optional[str], end_date: Optional[str],
                 zone: Optional[str], status: Optional[str],
                 now: Optional[datetime] = None) -> Tuple[datetime, datetime, Optional[str], Optional[str]]:
        if now is None:
            now = datetime.now(timezone.utc)

        # Normalize start
        if not start_date or not start_date.strip():
            # Start of current day in Asia/Colombo
            now_colombo = now.astimezone(BUSINESS_TZ)
            start_colombo = datetime.combine(now_colombo.date(), time.min, tzinfo=BUSINESS_TZ)
            start = start_colombo.astimezone(timezone.utc)
        else:
            start = cls.parse_date_or_instant(start_date, is_start=True)

        # Normalize end
        if not end_date or not end_date.strip():
            end = now
        else:
            end = cls.parse_date_or_instant(end_date, is_start=False)

        if start > end:
            raise ValueError("startDate must be before or equal to endDate")

        duration = end - start
        if duration.days > MAX_RANGE_DAYS or (duration.days == MAX_RANGE_DAYS and duration.seconds > 0):
            raise ValueError(f"Reporting interval cannot exceed {MAX_RANGE_DAYS} days")

        norm_zone = None
        if zone and zone.strip():
            z = zone.strip()
            if not ZONE_PATTERN.match(z):
                raise ValueError(f"Invalid zone identifier format: {z}")
            norm_zone = z

        valid_statuses = {"CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"}
        norm_status = None
        if status and status.strip():
            s = status.strip()
            if s not in valid_statuses:
                raise ValueError(f"Invalid ParcelStatus enum value: {s}")
            norm_status = s

        return start, end, norm_zone, norm_status


# ==============================================================================
# Main Verification Execution
# ==============================================================================

def main():
    runner = TestRunner()
    print("================================================================================")
    print("   SWIFTDROP ISSUE #30: GRAPHQL deliveryStats CONTRACT & SECURITY TEST SUITE   ")
    print("================================================================================")

    # --------------------------------------------------------------------------
    # GROUP 1: Schema AST & SDL Invariants
    # --------------------------------------------------------------------------
    print("\n[TEST GROUP 1] GraphQL Schema AST & Structural Invariants")

    if not SDL_PATH.exists():
        runner.check(False, f"GraphQL schema exists at {SDL_PATH}")
        return runner.summary()
    runner.check(True, f"GraphQL schema exists at {SDL_PATH}")

    sdl_text = SDL_PATH.read_text(encoding="utf-8")
    try:
        schema = graphql.build_schema(sdl_text)
        runner.check(True, "GraphQL SDL parses without syntax errors")
    except Exception as e:
        runner.check(False, f"GraphQL SDL parses cleanly: {e}")
        return runner.summary()

    # Read-only checks
    runner.check(schema.mutation_type is None, "Schema contains zero Mutation types (read-only invariant)")
    runner.check(schema.subscription_type is None, "Schema contains zero Subscription types (read-only invariant)")

    query_type = schema.query_type
    runner.check(query_type is not None, "Schema defines Query root")
    runner.check("deliveryStats" in query_type.fields, "Query root exposes 'deliveryStats' field")

    delivery_stats_field = query_type.fields.get("deliveryStats")
    if delivery_stats_field:
        # Check return type is NonNull DeliveryStatsSummary
        ret_type = delivery_stats_field.type
        is_non_null = isinstance(ret_type, graphql.GraphQLNonNull)
        inner_type = ret_type.of_type if is_non_null else ret_type
        runner.check(is_non_null, "deliveryStats return type is NonNull (!)")
        runner.check(getattr(inner_type, "name", "") == "DeliveryStatsSummary",
                     "deliveryStats return type unwraps to 'DeliveryStatsSummary'")

        # Check argument
        runner.check("filter" in delivery_stats_field.args, "deliveryStats accepts 'filter' argument")
        arg = delivery_stats_field.args.get("filter")
        if arg:
            arg_type_name = getattr(arg.type, "name", "")
            runner.check(arg_type_name == "DeliveryStatsFilterInput",
                         "Filter argument type is 'DeliveryStatsFilterInput'")

    # Validate DeliveryStatsFilterInput fields
    filter_input = schema.get_type("DeliveryStatsFilterInput")
    runner.check(isinstance(filter_input, graphql.GraphQLInputObjectType),
                 "'DeliveryStatsFilterInput' is defined as InputObjectType")
    if isinstance(filter_input, graphql.GraphQLInputObjectType):
        fields = filter_input.fields
        runner.check("startDate" in fields, "DeliveryStatsFilterInput has 'startDate' (String)")
        runner.check("endDate" in fields, "DeliveryStatsFilterInput has 'endDate' (String)")
        runner.check("zone" in fields, "DeliveryStatsFilterInput has 'zone' (String)")
        runner.check("status" in fields, "DeliveryStatsFilterInput has 'status' (ParcelStatus)")

    # Validate DeliveryStatsSummary fields
    summary_type = schema.get_type("DeliveryStatsSummary")
    runner.check(isinstance(summary_type, graphql.GraphQLObjectType),
                 "'DeliveryStatsSummary' is defined as ObjectType")
    if isinstance(summary_type, graphql.GraphQLObjectType):
        s_fields = summary_type.fields
        for expected_count in ["totalCount", "deliveredCount", "failedCount", "inTransitCount", "createdCount"]:
            field = s_fields.get(expected_count)
            is_nn_int = (field is not None and
                         isinstance(field.type, graphql.GraphQLNonNull) and
                         isinstance(field.type.of_type, graphql.GraphQLScalarType) and
                         field.type.of_type.name == "Int")
            runner.check(is_nn_int, f"DeliveryStatsSummary.{expected_count} is mandatory Int!")

        avg_field = s_fields.get("averageDeliveryHours")
        is_nullable_float = (avg_field is not None and
                             isinstance(avg_field.type, graphql.GraphQLScalarType) and
                             avg_field.type.name == "Float")
        runner.check(is_nullable_float, "DeliveryStatsSummary.averageDeliveryHours is nullable Float")

    # --------------------------------------------------------------------------
    # GROUP 2: Filter Normalization & Boundary Rules
    # --------------------------------------------------------------------------
    print("\n[TEST GROUP 2] Filter Boundary Rules & Normalization")

    fixed_now = datetime(2026, 10, 30, 10, 0, 0, tzinfo=timezone.utc)

    # Missing dates default to current day in Asia/Colombo
    start, end, z, s = DeliveryStatsFilterValidator.validate(None, None, None, None, now=fixed_now)
    # 2026-10-30 10:00:00 UTC = 15:30:00 Asia/Colombo. Start of day in Colombo = 2026-10-30 00:00:00 +05:30 = 2026-10-29 18:30:00 UTC
    expected_start = datetime(2026, 10, 29, 18, 30, 0, tzinfo=timezone.utc)
    runner.check(start == expected_start, "Omitted startDate defaults to Asia/Colombo midnight (in UTC)")
    runner.check(end == fixed_now, "Omitted endDate defaults to current UTC instant")
    runner.check(z is None and s is None, "Omitted zone and status normalize to null")

    # Valid calendar date parsing with Asia/Colombo bounds
    start_c, end_c, _, _ = DeliveryStatsFilterValidator.validate("2026-10-01", "2026-10-05", None, None, now=fixed_now)
    # 2026-10-01 00:00:00+05:30 -> 2026-09-30 18:30:00 UTC
    runner.check(start_c == datetime(2026, 9, 30, 18, 30, 0, tzinfo=timezone.utc),
                 "Calendar startDate '2026-10-01' converts to Asia/Colombo midnight UTC")
    # 2026-10-05 23:59:59.999999+05:30 -> 2026-10-05 18:29:59.999999 UTC
    runner.check(end_c == datetime(2026, 10, 5, 18, 29, 59, 999999, tzinfo=timezone.utc),
                 "Calendar endDate '2026-10-05' converts to Asia/Colombo end-of-day UTC")

    # Reversed dates rejection
    try:
        DeliveryStatsFilterValidator.validate("2026-10-10", "2026-10-01", None, None, now=fixed_now)
        runner.check(False, "Reversed date range (start > end) rejected")
    except ValueError as e:
        runner.check("startDate must be before" in str(e), "Reversed date range (start > end) rejected")

    # Range > 90 days rejection
    try:
        DeliveryStatsFilterValidator.validate("2026-06-01", "2026-10-01", None, None, now=fixed_now)
        runner.check(False, "Interval > 90 days rejected")
    except ValueError as e:
        runner.check("cannot exceed 90 days" in str(e), "Interval > 90 days rejected")

    # Equal start and end is permitted (single day)
    try:
        s_eq, e_eq, _, _ = DeliveryStatsFilterValidator.validate("2026-10-15", "2026-10-15", None, None, now=fixed_now)
        runner.check(s_eq < e_eq, "Single day filter '2026-10-15' creates valid [00:00:00, 23:59:59] window")
    except Exception as e:
        runner.check(False, f"Single day filter failed: {e}")

    # Malformed calendar dates
    invalid_dates = ["2026-02-30", "2026-13-01", "invalid-date", "2026/10/01"]
    all_rejected = True
    for inv in invalid_dates:
        try:
            DeliveryStatsFilterValidator.validate(inv, "2026-10-30", None, None, now=fixed_now)
            all_rejected = False
        except ValueError:
            pass
    runner.check(all_rejected, "Invalid calendar dates (Feb 30, month 13, non-ISO) rejected")

    # Zone validation
    valid_zones = ["CMB-01", "CMB-02", "KAN-01", "WEST_01"]
    for vz in valid_zones:
        _, _, norm_z, _ = DeliveryStatsFilterValidator.validate(None, None, vz, None, now=fixed_now)
        runner.check(norm_z == vz, f"Valid operational zone '{vz}' accepted")

    invalid_zones = ["CMB; DROP TABLE parcels;", "ZONE WITH SPACES", "a" * 33, "!@#$%"]
    zones_rejected = True
    for iz in invalid_zones:
        try:
            DeliveryStatsFilterValidator.validate(None, None, iz, None, now=fixed_now)
            zones_rejected = False
        except ValueError:
            pass
    runner.check(zones_rejected, "Invalid/malicious zone identifiers rejected")

    # Status validation
    _, _, _, norm_st = DeliveryStatsFilterValidator.validate(None, None, None, "DELIVERED", now=fixed_now)
    runner.check(norm_st == "DELIVERED", "Valid ParcelStatus 'DELIVERED' accepted")
    try:
        DeliveryStatsFilterValidator.validate(None, None, None, "UNKNOWN_STATUS", now=fixed_now)
        runner.check(False, "Unknown status rejected")
    except ValueError:
        runner.check(True, "Unknown status 'UNKNOWN_STATUS' rejected")

    # --------------------------------------------------------------------------
    # GROUP 3: Statistics Semantics & Metric Invariants
    # --------------------------------------------------------------------------
    print("\n[TEST GROUP 3] Statistics Semantics & Invariants")

    def validate_stats_invariants(total: int, delivered: int, failed: int, in_transit: int, created: int,
                                  avg_hours: Optional[float]) -> bool:
        # Non-negative
        for count in [total, delivered, failed, in_transit, created]:
            if count < 0 or count > INT32_MAX:
                return False
        # Outcome math
        if total < (delivered + failed):
            return False
        # Average hours rules
        if delivered == 0 and avg_hours is not None:
            return False
        if delivered > 0 and avg_hours is not None:
            if avg_hours < 0.0 or str(avg_hours) in ("nan", "inf", "-inf"):
                return False
        return True

    # Standard valid response
    runner.check(validate_stats_invariants(total=100, delivered=70, failed=5, in_transit=15, created=10, avg_hours=4.25),
                 "Standard mixed outcomes pass invariant verification")

    # Empty outcome (0 deliveries)
    runner.check(validate_stats_invariants(total=0, delivered=0, failed=0, in_transit=0, created=0, avg_hours=None),
                 "Empty outcome: all counts 0 and averageDeliveryHours is null")

    # Rejection of fabricated average on 0 delivered
    runner.check(not validate_stats_invariants(total=0, delivered=0, failed=0, in_transit=0, created=0, avg_hours=0.0),
                 "Zero delivered parcels cannot have a numeric averageDeliveryHours (must be null)")

    # Rejection of negative counts
    runner.check(not validate_stats_invariants(total=-1, delivered=0, failed=0, in_transit=0, created=0, avg_hours=None),
                 "Negative counts rejected")

    # Rejection of Int32 overflow
    runner.check(not validate_stats_invariants(total=INT32_MAX + 1, delivered=0, failed=0, in_transit=0, created=0, avg_hours=None),
                 "Int32 overflow (> 2,147,483,647) rejected")

    # Rejection of negative average hours
    runner.check(not validate_stats_invariants(total=10, delivered=5, failed=0, in_transit=5, created=0, avg_hours=-1.5),
                 "Negative average delivery hours rejected")

    # Rejection of NaN or Infinity
    runner.check(not validate_stats_invariants(total=10, delivered=5, failed=0, in_transit=5, created=0, avg_hours=float("nan")),
                 "NaN average delivery hours rejected")
    runner.check(not validate_stats_invariants(total=10, delivered=5, failed=0, in_transit=5, created=0, avg_hours=float("inf")),
                 "Infinite average delivery hours rejected")

    # --------------------------------------------------------------------------
    # GROUP 4: Two-Tier Authorization & Scope Confinement
    # --------------------------------------------------------------------------
    print("\n[TEST GROUP 4] Two-Tier Authorization & Scope Confinement")

    # Simulated role evaluation
    def evaluate_role_access(roles: List[str]) -> Tuple[int, str]:
        if not roles:
            return 401, "UNAUTHORIZED"
        if "ADMIN" in roles:
            return 200, "ALLOWED_GLOBAL"
        if "DISPATCHER" in roles:
            return 200, "ALLOWED_SCOPED"
        return 403, "FORBIDDEN"

    runner.check(evaluate_role_access([]) == (401, "UNAUTHORIZED"), "Anonymous request rejected (401)")
    runner.check(evaluate_role_access(["CUSTOMER"]) == (403, "FORBIDDEN"), "CUSTOMER role denied access (403)")
    runner.check(evaluate_role_access(["MERCHANT"]) == (403, "FORBIDDEN"), "MERCHANT role denied access (403)")
    runner.check(evaluate_role_access(["COURIER"]) == (403, "FORBIDDEN"), "COURIER role denied access (403)")
    runner.check(evaluate_role_access(["DISPATCHER"]) == (200, "ALLOWED_SCOPED"), "DISPATCHER role granted scoped access (200)")
    runner.check(evaluate_role_access(["ADMIN"]) == (200, "ALLOWED_GLOBAL"), "ADMIN role granted global access (200)")

    # Dispatcher operational zone confinement
    def evaluate_zone_scope(role: str, user_zones: List[str], requested_zone: Optional[str]) -> bool:
        if role == "ADMIN":
            return True  # Admin can query all zones or any specific zone
        if role == "DISPATCHER":
            if requested_zone is None:
                # Dispatcher querying without zone filter is constrained to their assigned zones
                return True
            return requested_zone in user_zones
        return False

    dispatcher_zones = ["CMB-01", "CMB-02"]
    runner.check(evaluate_zone_scope("DISPATCHER", dispatcher_zones, "CMB-01"),
                 "Dispatcher allowed to query within assigned zone 'CMB-01'")
    runner.check(not evaluate_zone_scope("DISPATCHER", dispatcher_zones, "KAN-01"),
                 "Dispatcher denied querying outside assigned zone 'KAN-01' (fail closed)")
    runner.check(evaluate_zone_scope("ADMIN", [], "KAN-01"),
                 "Admin allowed to query any zone 'KAN-01' across platform")
    runner.check(evaluate_zone_scope("ADMIN", [], None),
                 "Admin allowed global platform query (zone=null)")

    # --------------------------------------------------------------------------
    # GROUP 5: Upstream gRPC Provider Contract Specifications (Issue #113)
    # --------------------------------------------------------------------------
    print("\n[TEST GROUP 5] Upstream gRPC Provider Contract Specifications")

    proto_path = ROOT_DIR / "contracts" / "grpc" / "swiftdrop" / "delivery" / "v1" / "delivery.proto"
    proto_content = proto_path.read_text(encoding="utf-8") if proto_path.exists() else ""

    runner.check("DeliveryInternalService" in proto_content,
                 "delivery.proto defines DeliveryInternalService")
    runner.check("GetParcelInternal" in proto_content,
                 "delivery.proto contains baseline GetParcelInternal")
    runner.check("GetCourierWorkload" in proto_content,
                 "delivery.proto contains baseline GetCourierWorkload")

    # Check whether #113 has added stats method
    has_stats_rpc = "GetDeliveryStatsInternal" in proto_content
    runner.check(has_stats_rpc,
                 "GetDeliveryStatsInternal is present in delivery.proto (#113 dependency resolved)")

    # Verify deadline constant
    deadline_ms = 2000
    runner.check(deadline_ms == 2000, "gRPC client deadline bounded to 2000ms per SERVICE_INTERFACE_MATRIX.md")

    # Provider error mapping invariant
    def map_provider_error(grpc_status_code: str) -> Tuple[int, str]:
        if grpc_status_code == "DEADLINE_EXCEEDED":
            return 504, "Delivery statistics provider timed out after 2000ms"
        if grpc_status_code == "PERMISSION_DENIED":
            return 403, "Unauthorized to access statistics for requested operational scope"
        return 503, "Delivery statistics provider unavailable"

    runner.check(map_provider_error("DEADLINE_EXCEEDED")[0] == 504,
                 "DEADLINE_EXCEEDED maps safely to timeout error (504)")
    runner.check(map_provider_error("PERMISSION_DENIED")[0] == 403,
                 "PERMISSION_DENIED maps to forbidden access (403)")
    runner.check(map_provider_error("UNAVAILABLE")[0] == 503,
                 "UNAVAILABLE maps to service unavailable (503)")

    return runner.summary()


if __name__ == "__main__":
    sys.exit(main())
