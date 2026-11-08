# SwiftDrop System Audit — GitHub Issue Proposals

These issue proposals are ready for immediate copy-pasting into the repository's GitHub Issue tracker to drive remediation work across sprint cycles.

---

## Issue 1: [P0-BLOCKER] Missing Spring Cloud Gateway route for staff profile (/api/v1/staff/**)

- **Labels:** `bug`, `p0-blocker`, `gateway`, `backend`, `staff`
- **Component:** `services/api-gateway`
- **Owner:** Platform / Gateway Lead

### Problem Description
When staff users (Admin, Courier, Dispatcher) authenticate and access their respective portals, the frontend calls `GET /api/v1/staff/me` to fetch name, zone, and operational metadata. Because `services/api-gateway` does not declare a route for `/api/v1/staff/**`, the gateway returns HTTP 404 Not Found, breaking staff workspace initialization.

### Steps to Reproduce
1. Start `accounts-service` on port 8081 and `api-gateway` on port 8080.
2. Generate a valid Keycloak staff access token.
3. Execute:
   ```bash
   curl -i -H "Authorization: Bearer $STAFF_TOKEN" http://localhost:8080/api/v1/staff/me
   ```
4. Observe HTTP 404 Not Found.

### Expected Behavior
Gateway routes `/api/v1/staff/**` to `accounts-service` on port 8081 with JWT authentication filter applied.

### Proposed Fix
In `services/api-gateway/src/main/java/com/swiftdrop/apigateway/config/GatewayRoutesConfig.java`:
```java
.route("staff-service", r -> r.path("/api/v1/staff/**")
    .filters(f -> f.filter(authenticationFilter.apply(new JwtAuthenticationFilter.Config())))
    .uri(accountsServiceUrl))
```
In `services/api-gateway/src/main/resources/application.yml`:
```yaml
- id: staff-service
  uri: ${ACCOUNTS_SERVICE_URL:http://localhost:8081}
  predicates:
    - Path=/api/v1/staff/**
```

### Acceptance Criteria
- Unit test in `GatewayRoutesConfigTest` asserts route mapping for `/api/v1/staff/me`.
- Real HTTP GET through port 8080 forwards to port 8081 and returns 200 OK.

---

## Issue 2: [P1-CRITICAL] Gateway Java Route Fallback Port Defaults to 8084 Instead of 8085 for GraphQL

- **Labels:** `bug`, `p1-critical`, `gateway`, `graphql`
- **Component:** `services/api-gateway`
- **Owner:** Platform / Gateway Lead

### Problem Description
In `GatewayRoutesConfig.java`, line 31 specifies:
`@Value("${GRAPHQL_SERVICE_URL:http://localhost:8084}") private String graphqlServiceUrl;`
Port 8084 belongs to `communications-service`. If `GRAPHQL_SERVICE_URL` is omitted, `/graphql` requests are forwarded to the wrong service.

### Proposed Fix
Change default port to `8085`:
```java
@Value("${GRAPHQL_SERVICE_URL:http://localhost:8085}")
private String graphqlServiceUrl;
```

### Acceptance Criteria
- Unit test asserts fallback URI is `http://localhost:8085`.

---

## Issue 3: [P1-CRITICAL] Authenticated Merchant Browser Session Missing merchant_id Tenant Claim in Keycloak JWT

- **Labels:** `bug`, `p1-critical`, `auth`, `tenant-isolation`, `delivery-service`
- **Component:** `services/delivery-service`, `platform/identity`
- **Owner:** Delivery Lead / Security Lead

### Problem Description
In `MerchantParcelBookingController.java`, line 80 requires a `merchant_id` claim in the Keycloak JWT token:
```java
Object merchantClaim = jwt.getClaim("merchant_id");
if (merchantClaim == null || merchantClaim.toString().isBlank()) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified token does not contain a merchant_id tenant claim");
}
```
Keycloak issues tokens for merchants containing the `MERCHANT` realm role, but does not include `merchant_id` because merchant registration entities reside in `accounts_db`. Authenticated browser merchants receive HTTP 403 when creating parcels and an empty list when viewing `/merchant/orders`.

### Proposed Fix
In `delivery-service`, if the `merchant_id` JWT claim is absent but the user possesses the `ROLE_MERCHANT` authority and valid `sub`, query `accounts-service` via internal client to resolve the merchant ID, or implement a Keycloak token exchange / user attribute mapper.

### Acceptance Criteria
- Merchant logged into React web portal can book a parcel via `POST /api/v1/merchant/parcels` with a standard bearer token.
- Orders dashboard displays the merchant's active parcels.

---

## Issue 4: [P1-CRITICAL] Merchant Bulk Order CSV Upload Discards Data in Memory via Mock setTimeout

- **Labels:** `bug`, `p1-critical`, `frontend`, `delivery-service`, `data-integrity`
- **Component:** `frontend/src/features/merchant/components/BulkOrderUploadModal.tsx`
- **Owner:** Frontend Lead / Merchant Owner

### Problem Description
In `BulkOrderUploadModal.tsx`, clicking "Import Orders" runs a simulated `setTimeout(1500)` and invokes `onImportComplete(validRows.length)` without making any HTTP request. The parsed CSV rows are discarded in JavaScript memory.

