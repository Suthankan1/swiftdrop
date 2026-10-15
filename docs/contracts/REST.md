# Proposed external REST contract outline

REST is the public **command and simple read interface**, not an internal catch-all. Version prefix proposed `/api/v1`. Exact paths, request shapes, status codes, roles and OpenAPI files need owner review before implementation.

| Capability | Proposed route | Owning service | Authorization emphasis |
|---|---|---|---|
| Quote | `POST /api/v1/quotes` | Pricing | input validation/abuse limit |
| Book parcel | `POST /api/v1/parcels` | Delivery | bound authenticated customer/approved merchant, idempotency |
| Parcel tracking | `GET /api/v1/tracking/{trackingNumber}` | Delivery | intentionally redacted public fields, rate limit |
| My parcel/detail | `GET /api/v1/parcels/{parcelId}` | Delivery | parcel owner or authorized actor |
| Courier assignment | `POST /api/v1/assignments` | Delivery | dispatcher role, eligibility and concurrency |
| Courier update | `PATCH /api/v1/parcels/{parcelId}/status` | Delivery | *assigned* courier, state machine, version and proof |
| Merchant create order | `POST /api/v1/merchant/parcels` | Delivery (merchant credential checked via Accounts) | approved merchant tenant, scoped key, idempotency |
| Merchant register | `POST /api/v1/merchants` | Accounts | controlled pending state |
| Merchant approve | `POST /api/v1/merchants/{id}/approve` | Accounts | admin role and audit |
| Receipt | `GET /api/v1/parcels/{id}/receipt` | Delivery | owner/authorized recipient; no public proof link |
| Proof upload | `POST /api/v1/parcels/{id}/proof` | Delivery | assigned courier; private storage |
| Parcel live stream | Proposed `GET /api/v1/parcels/{id}/events` | Delivery | authorization header with fetch SSE client |

Avoid redundant REST aggregations when GraphQL is clearly more efficient; keep simple `/me`, `/health` and public tracking as REST. Support validation/problem responses, consistent error codes, pagination, OpenAPI docs and negative authorization tests. Never pass a caller-supplied `merchantId` as authoritative identity.
