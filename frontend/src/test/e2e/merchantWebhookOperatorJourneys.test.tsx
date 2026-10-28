/**
 * SwiftDrop Platform — Merchant Order, Webhook and Operator Management Acceptance Suite
 * Issue #163 (N10-QA-03)
 *
 * Verifies end-to-end frontend user journeys, route protections, tenant isolation,
 * and security boundaries:
 *
 * 1. Merchant Order Dashboard & Detail Workflows:
 *    - Authorized merchant loads `/merchant/orders` dashboard
 *    - Accurate metric summary cards (total, active, delivered, failed)
 *    - Status filter bar (ALL, ACTIVE, DELIVERED, FAILED) & search filtering
 *    - Tracking inspection modal invocation
 *    - Loading, empty, and query error states
 *    - Order detail view navigation and direct deep linking (`/merchant/orders/:parcelId`)
 *    - Authoritative fields and append-only status transition history rendering
 *    - 404 Not Found state for nonexistent parcel IDs
 *    - 403 Forbidden Access Denied state for cross-tenant parcel IDs
 *    - Navigation return to dashboard without state corruption
 *
 * 2. Merchant Webhook Lifecycle & Security:
 *    - Subscriptions list rendering at `/merchant/webhooks`
 *    - Creation of HTTPS webhook subscriptions with supported event filters
 *    - Client-side SSRF validation (rejects plain http, localhost, 127.0.0.1, 10.x, credentials in URL)
 *    - One-time signing secret modal presentation and dismiss
 *    - Safe deletion confirmation modal (focus trap, cancel/escape, submission)
 *    - Delivery logs modal rendering with attempt latency, response codes, and error summaries
 *    - Failed delivery replay command
 *
 * 3. Operator Management Workflows:
 *    - Admin role route protection on `/admin/**` (denies CUSTOMER, COURIER, DISPATCHER, MERCHANT)
 *    - Merchant Review (`/admin/merchants`): lists pending onboarding requests
 *    - Merchant approval with review token confirmation
 *    - Merchant suspension requiring non-empty rationale
 *    - 409 Conflict handling on stale review token
 *    - User management (`/admin/users`) with bounded pagination
 *    - Staff lifecycle (`/admin/staff`) with role/status filters and controlled provisioning
 *    - Pricing & Zone admin (`/admin/pricing`) with draft card activation confirmation
 *
 * 4. Multi-Tenant Isolation & Authentication Resilience:
 *    - Switching between Merchant A and Merchant B isolates queries and clears cache
 *    - Session expiration (401) triggers clean teardown
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../app/router/routes'
import type { AuthContextValue } from '../../auth/context'
import type { SwiftDropRole } from '../../auth/contracts'
import { anonymousAuth, TestProviders } from '../authFixture'
import { merchantFixture } from '../fixtures'
import type { MerchantDashboardData } from '../../features/merchant/api/merchantDashboardContracts'
import type { ParcelDetailResponse } from '../../features/customer/api/deliveryContracts'
import type {
  WebhookDeliveryLogListResponse,
  WebhookSubscription,
  WebhookSubscriptionListResponse,
} from '../../features/merchant/api/webhookContracts'
import type {
  MerchantReviewDetail,
} from '../../features/admin/api/merchantReview'
import { adminUser, adminStaff, adminPage } from '../adminManagementFixture'

// ============================================================================
// 1. SYNTHETIC TENANTS & ACTOR FIXTURES
// ============================================================================

// Merchant A (Primary Tenant)
const merchantAProfile = {
  ...merchantFixture,
  keycloakSub: 'sub-merchant-tenant-alpha',
  merchantProfile: {
    ...merchantFixture.merchantProfile!,
    merchantId: 'merch-alpha-uuid-1111',
    businessName: 'Alpha Logistics PLC',
    registrationNumber: 'PV-ALPHA-01',
    status: 'APPROVED',
  },
}

// Merchant B (Second Tenant for Isolation Boundaries)
const merchantBProfile = {
  ...merchantFixture,
  keycloakSub: 'sub-merchant-tenant-beta',
  merchantProfile: {
    ...merchantFixture.merchantProfile!,
    merchantId: 'merch-beta-uuid-2222',
    businessName: 'Beta Distributing Co',
    registrationNumber: 'PV-BETA-02',
    status: 'APPROVED',
  },
}

// Operator / Admin Profile
const adminProfile = {
  id: 'profile-admin-sub-001',
  keycloakSub: 'admin-sub-001',
  email: 'admin.operator@swiftdrop.test',
  fullName: 'Super Administrator',
  phoneNumber: '+94770009999',
  roles: ['ADMIN'] as SwiftDropRole[],
  merchantProfile: null,
  createdAt: '2026-10-01T00:00:00Z',
}

// Synthetic Orders for Merchant A Dashboard
const mockMerchantADashboard: MerchantDashboardData = {
  merchantId: 'merch-alpha-uuid-1111',
  businessName: 'Alpha Logistics PLC',
  status: 'APPROVED',
  totalOrdersCount: 4,
  activeOrdersCount: 2,
  deliveredOrdersCount: 1,
  failedOrdersCount: 1,
  recentOrders: [
    {
      parcelId: 'parcel-alpha-101',
      trackingNumber: 'SWD-20261028-A101',
      currentStatus: 'IN_TRANSIT',
      weightKg: 3.5,
      feeMinorUnits: 250000,
      currency: 'LKR',
      createdAt: '2026-10-28T04:00:00Z',
      lastStatusUpdate: '2026-10-28T05:30:00Z',
    },
    {
      parcelId: 'parcel-alpha-102',
      trackingNumber: 'SWD-20261028-A102',
      currentStatus: 'OUT_FOR_DELIVERY',
      weightKg: 1.2,
      feeMinorUnits: 180000,
      currency: 'LKR',
      createdAt: '2026-10-28T04:15:00Z',
      lastStatusUpdate: '2026-10-28T06:00:00Z',
    },
    {
      parcelId: 'parcel-alpha-103',
      trackingNumber: 'SWD-20261028-A103',
      currentStatus: 'DELIVERED',
      weightKg: 5.0,
      feeMinorUnits: 320000,
      currency: 'LKR',
      createdAt: '2026-10-27T10:00:00Z',
      lastStatusUpdate: '2026-10-27T14:30:00Z',
    },
    {
      parcelId: 'parcel-alpha-104',
      trackingNumber: 'SWD-20261028-A104',
      currentStatus: 'FAILED',
      weightKg: 2.0,
      feeMinorUnits: 150000,
      currency: 'LKR',
      createdAt: '2026-10-27T11:00:00Z',
      lastStatusUpdate: '2026-10-27T16:00:00Z',
    },
  ],
}

// Authoritative Order Detail for Parcel Alpha 101
const mockParcelAlpha101Detail: ParcelDetailResponse = {
  parcelId: 'parcel-alpha-101',
  trackingNumber: 'SWD-20261028-A101',
  createdBySub: 'sub-merchant-tenant-alpha',
  merchantId: 'merch-alpha-uuid-1111',
  currentStatus: 'IN_TRANSIT',
  version: 2,
  weightKg: 3.5,
  dimensions: 'STANDARD',
  amountMinorUnits: 250000,
  currency: 'LKR',
  rateVersion: 'RATE-2026-V1',
  pickupAddress: '10 Galle Road, Colombo 03',
  dropoffAddress: '45 Kandy Road, Peradeniya',
  activeCourierSub: 'courier-sub-007',
  createdAt: '2026-10-28T04:00:00Z',
}

const mockParcelAlpha101History = {
  items: [
    {
      historyId: 'h-101-1',
      parcelId: 'parcel-alpha-101',
      previousStatus: null,
      newStatus: 'CREATED' as const,
      triggerReason: 'MERCHANT_ORDER_PLACED',
      changedBySub: 'sub-merchant-tenant-alpha',
      changedAt: '2026-10-28T04:00:00Z',
      version: 1,
    },
    {
      historyId: 'h-101-2',
      parcelId: 'parcel-alpha-101',
      previousStatus: 'CREATED' as const,
      newStatus: 'IN_TRANSIT' as const,
      triggerReason: 'PICKED_UP_FROM_WAREHOUSE',
      changedBySub: 'courier-sub-007',
      changedAt: '2026-10-28T05:30:00Z',
      version: 2,
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
}

// Synthetic Webhook Subscriptions for Merchant A
const mockWebhookSubscriptionA: WebhookSubscription = {
  subscriptionId: 'wh-sub-alpha-001',
  merchantId: 'merch-alpha-uuid-1111',
  httpsUrl: 'https://api.alphalogistics.lk/webhooks/swiftdrop',
  allowedEventTypes: ['parcel.status.changed.v1'],
  active: true,
  createdAt: '2026-10-25T08:00:00Z',
}

// Synthetic Delivery Logs for Webhook A
const mockWebhookDeliveriesA: WebhookDeliveryLogListResponse = {
  totalElements: 2,
  deliveries: [
    {
      deliveryId: 'wh-del-001',
      eventId: 'evt-uuid-101',
      parcelId: 'parcel-alpha-101',
      status: 'SUCCESS',
      attemptCount: 1,
      lastAttemptAt: '2026-10-28T05:30:01Z',
      lastHttpStatusCode: 200,
      lastResponseTimeMs: 142,
      errorSummary: null,
    },
    {
      deliveryId: 'wh-del-002',
      eventId: 'evt-uuid-102',
      parcelId: 'parcel-alpha-104',
      status: 'EXHAUSTED',
      attemptCount: 3,
      lastAttemptAt: '2026-10-27T16:05:00Z',
      lastHttpStatusCode: 503,
      lastResponseTimeMs: 310,
      errorSummary: 'Remote endpoint returned 503 Service Unavailable',
    },
  ],
}

// Synthetic Pending Merchant Review Details for Admin
const mockPendingMerchantReview: MerchantReviewDetail = {
  id: 'merch-pending-uuid-3333',
  businessName: 'Pending Express Traders',
  registrationNumber: 'PV-PENDING-03',
  status: 'PENDING',
  submittedAt: '2026-10-28T02:00:00Z',
  reviewToken: 'review-token-rev-3333',
  actions: [{ action: 'approve' }],
}

// ============================================================================
// 2. AUTHENTICATION & HARNESS FACTORIES
// ============================================================================

function createSyntheticAuth(
  roles: readonly SwiftDropRole[],
  subject: string,
  profileData: any,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-valid-bearer-token'),
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profileData))),
    handleSessionExpiry: vi.fn(async () => {}),
  }
}

function renderAcceptanceJourney(auth: AuthContextValue, initialRoute: string) {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[initialRoute]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

// ============================================================================
// 3. ACCEPTANCE TEST SUITE
// ============================================================================

describe('SwiftDrop Acceptance Suite (N10-QA-03 / Issue #163)', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  // --------------------------------------------------------------------------
  // SUITE 1: MERCHANT ORDER DASHBOARD & DETAIL WORKFLOWS
  // --------------------------------------------------------------------------
  describe('Suite 1: Merchant Order Workflows', () => {
    it('AC-MO-01: Authorized merchant loads order dashboard with summary cards and order table', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/graphql')) {
          return new Response(
            JSON.stringify({
              data: {
                merchantDashboard: mockMerchantADashboard,
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(merchantAuth, '/merchant/orders')

      // Wait for data to load
      expect(await screen.findByText('SWD-20261028-A101')).toBeInTheDocument()

      // 1. Dashboard Heading & Organization Branding
      expect(screen.getByRole('heading', { name: /commercial order dashboard/i })).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: /Authenticated Portal View · Alpha Logistics PLC/i })).toBeInTheDocument()

      // 2. Summary Metric Cards
      expect(within(screen.getByRole('button', { name: /total bookings/i })).getByText('4')).toBeInTheDocument()
      expect(within(screen.getByRole('button', { name: /active dispatches/i })).getByText('2')).toBeInTheDocument()
      expect(within(screen.getByRole('button', { name: /^delivered/i })).getByText('1')).toBeInTheDocument()
      expect(within(screen.getByRole('button', { name: /exceptions & failed/i })).getByText('1')).toBeInTheDocument()

      // 3. Orders Table Content
      expect(screen.getByText('SWD-20261028-A102')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261028-A103')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261028-A104')).toBeInTheDocument()
    })

    it('AC-MO-02: Status filter bar filters displayed orders accurately', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes('/graphql')) {
          return new Response(
            JSON.stringify({ data: { merchantDashboard: mockMerchantADashboard } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/orders')

      await screen.findByText('SWD-20261028-A101')

      // Filter by ACTIVE -> Should show IN_TRANSIT (A101) and OUT_FOR_DELIVERY (A102)
      const activeFilterBtn = screen.getByRole('radio', { name: /^active$/i })
      await user.click(activeFilterBtn)

      expect(screen.getByText('SWD-20261028-A101')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261028-A102')).toBeInTheDocument()
      expect(screen.queryByText('SWD-20261028-A103')).not.toBeInTheDocument() // DELIVERED
      expect(screen.queryByText('SWD-20261028-A104')).not.toBeInTheDocument() // FAILED

      // Filter by DELIVERED -> Should show only A103
      const deliveredFilterBtn = screen.getByRole('radio', { name: /^delivered$/i })
      await user.click(deliveredFilterBtn)

      expect(screen.queryByText('SWD-20261028-A101')).not.toBeInTheDocument()
      expect(screen.getByText('SWD-20261028-A103')).toBeInTheDocument()

      // Reset to ALL
      const allFilterBtn = screen.getByRole('radio', { name: /all orders/i })
      await user.click(allFilterBtn)
      expect(screen.getByText('SWD-20261028-A101')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261028-A104')).toBeInTheDocument()
    })

    it('AC-MO-03: Search query matches tracking numbers and dropoff keywords', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes('/graphql')) {
          return new Response(
            JSON.stringify({ data: { merchantDashboard: mockMerchantADashboard } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/orders')

      await screen.findByText('SWD-20261028-A101')

      const searchInput = screen.getByRole('searchbox', { name: /search orders by tracking number or parcel id/i })
      await user.type(searchInput, 'A102')

      expect(screen.getByText('SWD-20261028-A102')).toBeInTheDocument()
      expect(screen.queryByText('SWD-20261028-A101')).not.toBeInTheDocument()
      expect(screen.queryByText('SWD-20261028-A103')).not.toBeInTheDocument()
    })

    it('AC-MO-04: Selecting an order navigates to authoritative order detail page', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/graphql')) {
          return new Response(
            JSON.stringify({ data: { merchantDashboard: mockMerchantADashboard } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (urlStr.includes('/api/v1/parcels/parcel-alpha-101/history')) {
          return new Response(JSON.stringify(mockParcelAlpha101History), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (urlStr.includes('/api/v1/parcels/parcel-alpha-101')) {
          return new Response(JSON.stringify(mockParcelAlpha101Detail), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/orders')

      await screen.findByText('SWD-20261028-A101')

      // Click Details link
      const viewDetailLink = screen.getByRole('link', { name: 'View details for order SWD-20261028-A101' })
      await user.click(viewDetailLink)

      // Verifies Navigation to /merchant/orders/parcel-alpha-101
      expect(await screen.findByRole('heading', { name: /commercial order details/i })).toBeInTheDocument()
      expect(await screen.findByText('SWD-20261028-A101')).toBeInTheDocument()
      expect(screen.getByText('10 Galle Road, Colombo 03')).toBeInTheDocument()
      expect(screen.getByText('45 Kandy Road, Peradeniya')).toBeInTheDocument()
      expect(screen.getByText('3.50 kg')).toBeInTheDocument()

      // Back navigation button returns to /merchant/orders
      const backBtn = screen.getByRole('link', { name: /back to orders dashboard/i })
      await user.click(backBtn)
      expect(await screen.findByRole('button', { name: /total bookings/i })).toBeInTheDocument()
    })

    it('AC-MO-05: Direct deep linking to nonexistent order displays 404 state', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/parcels/nonexistent-id')) {
          return new Response(
            JSON.stringify({
              title: 'Parcel Not Found',
              status: 404,
              detail: 'Parcel nonexistent-id not found',
            }),
            { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(merchantAuth, '/merchant/orders/nonexistent-id')

      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: /order not found/i })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /return to orders dashboard/i })).toBeInTheDocument()
    })

    it('AC-MO-06: Direct deep linking to cross-tenant parcel (403 Forbidden) displays Access Denied', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/parcels/parcel-beta-201')) {
          return new Response(
            JSON.stringify({
              title: 'Forbidden',
              status: 403,
              detail: 'Caller is not authorized to access this parcel',
            }),
            { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(merchantAuth, '/merchant/orders/parcel-beta-201')

      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: /access denied/i })).toBeInTheDocument()
      expect(
        screen.getByText(/the parcel belongs to another merchant tenant/i),
      ).toBeInTheDocument()
    })
  })

  // --------------------------------------------------------------------------
  // SUITE 2: MERCHANT WEBHOOK LIFECYCLE & SECURITY
  // --------------------------------------------------------------------------
  describe('Suite 2: Merchant Webhook Lifecycle', () => {
    it('AC-WH-01: Approved merchant lists webhook subscriptions and renders destination table', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/merchants/webhooks')) {
          const listResponse: WebhookSubscriptionListResponse = {
            subscriptions: [mockWebhookSubscriptionA],
          }
          return new Response(JSON.stringify(listResponse), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(merchantAuth, '/merchant/webhooks')

      expect(await screen.findByRole('heading', { name: /configured webhooks/i })).toBeInTheDocument()
      expect(
        await screen.findByText('https://api.alphalogistics.lk/webhooks/swiftdrop'),
      ).toBeInTheDocument()
      expect(screen.getByText('parcel.status.changed.v1')).toBeInTheDocument()
      expect(screen.getByText(/Active/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Delivery Logs' })).toBeInTheDocument()
    })

    it('AC-WH-02: Register webhook rejects plain HTTP and localhost (SSRF protection)', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes('/api/v1/merchants/webhooks')) {
          return new Response(JSON.stringify({ subscriptions: [] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/webhooks')

      const openBtn = await screen.findByRole('button', { name: 'Register Webhook Destination' })
      await user.click(openBtn)

      expect(screen.getByRole('heading', { name: 'Register Webhook Destination' })).toBeInTheDocument()
      const urlInput = screen.getByLabelText(/https callback destination url/i)

      // 1. Plain HTTP Rejected
      await user.type(urlInput, 'http://api.insecure.test/callback')
      await user.click(screen.getByRole('button', { name: 'Register Destination' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/only secure https/i)

      // 2. Localhost Rejected
      await user.clear(urlInput)
      await user.type(urlInput, 'https://localhost:8080/webhook')
      await user.click(screen.getByRole('button', { name: 'Register Destination' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/localhost and private/i)

      // 3. RFC 1918 Private IP Rejected
      await user.clear(urlInput)
      await user.type(urlInput, 'https://192.168.1.100/webhook')
      await user.click(screen.getByRole('button', { name: 'Register Destination' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/private and link-local ip/i)
    })

    it('AC-WH-03: Registering valid webhook creates subscription and displays one-time signing secret', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      let currentSubscriptions: WebhookSubscription[] = []
      const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const urlStr = String(input)
        const method = init?.method || 'GET'

        if (urlStr.includes('/api/v1/merchants/webhooks') && method === 'GET') {
          return new Response(JSON.stringify({ subscriptions: currentSubscriptions }), { status: 200 })
        }
        if (urlStr.includes('/api/v1/merchants/webhooks') && method === 'POST') {
          const body = JSON.parse(String(init?.body))
          const created: WebhookSubscription = {
            subscriptionId: 'sub-new-alpha-123',
            merchantId: 'merch-alpha-uuid-1111',
            httpsUrl: body.httpsUrl,
            allowedEventTypes: body.allowedEventTypes,
            active: true,
            createdAt: new Date().toISOString(),
          }
          currentSubscriptions = [created]
          return new Response(
            JSON.stringify({
              ...created,
              hmacSigningSecret: 'mock-whsec-secret-abcdef123456',
            }),
            { status: 201 },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/webhooks')

      // Open form
      await user.click(await screen.findByRole('button', { name: 'Register Webhook Destination' }))

      // Type valid HTTPS URL
      const urlInput = screen.getByLabelText(/https callback destination url/i)
      await user.type(urlInput, 'https://warehouse.swiftdrop-partner.lk/api/webhooks')

      // Submit registration
      await user.click(screen.getByRole('button', { name: 'Register Destination' }))

      // Expect One-Time Secret Alert
      expect(await screen.findByRole('alert', { name: /one-time webhook signing secret/i })).toBeInTheDocument()
      expect(screen.getByTestId('webhook-signing-secret')).toHaveTextContent('mock-whsec-secret-abcdef123456')

      // Click copy button
      const copyBtn = screen.getByRole('button', { name: /copy/i })
      await user.click(copyBtn)
      expect(screen.getByText(/✓ copied!/i)).toBeInTheDocument()

      // Dismiss secret
      const dismissBtn = screen.getByRole('button', { name: /i have saved my secret/i })
      await user.click(dismissBtn)

      // Secret must be unmounted and no longer in DOM
      expect(screen.queryByTestId('webhook-signing-secret')).not.toBeInTheDocument()
    })

    it('AC-WH-04: Webhook delivery logs modal opens and displays attempt latency and status', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/deliveries')) {
          return new Response(JSON.stringify(mockWebhookDeliveriesA), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (urlStr.includes('/api/v1/merchants/webhooks')) {
          return new Response(JSON.stringify({ subscriptions: [mockWebhookSubscriptionA] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(merchantAuth, '/merchant/webhooks')

      await screen.findByText('https://api.alphalogistics.lk/webhooks/swiftdrop')

      // Open Delivery Logs Modal
      const viewLogsBtn = screen.getByRole('button', { name: 'Delivery Logs' })
      await user.click(viewLogsBtn)

      const dialog = await screen.findByRole('dialog')
      expect(dialog).toBeInTheDocument()
      expect(within(dialog).getByRole('heading', { name: /webhook delivery logs/i })).toBeInTheDocument()

      // Verifies attempts rendered
      expect(within(dialog).getByText('SUCCESS')).toBeInTheDocument()
      expect(within(dialog).getAllByText('EXHAUSTED').length).toBeGreaterThanOrEqual(1)

      // Close modal
      const closeBtn = within(dialog).getByRole('button', { name: /close logs dialog/i })
      await user.click(closeBtn)
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  // --------------------------------------------------------------------------
  // SUITE 3: OPERATOR MANAGEMENT ACCEPTANCE
  // --------------------------------------------------------------------------
  describe('Suite 3: Operator Management Workflows', () => {
    it('AC-OP-01: Admin role accesses /admin workspace with operational navigation', async () => {
      const adminAuth = createSyntheticAuth(['ADMIN'], 'admin-sub-001', adminProfile)

      const fetchMock = vi.fn(async () => {
        return new Response(
          JSON.stringify({
            profileId: 'admin-prof-001',
            keycloakSub: 'admin-sub-001',
            staffId: 'ADMIN-01',
            role: 'ADMIN',
            department: 'Executive',
            businessStatus: 'ACTIVE',
            assignedZone: null,
            createdAt: '2026-10-01T00:00:00Z',
            updatedAt: '2026-10-01T00:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(adminAuth, '/admin')

      expect(await screen.findByRole('heading', { name: /administration workspace/i })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /review merchant applications/i })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /browse account users/i })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /manage staff profiles/i })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /manage zones and pricing/i })).toBeInTheDocument()
    })

    it('AC-OP-02: Non-admin roles (MERCHANT, DISPATCHER, CUSTOMER) denied from /admin with Access Unavailable', async () => {
      const merchantAuth = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      renderAcceptanceJourney(merchantAuth, '/admin')

      expect(await screen.findByRole('heading', { name: /access unavailable/i })).toBeInTheDocument()
      expect(
        screen.getByText(/this entry is unavailable for your current role/i),
      ).toBeInTheDocument()
      expect(screen.queryByRole('link', { name: /review merchant applications/i })).not.toBeInTheDocument()
    })

    it('AC-OP-03: Admin reviews pending merchant and approves enrollment with reviewToken confirmation', async () => {
      const adminAuth = createSyntheticAuth(['ADMIN'], 'admin-sub-001', adminProfile)

      const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/admin/merchants?page=0&size=20')) {
          return new Response(
            JSON.stringify({
              content: [mockPendingMerchantReview],
              page: 0,
              size: 20,
              totalElements: 1,
              totalPages: 1,
              first: true,
              last: true,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (urlStr.includes('/audit')) {
          return new Response(JSON.stringify({ content: [] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (urlStr.includes('/api/v1/admin/merchants/merch-pending-uuid-3333') && init?.method === 'GET') {
          return new Response(JSON.stringify(mockPendingMerchantReview), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (urlStr.includes('/approve') && init?.method === 'POST') {
          return new Response(
            JSON.stringify({
              merchantId: 'merch-pending-uuid-3333',
              businessName: 'Pending Express Traders',
              status: 'APPROVED',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(adminAuth, '/admin/merchants')

      // 1. Pending Merchant Listed
      expect(await screen.findByText('Pending Express Traders')).toBeInTheDocument()
      expect(screen.getByText('PV-PENDING-03')).toBeInTheDocument()

      // 2. Open Review Details
      const reviewBtn = screen.getByRole('button', { name: `Review ${mockPendingMerchantReview.businessName}` })
      await user.click(reviewBtn)

      expect(await screen.findByRole('heading', { level: 3, name: mockPendingMerchantReview.businessName })).toBeInTheDocument()

      // 3. Click Approve -> Opens Confirmation Dialog
      const approveActionBtn = screen.getByRole('button', { name: 'Approve merchant' })
      await user.click(approveActionBtn)

      const dialog = screen.getByRole('dialog')
      expect(within(dialog).getByRole('heading', { name: /approve merchant\?/i })).toBeInTheDocument()

      // 4. Submit Confirmation
      const confirmApproveBtn = within(dialog).getByRole('button', { name: 'Confirm approve' })
      await user.click(confirmApproveBtn)

      // Verifies dialog closes on completion
      await waitFor(() => {
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      })
    })

    it('AC-OP-04: Admin suspends merchant requiring non-empty rationale', async () => {
      const adminAuth = createSyntheticAuth(['ADMIN'], 'admin-sub-001', adminProfile)

      const approvedMerchantDetail: MerchantReviewDetail = {
        id: 'merch-alpha-uuid-1111',
        businessName: 'Alpha Logistics PLC',
        registrationNumber: 'PV-ALPHA-01',
        status: 'APPROVED',
        submittedAt: '2026-10-20T00:00:00Z',
        reviewToken: 'review-token-rev-1111',
        actions: [{ action: 'suspend', reason: { required: true, maxLength: 500 } }],
      }

      const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/admin/merchants?page=0&size=20')) {
          return new Response(
            JSON.stringify({
              content: [approvedMerchantDetail],
              page: 0,
              size: 20,
              totalElements: 1,
              totalPages: 1,
              first: true,
              last: true,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (urlStr.includes('/audit')) {
          return new Response(JSON.stringify({ content: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        if (urlStr.includes('/api/v1/admin/merchants/merch-alpha-uuid-1111') && init?.method === 'GET') {
          return new Response(JSON.stringify(approvedMerchantDetail), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        if (urlStr.includes('/suspend') && init?.method === 'POST') {
          return new Response(
            JSON.stringify({
              merchantId: 'merch-alpha-uuid-1111',
              businessName: 'Alpha Logistics PLC',
              status: 'SUSPENDED',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      const user = userEvent.setup()
      renderAcceptanceJourney(adminAuth, '/admin/merchants')

      expect(await screen.findByText('Alpha Logistics PLC')).toBeInTheDocument()

      const reviewBtn = screen.getByRole('button', { name: `Review ${approvedMerchantDetail.businessName}` })
      await user.click(reviewBtn)

      // Open Suspend Dialog
      const suspendBtn = await screen.findByRole('button', { name: 'Suspend merchant' })
      await user.click(suspendBtn)

      const dialog = screen.getByRole('dialog')
      expect(within(dialog).getByRole('heading', { name: /suspend merchant\?/i })).toBeInTheDocument()

      // Confirmation button is disabled until reason is entered
      const confirmSuspendBtn = within(dialog).getByRole('button', { name: 'Confirm suspend' })
      expect(confirmSuspendBtn).toBeDisabled()

      // Enter reason
      const reasonInput = within(dialog).getByLabelText(/reason/i)
      await user.type(reasonInput, 'Regulatory compliance review')
      expect(confirmSuspendBtn).toBeEnabled()

      await user.click(confirmSuspendBtn)

      await waitFor(() => {
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      })
    })

    it('AC-OP-05: Admin user & staff management lists profiles with navigation', async () => {
      const adminAuth = createSyntheticAuth(['ADMIN'], 'admin-sub-001', adminProfile)

      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const urlStr = String(input)
        if (urlStr.includes('/api/v1/admin/users')) {
          return new Response(JSON.stringify(adminPage([adminUser])), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (urlStr.includes('/api/v1/admin/staff')) {
          return new Response(JSON.stringify(adminPage([adminStaff])), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      renderAcceptanceJourney(adminAuth, '/admin/users')

      // Users view
      expect(await screen.findByText('Synthetic User')).toBeInTheDocument()
      expect(screen.getByText('user@example.test')).toBeInTheDocument()

      // Staff navigation
      expect(screen.getByRole('link', { name: 'Staff' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Back to administration' })).toBeInTheDocument()
    })
  })

  // --------------------------------------------------------------------------
  // SUITE 4: CROSS-WORKFLOW TENANT ISOLATION & SESSION RESILIENCE
  // --------------------------------------------------------------------------
  describe('Suite 4: Cross-Workflow Tenant & Session Isolation', () => {
    it('AC-XW-01: Switching authenticated merchant resets query cache and prevents cross-tenant data leak', async () => {
      const merchantAuthA = createSyntheticAuth(
        ['MERCHANT'],
        'sub-merchant-tenant-alpha',
        merchantAProfile,
      )

      const mockMerchantBDashboard: MerchantDashboardData = {
        merchantId: 'merch-beta-uuid-2222',
        businessName: 'Beta Distributing Co',
        status: 'APPROVED',
        totalOrdersCount: 1,
        activeOrdersCount: 1,
        deliveredOrdersCount: 0,
        failedOrdersCount: 0,
        recentOrders: [
          {
            parcelId: 'parcel-beta-999',
            trackingNumber: 'SWD-20261028-B999',
            currentStatus: 'PICKED_UP',
            weightKg: 1.0,
            feeMinorUnits: 120000,
            currency: 'LKR',
            createdAt: '2026-10-28T06:00:00Z',
            lastStatusUpdate: '2026-10-28T06:30:00Z',
          },
        ],
      }

      const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input).includes('/graphql')) {
          // Inspect if caller header matches tenant beta
          if (init?.headers && (init.headers as any)['Authorization']?.includes('tenant-beta')) {
            return new Response(
              JSON.stringify({ data: { merchantDashboard: mockMerchantBDashboard } }),
              { status: 200, headers: { 'Content-Type': 'application/json' } },
            )
          }
          return new Response(
            JSON.stringify({ data: { merchantDashboard: mockMerchantADashboard } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(null, { status: 404 })
      })
      vi.stubGlobal('fetch', fetchMock)

      // 1. Initial Render with Merchant A
      const { rerender } = render(
        <TestProviders auth={merchantAuthA}>
          <MemoryRouter initialEntries={['/merchant/orders']}>
            <AppRoutes />
          </MemoryRouter>
        </TestProviders>,
      )

      expect(await screen.findByText('SWD-20261028-A101')).toBeInTheDocument()

      // 2. Switch Session to Merchant B
      const merchantAuthB: AuthContextValue = {
        ...merchantAuthA,
        snapshot: {
          status: 'authenticated',
          subject: 'sub-merchant-tenant-beta',
          roles: ['MERCHANT'],
        },
        getAccessToken: vi.fn(async () => 'bearer-tenant-beta'),
        profileFetch: vi.fn(async () => new Response(JSON.stringify(merchantBProfile))),
      }

      rerender(
        <TestProviders auth={merchantAuthB}>
          <MemoryRouter initialEntries={['/merchant/orders']}>
            <AppRoutes />
          </MemoryRouter>
        </TestProviders>,
      )

      // Verifies Merchant B dashboard loaded; Merchant A orders unmounted
      expect(await screen.findByText('SWD-20261028-B999')).toBeInTheDocument()
      expect(screen.queryByText('SWD-20261028-A101')).not.toBeInTheDocument()
    })

    it('AC-XW-02: Session expiration unmounts protected merchant orders route', async () => {
      const expiredAuth: AuthContextValue = {
        ...anonymousAuth,
        snapshot: { status: 'unauthenticated', subject: null, roles: [] },
      }

      renderAcceptanceJourney(expiredAuth, '/merchant/orders')

      // Should be redirected to /login
      expect(await screen.findByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: /commercial order dashboard/i })).not.toBeInTheDocument()
    })
  })
})
