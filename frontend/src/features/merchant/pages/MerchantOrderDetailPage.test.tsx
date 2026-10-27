import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { merchantFixture } from '../../../test/fixtures'
import type { ParcelDetailResponse } from '../../customer/api/deliveryContracts'

// Merchant A (Owner of Parcel A)
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

// Merchant B (Owner of Parcel B)
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

const mockParcelDetailA: ParcelDetailResponse = {
  parcelId: 'parcel-alpha-1111-2222',
  trackingNumber: 'SWD-20261027-ALPHA1',
  createdBySub: 'sub-merchant-alpha',
  merchantId: 'm-alpha-1111-2222',
  currentStatus: 'IN_TRANSIT',
  version: 3,
  weightKg: 2.75,
  dimensions: 'STANDARD',
  amountMinorUnits: 450000,
  currency: 'LKR',
  rateVersion: 'RATE-2026-V1',
  pickupAddress: '42 Commercial St, Colombo 03',
  dropoffAddress: '88 Temple Rd, Kandy',
  activeCourierSub: 'courier-charlie-sub',
  createdAt: '2026-10-27T08:00:00Z',
}

const mockParcelHistoryA = {
  items: [
    {
      historyId: 'h-1',
      parcelId: 'parcel-alpha-1111-2222',
      previousStatus: null,
      newStatus: 'CREATED' as const,
      reason: null,
      note: 'Booked via commercial merchant portal',
      proofId: null,
      resultingVersion: 1,
      occurredAt: '2026-10-27T08:00:00Z',
    },
    {
      historyId: 'h-2',
      parcelId: 'parcel-alpha-1111-2222',
      previousStatus: 'CREATED' as const,
      newStatus: 'PICKED_UP' as const,
      reason: null,
      note: 'Collected from merchant warehouse',
      proofId: null,
      resultingVersion: 2,
      occurredAt: '2026-10-27T09:15:00Z',
    },
    {
      historyId: 'h-3',
      parcelId: 'parcel-alpha-1111-2222',
      previousStatus: 'PICKED_UP' as const,
      newStatus: 'IN_TRANSIT' as const,
      reason: null,
      note: 'Departed central sorting hub',
      proofId: null,
      resultingVersion: 3,
      occurredAt: '2026-10-27T10:00:00Z',
    },
  ],
  totalElements: 3,
  totalPages: 1,
  currentPage: 0,
  pageSize: 20,
  hasNext: false,
  hasPrevious: false,
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

function renderOrderDetail(auth: AuthContextValue, parcelId = 'parcel-alpha-1111-2222') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[`/merchant/orders/${parcelId}`]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

describe('MerchantOrderDetailPage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('redirects unauthenticated visitor to login page', () => {
    renderOrderDetail(anonymousAuth)
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByText(/commercial order details/i)).not.toBeInTheDocument()
  })

  it('renders authorized order details and transition history truthfully', async () => {
    const fetchMock = vi.fn(async (url: RequestInfo | URL) => {
      const urlStr = url.toString()
      if (urlStr.includes('/api/v1/parcels/parcel-alpha-1111-2222/history')) {
        return new Response(JSON.stringify(mockParcelHistoryA), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (urlStr.includes('/api/v1/parcels/parcel-alpha-1111-2222')) {
        return new Response(JSON.stringify(mockParcelDetailA), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrderDetail(auth)

    // Page Heading & Navigation
    expect(screen.getByRole('heading', { name: /commercial order details/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to orders dashboard/i })).toBeInTheDocument()

    // Tracking Number & Badges
    expect(await screen.findByText('SWD-20261027-ALPHA1')).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /package in transit/i })).toHaveTextContent('In Transit')
    expect(screen.getAllByText('v3').length).toBeGreaterThanOrEqual(1)

    // Read-only indicator
    expect(screen.getByText(/read-only merchant view/i)).toBeInTheDocument()

    // Specifications
    expect(screen.getByText('parcel-alpha-1111-2222')).toBeInTheDocument()
    expect(screen.getByText('2.75 kg')).toBeInTheDocument()
    expect(screen.getByText('STANDARD')).toBeInTheDocument()
    expect(screen.getByText('LKR 4,500.00')).toBeInTheDocument()
    expect(screen.getByText('RATE-2026-V1')).toBeInTheDocument()

    // Routing & Courier Assignment
    expect(screen.getByText('42 Commercial St, Colombo 03')).toBeInTheDocument()
    expect(screen.getByText('88 Temple Rd, Kandy')).toBeInTheDocument()
    expect(screen.getByText('courier-charlie-sub')).toBeInTheDocument()

    // Transition History
    expect(await screen.findByText(/departed central sorting hub/i)).toBeInTheDocument()
    expect(screen.getByText(/collected from merchant warehouse/i)).toBeInTheDocument()
    expect(screen.getByText(/booked via commercial merchant portal/i)).toBeInTheDocument()

    // Read-Only: Verify NO unauthorized mutation controls or status change buttons are rendered
    expect(screen.queryByRole('button', { name: /update status/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /mark delivered/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /assign courier/i })).not.toBeInTheDocument()
  })

  it('displays 404 Not Found error state when parcel does not exist', async () => {
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify({ status: 404, title: 'Parcel Not Found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/problem+json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrderDetail(auth, 'non-existent-parcel-id')

    expect(await screen.findByRole('alert')).toHaveTextContent(/order not found/i)
    expect(screen.getByText(/the requested order id does not exist/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /return to orders dashboard/i })).toBeInTheDocument()
  })

  it('enforces merchant tenant isolation (403 Forbidden) when accessing another merchant parcel', async () => {
    const fetchMock = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => {
      const headers = options?.headers as Record<string, string>
      const authHeader = headers?.Authorization || headers?.authorization || ''

      // If Merchant B tries to read Parcel A (owned by Merchant A), backend rejects with 403
      if (authHeader.includes('token-merchant-beta')) {
        return new Response(
          JSON.stringify({
            status: 403,
            title: 'Access Denied',
            detail: 'Cross-tenant access prohibited.',
          }),
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    // Render with Merchant B's token trying to view Merchant A's parcel
    const authB = authenticatedMerchant(merchantB, ['MERCHANT'], 'token-merchant-beta')
    renderOrderDetail(authB, 'parcel-alpha-1111-2222')

    expect(await screen.findByRole('alert')).toHaveTextContent(/access denied/i)
    expect(screen.getByText(/the parcel belongs to another merchant tenant/i)).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261027-ALPHA1')).not.toBeInTheDocument()
    expect(screen.queryByText('42 Commercial St, Colombo 03')).not.toBeInTheDocument()
  })

  it('handles backend service failure with working retry button', async () => {
    const user = userEvent.setup()
    let attempt = 0
    const fetchMock = vi.fn(async (url: RequestInfo | URL) => {
      const urlStr = url.toString()
      attempt++
      if (attempt === 1) {
        return new Response(JSON.stringify({ error: 'Service Unavailable' }), { status: 503 })
      }
      if (urlStr.includes('/api/v1/parcels/parcel-alpha-1111-2222/history')) {
        return new Response(JSON.stringify(mockParcelHistoryA), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(JSON.stringify(mockParcelDetailA), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrderDetail(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/unable to load order/i)

    const retryBtn = screen.getByRole('button', { name: /retry order/i })
    await user.click(retryBtn)

    expect(await screen.findByText('SWD-20261027-ALPHA1')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('navigates back to merchant orders dashboard when clicking back button', async () => {
    const fetchMock = vi.fn(async () => {
      return new Response(JSON.stringify(mockParcelDetailA), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const auth = authenticatedMerchant(merchantA)
    renderOrderDetail(auth)

    const backBtn = screen.getByRole('link', { name: /back to orders dashboard/i })
    expect(backBtn).toHaveAttribute('href', '/merchant/orders')
  })
})
