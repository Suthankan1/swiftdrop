# Contract & Implementation Documentation: Authorized Read-Only GraphQL customerDashboard Resolver (N10-WF03 / Issue #132)

- **Issue:** [Implement authorized customerDashboard GraphQL resolver (#132)](https://github.com/Suthankan1/swiftdrop/issues/132)
- **Primary Implementation Owner:** Gajanthan (@Gajanthan23)
- **Reviewers:** Sinthuha (@Sinthuha-n), Vibhushan (@Vibhushan-2003)
- **Target Integration Branch:** `test`
- **Feature Branch:** `feat/n10-wf03-customer-dashboard-graphql`
- **Service Boundaries:** GraphQL Query API (`services/graphql-query-service`), Delivery Operations (`services/delivery-service`)
- **Status:** **IMPLEMENTED & VERIFIED**

---

## 1. Architectural Mission & Invariants

In accordance with SwiftDrop Architecture Baseline v1.3 and Issue #132:
1. **Single Read-Only GraphQL Query API:** SwiftDrop operates **one** centralized read-only GraphQL Query API behind Spring Cloud Gateway at `/graphql`.
2. **Zero Direct Persistence in GraphQL:** The GraphQL service does not own or connect to any business database (`delivery_db`). Direct SQL queries, joins, and cross-service database references are strictly forbidden.
3. **Internal gRPC Read Delegation:** All underlying parcel and delivery status data is resolved exclusively through approved, private internal gRPC RPC `DeliveryInternalService.ListCustomerParcels` using mutual service authentication and signed actor token forwarding (`shared/security-adapter`).
4. **Strictly Read-Only (Zero Mutations / Subscriptions):** Customer parcel booking executes over REST commands on Delivery Operations (`POST /api/v1/parcels`, Issue #20/#114). Live parcel tracking and status updates flow over SSE (`/api/v1/parcels/{id}/live-tracking`, Issue #130). GraphQL contains zero mutations and zero subscriptions.
5. **Exact Approved SDL Contract:** The resolver implements `Query.customerDashboard: CustomerDashboard!` without modifying field names, types, or nullability.

---

## 2. Upstream Dependency Verification Matrix

| Component / Layer | Owning Issue & PR | Target RPC / API | Actual Status on `test` | Verification Evidence |
|---|---|---|---|---|
| **GraphQL Service Scaffold** | #119 / PR #217 (@Harithkeshan) | Spring GraphQL runtime, field policy, depth/complexity instrumentations | **MERGED & VERIFIED** | 58 scaffold test cases pass cleanly |
| **Delivery Customer Read Provider** | #112 / PR #240 (@Vibhushan-2003) | `DeliveryInternalService.ListCustomerParcels` | **MERGED & VERIFIED** | Scoped pagination (page 0, size 20), status filter, delegated actor customer verification |
| **Shared Security Adapter** | #100 / PR #190 (@Suthankan1) | `SwiftDropGrpcClientInterceptor`, `JwtServiceTokenValidator` | **MERGED & VERIFIED** | Delegated actor token forwarding, service JWT validation, 2000ms timeout |
| **Versioned Protobuf Contracts** | #92 / PR #188 (@Gajanthan23) | `DeliveryProto v1.0` (`ListCustomerParcels`) | **MERGED & VERIFIED** | Canonical Protobuf messages generated in `shared/proto-contracts` |

---

## 3. Approved GraphQL Contract (SDL)

The schema artifact is `contracts/graphql/schema.graphql`:

```graphql
type Query {
  """
  Customer dashboard summary: scoped strictly to the authenticated Keycloak subject.
  Requires role: CUSTOMER.
  """
  customerDashboard: CustomerDashboard!
}

type CustomerDashboard {
  userId: ID!
  activeDeliveriesCount: Int!
  completedDeliveriesCount: Int!
  recentParcels: [CustomerParcelSummary!]!
}

type CustomerParcelSummary {
  parcelId: ID!
  trackingNumber: String!
  currentStatus: ParcelStatus!
  amountMinorUnits: Int!
  currency: String!
  recipientCity: String!
  createdAt: String!
  updatedAt: String!
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

---

## 4. Implementation Details

### 4.1 Resolver Architecture

- Class: `com.swiftdrop.graphql.resolver.CustomerDashboardResolver`
- Implements: `RootQueryResolver` (`field() == "customerDashboard"`)
- Registered automatically via `GraphqlRuntimeConfig` as a Spring `@Component`
- Role gate: `ROLE_CUSTOMER` enforced by `GraphqlFieldPolicy` and verified defensively in the resolver

### 4.2 Actor & Customer Identity Binding

1. Actor is extracted exclusively from `environment.getGraphQlContext().get("actor")` (`JwtAuthenticationToken`).
2. Subject is obtained from `actor.getToken().getSubject().trim()`.
3. Verified nonblank; missing or unauthenticated actor fails closed with `Status.UNAUTHENTICATED`.
4. Role check verifies `ROLE_CUSTOMER`. Missing or mismatched roles (e.g. `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) throw `AccessDeniedException` (mapped to `FORBIDDEN`).
5. No client-supplied customer ID, GraphQL argument, variable, or header is accepted or trusted.

### 4.3 Delivery Operations gRPC Integration

- RPC: `DeliveryInternalServiceGrpc.listCustomerParcels(ListCustomerParcelsRequest)`
- Request scoped:
  - `customer_sub`: Verified actor JWT subject
  - `page`: 0
  - `page_size`: 20 (bounded default preview size, ceiling 50)
- Called via `AuthenticatedReadClients.delivery(context, ...)`:
  - Propagates signed caller JWT in gRPC context
  - Authenticates with platform rotating service token
  - Enforces 2,000ms deadline
  - Max inbound message size bounded to 256 KB
- Zero N+1 calls: preview parcels and canonical totals are fetched in a single bounded RPC call.

### 4.4 Canonical Totals and Status Reconciliation

- Active deliveries count: Mapped from `response.getActiveCount()`. Corresponds to non-terminal lifecycle statuses: `CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`.
- Completed deliveries count: Mapped from `response.getCompletedCount()`. Corresponds to terminal lifecycle statuses: `DELIVERED`, `FAILED`.
- Invariant check: `activeCount + completedCount == totalCount` is strictly enforced.
- Empty result: When a customer has no parcels (`total_count = 0`), returns `userId`, `activeDeliveriesCount = 0`, `completedDeliveriesCount = 0`, `recentParcels = []`.

### 4.5 Data Validation & Mapping

- `parcelId`: Validated nonblank UUID.
- `trackingNumber`: Validated nonblank string.
- `currentStatus`: Validated against approved enum `[CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED]`.
- `amountMinorUnits`: Validated non-negative; conversion using `Math.toIntExact` prevents 32-bit `Int` overflow.
- `currency`: Validated nonblank string (e.g. `"LKR"`).
- `recipientCity`: Sanitized, bounded length `<= 256`. Zero contact PII (address, phone, recipient name) exposed.
- `createdAt` & `updatedAt`: Protobuf timestamps converted to ISO-8601 UTC strings (`Instant.toString()`). Invariant `updatedAt >= createdAt` enforced.
- Deduplication: Invariant that preview does not contain duplicate parcel records.

---

## 5. Sample Query and Response

### GraphQL Query

```graphql
query MyCustomerDashboard {
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
      recipientCity
      createdAt
      updatedAt
    }
  }
}
```

### Sample Response

```json
{
  "data": {
    "customerDashboard": {
      "userId": "11111111-1111-1111-1111-111111111111",
      "activeDeliveriesCount": 2,
      "completedDeliveriesCount": 1,
      "recentParcels": [
        {
          "parcelId": "550e8400-e29b-41d4-a716-446655440000",
          "trackingNumber": "SWD-2026-000101",
          "currentStatus": "IN_TRANSIT",
          "amountMinorUnits": 1500,
          "currency": "LKR",
          "recipientCity": "Colombo",
          "createdAt": "2026-10-27T10:00:00Z",
          "updatedAt": "2026-10-27T10:30:00Z"
        },
        {
          "parcelId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
          "trackingNumber": "SWD-2026-000102",
          "currentStatus": "CREATED",
          "amountMinorUnits": 2000,
          "currency": "LKR",
          "recipientCity": "Kandy",
          "createdAt": "2026-10-27T11:00:00Z",
          "updatedAt": "2026-10-27T11:00:00Z"
        },
        {
          "parcelId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
          "trackingNumber": "SWD-2026-000099",
          "currentStatus": "DELIVERED",
          "amountMinorUnits": 1800,
          "currency": "LKR",
          "recipientCity": "Galle",
          "createdAt": "2026-10-26T08:00:00Z",
          "updatedAt": "2026-10-26T16:00:00Z"
        }
      ]
    }
  }
}
```

---

## 6. Verification and Test Evidence

- **Unit Tests:** `com.swiftdrop.graphql.resolver.CustomerDashboardResolverTest` (50 test cases passed)
- **Endpoint Integration Tests:** `com.swiftdrop.graphql.CustomerDashboardEndpointIntegrationTest` (18 test cases passed)
- **Overall GraphQL Service Test Suite:** 189 tests run, 0 failures, 0 errors, 0 skipped
- **Contract Compatibility:** `validate_contracts.py` passed all gates against `origin/test` with 0 breaking changes.
