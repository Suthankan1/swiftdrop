# Server-side authorization matrix — minimum model

The table lists **planned default permissions**, not an implementation guarantee. Validate membership, status and ownership **inside the owning backend service**, not solely via GraphQL resolver or client route guard.

| Action / data | Customer | Merchant | Courier | Dispatcher | Admin |
|---|---|---|---|---|---|
| Read own profile | Own | Own | Own | Own | Own |
| Create customer parcel | Own | — | — | — | Policy approval required |
| Create merchant parcel via scoped credential | — | Approved own merchant | — | — | — |
| Read parcel details/history | Own | Own tenant | Assigned/authorized | Operational zone authorization | Admin policy |
| Read public tracking by number | Redacted, rate-limited and no auth | Same public limits | Same public limits | Same public limits | Same public limits |
| Update parcel state / attach courier proof | — | — | **Currently assigned** | Only if explicitly approved workflow | Restricted override policy |
| Assign/reassign courier | — | — | — | Authorized dispatcher | Admin policy |
| Approve merchant / elevate staff roles | — | — | — | — | Controlled admin flow |
| Manage pricing/zones | — | — | — | Only if explicitly delegated | Authorized admin |
| GraphQL composite dashboard | Own | Approved own tenant | Own assignments | Authorized operational data | Authorized admin data |
| Register/manage merchant webhook | — | Approved own tenant | — | — | Authorized admin |

- Cross-owner/tenant/courier attempts produce **403** or appropriate privacy-preserving **404** per reviewed API contract; bad/missing token returns **401**. Tests should verify real service rejection.
- Never trust a `merchantId`, `courierId`, `createdBy` or `sub` supplied in unverified request bodies as authoritative identity.
- For gRPC, service authentication is **not** sufficient to impersonate any end user. Forward only verified/scoped actor context and recheck at owner.
- Decide if Dispatcher and Admin are separate user experiences but both need precise scopes; avoid a blanket "all STAFF can do everything" rule.
