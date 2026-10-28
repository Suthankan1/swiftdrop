# Read-only GraphQL query API — draft

**Only one independent GraphQL Query API** behind `/graphql` on the gateway. It reads domain data through **authorized, private gRPC** services. Do not add another GraphQL server in each business service, direct SQL federation, `Mutation` or `Subscription` types in the MVP. The browser uses authenticated GraphQL HTTP queries for **composite dashboards**.

Planned aggregate query families:

- `customerDashboard`: current user parcels, latest delivery status, totals; scope strictly to authenticated Keycloak `sub`. Requires role `CUSTOMER` (`ROLE_CUSTOMER`).
  - **`userId` (`ID!`):** Verified Keycloak subject UUID of the authenticated customer caller. Never inferred from query arguments, variables, or caller-controlled headers.
  - **`activeDeliveriesCount` (`Int!`):** Authoritative count of non-terminal owned parcels (`CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`) resolved from `DeliveryInternalService.ListCustomerParcels` (`active_count`). Validated non-negative signed 32-bit GraphQL `Int`.
  - **`completedDeliveriesCount` (`Int!`):** Authoritative count of terminal owned parcels (`DELIVERED`, `FAILED`) resolved from `DeliveryInternalService.ListCustomerParcels` (`completed_count`). Validated non-negative signed 32-bit GraphQL `Int`.
  - **`recentParcels` (`[CustomerParcelSummary!]!`):** Bounded preview list (page 0, bounded default size 20, ceiling 50) of recent customer parcels (`parcelId`, `trackingNumber`, `currentStatus`, `amountMinorUnits`, `currency`, `recipientCity`, `createdAt`, `updatedAt` in ISO-8601 UTC). Zero recipient phone numbers, email addresses, or internal database keys exposed. Validated UUIDs, canonical lifecycle statuses, Int32 monetary amounts, and monotonic timestamps.
  - **Security & Error Model:** Strictly requires `ROLE_CUSTOMER` from verified JWT `actor` in GraphQL context. Missing, unauthenticated, or wrong-role callers fail closed (`UNAUTHENTICATED` / `FORBIDDEN`). Delivery provider independently authorizes delegated customer identity and rejects cross-customer queries. Failures in provider propagate via `SafeGraphqlExceptionResolver` without fabricating placeholder zero records.
- `merchantDashboard`: approved merchant tenant orders and state counts; reject pending/unapproved tenants.
- `courierDashboard`: assigned deliveries for current authenticated courier and daily counts; do not accept arbitrary courier ID.
- `dispatcherDashboard(pagination: PaginationInput)`: Authoritative operational dashboard for parcel dispatching and courier workload monitoring. Scoped to the authenticated dispatcher's operational zone, or platform-wide for administrators. Requires role `DISPATCHER` (`ROLE_DISPATCHER`) or `ADMIN` (`ROLE_ADMIN`).
  - **`unassignedParcels` (`[UnassignedParcelItem!]!`):** Bounded preview list of unassigned parcels awaiting courier allocation. Resolved from `DeliveryInternalService.ListUnassignedParcels` filtered strictly by the dispatcher's assigned operational zone (or unconstrained for admin).
    - `parcelId` (`ID!`): Validated UUID of the parcel.
    - `trackingNumber` (`String!`): Canonical SwiftDrop parcel tracking number.
    - `pickupZone` (`String!`): Operational pickup zone identifier.
    - `dropoffZone` (`String!`): Operational destination dropoff zone identifier.
    - `weightKg` (`Float!`): Verified package weight in kilograms (strictly positive, finite float).
    - `sizeCategory` (`String!`): Package size tier mapped from protobuf `size_category`.
    - `createdAt` (`String!`): ISO-8601 UTC timestamp of parcel creation.
    - `version` (`Int!`): Non-negative optimistic concurrency version used for safe assignment.
    - **Filtering & Validation:** Strictly rejects any parcel not in `CREATED` status. Enforces unique parcel IDs per response (no duplicates).
  - **`pageInfo` (`PageInfo!`):** Pagination metadata for the unassigned parcel queue.
    - `currentPage` (`Int!`): 0-indexed page number (default 0).
    - `pageSize` (`Int!`): Bounded requested page size (default 20, ceiling 50, minimum 1).
    - `totalElements` (`Int!`): Authoritative total count of unassigned parcels matching the operational scope.
    - `totalPages` (`Int!`): Calculated total pages ($\lceil \text{totalElements} / \text{pageSize} \rceil$).
    - `hasNext` (`Boolean!`): Flag indicating whether additional unassigned parcel pages exist ($(\text{currentPage} + 1) \times \text{pageSize} < \text{totalElements}$).
  - **`availableCouriers` (`[CourierWorkloadSummary!]!`):** List of active, verified couriers operating in the dispatcher's operational zone along with their authoritative live assignment counts.
    - `courierSub` (`ID!`): Verified Keycloak subject UUID of the courier.
    - `fullName` (`String!`): Full name from Accounts profile.
    - `activeAssignmentsCount` (`Int!`): Authoritative count of parcels currently assigned to the courier in active states (`ASSIGNED`, `ACCEPTED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`), fetched from `DeliveryInternalService.GetCourierWorkloadSummary`.
    - `maxCapacity` (`Int!`): Configurable operating advisory soft-cap per courier (default 5, per DEC-ASG-04).
    - `eligible` (`Boolean!`): Courier dispatch eligibility flag from Accounts eligibility verification.
  - **N+1 Prevention & Single-Batch Lookup:** Available couriers are first retrieved in a single call to `AccountsInternalService.GetAvailableCouriersSummary` (bounded to ceiling 50, deduplicated). Then, all courier IDs are passed in a single batch call to `DeliveryInternalService.GetCourierWorkloadSummary(courier_ids=[...])`. Under no circumstances does the resolver execute individual workload queries in a loop. If zero couriers are returned by Accounts, the Delivery workload gRPC call is skipped entirely.
  - **Operational Scoping & Security:**
    - Requires verified JWT `actor` in GraphQL context with `ROLE_DISPATCHER` or `ROLE_ADMIN`.
    - For dispatchers (`ROLE_DISPATCHER`), caller account eligibility is verified via `AccountsInternalService.CheckAccountEligibility`, and assigned operational zone is resolved via `AccountsInternalService.GetScopedProfileSummary`.
    - Unassigned parcel queue is filtered strictly by the dispatcher's assigned operational zone; dispatchers cannot query or view parcels outside their operational zone.
    - Administrators (`ROLE_ADMIN`) default to platform-wide operational scope (`zone = ""` or their assigned operational zone if constrained).
    - Caller-controlled headers (`X-User-Id`, `X-Roles`, `X-Dispatcher-Sub`, `X-Authorized-Zones`) are never trusted for identity or zone.
    - Wrong roles (`ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_COURIER`) and unauthenticated callers fail closed (`FORBIDDEN` / `UNAUTHENTICATED`).
    - Provider failures (timeouts, gRPC status errors) fail closed without returning fabricated or partial placeholder records.
  - **Read-Only Architecture & Assignment Command Boundary:**
    - The `dispatcherDashboard` GraphQL query is strictly read-only.
    - Courier parcel assignment, reassignment, and cancellation mutations are explicitly excluded from GraphQL and are executed exclusively via Delivery REST commands (`POST /api/v1/deliveries/assignments`) adhering to DEC-ASG-01 (single active assignment consistency) and DEC-ASG-03 (optimistic concurrency version verification).
