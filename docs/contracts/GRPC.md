# Internal gRPC/Protobuf contract plan — draft

Private service-to-service calls must have a clear business reason. gRPC is **not** the browser API. Review `.proto` versioned packages and Java generation setup separately; no `.proto` files are included in this starter.

| Suggested caller | Callee | Use | Failure policy |
|---|---|---|---|
| Delivery | Accounts | verify account/courier/merchant eligibility | fail closed, no unauthorized booking/assignment |
| Delivery | Pricing | verify quote/price-version binding | don't silently issue a fresh price on failed verification |
| GraphQL Query API | Accounts | authorized profile/merchant/courier summaries | bounded read deadline, no cross-tenant leakage |
| GraphQL Query API | Pricing | pricing/zone dashboard summaries | bounded read deadline |
| GraphQL Query API | Delivery | booking/assignment/status dashboard projections | bounded read deadline, pagination and batch APIs |

**Must define when coding:** package and message versions (`devarom.<domain>.v1`); error/status model; UTC timestamp and money minor-unit representation; pagination; request correlation; mTLS or authenticated service identity; actor/tenant context signed or verified at each hop; deadlines; no naïve retry for non-idempotent writes. Keep protobuf field numbers stable; never reuse removed numbers. Treat domain reads as secure, not magically trusted just because gRPC is private.
