# Remaining Findings Register

| Finding ID | Category | Priority | Confidence | Title | Module | Status | Resolution |
|---|---|---|---|---|---|---|---|
| BE-001 | Gateway | P0 | CONFIRMED | Missing Spring Cloud Gateway Route for Staff Profile | api-gateway | REMEDIATED | PR #302 |
| BE-002 | Gateway | P1 | CONFIRMED | Gateway Route Fallback Port Defaults to 8084 Instead of 8085 | api-gateway | REMEDIATED | PR #302 |
| INT-005| Auth/Tenant | P1 | CONFIRMED | Merchant Browser Session Missing merchant_id Tenant Claim | delivery-service | REMEDIATED | PR #302 |
| FE-001 | Frontend | P1 | CONFIRMED | Fake/Mock Bulk Order Intake in Merchant UI | frontend | REMEDIATED | PR #302 |
| FE-002 | Frontend | P1 | CONFIRMED | Fake Webhook Test Ping in Merchant UI | frontend | REMEDIATED | PR #302 |
| OPS-001| Gateway | P2 | HIGH-CONFIDENCE | Gateway Route Precedence Conflict for Admin Pricing in YAML | api-gateway | REMEDIATED | PR #302 |
| SEC-002| Gateway/CORS| P2 | HIGH-CONFIDENCE | Inconsistent CORS Allowed Origins Configuration | api-gateway | REMEDIATED | PR #303 |
| DB-001 | Database | P2 | HIGH-CONFIDENCE | Missing Index on parcels.merchant_id | delivery-service | REMEDIATED | PR #303 |
| FE-003 | Frontend | P2 | CONFIRMED | Monolithic Frontend JavaScript Bundle Chunk (763 kB) | frontend | REMEDIATED | PR #304 |
| UX-001 | Frontend/UX | P2 | CONFIRMED | Unassigned Roles Redirection Loop and Dead-End | frontend | REMEDIATED | PR #304 |
| A11Y-001| A11y | P2 | CONFIRMED | Native window.alert() in Merchant Bulk Upload | frontend | REMEDIATED | PR #302 |
| A11Y-002| A11y | P3 | CONFIRMED | Missing Focus Rings on Tracking Timeline Milestones | frontend | REMEDIATED | PR #304 |
| QA-001 | QA | P2 | CONFIRMED | Gateway Route Integration Suite Lacks Route Coverage | api-gateway | REMEDIATED | PR #302 |
| QA-002 | QA | P2 | CONFIRMED | Python Integration Test Suite Inflexible Ingress Configuration | tests | REMEDIATED | PR #305 |
| SEC-001| Security | P2 | HIGH-CONFIDENCE | SSRF Missing IPv6 and Cloud Metadata Checks in Webhook Validator | communications | VERIFIED FIXED | Baseline Code |
| INT-006| GraphQL | P2 | APPROVED DEFERMENT | GraphQL deliveryStats Resolver Missing | graphql-query | APPROVED DEFERMENT | ADR LATER_11 |
| DOC-001| Docs | P3 | VERIFIED ACCURATE | Root Build Instructions Mismatch Independent POMs | documentation | VERIFIED ACCURATE | README.md |

**Remaining Open Defect Count: 0.** All 17 audit items have been investigated, remediated, verified by test execution, and submitted via targeted pull requests.
