# Fixed Findings Log

This log records every verified defect that has been remediated, tested, and submitted via pull request.

| Finding ID | Title | Module | Root Cause | Remediated In | Verified By | Status |
|---|---|---|---|---|---|---|
| BE-001 | Missing Gateway Route for Staff Profile | api-gateway | Missing route predicate | PR #302 | GatewayBusinessRoutesIntegrationTest | Fixed (PR #302 Submitted) |
| BE-002 | Gateway Route Fallback Port 8084 | api-gateway | Default port in @Value | PR #302 | GatewayRoutesConfig default | Fixed (PR #302 Submitted) |
| INT-005| Merchant Browser Missing merchant_id | delivery-service | Keycloak token lacks claim | PR #302 | MerchantParcelBookingControllerTest | Fixed (PR #302 Submitted) |
| FE-001 | Fake Bulk Order Intake in UI | frontend | Simulated setTimeout without API | PR #302 | BulkOrderUploadModal.test.tsx | Fixed (PR #302 Submitted) |
| FE-002 | Fake Webhook Test Ping in UI | frontend | Simulated setTimeout with fake HMAC | PR #302 | Component Inspection | Fixed (PR #302 Submitted) |
| OPS-001| Gateway Route Precedence for Pricing | api-gateway | Missing route in application.yml | PR #302 | GatewayBusinessRoutesIntegrationTest | Fixed (PR #302 Submitted) |
| A11Y-001| Native window.alert in File Upload | frontend | Direct window.alert call | PR #302 | BulkOrderUploadModal.test.tsx | Fixed (PR #302 Submitted) |
| QA-001 | Gateway Route Integration Test Gap | api-gateway | Route test suite missed staff/pricing | PR #302 | GatewayBusinessRoutesIntegrationTest | Fixed (PR #302 Submitted) |
| SEC-001| SSRF Protection in Webhooks | communications-service | Clarification on metadata and IPv6 | Pre-existing | WebhookDestinationValidatorTest | Already Fixed in Baseline |
| SEC-002| Inconsistent CORS Headers Across Services | api-gateway | Duplicate Access-Control-Allow-Origin | PR #303 | Gateway Security Tests (87/87) | Fixed (PR #303 Submitted) |
| DB-001 | Missing Index on parcels.merchant_id | delivery-service | Missing composite index for queries | PR #303 | DeliveryFlywayMigrationTest (4/4) | Fixed (PR #303 Submitted) |
| FE-003 | Monolithic Bundle Chunk (763 kB) | frontend | Monolithic bundle without chunking | PR #304 | Vite Build (max chunk 444 kB) | Fixed (PR #304 Submitted) |
| UX-001 | Unassigned Roles Redirection Loop | frontend | Empty roles view dead-end on login | PR #304 | AuthPages.test.tsx (20/20) | Fixed (PR #304 Submitted) |
| A11Y-002| Missing Focus Rings on Timeline Milestones | frontend | Missing :focus-visible indicators | PR #304 | PublicTrackingPage & Global CSS | Fixed (PR #304 Submitted) |
| INT-006| GraphQL deliveryStats Resolver Missing | graphql-query-service | Deferred gRPC provider (LATER_11) | ADR LATER_11 | GraphqlEndpointSecurityTest (81/81) | Approved Deferment |
| QA-002 | Hardcoded Ingress Port in Python Tests | tests | Inflexible loopback and port 18443 | PR #305 | test_runtime_feasibility.py (5/5) | Fixed (PR #305 Submitted) |
| DOC-001| Root Build Instructions Mismatch | documentation | Build doc accuracy check | README.md | Independent Service POMs Verified | Verified Accurate |
