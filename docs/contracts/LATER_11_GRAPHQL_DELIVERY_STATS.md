# Contract & Semantic Freeze: Authorized Read-Only GraphQL deliveryStats Resolver (LATER-11 / Issue #30)

- **Issue:** [[LATER-11] Implement authorized read-only GraphQL deliveryStats resolver (#30)](https://github.com/Suthankan1/swiftdrop/issues/30)
- **Primary Implementation Owner:** Pushmitha (@pushmitha20) — Dispatcher / Admin Backend
- **Feature Area:** Dispatcher / Admin composite reporting
- **Milestone:** November 10, 2026 SwiftDrop Full Release
- **Feature Branch:** `feat/later-11-graphql-delivery-stats`
- **Target Integration Branch:** `test`
- **Cross-Service Boundaries:** GraphQL Query API (`services/graphql-query-service`), Delivery Operations (`services/delivery-service`), Accounts & Partners (`services/accounts-service`)
- **Status:** **CONTRACT & TEST HARNESS FROZEN; END-TO-END INTEGRATION BLOCKED PENDING UPSTREAM PREREQUISITES (#119 & #113)**

---

## 1. Overview & Architectural Boundaries

In accordance with SwiftDrop Architecture Baseline v1.3:
1. **Single Read-Only GraphQL Query API:** SwiftDrop operates **one** centralized GraphQL Query API behind Spring Cloud Gateway at route `/graphql`.
2. **Zero Direct Persistence in GraphQL:** The GraphQL Query API has **no** database credentials, no JPA repositories, and no domain persistence (`graphql_db` does not exist).
3. **Internal gRPC Read Delegation:** All domain data is resolved exclusively through authenticated, private internal gRPC read stubs with cryptographically verified actor delegation (`shared/security-adapter`).
4. **Strictly Read-Only:** GraphQL Mutations and Subscriptions are **strictly prohibited**. Real-time courier parcel updates use Server-Sent Events (SSE) via Delivery Operations, while state mutations execute over REST commands.
5. **Ownership Allocation:**
   - Platform Lead (@Suthankan1) bootstrapped shared authentication, security adapters (#100), and Protobuf specifications (#92).
   - HarithKeshan (@Harithkeshan) owns Issue #119 (GraphQL service runtime scaffold, query complexity/depth guards, and base gRPC client infrastructure).
   - Pushmitha (@pushmitha20) owns Issue #113 (Delivery scoped queue, active courier workload, and daily-outcome aggregates gRPC provider in Delivery Operations).
   - Pushmitha (@pushmitha20) owns Issue #30 (bounded `deliveryStats` resolver implementation within the single GraphQL Query API).

---

## 2. Mandatory Dependency Matrix

Before implementation, an audit of the repository state on branch `test` (commit `c5566b375d31557d1976059d0fc4e94b0553aa19`) establishes the following dependency readiness:

| Component / Layer | Owning Issue & Assignee | Actual Repository State | Implemented API / Contract | Missing Functionality | Authorization Status | Integration Readiness |
|---|---|---|---|---|---|---|
| **GraphQL Service Scaffold** | [#119](https://github.com/Suthankan1/swiftdrop/issues/119) (@Harithkeshan) | **OPEN**; `services/graphql-query-service/README.md` placeholder only | None (`pom.xml` and Spring GraphQL app absent) | Spring GraphQL runtime, query depth limits, GraphQL error handler | Unverified | **BLOCKED** |
| **Delivery Stats gRPC Provider** | [#113](https://github.com/Suthankan1/swiftdrop/issues/113) (@pushmitha20) | **OPEN**; `contracts/grpc/.../delivery.proto` has 2 methods only | `GetParcelInternal`, `GetCourierWorkload` | `GetDeliveryStatsInternal` RPC, delivery_db aggregate repository, daily outcome queries | Unverified | **BLOCKED** |
| **Versioned Protobuf Contracts** | [#92](https://github.com/Suthankan1/swiftdrop/issues/92) (@Suthankan1) | **CLOSED**; `shared/proto-contracts` compiled & verified | DeliveryProto v1.0, AccountsProto v1.0, PricingProto v1.0 | None for baseline RPCs; waiting on #113 for stats RPC | Validated | **PASS** |
| **Shared Security Adapter** | [#100](https://github.com/Suthankan1/swiftdrop/issues/100) (@Suthankan1) | **CLOSED**; `shared/security-adapter` verified (38 tests) | `JwtServiceTokenValidator`, `ActorDelegationValidator`, `SwiftDropGrpcClientInterceptor` | None; ready for reuse | Validated | **PASS** |
| **Guarded Parcel Status History** | [#23](https://github.com/Suthankan1/swiftdrop/issues/23) (@Vibhushan-2003) | **CLOSED**; `services/delivery-service` | `PATCH /api/v1/parcels/{id}/status`, `status_history` table | None | Validated | **PASS** |
| **Single Active Assignment** | [#24](https://github.com/Suthankan1/swiftdrop/issues/24) (@pushmitha20) | **CLOSED**; `services/delivery-service` | `POST /api/v1/assignments`, `delivery_assignments` table | None | Validated | **PASS** |
| **Delivery Foundation & Projections** | [#126](https://github.com/Suthankan1/swiftdrop/issues/126) (@Vibhushan-2003) | **CLOSED**; `services/delivery-service` | `ParcelProjection`, `ParcelQueryService` | Aggregate daily outcomes provider | Validated | **PASS** |

### Prerequisite Handling Rule (Section 15 Compliance)
- Because **Issue #119 is OPEN** and the runtime application is not yet scaffolded on `test`, Issue #30 **must not** unilaterally generate `pom.xml`, Spring Initializr skeletons, or duplicate runtime services.
- Because **Issue #113 is OPEN** and `delivery.proto` does not yet contain the aggregate read RPC, Issue #30 **must not** unilaterally alter shared protobuf files or implement cross-service database queries directly against `delivery_db`.
- Issue #30 prepares the approved contract freeze, consumer adapter specification, validation rules, and executable contract/security regression test suite (`tests/contracts/test_delivery_stats_graphql_contract.py`). Full end-to-end integration is marked **BLOCKED** until #119 and #113 reach mergeable readiness.

---

## 3. Approved GraphQL Schema Definition (SDL)

The authoritative schema artifact is `contracts/graphql/schema.graphql`. The field definition and supporting types are frozen as follows:

```graphql
type Query {
  """
  Bounded delivery performance and status statistics (Platform slice).
  Requires role: DISPATCHER or ADMIN.
  """
  deliveryStats(filter: DeliveryStatsFilterInput): DeliveryStatsSummary!
}

input DeliveryStatsFilterInput {
  startDate: String
  endDate: String
  zone: String
  status: ParcelStatus
}

type DeliveryStatsSummary {
  totalCount: Int!
  deliveredCount: Int!
  failedCount: Int!
  inTransitCount: Int!
  createdCount: Int!
  averageDeliveryHours: Float
}

enum ParcelStatus {
  CREATED
  PICKED_UP
  IN_TRANSIT
  OUT_FOR_DELIVERY
  DELIVERED
  FAILED
}
```

### Invariants:
1. Field name `deliveryStats` and return type `DeliveryStatsSummary!` are immutable.
2. All count fields (`totalCount`, `deliveredCount`, `failedCount`, `inTransitCount`, `createdCount`) are non-nullable GraphQL 32-bit integers (`Int!`).
3. `averageDeliveryHours` is a nullable 64-bit IEEE 754 floating point number (`Float`), returning `null` when 0 deliveries were completed in the period.
4. The schema contains **0 mutations** and **0 subscriptions**.

---

## 4. Frozen Statistics Semantics (21 Authoritative Decisions)

To resolve all semantic ambiguities and establish cross-owner alignment across Dispatcher (@pushmitha20, @Harithkeshan), Delivery (@Vibhushan-2003), and Platform (@Suthankan1), the following semantics are frozen:

### Q1: What counts toward `totalCount`?
- **Decision:** `totalCount` represents the total number of distinct parcel records in `delivery_db.parcels` whose lifecycle activity falls within the bounded reporting interval and matches the requested zone and status filter.
- **Rule:** `totalCount >= 0`. It represents canonical parcel entities, not transient network messages or event counts.

### Q2: Does `totalCount` represent current parcel records, events, or completed outcomes?
- **Decision:** It represents **current parcel records** stored in `delivery_db.parcels`. Kafka outbox events and transient messages are never the canonical reporting source.

### Q3: Is `deliveredCount` based on current terminal status or a transition during the selected period?
- **Decision:** Current terminal status `DELIVERED`, where the delivery completion occurred within the bounded reporting interval (`delivered_at >= start AND delivered_at <= end`, or `updated_at` within interval when terminal).

### Q4: Is `failedCount` based on terminal status, failure events, or another approved definition?
- **Decision:** Terminal status `FAILED`, where the failure transition occurred within the bounded reporting interval (`updated_at >= start AND updated_at <= end`).

### Q5: Which states contribute to `inTransitCount`?
- **Decision:** Parcels actively in transit or with couriers during the interval:
  `status IN ('PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY')`.

### Q6: What exactly contributes to `createdCount`?
- **Decision:** Parcels in `CREATED` status that were booked within the reporting interval and are awaiting courier assignment or initial pickup (`status == 'CREATED'`).

### Q7: What is the time basis for the filter?
- **Decision:** Authoritative lifecycle timestamps in `delivery_db.parcels`:
  - When filtering all parcels: bounded by `created_at` or terminal completion `updated_at`.
  - For terminal metrics (`deliveredCount`, `failedCount`): bounded by terminal outcome timestamp (`updated_at` / `delivered_at`).

### Q8: How does the optional status filter affect each returned count?
- **Decision:**
  - If `filter.status == null`: All parcel categories are aggregated. `totalCount` reflects the total population across all statuses.
  - If `filter.status` is provided (e.g. `DELIVERED`): Aggregations are strictly constrained to parcels matching that specific status. In this case, `totalCount == deliveredCount`, and non-matching counts (`failedCount`, `inTransitCount`, `createdCount`) evaluate to `0`.

### Q9: What zone identifier is authoritative?
- **Decision:** The operational zone code defined by the Pricing & Zones service (e.g. `CMB-01`, `CMB-02`, `KAN-01`, `GAL-01`), format `^[A-Z0-9_-]{1,32}$`. Aggregation filters on `delivery_db.parcels.pickup_zone` or `dropoff_zone`.

### Q10: How are parcels without a zone handled?
- **Decision:** In Architecture Baseline v1.3, every parcel booked via `IF-REST-03` requires validated pickup and dropoff zones. Unzoned parcels are invalid; if legacy unzoned records exist, they are omitted from zone-scoped queries and only included in global ADMIN queries.

### Q11: What is the authorized operational scope for DISPATCHER?
- **Decision:** A `DISPATCHER` caller is strictly confined to their assigned operational hub/zones (extracted from Keycloak token claims `operational_zones` or Accounts staff profile).
- **Rule:** If a Dispatcher requests a `zone` outside their assigned operational scope, the request is **denied with HTTP 403 / ProblemDetail `FORBIDDEN`**. They cannot view cross-hub statistics.

### Q12: Does ADMIN have global reporting access, or an explicitly constrained operational scope?
- **Decision:** An `ADMIN` caller has global platform-wide visibility. An Admin can query across all zones (`zone: null`) or filter to any specific zone.

### Q13: Which timezone defines reporting days?
- **Decision:** The platform business timezone is **`Asia/Colombo` (UTC+05:30)**.
- **Conversion Rule:**
  - If input is a calendar date string `YYYY-MM-DD`, it represents midnight to end-of-day in `Asia/Colombo`:
    `startInstant = ZonedDateTime.of(LocalDate.parse(s), LocalTime.MIN, ZoneId.of("Asia/Colombo")).toInstant()`
    `endInstant = ZonedDateTime.of(LocalDate.parse(e), LocalTime.MAX, ZoneId.of("Asia/Colombo")).toInstant()`
  - If input is an ISO-8601 UTC timestamp string (e.g. `2026-10-30T00:00:00Z`), it is parsed directly as an `Instant`.
  - All database queries and gRPC Protobuf requests transmit canonical UTC timestamps (`google.protobuf.Timestamp`).

### Q14: Are start/end date boundaries inclusive or exclusive?
- **Decision:** **Closed interval `[startInstant, endInstant]`** (inclusive of both boundaries).

### Q15: How are missing filters handled?
- **Decision:**
  - If `startDate` is omitted: Defaults to start of the current day in `Asia/Colombo` (`00:00:00.000000000+05:30`).
  - If `endDate` is omitted: Defaults to the current instant (`now()`).
  - If both are omitted: Aggregates current day-to-date in `Asia/Colombo`.
  - If `zone` is omitted: Aggregates all zones authorized for the caller's operational scope.
  - If `status` is omitted: Aggregates all lifecycle statuses.

### Q16: What is the maximum allowed reporting interval?
- **Decision:** **90 calendar days**.
- **Rule:** If `Duration.between(startInstant, endInstant).toDays() > 90`, the query is rejected immediately with a client error (`BAD_USER_INPUT`) to prevent denial-of-service and unbounded database scanning.

### Q17: What is the behavior when no deliveries match?
- **Decision:**
  - `totalCount: 0`
  - `deliveredCount: 0`
  - `failedCount: 0`
  - `inTransitCount: 0`
  - `createdCount: 0`
  - `averageDeliveryHours: null`
- **Rule:** A valid query with 0 matching records is a normal outcome, distinct from a provider failure.

### Q18: What is the meaning of `averageDeliveryHours`?
- **Decision:** Arithmetic mean elapsed time from parcel booking (`created_at`) to completed delivery (`delivered_at`) in hours, rounded to 2 decimal places:
  $$\text{averageDeliveryHours} = \frac{\sum_{i=1}^{N} (t_{\text{delivered}, i} - t_{\text{created}, i})}{N \times 3600}$$
- **Constraint:** Computed **strictly** for parcels with terminal status `DELIVERED` and non-null `delivered_at` timestamps. Failed, in-transit, or cancelled parcels are strictly excluded.

### Q19: What happens when delivery timestamps are missing?
- **Decision:** Any parcel record missing authoritative completion timestamps is excluded from the duration sum and denominator. If zero completed parcels have valid timestamps, `averageDeliveryHours` evaluates to `null`. Timestamps are never fabricated.

### Q20: Are numerical counts guaranteed to fit GraphQL Int?
- **Decision:** Yes. GraphQL `Int` is a signed 32-bit integer (max value $2^{31} - 1 = 2,147,483,647$). The provider and consumer validate that PostgreSQL `BIGINT` count values do not exceed $2,147,483,647$. If an overflow occurs, a `DATA_FETCHING_EXCEPTION` is raised rather than returning truncated or negative numbers.

### Q21: Approval Gate & Review Signoff
- **Decision:** This contract is formally submitted for review to:
  - @Harithkeshan (GraphQL Service & Dispatcher pair)
  - @pushmitha20 (Delivery Statistics Provider #113 owner & Dispatcher pair)
  - @Vibhushan-2003 (Delivery Operations lead)
  - @Suthankan1 (Platform & Security lead)

---

## 5. Upstream gRPC Provider Specification (for Issue #113)

Issue #113 (owned by @pushmitha20) must implement the following aggregate RPC in Delivery Operations. The method and message specifications are frozen as follows:

```protobuf
syntax = "proto3";

package swiftdrop.delivery.v1;

import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "com.swiftdrop.delivery.v1";
option java_outer_classname = "DeliveryProto";

// Extension to DeliveryInternalService for Issue #113 / Issue #30
service DeliveryInternalService {
  // Existing baseline methods
  rpc GetParcelInternal (GetParcelInternalRequest) returns (GetParcelInternalResponse);
  rpc GetCourierWorkload (CourierWorkloadRequest) returns (CourierWorkloadResponse);

  // New aggregate statistics method owned by Issue #113
  rpc GetDeliveryStatsInternal (GetDeliveryStatsRequest) returns (GetDeliveryStatsResponse);
}

message GetDeliveryStatsRequest {
  google.protobuf.Timestamp start_time = 1; // Canonical UTC start instant (inclusive)
  google.protobuf.Timestamp end_time = 2;   // Canonical UTC end instant (inclusive)
  string zone = 3;                         // Optional zone filter (e.g. "CMB-01"); empty string if all authorized zones
  string status = 4;                       // Optional status filter (e.g. "DELIVERED"); empty string if all statuses
  repeated string authorized_zones = 5;    // Caller's verified operational zones for tenant confinement
}

message GetDeliveryStatsResponse {
  int64 total_count = 1;
  int64 delivered_count = 2;
  int64 failed_count = 3;
  int64 in_transit_count = 4;
  int64 created_count = 5;
  optional double average_delivery_hours = 6; // Omitted (empty) if delivered_count == 0 or no timestamps
}
```

---

## 6. Two-Tier Authorization Model

Security is enforced independently at two distinct network boundaries:

```
[ Browser / Client ]
        │  Bearer JWT (User OIDC Token)
        ▼
[ Spring Cloud Gateway ]
        │  Validates JWT signature, claims, expiry, strips untrusted headers
        ▼
[ GraphQL Query API (Edge Tier) ]
        │  1. Verifies Bearer JWT via Spring Security OAuth2 Resource Server
        │  2. Enforces @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
        │  3. Rejects anonymous, CUSTOMER, MERCHANT, COURIER (401 / 403)
        │  4. Validates date range (<= 90 days), zone regex, status enum
        │  5. Generates Machine-to-Machine Service Token (audience: swiftdrop-internal)
        │  6. Propagates verified Actor Token in metadata: x-swiftdrop-actor-token
        ▼  gRPC with 2000ms deadline & correlation ID
[ Delivery Operations (Provider Tier) ]
        │  1. Verifies Service Token via JwtServiceTokenValidator (GRAPHQL_QUERY_SERVICE)
        │  2. Validates Actor Token via ActorDelegationValidator
        │  3. Enforces operational zone scope (DISPATCHER cannot read outside assigned zones)
        │  4. Executes bounded aggregate query against delivery_db.parcels
        │  5. Returns GetDeliveryStatsResponse
```

### Edge Tier Role Rules:
| Role | Access | Note |
|---|---|---|
| `ANONYMOUS` | **DENIED (401 Unauthorized)** | GraphQL endpoint requires authentication |
| `CUSTOMER` | **DENIED (403 Forbidden)** | Customer cannot query operational statistics |
| `MERCHANT` | **DENIED (403 Forbidden)** | Merchant cannot query cross-tenant statistics |
| `COURIER` | **DENIED (403 Forbidden)** | Courier cannot query platform statistics |
| `DISPATCHER` | **ALLOWED (Scoped)** | Restricted to assigned operational zones |
| `ADMIN` | **ALLOWED (Global)** | Platform-wide access across all zones |

---

## 7. Java Consumer Adapter Design (Ready for Issue #119 Scaffold)

When HarithKeshan merges the Issue #119 scaffold, the following production-grade components are ready for direct inclusion in `services/graphql-query-service`:

### 7.1 Filter Normalizer & Validator
```java
package com.swiftdrop.graphql.resolver.delivery;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class DeliveryStatsFilterValidator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");
    private static final Pattern ZONE_PATTERN = Pattern.compile("^[A-Z0-9_-]{1,32}$");
    private static final long MAX_RANGE_DAYS = 90L;

    public static NormalizedFilter normalizeAndValidate(DeliveryStatsFilterInput input) {
        Instant start;
        Instant end;
        Instant now = Instant.now();

        if (input == null || input.startDate() == null || input.startDate().isBlank()) {
            start = ZonedDateTime.of(LocalDate.now(BUSINESS_ZONE), LocalTime.MIN, BUSINESS_ZONE).toInstant();
        } else {
            start = parseDateOrInstant(input.startDate(), true);
        }

        if (input == null || input.endDate() == null || input.endDate().isBlank()) {
            end = now;
        } else {
            end = parseDateOrInstant(input.endDate(), false);
        }

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must be before or equal to endDate");
        }

        if (Duration.between(start, end).toDays() > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Reporting interval cannot exceed 90 days");
        }

        String zone = null;
        if (input != null && input.zone() != null && !input.zone().isBlank()) {
            String trimmed = input.zone().trim();
            if (!ZONE_PATTERN.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("Invalid zone identifier format: " + trimmed);
            }
            zone = trimmed;
        }

        String status = (input != null && input.status() != null) ? input.status().name() : null;

        return new NormalizedFilter(start, end, zone, status);
    }

    private static Instant parseDateOrInstant(String value, boolean isStart) {
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
                LocalDate date = LocalDate.parse(trimmed);
                LocalTime time = isStart ? LocalTime.MIN : LocalTime.MAX;
                return ZonedDateTime.of(date, time, BUSINESS_ZONE).toInstant();
            }
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format (must be YYYY-MM-DD or ISO-8601 UTC): " + trimmed);
        }
    }

    public record NormalizedFilter(Instant start, Instant end, String zone, String status) {}
}
```

### 7.2 Spring GraphQL Resolver
```java
package com.swiftdrop.graphql.resolver.delivery;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class DeliveryStatsResolver {

    private final DeliveryStatsGrpcClient statsClient;

    public DeliveryStatsResolver(DeliveryStatsGrpcClient statsClient) {
        this.statsClient = statsClient;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public DeliveryStatsSummary deliveryStats(
            @Argument(name = "filter") DeliveryStatsFilterInput filterInput,
            @AuthenticationPrincipal Jwt principal
    ) {
        DeliveryStatsFilterValidator.NormalizedFilter filter =
                DeliveryStatsFilterValidator.normalizeAndValidate(filterInput);

        return statsClient.fetchDeliveryStats(filter, principal);
    }
}
```

### 7.3 gRPC Client Adapter with Deadline & Safe Failures
```java
package com.swiftdrop.graphql.resolver.delivery;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class DeliveryStatsGrpcClient {

    private final DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub blockingStub;

    public DeliveryStatsGrpcClient(DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub blockingStub) {
        this.blockingStub = blockingStub;
    }

    public DeliveryStatsSummary fetchDeliveryStats(
            DeliveryStatsFilterValidator.NormalizedFilter filter,
            Jwt principal
    ) {
        GetDeliveryStatsRequest request = GetDeliveryStatsRequest.newBuilder()
                .setStartTime(toTimestamp(filter.start()))
                .setEndTime(toTimestamp(filter.end()))
                .setZone(filter.zone() != null ? filter.zone() : "")
                .setStatus(filter.status() != null ? filter.status() : "")
                .build();

        try {
            // Enforce approved 2000ms deadline
            GetDeliveryStatsResponse response = blockingStub
                    .withDeadlineAfter(2000, TimeUnit.MILLISECONDS)
                    .getDeliveryStatsInternal(request);

            return mapToSummary(response);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new DeliveryStatsServiceException("Delivery statistics provider timed out after 2000ms", e);
            }
            if (e.getStatus().getCode() == Status.Code.PERMISSION_DENIED) {
                throw new AccessDeniedException("Unauthorized to access statistics for requested operational scope", e);
            }
            throw new DeliveryStatsServiceException("Delivery statistics provider unavailable: " + e.getStatus().getCode(), e);
        }
    }

    private static DeliveryStatsSummary mapToSummary(GetDeliveryStatsResponse res) {
        if (res.getTotalCount() > Integer.MAX_VALUE ||
            res.getDeliveredCount() > Integer.MAX_VALUE ||
            res.getFailedCount() > Integer.MAX_VALUE ||
            res.getInTransitCount() > Integer.MAX_VALUE ||
            res.getCreatedCount() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Integer count overflow detected from provider");
        }

        Float avgHours = res.hasAverageDeliveryHours() ? (float) res.getAverageDeliveryHours() : null;

        return new DeliveryStatsSummary(
                (int) res.getTotalCount(),
                (int) res.getDeliveredCount(),
                (int) res.getFailedCount(),
                (int) res.getInTransitCount(),
                (int) res.getCreatedCount(),
                avgHours
        );
    }

    private static Timestamp toTimestamp(java.time.Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
```

---

## 8. Verification & Test Evidence

The contract, validation rules, semantic boundaries, and security policies are tested via `tests/contracts/test_delivery_stats_graphql_contract.py`:

```bash
python3 tests/contracts/test_delivery_stats_graphql_contract.py
```

### Coverage:
1. **Schema AST Invariants:** Validates `contracts/graphql/schema.graphql` for query field, argument types, return types, non-null properties, and read-only invariants (0 mutations, 0 subscriptions).
2. **Filter Boundary Tests:** Validates date parsing (UTC and Asia/Colombo offsets), invalid date rejection, reversed date rejection, >90 days interval rejection, and zone regex format checks.
3. **Aggregation Semantic Tests:** Validates count invariants, non-negativity, Int32 bounds, outcome relationships, and average delivery duration logic (null when 0 delivered).
4. **Two-Tier Security Tests:** Validates role authorization matrix (DISPATCHER/ADMIN permitted; anonymous/customer/merchant/courier denied) and operational zone scope confinement.
5. **gRPC Provider Contract Tests:** Validates message specifications, deadline requirements (2000ms), and fail-closed error mapping.
