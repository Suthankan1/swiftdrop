# Regression Prevention Checklist

- [ ] Staff profile (/api/v1/staff/me) accessible via Gateway with Bearer token
- [ ] Anonymous access to /api/v1/staff/** rejected with 401
- [ ] Non-staff role (CUSTOMER) rejected with 403
- [ ] Gateway fallback for /graphql routes to 8085
- [ ] Merchant browser JWT without merchant_id claim resolves tenant in delivery-service
- [ ] Non-approved merchant rejected with 403
- [ ] Merchant bulk order upload does not display fake success when no backend import exists
- [ ] Webhook test ping does not display fake success
- [ ] All 1,743 Java unit/integration tests passing
- [ ] All 1,260 Vitest tests passing
- [ ] Frontend build succeeds without TypeScript errors
