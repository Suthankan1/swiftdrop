# Read-only GraphQL query API — draft

**Only one independent GraphQL Query API** behind `/graphql` on the gateway. It reads domain data through **authorized, private gRPC** services. Do not add another GraphQL server in each business service, direct SQL federation, `Mutation` or `Subscription` types in the MVP. The browser uses authenticated GraphQL HTTP queries for **composite dashboards**.

Planned aggregate query families:

- `customerDashboard`: current user parcels, latest delivery status, totals; scope to authenticated `sub`.
- `merchantDashboard`: approved merchant tenant orders and state counts; reject pending/unapproved tenants.
- `courierDashboard`: assigned deliveries for current authenticated courier and daily counts; do not accept arbitrary courier ID.
- `dispatcherDashboard`: unassigned parcel preview, available couriers and active workload; dispatcher permission required.
- `adminDashboard`: merchant approval and delivery outcomes summary; admin-only views.
- `deliveryStats` (the ninth contributor's bounded Dispatcher slice): totals per day/terminal status with authorized filters, not an unrestricted cross-tenant query.

**Rules:** filter authoritatively at owning service, cap depth/complexity/result sizes, paginate lists, use batching to avoid N+1 gRPC, enforce independent per-field authorization, avoid PII in generic error messages, define timeouts and partial-failure semantics. Keep GraphQL entity fields aligned with owned service APIs. Query names/field shapes are **illustrative** until schema approval.