### Proposed Fix
1. Add endpoint `POST /api/v1/merchant/parcels/bulk` in `delivery-service`.
2. Connect `BulkOrderUploadModal` via React Query mutation to submit parsed orders to the backend.
3. Display real server validation errors if specific rows fail.

### Acceptance Criteria
- Uploaded CSV orders are saved to `delivery_db`.
- Modal displays authentic created parcel IDs.

---

## Issue 5: [P1-CRITICAL] Merchant Webhook Test Ping Simulates Fake Dispatch with Hardcoded Signature

- **Labels:** `bug`, `p1-critical`, `frontend`, `communications-service`
- **Component:** `frontend/src/features/merchant/components/WebhookSubscriptionList.tsx`
- **Owner:** Frontend Lead / Communications Lead

### Problem Description
`handleSendTestPing` in `WebhookSubscriptionList.tsx` executes a `setTimeout(700)` and hardcodes a simulated 200 OK result with a static empty string SHA-256 hash.

### Proposed Fix
Expose `POST /api/v1/merchants/webhooks/{id}/test` in `communications-service` that dispatches an actual test payload signed with HMAC-SHA256 and returns real latency and HTTP status code.

### Acceptance Criteria
- Clicking "Test Ping" triggers a real outbound network request to the merchant's destination URL and displays actual response details.

---

## Issue 6: [P2-MAJOR] Implement Deferred deliveryStats gRPC Service & Resolver to Fix Admin Reports

- **Labels:** `bug`, `p2-major`, `graphql`, `delivery-service`
- **Component:** `services/graphql-query-service`, `services/delivery-service`
- **Owner:** Delivery Lead / GraphQL Lead

### Problem Description
ADR `LATER_11` deferred the implementation of `deliveryStats` gRPC provider. Currently, `GraphqlRuntimeConfig` throws `Status.UNAVAILABLE`, causing the Admin Reports page to display a persistent error banner.

### Proposed Fix
Implement `DeliveryStatsServiceGrpc` in `delivery-service` calculating daily metrics, register the gRPC client in `graphql-query-service`, and resolve ADR `LATER_11`.

### Acceptance Criteria
- GraphQL query `deliveryStats` executes successfully with 0 errors.

---

## Issue 7: [P2-SECURITY] Enhance Webhook Destination SSRF Validator with IPv6 and Cloud Metadata Checks

- **Labels:** `security`, `p2-major`, `ssrf`, `communications-service`
- **Component:** `services/communications-service`
- **Owner:** Security Lead

### Problem Description
`WebhookDestinationValidator.java` filters private IPv4 ranges, but does not block `169.254.169.254` (cloud instance metadata) or IPv6 loopback / local addresses (`[::1]`, `[fe80::1]`).

### Proposed Fix
Resolve hostname to `InetAddress` and block all loopback, site-local, link-local, and cloud IMDS IPs.

### Acceptance Criteria
- Submitting webhook destination `http://169.254.169.254/latest/meta-data` is rejected with 400 Bad Request.

---

## Issue 8: [P2-PERF] Add Composite Index on parcels.merchant_id for Fast Order Queries

- **Labels:** `performance`, `p2-major`, `database`, `delivery-service`
- **Component:** `services/delivery-service`
- **Owner:** Database Reliability Lead

### Problem Description
`parcels.merchant_id` has no index in Flyway migration V1, causing sequential scans on merchant parcel queries.

### Proposed Fix
Add Flyway migration `V11__add_idx_parcels_merchant_id.sql`:
```sql
CREATE INDEX idx_parcels_merchant_created ON parcels (merchant_id, created_at DESC);
```

### Acceptance Criteria
- Migration executes cleanly; `EXPLAIN` confirms index scan.

---

## Issue 9: [P2-PERF] Implement Route-Level Code Splitting to Reduce 763 kB Frontend Monolithic Chunk

- **Labels:** `performance`, `p2-major`, `frontend`
- **Component:** `frontend/src/routes.tsx`, `frontend/vite.config.ts`
- **Owner:** Frontend Lead

### Problem Description
All routes are imported synchronously in `routes.tsx`, causing Vite to bundle all features into a single 763 kB JS chunk.

### Proposed Fix
Use `React.lazy()` for route pages and configure manual chunks in Vite.

### Acceptance Criteria
- Main initial bundle chunk is under 300 kB.

---

## Issue 10: [P2-A11Y] Replace window.alert() in Merchant Bulk Upload with Accessible Alert Banner

- **Labels:** `accessibility`, `p2-major`, `wcag`, `frontend`
- **Component:** `frontend/src/features/merchant/components/BulkOrderUploadModal.tsx`
- **Owner:** Senior UI/UX Engineer

### Problem Description
Line 53 uses native `alert()`, disrupting screen reader focus and violating WCAG 2.2 standards.

### Proposed Fix
Replace `alert()` with an inline alert banner declaring `role="alert"` and `aria-live="polite"`.

### Acceptance Criteria
- No `alert()` calls remain; invalid file displays an inline banner.
