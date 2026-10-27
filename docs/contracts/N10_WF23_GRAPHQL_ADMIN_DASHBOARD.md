# Contract & Semantic Freeze: Authorized Read-Only GraphQL adminDashboard Resolver (N10-WF23 / Issue #139)

- **Issue:** [Implement adminDashboard GraphQL aggregate resolver (#139)](https://github.com/Suthankan1/swiftdrop/issues/139)
- **Primary Implementation Owner:** Pushmitha (@pushmitha20) — Admin / Dispatcher Lead
- **Primary Reviewer:** HarithKeshan (@Harithkeshan)
- **Cross-Team Reviewers:**
  - Accounts Merchant Provider: Thuvarahan (@thuvarahan-t)
  - Delivery Domain Steward: Vibhushan (@Vibhushan-2003)
  - Shared Security & Platform Lead: Suthankan1 (@Suthankan1)
- **Schedule:**
  - Scheduled Start: October 30, 2026
  - Non-Disruptive Preparation: October 27, 2026
  - Due: November 5, 2026
  - Release Target: November 10, 2026 (SwiftDrop Full Release)
- **Feature Branch:** `feat/n10-wf23-admin-dashboard-resolver`
- **Target Integration Branch:** `test`
- **Cross-Service Boundaries:** GraphQL Query API (`services/graphql-query-service`), Accounts & Partners (`services/accounts-service`), Delivery Operations (`services/delivery-service`)
- **Status:** **CONTRACT & TEST FIXTURES FROZEN; SCHEDULE GATE PRESERVED FOR OCTOBER 30 IMPLEMENTATION WINDOW**

---

## 1. Architectural Mission & Invariants

In accordance with SwiftDrop Architecture Baseline v1.3 and Issue #139:
1. **Single Read-Only GraphQL Query API:** SwiftDrop operates **one** centralized read-only GraphQL Query API behind Spring Cloud Gateway at `/graphql`.
2. **Zero Direct Persistence in GraphQL:** The GraphQL service does not own or connect to any business database (`accounts_db` or `delivery_db`). Direct SQL queries, joins, and cross-service database references are strictly forbidden.
3. **Internal gRPC Read Delegation:** All underlying business data is aggregated exclusively through approved, private internal gRPC RPCs using mutual service authentication and signed actor token forwarding (`shared/security-adapter`).
4. **Strictly Read-Only (Zero Mutations / Subscriptions):** Admin approval decisions execute over REST commands on Accounts & Partners (`PATCH /api/v1/admin/merchants/{id}/approval`, Issue #121/#17). Real-time courier/parcel updates flow over SSE. GraphQL contains zero mutations and zero subscriptions.
5. **Exact Approved SDL Contract:** The resolver implements `Query.adminDashboard: AdminDashboard!` without modifying field names, types, or nullability.

---

## 2. Upstream Dependency Verification Matrix

| Component / Layer | Owning Issue & PR | Target RPC / API | Actual Status on `test` | Verification Evidence |
|---|---|---|---|---|
| **GraphQL Service Scaffold** | #119 / PR #217 (@Harithkeshan) | Spring GraphQL runtime, field policy, depth/complexity instrumentations | **MERGED & VERIFIED** | All 58 test suite cases pass cleanly in `services/graphql-query-service` |
| **Accounts Admin Review Provider** | #121 / PR #244 (@thuvarahan-t) | `AccountsInternalService.GetPendingMerchantsSummary` (IF-GRPC-06) | **MERGED & VERIFIED** | Deterministic pagination (1-50), status filter "PENDING", actor ADMIN enforcement |
| **Delivery Stats Provider** | #113 / PR #216 (@pushmitha20) | `DeliveryInternalService.GetDeliveryStatsInternal` (IF-GRPC-12) | **MERGED & VERIFIED** | Bounded interval, Asia/Colombo reporting days, outcome counts (`total`, `delivered`, `failed`, `in_transit`, `created`) |
| **deliveryStats Contract Freeze** | #30 / PR #213 (@pushmitha20) | `LATER_11_GRAPHQL_DELIVERY_STATS.md` | **FROZEN** | Reused semantics: Asia/Colombo date normalization, Int32 safety, 2000ms deadline |
| **Shared Security Adapter** | #100 / PR #190 (@Suthankan1) | `SwiftDropGrpcClientInterceptor`, `JwtServiceTokenValidator` | **MERGED & VERIFIED** | Delegated actor token forwarding, service JWT validation, 2000ms timeout |
| **Versioned Protobuf Contracts** | #92 / PR #188 (@Gajanthan23) | `AccountsProto v1.0`, `DeliveryProto v1.0` | **MERGED & VERIFIED** | Canonical Protobuf messages generated in `shared/proto-contracts` |

---

## 3. Approved GraphQL Contract (SDL)

The schema artifact is `contracts/graphql/schema.graphql`:

```graphql
type Query {
  """
  System administrative summary: platform overview, merchant onboarding queue, delivery outcome totals.
  Requires role: ADMIN.
  """
  adminDashboard: AdminDashboard!
}

type AdminDashboard {
  pendingMerchantsCount: Int!
  totalParcelsCount: Int!
  activeParcelsCount: Int!
  terminalSuccessRate: Float!
  recentPendingMerchants: [PendingMerchantItem!]!
}

type PendingMerchantItem {
  merchantId: ID!
  businessName: String!
  registrationNumber: String!
  submittedAt: String!
}
```

### Invariants:
1. `Query.adminDashboard` takes **zero arguments**. No input arguments or date filters may be added without separate formal schema review.
2. `adminDashboard` return type is non-nullable (`AdminDashboard!`). A failure in either required provider (Accounts or Delivery) fails the entire query; placeholder objects with synthetic zeros must never be fabricated.
3. Count fields (`pendingMerchantsCount`, `totalParcelsCount`, `activeParcelsCount`) are non-nullable signed 32-bit GraphQL integers (`Int!`).
4. `terminalSuccessRate` is a non-nullable 64-bit IEEE 754 floating point number (`Float!`).
5. `recentPendingMerchants` is a non-nullable list of non-nullable items (`[PendingMerchantItem!]!`).

---

## 4. Authoritative Semantic Decisions (Section 12 Alignment)

### Decision A: `totalParcelsCount` Reporting Basis
- **Decision:** Represents the total population of parcels created or active within the authoritative reporting window: the **current calendar day in `Asia/Colombo` [UTC+05:30]**.
- **Rule:** Calculated by `DeliveryInternalService.GetDeliveryStatsInternal` over the interval `[todayStartColombo, todayEndColombo]`. Matches `deliveryRes.getTotalCount()`. Must be validated non-negative and $\le 2,147,483,647$.

### Decision B: `activeParcelsCount` Scope
- **Decision:** Represents parcels currently active in the delivery lifecycle out in the field:
  $$\text{activeParcelsCount} = \text{in\_transit\_count}$$
  which encompasses statuses `PICKED_UP`, `IN_TRANSIT`, and `OUT_FOR_DELIVERY`.
- **Rule:** Parcels in `CREATED` status (awaiting pickup/assignment) and terminal statuses (`DELIVERED`, `FAILED`) are excluded from this metric.

### Decision C: `terminalSuccessRate` Format & Representation
- **Decision:** Formatted as a **normalized ratio between `0.0` and `1.0`** (e.g. `0.95` for 95% completion rate), rounded to 4 decimal places where applicable.
- **Rule:** UI consumers in Issue #147 format this ratio into user-facing percentage displays (`(rate * 100).toFixed(1) + '%'`).

### Decision D: `terminalSuccessRate` Denominator
- **Decision:** The denominator is strictly the total completed terminal delivery outcomes:
  $$\text{terminalOutcomes} = \text{deliveredCount} + \text{failedCount}$$
  $$\text{terminalSuccessRate} = \frac{\text{deliveredCount}}{\text{deliveredCount} + \text{failedCount}}$$

### Decision E: Zero Terminal Outcomes Handling
- **Decision:** When zero parcels have reached a terminal outcome in the reporting period ($\text{deliveredCount} + \text{failedCount} = 0$), `terminalSuccessRate` evaluates strictly to **`0.0`**.
- **Rule:** Division by zero is safeguarded; `NaN` and `Infinity` are strictly prohibited by GraphQL `Float!` invariants.

### Decision F: Alignment with Issue #147 Admin UI
- **Decision:** Align with Issue #147 daily outcome cards. Both screens share the canonical `Asia/Colombo` day boundaries and standard outcome counts provided by `DeliveryOperations`.

### Decision G: Reporting Timezone & Instant Conversion
- **Decision:** The platform business timezone is **`Asia/Colombo` (UTC+05:30)**.
- **Conversion Rule:**
  ```java
  ZoneId colomboZone = ZoneId.of("Asia/Colombo");
  LocalDate today = LocalDate.now(colomboZone);
  Instant startInstant = today.atStartOfDay(colomboZone).toInstant();
  Instant endInstant = today.plusDays(1).atStartOfDay(colomboZone).toInstant();
  ```
  Timestamps sent across gRPC stubs are canonical UTC `google.protobuf.Timestamp`.

---

## 5. Authoritative Merchant Review Summary (Accounts Integration)

- **Provider Method:** `AccountsInternalService.GetPendingMerchantsSummary` (IF-GRPC-06).
- **Request Parameters:**
  - `status_filter`: `"PENDING"`
  - `page`: `0`
  - `page_size`: `20` (bounded preview size, ceiling 50)
- **Response Validation:**
  - `total_pending_count`: Validated $\ge 0$ and $\le 2,147,483,647$. Mapped directly to `pendingMerchantsCount`.
  - Preview `items`: Mapped to `recentPendingMerchants`. Length bounded to $\le 50$.
  - Every item verified: `merchant_id` is a valid UUID; `business_name` is non-blank; `registration_number` is present; `submitted_at` is a valid UTC instant; `status` is strictly `"PENDING"`.
- **Privacy & Data Minimization Invariant:** Zero merchant API key digests, secrets, contact telephone numbers, or email addresses are exposed in `PendingMerchantItem`.

---

## 6. Two-Tier Authorization & Security Model

1. **GraphQL Root Policy:**
   - Enforced by `GraphqlFieldPolicy.check("adminDashboard", actor)`.
   - Requires verified authority `ROLE_ADMIN`.
   - Denies `ANONYMOUS` (401), `CUSTOMER` (403), `MERCHANT` (403), `COURIER` (403), and `DISPATCHER` (403).
2. **Provider Actor Delegation:**
   - Client interceptor `SwiftDropGrpcClientInterceptor` propagates the signed caller JWT as bearer credential.
   - Accounts gRPC service verifies the caller possesses delegated ADMIN privileges independently (`AccountsInternalServiceImpl.java` line 500).
   - Delivery gRPC service verifies the caller possesses staff privileges independently (`DeliveryInternalServiceImpl.java` line 540).
   - GraphQL role checking does not bypass or replace provider-level authorization.

---

## 7. Error Handling & Sanitization

- Uses `SafeGraphqlExceptionResolver`:
  - `Status.PERMISSION_DENIED` $\rightarrow$ GraphQL `FORBIDDEN` (ErrorType.FORBIDDEN)
  - `Status.UNAUTHENTICATED` $\rightarrow$ GraphQL `UNAUTHENTICATED` (ErrorType.UNAUTHORIZED)
  - `Status.INVALID_ARGUMENT` $\rightarrow$ GraphQL `INVALID_ARGUMENT` (ErrorType.BAD_REQUEST)
  - `Status.DEADLINE_EXCEEDED` $\rightarrow$ GraphQL `DEADLINE_EXCEEDED` (ErrorType.INTERNAL_ERROR)
  - `Status.UNAVAILABLE` $\rightarrow$ GraphQL `UNAVAILABLE` (ErrorType.INTERNAL_ERROR)
- **Sanitization Invariant:** No raw stack traces, hostnames, SQL statements, or token dumps are ever leaked into GraphQL error responses.

---

## 8. Test Strategy & Fixture Planning

The following test fixtures and scenarios are planned for implementation on October 30:

### A. Role Security Tests
1. Unauthenticated request rejected with HTTP 401.
2. `CUSTOMER` JWT rejected with `FORBIDDEN`.
3. `MERCHANT` JWT rejected with `FORBIDDEN`.
4. `COURIER` JWT rejected with `FORBIDDEN`.
5. `DISPATCHER` JWT rejected with `FORBIDDEN`.
6. `ADMIN` JWT granted access.
7. Forged / untrusted actor context rejected before provider execution.

### B. Merchant Queue Aggregation Tests
1. Authoritative total count from provider matches `pendingMerchantsCount`.
2. Preview list mapped correctly to `recentPendingMerchants`.
3. Bounded page size (20) enforced.
4. Non-PENDING records from provider rejected as protocol violation.
5. Empty pending queue returns count 0 and empty array `[]`.
6. Negative count from provider rejected with `INTERNAL`.
7. Invalid UUID or malformed timestamp rejected.

### C. Delivery Operational Statistics Tests
1. Correct mapping of `totalParcelsCount` from `GetDeliveryStatsResponse.total_count`.
2. Correct mapping of `activeParcelsCount` from `GetDeliveryStatsResponse.in_transit_count`.
3. Terminal success rate computed correctly: $\frac{\text{delivered}}{\text{delivered} + \text{failed}}$.
4. Zero terminal outcomes returns `0.0`.
5. Int32 overflow (> $2^{31}-1$) rejected safely.
6. Timezone boundaries align with `Asia/Colombo` business calendar.

### D. Multi-Provider Failure Tests
1. Accounts timeout / `DEADLINE_EXCEEDED` propagates sanitized GraphQL error.
2. Delivery timeout / `DEADLINE_EXCEEDED` propagates sanitized GraphQL error.
3. Accounts `UNAVAILABLE` fails closed (null `data`).
4. Delivery `UNAVAILABLE` fails closed (null `data`).
5. Accounts `PERMISSION_DENIED` maps to `FORBIDDEN`.
6. Delivery `PERMISSION_DENIED` maps to `FORBIDDEN`.

---

## 9. Signoff & Review Checklist

- [x] Schedule gate respected: Preparation phase completed ahead of October 30.
- [x] Schema backward compatibility validated: No breaking changes against `origin/test`.
- [x] Upstream provider dependencies verified on latest `test` branch.
- [x] Authoritative metrics semantics agreed:
  - `Asia/Colombo` business day
  - `activeParcelsCount` = `inTransitCount`
  - `terminalSuccessRate` = normalized ratio 0.0..1.0
  - `terminalSuccessRate` = 0.0 on zero terminal outcomes
- [ ] Bounded implementation of `AdminDashboardResolver` (scheduled October 30).
- [ ] Integration tests and Surefire verification (scheduled October 30).
- [ ] Peer review by @Harithkeshan, @thuvarahan-t, and @Suthankan1.
