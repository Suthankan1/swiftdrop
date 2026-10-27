import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { merchantFixture } from '../../../test/fixtures'
import type { MerchantDashboardData } from '../api/merchantDashboardContracts'

// Merchant A (Primary)
const merchantA = {
  ...merchantFixture,
  keycloakSub: 'sub-merchant-alpha',
  merchantProfile: {
    ...merchantFixture.merchantProfile!,
    merchantId: 'm-alpha-1111-2222',
    businessName: 'Alpha Wholesale Ltd',
    status: 'APPROVED',
  },
}

// Merchant B (Second Tenant for Isolation)
const merchantB = {
  ...merchantFixture,
  keycloakSub: 'sub-merchant-beta',
  merchantProfile: {
    ...merchantFixture.merchantProfile!,
    merchantId: 'm-beta-3333-4444',
    businessName: 'Beta Distributing Ltd',
    status: 'APPROVED',
  },
}

const mockMerchantADashboardData: MerchantDashboardData = {
  merchantId: 'm-alpha-1111-2222',
  businessName: 'Alpha Wholesale Ltd',
  status: 'APPROVED',
  totalOrdersCount: 4,
  activeOrdersCount: 2,
  deliveredOrdersCount: 1,
  failedOrdersCount: 1,
  recentOrders: [
    {
      parcelId: 'parcel-alpha-1',
      trackingNumber: 'SWD-20261027-ALPH1',
      currentStatus: 'IN_TRANSIT',
      weightKg: 2.5,
      feeMinorUnits: 150000,
      currency: 'LKR',
      createdAt: '2026-10-27T08:00:00Z',
      lastStatusUpdate: '2026-10-27T09:30:00Z',
    },
    {
      parcelId: 'parcel-alpha-2',
      trackingNumber: 'SWD-20261027-ALPH2',
      currentStatus: 'OUT_FOR_DELIVERY',
      weightKg: 4.0,
      feeMinorUnits: 220000,
      currency: 'LKR',
      createdAt: '2026-10-27T08:30:00Z',
      lastStatusUpdate: '2026-10-27T10:00:00Z',
    },
    {
      parcelId: 'parcel-alpha-3',
      trackingNumber: 'SWD-20261027-ALPH3',
      currentStatus: 'DELIVERED',
      weightKg: 1.0,
      feeMinorUnits: 80000,
      currency: 'LKR',
      createdAt: '2026-10-26T12:00:00Z',
      lastStatusUpdate: '2026-10-26T16:00:00Z',
    },
    {
      parcelId: 'parcel-alpha-4',
      trackingNumber: 'SWD-20261027-ALPH4',
      currentStatus: 'FAILED',
      weightKg: 3.2,
      feeMinorUnits: 190000,
      currency: 'LKR',
      createdAt: '2026-10-25T14:00:00Z',
      lastStatusUpdate: '2026-10-25T18:00:00Z',
    },
  ],
}

const mockMerchantBDashboardData: MerchantDashboardData = {
  merchantId: 'm-beta-3333-4444',
  businessName: 'Beta Distributing Ltd',
  status: 'APPROVED',
  totalOrdersCount: 1,
  activeOrdersCount: 1,
  deliveredOrdersCount: 0,
  failedOrdersCount: 0,
  recentOrders: [
    {
      parcelId: 'parcel-beta-1',
      trackingNumber: 'SWD-20261027-BETA1',
      currentStatus: 'CREATED',
      weightKg: 5.5,
      feeMinorUnits: 310000,
      currency: 'LKR',
      createdAt: '2026-10-27T11:00:00Z',
      lastStatusUpdate: '2026-10-27T11:00:00Z',
    },
  ],
}

function authenticatedMerchant(
  profile = merchantA,
  roles: readonly SwiftDropRole[] = ['MERCHANT'],
  token = 'valid-merchant-token',
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: {
      status: 'authenticated',
      subject: profile.keycloakSub,
      roles,
    },
    getAccessToken: async () => token,
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profile))),
  }
}

function renderOrdersDashboard(auth: AuthContextValue, route = '/merchant/orders') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