- `adminDashboard`: System administrative summary: platform overview, merchant onboarding review queue, and delivery outcome aggregates; strictly ADMIN-only (`ROLE_ADMIN`).
  - **`pendingMerchantsCount` (`Int!`):** Authoritative total count of merchants matching `status_filter="PENDING"` resolved from `AccountsInternalService.GetPendingMerchantsSummary` (IF-GRPC-06). Must not be inferred from page length. Validated non-negative.
  - **`recentPendingMerchants` (`[PendingMerchantItem!]!`):** Bounded preview list (page 0, size bounded up to 20/50) of pending merchants (`merchantId`, `businessName`, `registrationNumber`, `submittedAt` in ISO-8601 UTC). Zero merchant credentials, secrets, phone numbers, or contact PII exposed. Validated non-empty, valid UUIDs, non-negative timestamps, and strictly `PENDING` status.
  - **`totalParcelsCount` (`Int!`):** Authoritative total parcel records created or active within the business reporting window (current calendar day in `Asia/Colombo` [UTC+05:30]), resolved via `DeliveryInternalService.GetDeliveryStatsInternal` (IF-GRPC-12). Validated non-negative signed 32-bit GraphQL `Int`.
  - **`activeParcelsCount` (`Int!`):** Total parcels actively in transit (`in_transit_count`, representing states `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`) within the reporting window.
  - **`terminalSuccessRate` (`Float!`):** Normalized ratio between `0.0` and `1.0` (e.g. `0.95` for 95% completion) calculated as $\frac{\text{deliveredCount}}{\text{deliveredCount} + \text{failedCount}}$. Evaluates strictly to `0.0` when zero terminal outcomes exist (division by zero safe).
  - **Security & Error Model:** Strictly requires `ROLE_ADMIN` from verified JWT `actor` in GraphQL context. Missing or non-admin callers fail closed (`UNAUTHENTICATED` / `FORBIDDEN`). Accounts and Delivery providers independently verify machine identity and delegated ADMIN credentials. Failures in required providers fail closed and propagate via `SafeGraphqlExceptionResolver` without fabricating placeholder zero records.
- `deliveryStats` (the ninth contributor's bounded Dispatcher slice): totals per day/terminal status with authorized filters, not an unrestricted cross-tenant query.

**Rules:** filter authoritatively at owning service, cap depth/complexity/result sizes, paginate lists, use batching to avoid N+1 gRPC, enforce independent per-field authorization, avoid PII in generic error messages, define timeouts and partial-failure semantics. Keep GraphQL entity fields aligned with owned service APIs. Query names/field shapes are **illustrative** until schema approval.
