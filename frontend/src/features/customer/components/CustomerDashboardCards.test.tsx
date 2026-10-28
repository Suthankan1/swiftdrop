import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CustomerDashboardError, type CustomerDashboardData } from '../api/customerDashboardContracts'
import { CustomerDashboardCards } from './CustomerDashboardCards'
import type { createCustomerDashboardApi } from '../api/customerDashboard'

function customerAuth(subject = 'customer-test-sub', roles: SwiftDropRole[] = ['CUSTOMER']): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'test-customer-token',
    handleSessionExpiry: vi.fn(),
  }
}

const mockDashboardData: CustomerDashboardData = {
  userId: 'customer-test-sub',
  activeDeliveriesCount: 3,
  completedDeliveriesCount: 8,
  recentParcels: [
    {
      parcelId: '550e8400-e29b-41d4-a716-446655440001',
      trackingNumber: 'SD-TRK-1001',
      currentStatus: 'IN_TRANSIT',
      amountMinorUnits: 150000,
      currency: 'LKR',
      recipientCity: 'Colombo',
      createdAt: '2026-10-28T04:00:00Z',
      updatedAt: '2026-10-28T05:30:00Z',
    },
    {
      parcelId: '550e8400-e29b-41d4-a716-446655440002',
      trackingNumber: 'SD-TRK-1002',
      currentStatus: 'DELIVERED',
      amountMinorUnits: 250000,
      currency: 'LKR',
      recipientCity: 'Kandy',
      createdAt: '2026-10-27T08:00:00Z',
      updatedAt: '2026-10-27T14:30:00Z',
    },
  ],
}

function createMockApi(overrides: Partial<ReturnType<typeof createCustomerDashboardApi>> = {}) {
  return {
    getDashboard: vi.fn(async () => mockDashboardData),
    ...overrides,
  } as unknown as ReturnType<typeof createCustomerDashboardApi>
}

describe('CustomerDashboardCards component', () => {
  it('renders loading state initially then displays authoritative delivery metrics and recent parcels', async () => {
    const api = createMockApi()
    render(
      <MemoryRouter>
        <TestProviders auth={customerAuth()}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: /your deliveries summary/i })).toBeInTheDocument()

    // Metric values
    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(screen.getByText('11')).toBeInTheDocument() // 3 + 8 = 11

    // Metric labels
    expect(screen.getByText('Active Deliveries')).toBeInTheDocument()
    expect(screen.getByText('Completed Deliveries')).toBeInTheDocument()
    expect(screen.getByText('Total Shipments')).toBeInTheDocument()

    // Recent parcels preview table
    expect(screen.getByText('SD-TRK-1001')).toBeInTheDocument()
    expect(screen.getByText('In Transit')).toBeInTheDocument()
    expect(screen.getByText('Colombo')).toBeInTheDocument()
    expect(screen.getByText('LKR 1,500.00')).toBeInTheDocument()

    expect(screen.getByText('SD-TRK-1002')).toBeInTheDocument()
    expect(screen.getByText('Delivered')).toBeInTheDocument()
    expect(screen.getByText('Kandy')).toBeInTheDocument()
    expect(screen.getByText('LKR 2,500.00')).toBeInTheDocument()
  })

  it('renders empty state when customer has zero recent deliveries', async () => {
    const emptyData: CustomerDashboardData = {
      userId: 'customer-test-sub',
      activeDeliveriesCount: 0,
      completedDeliveriesCount: 0,
      recentParcels: [],
    }
    const api = createMockApi({
      getDashboard: vi.fn(async () => emptyData),
    })

    render(
      <MemoryRouter>
        <TestProviders auth={customerAuth()}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(await screen.findByText(/you don't have any recent delivery bookings/i)).toBeInTheDocument()
    const quoteButton = screen.getAllByRole('link', { name: /request a quote & book/i })
    expect(quoteButton.length).toBeGreaterThan(0)
  })

  it('displays provider error alert with functional retry', async () => {
    const user = userEvent.setup()
    let calls = 0
    const api = createMockApi({
      getDashboard: vi.fn(async () => {
        calls++
        if (calls === 1) {
          throw new CustomerDashboardError('unavailable', 'GraphQL summary provider unavailable.')
        }
        return mockDashboardData
      }),
    })

    render(
      <MemoryRouter>
        <TestProviders auth={customerAuth()}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(await screen.findByText(/graphql summary provider unavailable/i)).toBeInTheDocument()

    const retryBtn = screen.getByRole('button', { name: /retry summary/i })
    await user.click(retryBtn)

    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(screen.queryByText(/graphql summary provider unavailable/i)).not.toBeInTheDocument()
  })

  it('denies access if caller lacks CUSTOMER role', () => {
    const api = createMockApi()
    const merchantAuth = customerAuth('merchant-sub', ['MERCHANT'])

    render(
      <MemoryRouter>
        <TestProviders auth={merchantAuth}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(screen.getByText(/customer dashboard is only accessible by customers/i)).toBeInTheDocument()
    expect(api.getDashboard).not.toHaveBeenCalled()
  })

  it('delegates to handleSessionExpiry on auth-required error', async () => {
    const auth = customerAuth()
    const api = createMockApi({
      getDashboard: vi.fn(async () => {
        throw new CustomerDashboardError('auth-required')
      }),
    })

    render(
      <MemoryRouter>
        <TestProviders auth={auth}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(auth.handleSessionExpiry).toHaveBeenCalledTimes(1)
    })
  })

  it('isolates cache per authenticated subject when identity changes', async () => {
    const api = createMockApi()
    const { rerender } = render(
      <MemoryRouter>
        <TestProviders auth={customerAuth('user-alpha')}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(api.getDashboard).toHaveBeenCalledTimes(1)

    rerender(
      <MemoryRouter>
        <TestProviders auth={customerAuth('user-beta')}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(api.getDashboard).toHaveBeenCalledTimes(2)
    })
  })

  it('refreshes summary when clicking refresh button', async () => {
    const user = userEvent.setup()
    const api = createMockApi()

    render(
      <MemoryRouter>
        <TestProviders auth={customerAuth()}>
          <CustomerDashboardCards apiOverride={api} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(api.getDashboard).toHaveBeenCalledTimes(1)

    const refreshBtn = screen.getByRole('button', { name: /refresh (deliveries )?summary/i })
    await user.click(refreshBtn)

    await waitFor(() => {
      expect(api.getDashboard).toHaveBeenCalledTimes(2)
    })
  })
})
