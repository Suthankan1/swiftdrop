# Public Tracking Page — N10-WF06

## Overview

The public tracking page provides an **unauthenticated**, mobile-first interface for anyone with a valid SwiftDrop tracking reference to view redacted delivery milestones.

## Routes

| Path | Description |
|------|-------------|
| `/tracking` | Search form for tracking reference lookup |
| `/tracking/:trackingNumber` | Direct deep-link to a specific shipment's milestones |

Both routes are **anonymous** — no login, JWT token, or role is required.

## Tracking Reference Format

```
^SWD-[0-9]{8}-[A-Z0-9]{4,8}$
```

Example: `SWD-20261023-A1B2C3`

Input is **normalized** (trimmed + uppercased) before validation and API request.

## REST Provider

| Method | Endpoint | Auth |
|--------|----------|------|
| `GET` | `/api/v1/tracking/{trackingNumber}` | None (`credentials: 'omit'`, `cache: 'no-store'`) |

The endpoint is served by the **Delivery Operations** service via the API Gateway.

## Approved Public Display Fields (Allowlist)

| Field | Type | Description |
|-------|------|-------------|
| `trackingNumber` | string | SwiftDrop tracking reference |
| `currentStatus` | ParcelStatus | CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED |
| `originCity` | string? | Coarse origin city (optional) |
| `destinationCity` | string? | Coarse destination city (optional) |
| `timeline[].status` | string | Lifecycle milestone status |
| `timeline[].occurredAt` | string (ISO 8601) | Milestone timestamp |
| `timeline[].cityOrZone` | string? | Coarse city/zone checkpoint (optional) |

## Forbidden Private Fields

The following **must never appear** in the rendered DOM, error messages, clipboard output, or debug UI:

- `recipientName`, `recipientPhone`, `recipientEmail`
- `pickupAddress`, `dropoffAddress`
- `customerSub`, `createdBySub`, `merchantId`
- `courierSub`, `courierName`, `driverPhone`
- `proofId`, `proofUrl`, `receiptUrl`, `receiptDownload`
- `objectKey`, `storagePath`, `internalDatabaseId`
- `privateNotes`, `deliveryInstructions`, `customerEmail`

The frontend applies a strict **defense-in-depth allowlist projection** on the API response. If the backend leaks private fields, the frontend still strips them before rendering.

## Error Handling

| HTTP Status | User Message |
|-------------|-------------|
| 400 | Enter a valid SwiftDrop tracking number |
| 404 | Tracking reference not found |
| 429 | Too many tracking requests. Please wait |
| 500 | Generic safe error (no stack traces) |
| 503 / network | Tracking temporarily unavailable |
| Invalid JSON | Safe unavailable state |

- **No automatic retry** for 400, 404, 429, or validation errors
- Manual **Refresh** button provided for recoverable errors
- User input is preserved during retry

## Mobile Responsiveness

- Tested at viewports: 320px, 360px, 375px, 390px, 428px, 768px, 1024px, 1440px
- Form stacks vertically on narrow screens
- No horizontal overflow; long tracking references wrap safely
- Touch targets ≥44px min-height
- Tracking card and meta-strip adapt to single-column on small screens

## Accessibility

- Programmatic label on input via `<label>` + `aria-label`
- Error messages associated via `aria-describedby` and `aria-invalid`
- Loading announced via `role="status"` with `aria-live="polite"`
- Errors use `role="alert"`
- Semantic `<ol>` for timeline with `aria-label`
- Decorative timeline markers `aria-hidden="true"`
- Correct heading hierarchy (h1 → h2 → h3)
- `prefers-reduced-motion` respected (via existing global CSS)

## Privacy Controls

- `credentials: 'omit'` on all tracking requests
- `cache: 'no-store'` to prevent browser caching
- `<meta name="robots" content="noindex, nofollow">` injected on mount
- No tracking reference persistence in localStorage/sessionStorage
- No authenticated tokens sent to public endpoint
- No SSE, WebSocket, or GraphQL connections
- No external analytics scripts

## Refresh Behavior

- Manual **Refresh** button on results view
- `staleTime: 15_000` on TanStack Query to prevent rapid re-fetches
- No automatic polling or subscription
- No claim of "real-time" or "live" updates — REST snapshot only

## Test Evidence

- 48 component/integration tests covering: anonymous access, input validation, keyboard interaction, deep-link, status display, timeline, empty timeline, missing locations, clipboard, 404/429/503/500/network errors, malformed responses, oversized timelines, privacy allowlist, forbidden field stripping, accessibility, stale response prevention
- 36 API client tests (delivery.ts) including validation, error handling, privacy stripping
- Full suite: 787 tests, 51 files, all passing
- ESLint: clean
- TypeScript: clean
- Production build: successful
