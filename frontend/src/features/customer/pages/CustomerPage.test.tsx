import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CustomerPage } from './CustomerPage'
import type { createCustomerDashboardApi } from '../api/customerDashboard'
import type { CustomerDashboardData } from '../api/customerDashboardContracts'

function customerAuth(subject = 'customer-sub-1', roles: SwiftDropRole[] = ['CUSTOMER']): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'test-customer-token',
    profileFetch: vi.fn(async () =>
      new Response(
        JSON.stringify({
          id: 'acc-uuid-1234',
          keycloakSub: subject,
          fullName: 'Sinthuha N',
          email: 'sinthuha@example.com',
          phoneNumber: '+94771234567',
          roles: ['CUSTOMER'],
          createdAt: '2026-10-25T10:00:00Z',
          merchantProfile: null,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    ),
  }
}

const mockDashboardData: CustomerDashboardData = {
  userId: 'customer-sub-1',
  activeDeliveriesCount: 4,
  completedDeliveriesCount: 12,
  recentParcels: [
    {
      parcelId: '550e8400-e29b-41d4-a716-446655440001',
      trackingNumber: 'SD-TRK-7777',
      currentStatus: 'OUT_FOR_DELIVERY',
      amountMinorUnits: 180000,
      currency: 'LKR',
      recipientCity: 'Jaffna',
      createdAt: '2026-10-28T06:00:00Z',
      updatedAt: '2026-10-28T07:15:00Z',
    },
  ],
}

function createMockDashboardApi(overrides: Partial<ReturnType<typeof createCustomerDashboardApi>> = {}) {
  return {
    getDashboard: vi.fn(async () => mockDashboardData),
    ...overrides,
  } as unknown as ReturnType<typeof createCustomerDashboardApi>
}

describe('CustomerPage component', () => {
  it('renders customer space header, navigation, dashboard cards and profile', async () => {
    const dashboardApi = createMockDashboardApi()

    render(
      <MemoryRouter>
        <TestProviders auth={customerAuth()}>
          <CustomerPage dashboardApiOverride={dashboardApi} />
        </TestProviders>
      </MemoryRouter>,
    )

    // Header & Eyebrow
    expect(screen.getByText('Customer space')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: /a little space for/i })).toBeInTheDocument()

    // Navigation
    expect(screen.getByRole('navigation', { name: /customer space sections/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /account profile/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /request quote & book/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /deliveries & tracking/i })).toBeInTheDocument()

    // Dashboard Cards
    expect(await screen.findByRole('heading', { name: /your deliveries summary/i })).toBeInTheDocument()
    expect(await screen.findByText('4')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('16')).toBeInTheDocument() // 4 + 12 = 16
    expect(screen.getByText('SD-TRK-7777')).toBeInTheDocument()
    expect(screen.getByText('Out for Delivery')).toBeInTheDocument()
    expect(screen.getByText('Jaffna')).toBeInTheDocument()

    // Profile Grid
    expect(await screen.findByText('Sinthuha N')).toBeInTheDocument()
    expect(screen.getByText('sinthuha@example.com')).toBeInTheDocument()

    // Aside notes & CTAs
    expect(screen.getByRole('heading', { name: /built around you/i })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /request a quote & book/i }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: /view delivery history/i }).length).toBeGreaterThan(0)
  })

  it('renders forbidden state when user lacks CUSTOMER role', async () => {
    const courierAuth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: 'courier-sub', roles: ['COURIER'] },
      getAccessToken: async () => 'courier-token',
    }
    const dashboardApi = createMockDashboardApi()

    render(
      <MemoryRouter>
        <TestProviders auth={courierAuth}>
          <CustomerPage dashboardApiOverride={dashboardApi} />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(screen.getByText(/profile access unavailable/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /your deliveries summary/i })).not.toBeInTheDocument()
    expect(dashboardApi.getDashboard).not.toHaveBeenCalled()
  })

  it('renders auth-required state when unauthenticated', async () => {
    render(
      <MemoryRouter>
        <TestProviders auth={anonymousAuth}>
          <CustomerPage />
        </TestProviders>
      </MemoryRouter>,
    )

    expect(screen.getByText(/sign-in is not available yet/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /your deliveries summary/i })).not.toBeInTheDocument()
  })
})
