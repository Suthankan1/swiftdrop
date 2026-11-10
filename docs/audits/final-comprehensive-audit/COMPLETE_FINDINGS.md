# SwiftDrop System Audit — Complete Technical Findings Catalog

This catalog documents every defect, architectural discrepancy, integration gap, UI/UX issue, security risk, and testing blindspot discovered during the exhaustive technical audit of the SwiftDrop repository on branch `test`.

---

## Table of Contents
1. [BE-001 / INT-001: Missing Spring Cloud Gateway Route for Staff Profile](#be-001--int-001-missing-spring-cloud-gateway-route-for-staff-profile)
2. [BE-002 / INT-002: Gateway Route Fallback Port Defaults to Communications Service Instead of GraphQL Service](#be-002--int-002-gateway-route-fallback-port-defaults-to-communications-service)
3. [INT-005 / BE-003: Authenticated Merchant Browser Session Missing merchant_id Tenant Claim in Keycloak JWT](#int-005--be-003-authenticated-merchant-browser-session-missing-merchant_id-claim)
4. [FE-001 / INT-003: Fake/Mock Bulk Order Intake in Merchant UI Discards Orders in Memory](#fe-001--int-003-fakemock-bulk-order-intake-in-merchant-ui)
5. [FE-002 / INT-004: Fake Webhook Test Ping in Merchant UI Simulates Mock Dispatch](#fe-002--int-004-fake-webhook-test-ping-in-merchant-ui)
6. [INT-006 / BE-004: GraphQL deliveryStats Resolver Missing, Surfacing gRPC UNAVAILABLE Error](#int-006--be-004-graphql-deliverystats-resolver-missing)
7. [OPS-001 / BE-005: Gateway Route Conflict for Admin Pricing in application.yml](#ops-001--be-005-gateway-route-conflict-for-admin-pricing)
8. [FE-003: Monolithic Frontend JavaScript Bundle Chunk Exceeds Performance Budget (763 kB)](#fe-003-monolithic-frontend-javascript-bundle-chunk)
9. [UX-001: Unassigned Roles Redirection Loop and Dead-End on Callback Page](#ux-001-unassigned-roles-redirection-loop-and-dead-end)
10. [SEC-001: SSRF Protection Missing IPv6 and Cloud Metadata Checks in WebhookDestinationValidator](#sec-001-ssrf-protection-missing-ipv6-and-cloud-metadata-checks)
11. [SEC-002: Inconsistent CORS Configurations Between Edge Gateway and Microservices](#sec-002-inconsistent-cors-configurations)
12. [DB-001: Missing Index on parcels.merchant_id in Delivery Service Database Schema](#db-001-missing-index-on-parcelsmerchant_id)
13. [QA-001: Gateway Route Integration Suite Lacks Route Coverage for Staff and Admin Pricing Paths](#qa-001-gateway-route-integration-suite-lacks-route-coverage)
14. [QA-002: Python Integration Test Suite Skips Ingress Tests Without Local Mock Fallback](#qa-002-python-integration-test-suite-skips-ingress-tests)
15. [A11Y-001: Native window.alert() Used for File Upload Validation Error in Merchant Modal](#a11y-001-native-windowalert-used-for-file-upload-validation)
16. [A11Y-002: Missing Visible Keyboard Focus Indicators on Custom Tracking Timeline Milestones](#a11y-002-missing-visible-keyboard-focus-indicators)
17. [DOC-001: Root Build Instructions Mismatch with Independent Service POM Architecture](#doc-001-root-build-instructions-mismatch)

---

## BE-001 / INT-001: Missing Spring Cloud Gateway Route for Staff Profile

- **Finding ID:** `BE-001` (Cross-referenced with `INT-001`)
- **Category:** Backend / Gateway Routing
- **Priority:** **P0 (Blocker)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `services/api-gateway`, `services/accounts-service`, `frontend`
- **Affected Files & Lines:**
  - `services/api-gateway/src/main/java/com/swiftdrop/apigateway/config/GatewayRoutesConfig.java` (Lines 40–110)
  - `services/api-gateway/src/main/resources/application.yml` (Lines 20–60)
  - `services/accounts-service/src/main/java/com/swiftdrop/accountsservice/staff/StaffProfileController.java` (Lines 35–45)
  - `frontend/src/features/staff/api/staffProfile.ts` (Line 30)

### 1. Workflow Description
When an authorized staff member (Admin, Courier, or Dispatcher) logs into SwiftDrop, the frontend calls `fetchStaffProfile()` (`GET /api/v1/staff/me`) to populate operator names, operational zones, staff roles, and display avatars in the main header and workspaces.

### 2. Root Cause Analysis
In `accounts-service`, the `StaffProfileController` maps:
```java
@RestController
@RequestMapping("/api/v1/staff")
public class StaffProfileController {
    @GetMapping({"/me", "/profile"})
    public ResponseEntity<StaffProfileResponse> getStaffProfile(@AuthenticationPrincipal Jwt jwt) { ... }
}
```
However, in `api-gateway`, the `GatewayRoutesConfig` defines routes only for:
- `/api/v1/users/**` -> `accounts-service`
- `/api/v1/admin/**` -> `accounts-service`
- `/api/v1/merchants/**` -> `accounts-service`
- `/api/v1/pricing/**` -> `pricing-service`
- `/api/v1/parcels/**` -> `delivery-service`
- `/api/v1/notifications/**` -> `communications-service`

The path `/api/v1/staff/**` is missing from both `GatewayRoutesConfig.java` and `application.yml`.

### 3. Empirical Proof & Reproduction
1. Start `accounts-service` on port 8081 and `api-gateway` on port 8080.
2. Obtain a valid Keycloak staff JWT.
3. Send `curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/staff/me`.
4. Result: Gateway responds with **HTTP 404 Not Found** (No route matched).
5. Directly calling `curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/staff/me` responds with **HTTP 200 OK**.

### 4. Real-World Impact
Any real browser or API client communicating through the API Gateway cannot retrieve the staff profile. In the frontend, `useStaffProfile()` encounters a rejected promise, preventing user workspace personalization and throwing unhandled API errors.

### 5. Recommended Fix
In `GatewayRoutesConfig.java`, add the staff route to the RouteLocator builder:
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

---

## BE-002 / INT-002: Gateway Route Fallback Port Defaults to Communications Service

- **Finding ID:** `BE-002` (Cross-referenced with `INT-002`)
- **Category:** Backend / Configuration
- **Priority:** **P1 (Critical)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `services/api-gateway`
- **Affected Files & Lines:**
  - `services/api-gateway/src/main/java/com/swiftdrop/apigateway/config/GatewayRoutesConfig.java` (Line 31)

### 1. Workflow Description
Routing GraphQL composite query requests (`POST /graphql`) from the React frontend or API clients through the API Gateway to `graphql-query-service`.

### 2. Root Cause Analysis
In `GatewayRoutesConfig.java` line 31:
```java
@Value("${GRAPHQL_SERVICE_URL:http://localhost:8084}")
private String graphqlServiceUrl;
```
Port 8084 is allocated to `communications-service`. Port 8085 is allocated to `graphql-query-service`.
While `application.yml` specifies `http://localhost:8085`, if the environment variable `GRAPHQL_SERVICE_URL` is omitted and the bean is initialized using the Java field default, `/graphql` requests are forwarded to `communications-service` on port 8084.

### 3. Real-World Impact
GraphQL queries receive HTTP 404 responses or connection errors in any environment relying on Java default configuration properties.

### 4. Recommended Fix
Modify line 31 in `GatewayRoutesConfig.java`:
```java
@Value("${GRAPHQL_SERVICE_URL:http://localhost:8085}")
private String graphqlServiceUrl;
```

---

## INT-005 / BE-003: Authenticated Merchant Browser Session Missing merchant_id Claim

- **Finding ID:** `INT-005` (Cross-referenced with `BE-003`)
- **Category:** Integration / Authentication & Tenant Isolation
- **Priority:** **P1 (Critical)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `services/delivery-service`, `platform/identity`, `frontend`
- **Affected Files & Lines:**
  - `services/delivery-service/src/main/java/com/swiftdrop/deliveryservice/booking/web/MerchantParcelBookingController.java` (Lines 75–85)
  - `services/delivery-service/src/main/java/com/swiftdrop/deliveryservice/parcel/service/DefaultParcelQueryService.java` (Lines 62–75)
  - `platform/identity/realm-swiftdrop.json` (Keycloak client mappers)

### 1. Workflow Description
A merchant logs into the SwiftDrop React application via Keycloak OIDC, navigates to `/merchant/orders`, and attempts to book a new parcel via `POST /api/v1/merchant/parcels` or view their existing orders.

### 2. Root Cause Analysis
`MerchantParcelBookingController` enforces tenant isolation via:
```java
Object merchantClaim = jwt.getClaim("merchant_id");
if (merchantClaim == null || merchantClaim.toString().isBlank()) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified token does not contain a merchant_id tenant claim");
}
```
In Keycloak's `realm-swiftdrop.json`, merchant users are granted the realm role `MERCHANT`. However, Keycloak does not store `merchant_id` in user attributes, nor is there a protocol mapper configured to inject `merchant_id` into the access token JWT. The `merchants` table exists in PostgreSQL inside `accounts-service`.

### 3. Empirical Proof & Reproduction
1. Log into Keycloak with merchant credentials (`merchant@swiftdrop.com`).
2. Inspect the JWT access token decoded payload.
3. Note the presence of realm roles (`["MERCHANT"]`), `sub`, `email`, and `preferred_username`.
4. Note that `merchant_id` is null / absent.
5. Submit `POST /api/v1/merchant/parcels` with `Authorization: Bearer <token>`.
6. Result: Returns **HTTP 403 Forbidden** with message `"Verified token does not contain a merchant_id tenant claim"`.

### 4. Real-World Impact
Merchants cannot use the web UI to create parcels or view their parcel list. The endpoint currently only works when invoked with an API key via `X-Merchant-Api-Key`, which is only suitable for M2M integrations.

### 5. Recommended Fix
Two coordinated solutions:
1. In `accounts-service`, on successful merchant enrollment/login, map user ID to merchant profile ID.
2. In `delivery-service`, if `merchant_id` claim is missing but the actor has role `ROLE_MERCHANT` and a valid `sub` claim, resolve the merchant ID via gRPC/internal client from `accounts-service` or add a Keycloak custom token mapper script/authenticator.

---

## FE-001 / INT-003: Fake/Mock Bulk Order Intake in Merchant UI

- **Finding ID:** `FE-001` (Cross-referenced with `INT-003`)
- **Category:** Frontend / Data Integrity
- **Priority:** **P1 (Critical)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`
- **Affected Files & Lines:**
  - `frontend/src/features/merchant/components/BulkOrderUploadModal.tsx` (Lines 94–105, 137–142)

### 1. Workflow Description
Merchant uploads a CSV batch file on `/merchant/orders` to book multiple parcels simultaneously.

### 2. Root Cause Analysis
In `BulkOrderUploadModal.tsx`:
```typescript
const handleConfirmImport = async () => {
    setIsSubmitting(true);
    setUploadProgress(0);
    // Simulate batch upload
    const interval = setInterval(() => {
        setUploadProgress((prev) => {
            if (prev >= 100) {
                clearInterval(interval);
                return 100;
            }
            return prev + 25;
        });
    }, 300);

    setTimeout(() => {
        setIsSubmitting(false);
        onImportComplete(validRows.length);
        onClose();
    }, 1500);
};
```
No HTTP request or API call is made. The parsed `validRows` array is completely discarded in JavaScript memory.

### 3. Real-World Impact
Merchants believe dozens or hundreds of parcels were booked, but nothing is written to the database. This represents critical data loss and deceitful UI behavior.

### 4. Recommended Fix
Implement a real bulk booking API client:
- In `delivery-service`: Support `POST /api/v1/merchant/parcels/bulk` accepting JSON array of parcel requests.
- In `frontend/src/features/merchant/api/merchantParcels.ts`: Add `importBulkParcels(rows: BulkOrderRow[])`.
- In `BulkOrderUploadModal.tsx`: Call the API, display actual processing results, and surface failed rows with error reasons.

---

## FE-002 / INT-004: Fake Webhook Test Ping in Merchant UI

- **Finding ID:** `FE-002` (Cross-referenced with `INT-004`)
- **Category:** Frontend / Integration
- **Priority:** **P1 (Critical)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`, `services/communications-service`
- **Affected Files & Lines:**
  - `frontend/src/features/merchant/components/WebhookSubscriptionList.tsx` (Lines 31–43)

### 1. Workflow Description
Merchant clicks "Test Ping" next to a configured webhook endpoint on `/merchant/webhooks`.

### 2. Root Cause Analysis
```typescript
const handleSendTestPing = (id: string) => {
    setTestingId(id);
    setTimeout(() => {
        setTestingId(null);
        setPingResult({
            id,
            status: 200,
            duration: 72,
            signature: 'sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
            timestamp: new Date().toISOString()
        });
    }, 700);
};
```
The test ping is completely fabricated using a fixed SHA-256 hash of an empty string (`e3b0c44...`). No HTTP test dispatch is performed by `communications-service`.

### 3. Recommended Fix
- In `communications-service`: Expose `POST /api/v1/merchants/webhooks/{id}/test` which dispatches a signed test payload to the registered URL and records the real HTTP status, roundtrip latency, and signature.
- In `frontend`: Call this API and display authentic ping results.

---

## INT-006 / BE-004: GraphQL deliveryStats Resolver Missing

- **Finding ID:** `INT-006` (Cross-referenced with `BE-004`)
- **Category:** Backend / GraphQL & gRPC
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT / APPROVED DEFERMENT**
- **Affected Modules:** `services/graphql-query-service`, `frontend`
- **Affected Files & Lines:**
  - `services/graphql-query-service/src/main/java/com/swiftdrop/graphqlqueryservice/config/GraphqlRuntimeConfig.java` (Lines 80–92)
  - `contracts/graphql/schema.graphql` (query `deliveryStats`)
  - `frontend/src/features/admin/pages/ReportsPage.tsx`
  - `docs/architecture/adr/LATER_11_GRAPHQL_DELIVERY_STATS.md`

### 1. Root Cause Analysis
The GraphQL schema exposes `deliveryStats(from: String, to: String): DeliveryStats!`. However, the underlying gRPC service in `delivery-service` was deferred under ADR `LATER_11`. The GraphQL resolver throws `Status.UNAVAILABLE.asRuntimeException("gRPC provider for deliveryStats is deferred")`.
When the Admin Reports page loads, it runs this query and displays an unhandled error alert.

### 2. Recommended Fix
Complete the gRPC `DeliveryStatsService` in `delivery-service` or return a graceful empty statistics response (`null` with informative message) rather than throwing `Status.UNAVAILABLE`.

---

## OPS-001 / BE-005: Gateway Route Conflict for Admin Pricing in application.yml

- **Finding ID:** `OPS-001` (Cross-referenced with `BE-005`)
- **Category:** DevOps / Gateway
- **Priority:** **P2 (Major)**
- **Confidence:** **HIGH-CONFIDENCE STATIC FINDING**
- **Affected Modules:** `services/api-gateway`
- **Affected Files & Lines:**
  - `services/api-gateway/src/main/resources/application.yml` (Lines 35–50)
  - `services/api-gateway/src/main/java/com/swiftdrop/apigateway/config/GatewayRoutesConfig.java` (Lines 55–65)

### 1. Root Cause Analysis
In `application.yml`, the rule `/api/v1/admin/**` maps to `accounts-service`. There is no YAML rule for `/api/v1/admin/pricing/**`.
In `GatewayRoutesConfig.java`, there is a programmatic route for `/api/v1/admin/pricing/**` mapping to `pricing-service` with order 5.
If configuration changes cause Spring Cloud Gateway to evaluate YAML routes first, admin requests to update pricing zones will be sent to `accounts-service` and return 404.

### 2. Recommended Fix
Explicitly declare the `/api/v1/admin/pricing/**` route in `application.yml` preceding the catch-all `/api/v1/admin/**` route.

---

## FE-003: Monolithic Frontend JavaScript Bundle Chunk (763 kB)

- **Finding ID:** `FE-003`
- **Category:** Frontend / Performance
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`
- **Affected Files & Lines:**
  - `frontend/src/routes.tsx`
  - `frontend/vite.config.ts`

### 1. Root Cause Analysis
`npm run build` outputs:
`dist/assets/index-DfwBlCYu.js   763.15 kB │ gzip: 207.10 kB`
Every page in the application is imported synchronously in `routes.tsx`, forcing public tracking visitors to download admin, merchant, and courier bundles.

### 2. Recommended Fix
Refactor `routes.tsx` to use `React.lazy()` for route components:
```typescript
const CustomerPortal = React.lazy(() => import('./features/customer/pages/CustomerPortalPage'));
const MerchantDashboard = React.lazy(() => import('./features/merchant/pages/MerchantOrdersDashboardPage'));
const AdminDashboard = React.lazy(() => import('./features/admin/pages/AdminPage'));
```
Wrap the `<Routes>` in `<Suspense fallback={<PageLoadingSpinner />}>`.

---

## UX-001: Unassigned Roles Redirection Loop and Dead-End on Callback Page

- **Finding ID:** `UX-001`
- **Category:** Frontend / UX
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`
- **Affected Files & Lines:**
  - `frontend/src/auth/AuthPages.tsx` (Lines 145–155)
  - `frontend/src/auth/redirect.ts` (Lines 20–35)

### 1. Root Cause Analysis
If an authenticated user has no matching realm role, `getDefaultRouteForRoles` returns `null`. The `CallbackPage` navigates to `/login`.
On `/login`, since `isAuthenticated` is true, the user is presented with a static card: `"You are currently authenticated in an active session."` with no available workspace links or instructions.

### 2. Recommended Fix
Route unassigned authenticated users to an informative `/unauthorized` or `/pending-approval` view explaining that their account is pending role assignment, with an explicit "Log Out" button.

---

## SEC-001: SSRF Protection Missing IPv6 and Cloud Metadata Checks

- **Finding ID:** `SEC-001`
- **Category:** Security / Webhooks
- **Priority:** **P2 (Major)**
- **Confidence:** **HIGH-CONFIDENCE STATIC FINDING**
- **Affected Modules:** `services/communications-service`
- **Affected Files & Lines:**
  - `services/communications-service/src/main/java/com/swiftdrop/communicationsservice/webhook/service/WebhookDestinationValidator.java` (Lines 40–75)

### 1. Root Cause Analysis
`WebhookDestinationValidator` checks IPv4 private ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.1). It does not validate against:
- IPv6 loopback (`::1`)
- IPv4-mapped IPv6 (`::ffff:127.0.0.1`)
- Cloud instance metadata service (`169.254.169.254`)
- Link-local addresses (`169.254.0.0/16`, `fe80::/10`)

### 2. Recommended Fix
Resolve the hostname to an `InetAddress` array and verify that no resolved address is `isLoopbackAddress()`, `isLinkLocalAddress()`, `isSiteLocalAddress()`, or in `169.254.0.0/16`.

---

## SEC-002: Inconsistent CORS Configurations

- **Finding ID:** `SEC-002`
- **Category:** Security / Gateway
- **Priority:** **P2 (Major)**
- **Confidence:** **HIGH-CONFIDENCE STATIC FINDING**
- **Affected Modules:** `services/api-gateway`, Downstream Microservices

### 1. Root Cause Analysis
`api-gateway` sets CORS headers for allowed origins. However, downstream services also configure CORS independently. If Gateway forwards preflight OPTIONS requests without stripping downstream CORS headers, browsers reject responses containing duplicate `Access-Control-Allow-Origin` values.

### 2. Recommended Fix
Configure `DedupeResponseHeader` filter in `api-gateway`:
```yaml
default-filters:
  - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST
```

---

## DB-001: Missing Index on parcels.merchant_id

- **Finding ID:** `DB-001`
- **Category:** Database / Performance
- **Priority:** **P2 (Major)**
- **Confidence:** **HIGH-CONFIDENCE STATIC FINDING**
- **Affected Modules:** `services/delivery-service`
- **Affected Files & Lines:**
  - `services/delivery-service/src/main/resources/db/migration/V1__init_delivery_schema.sql` (Lines 30–70)

### 1. Root Cause Analysis
The `parcels` table defines `merchant_id VARCHAR(64)`, but no index exists on this column. Queries filtering by `merchant_id` will trigger sequential table scans as parcel volume scales.

### 2. Recommended Fix
Add Flyway migration `V11__add_idx_parcels_merchant_id.sql`:
```sql
CREATE INDEX idx_parcels_merchant_id ON parcels (merchant_id, created_at DESC);
```

---

## QA-001: Gateway Route Integration Suite Lacks Route Coverage for Staff

- **Finding ID:** `QA-001`
- **Category:** QA / Test Coverage
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `services/api-gateway`
- **Affected Files & Lines:**
  - `services/api-gateway/src/test/java/com/swiftdrop/apigateway/config/GatewayRoutesConfigTest.java`

### 1. Root Cause Analysis
`GatewayRoutesConfigTest` tests parcel booking, pricing, and tracking routes, but omitted `/api/v1/staff/**` and `/api/v1/admin/pricing/**`. This test gap allowed `BE-001` to escape into the `test` branch.

### 2. Recommended Fix
Add route test cases verifying destination URIs for all endpoints listed in `docs/architecture/PROTOCOL_MATRIX.md`.

---

## QA-002: Python Integration Test Suite Skips Ingress Tests

- **Finding ID:** `QA-002`
- **Category:** QA / Test Automation
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `tests/`
- **Affected Files & Lines:**
  - `tests/test_ingress.py` (Lines 15–40)

### 1. Root Cause Analysis
5 tests in `tests/test_ingress.py` skip unconditionally when port 80 is not listening. In local non-Kubernetes environments, tests should be able to validate gateway routing against port 8080.

### 2. Recommended Fix
Allow `SWIFTDROP_GATEWAY_URL` environment override to target local API Gateway during standalone testing.

---

## A11Y-001: Native window.alert() Used for File Upload Validation

- **Finding ID:** `A11Y-001`
- **Category:** Frontend / Accessibility
- **Priority:** **P2 (Major)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`
- **Affected Files & Lines:**
  - `frontend/src/features/merchant/components/BulkOrderUploadModal.tsx` (Line 53)

### 1. Root Cause Analysis
Line 53 directly invokes `alert('Please upload a valid CSV file.');` when a non-CSV file is dropped, violating WCAG 2.2 accessibility standards and locking user focus.

### 2. Recommended Fix
Replace `alert()` with an accessible inline error banner with `role="alert"` and `aria-live="polite"`.

---

## A11Y-002: Missing Visible Keyboard Focus Indicators on Timeline Milestones

- **Finding ID:** `A11Y-002`
- **Category:** Frontend / Accessibility
- **Priority:** **P3 (Minor)**
- **Confidence:** **CONFIRMED DEFECT**
- **Affected Modules:** `frontend`
- **Affected Files & Lines:**
  - `frontend/src/features/tracking/components/TrackingTimeline.tsx` (Lines 45–75)

### 1. Root Cause Analysis
Timeline milestone nodes lack high-contrast focus rings when navigated via keyboard.

### 2. Recommended Fix
Add Tailwind classes `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2`.

---

## DOC-001: Root Build Instructions Mismatch with Independent Service POM Architecture

- **Finding ID:** `DOC-001`
- **Category:** Documentation
- **Priority:** **P3 (Minor)**
- **Confidence:** **DOCUMENTATION MISMATCH**
- **Affected Modules:** `documentation`
- **Affected Files & Lines:**
  - `PROJECT_STRUCTURE.md` (Lines 15–35)

### 1. Root Cause Analysis
`PROJECT_STRUCTURE.md` mentions running `mvn clean install` at the repository root, but there is no root aggregator `pom.xml`. Each microservice is maintained as an independent Maven project to enforce loose coupling.

### 2. Recommended Fix
Update documentation with instructions to build services individually or via the master build script `./scripts/build-all.sh`.
