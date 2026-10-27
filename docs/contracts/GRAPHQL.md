# Read-only GraphQL query API — draft

**Only one independent GraphQL Query API** behind `/graphql` on the gateway. It reads domain data through **authorized, private gRPC** services. Do not add another GraphQL server in each business service, direct SQL federation, `Mutation` or `Subscription` types in the MVP. The browser uses authenticated GraphQL HTTP queries for **composite dashboards**.

Planned aggregate query families:

- `customerDashboard`: current user parcels, latest delivery status, totals; scope to authenticated `sub`.
- `merchantDashboard`: approved merchant tenant orders and state counts; reject pending/unapproved tenants.
- `courierDashboard`: assigned deliveries for current authenticated courier and daily counts; do not accept arbitrary courier ID.
- `dispatcherDashboard`: unassigned parcel preview, available couriers and active workload; dispatcher permission required.
- `adminDashboard`: System administrative summary: platform overview, merchant onboarding review queue, and delivery outcome aggregates; strictly ADMIN-only (`ROLE_ADMIN`).
  - **`pendingMerchantsCount` (`Int!`):** Authoritative total count of merchants matching `status_filter="PENDING"` resolved from `AccountsInternalService.GetPendingMerchantsSummary` (IF-GRPC-06). Must not be inferred from page length. Validated non-negative.
  - **`recentPendingMerchants` (`[PendingMerchantItem!]!`):** Bounded preview list (page 0, size bounded up to 20/50) of pending merchants (`merchantId`, `businessName`, `registrationNumber`, `submittedAt` in ISO-8601 UTC). Zero merchant credentials, secrets, phone numbers, or contact PII exposed. Validated non-empty, valid UUIDs, non-negative timestamps, and strictly `PENDING` status.
  - **`totalParcelsCount` (`Int!`):** Authoritative total parcel records created or active within the business reporting window (current calendar day in `Asia/Colombo` [UTC+05:30]), resolved via `DeliveryInternalService.GetDeliveryStatsInternal` (IF-GRPC-12). Validated non-negative signed 32-bit GraphQL `Int`.
  - **`activeParcelsCount` (`Int!`):** Total parcels actively in transit (`in_transit_count`, representing states `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`) within the reporting window.
  - **`terminalSuccessRate` (`Float!`):** Normalized ratio between `0.0` and `1.0` (e.g. `0.95` for 95% completion) calculated as $\frac{\text{deliveredCount}}{\text{deliveredCount} + \text{failedCount}}$. Evaluates strictly to `0.0` when zero terminal outcomes exist (division by zero safe).
  - **Security & Error Model:** Strictly requires `ROLE_ADMIN` from verified JWT `actor` in GraphQL context. Missing or non-admin callers fail closed (`UNAUTHENTICATED` / `FORBIDDEN`). Accounts and Delivery providers independently verify machine identity and delegated ADMIN credentials. Failures in required providers fail closed and propagate via `SafeGraphqlExceptionResolver` without fabricating placeholder zero records.
- `deliveryStats` (the ninth contributor's bounded Dispatcher slice): totals per day/terminal status with authorized filters, not an unrestricted cross-tenant query.

**Rules:** filter authoritatively at owning service, cap depth/complexity/result sizes, paginate lists, use batching to avoid N+1 gRPC, enforce independent per-field authorization, avoid PII in generic error messages, define timeouts and partial-failure semantics. Keep GraphQL entity fields aligned with owned service APIs. Query names/field shapes are **illustrative** until schema approval.