describe('MerchantOrdersDashboardPage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('redirects unauthenticated visitor to the login page', () => {
    renderOrdersDashboard(anonymousAuth)
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByText(/commercial order dashboard/i)).not.toBeInTheDocument()
  })

  it('denies access to a user without the MERCHANT role', () => {
    const customerAuth = authenticatedMerchant(merchantA, ['CUSTOMER'])
    renderOrdersDashboard(customerAuth)
    expect(screen.getByText('Access unavailable')).toBeInTheDocument()
    expect(screen.getByText(/this entry is unavailable for your current role/i)).toBeInTheDocument()
    expect(screen.queryByText(/commercial order dashboard/i)).not.toBeInTheDocument()
  })

  it('renders authorized merchant dashboard with real orders and authoritative state counts', async () => {
    const fetchMock = vi.fn(async (url: RequestInfo | URL) => {
      const urlStr = url.toString()
      if (urlStr.includes('/graphql')) {
        return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    // Page Heading & Navigation
    expect(screen.getByRole('heading', { name: /commercial order dashboard/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /orders dashboard/i })).toBeInTheDocument()

    // Orders Table Rows (wait for async query resolution)
    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH2')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH3')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH4')).toBeInTheDocument()

    // Scoped Machine API vs Browser Session Banner (AC1)
    expect(screen.getByText(/browser session jwt/i)).toBeInTheDocument()
    expect(screen.getByText(/scoped machine api \(api keys\)/i)).toBeInTheDocument()
    expect(screen.getByText(/tenant id: m-alpha-1111-2222/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /manage api keys ↗/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /webhook endpoints ↗/i })).toBeInTheDocument()

    // Authoritative State Counts (AC1)
    expect(within(screen.getByRole('button', { name: /total bookings/i })).getByText('4')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /active dispatches/i })).getByText('2')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /^delivered/i })).getByText('1')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /exceptions & failed/i })).getByText('1')).toBeInTheDocument()

    // Canonical Status Labels (rendered via role="status" pills with descriptive titles)
    expect(screen.getByRole('status', { name: /package in transit/i })).toHaveTextContent('In Transit')
    expect(screen.getByRole('status', { name: /out for delivery/i })).toHaveTextContent('Out for Delivery')
    expect(screen.getByRole('status', { name: /successfully delivered/i })).toHaveTextContent('Delivered')
    expect(screen.getByRole('status', { name: /could not be completed/i })).toHaveTextContent('Failed')
  })

  it('filters table rows when selecting quick state filter buttons', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()

    // Click Active filter
    const activeBtn = screen.getByRole('radio', { name: /^active$/i })
    await user.click(activeBtn)

    // Active dispatches (ALPH1 and ALPH2) should be visible; Delivered and Failed should be filtered out
    expect(screen.getByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH2')).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-ALPH3')).not.toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-ALPH4')).not.toBeInTheDocument()

    // Click Delivered filter
    const deliveredBtn = screen.getByRole('radio', { name: /^delivered$/i })
    await user.click(deliveredBtn)

    expect(screen.queryByText('SWD-20261027-ALPH1')).not.toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH3')).toBeInTheDocument()

    // Click All Orders to restore
    const allBtn = screen.getByRole('radio', { name: /all orders/i })
    await user.click(allBtn)

    expect(screen.getByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH3')).toBeInTheDocument()
  })

  it('filters orders by tracking number or parcel ID search query', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()

    const searchInput = screen.getByRole('searchbox', { name: /search orders/i })
    await user.type(searchInput, 'ALPH2')

    expect(screen.queryByText('SWD-20261027-ALPH1')).not.toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH2')).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-ALPH3')).not.toBeInTheDocument()

    // Clear search
    const clearBtn = screen.getByRole('button', { name: /clear search query/i })
    await user.click(clearBtn)

    expect(screen.getByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261027-ALPH2')).toBeInTheDocument()
  })

  it('renders empty state when search produces no matching orders with working reset button', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()

    const searchInput = screen.getByRole('searchbox', { name: /search orders/i })
    await user.type(searchInput, 'NONEXISTENT')

    expect(await screen.findByText(/no orders found/i)).toBeInTheDocument()
    expect(screen.getByText(/no orders matched your current status filter/i)).toBeInTheDocument()

    const resetBtn = screen.getByRole('button', { name: /reset filters/i })
    await user.click(resetBtn)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()
  })

  it('handles forbidden / unapproved merchant error with alert banner', async () => {
    const fetchMock = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          data: null,
          errors: [
            {
              message: 'Merchant is not approved',
              extensions: { code: 'PERMISSION_DENIED' },
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/access restricted\. your merchant account is pending review/i)
  })

  it('handles provider failure with working retry button', async () => {
    const user = userEvent.setup()
    let attempt = 0
    const fetchMock = vi.fn(async () => {
      attempt++
      if (attempt === 1) {
        return new Response(JSON.stringify({ error: 'Service Unavailable' }), { status: 503 })
      }
      return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrdersDashboard(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/unable to load merchant dashboard/i)

    const retryBtn = screen.getByRole('button', { name: /retry dashboard/i })
    await user.click(retryBtn)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('proves tenant isolation across two merchants with immediate cache clearing', async () => {
    const fetchMock = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => {
      const headers = options?.headers as Record<string, string>
      const authHeader = headers?.Authorization || headers?.authorization || ''

      if (authHeader.includes('token-merchant-alpha')) {
        return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantADashboardData } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (authHeader.includes('token-merchant-beta')) {
        return new Response(JSON.stringify({ data: { merchantDashboard: mockMerchantBDashboardData } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 403 })
    })
    vi.stubGlobal('fetch', fetchMock)

    // 1. Render Merchant A
    const authA = authenticatedMerchant(merchantA, ['MERCHANT'], 'token-merchant-alpha')
    const { rerender } = renderOrdersDashboard(authA)

    expect(await screen.findByText('SWD-20261027-ALPH1')).toBeInTheDocument()
    expect(screen.getByText(/Alpha Wholesale Ltd/i)).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-BETA1')).not.toBeInTheDocument()

    // 2. Switch identity to Merchant B (proving complete tenant isolation)
    const authB = authenticatedMerchant(merchantB, ['MERCHANT'], 'token-merchant-beta')
    rerender(
      <TestProviders auth={authB}>
        <MemoryRouter initialEntries={['/merchant/orders']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    // Merchant A's data is cleared, and only Merchant B's order is visible
    expect(await screen.findByText('SWD-20261027-BETA1')).toBeInTheDocument()
    expect(screen.getByText(/Beta Distributing Ltd/i)).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-ALPH1')).not.toBeInTheDocument()
    expect(screen.queryByText(/Alpha Wholesale Ltd/i)).not.toBeInTheDocument()
  })
})
