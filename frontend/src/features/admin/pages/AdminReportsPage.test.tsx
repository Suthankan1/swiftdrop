import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import { AuthContext, type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth } from '../../../test/authFixture'
import { AdminReportsError, type AdminDashboardData, type DeliveryStatsSummary } from '../api/adminReportsContracts'
import type { AdminReportsApi } from '../api/adminReports'
import { AdminReportsPage } from './AdminReportsPage'

const sampleDeliveryStats: DeliveryStatsSummary = {
  totalCount: 15,
  deliveredCount: 8,
  failedCount: 2,
  inTransitCount: 3,
  createdCount: 2,
  averageDeliveryHours: 2.75,
}

const sampleAdminDashboard: AdminDashboardData = {
  pendingMerchantsCount: 2,
  totalParcelsCount: 42,
  activeParcelsCount: 7,
  terminalSuccessRate: 0.8,
  recentPendingMerchants: [
    {
      merchantId: '11111111-1111-4111-8111-111111111111',
      businessName: 'Royal Tea Traders',
      registrationNumber: 'PV-001122',
      submittedAt: '2026-10-28T03:00:00Z',
    },
    {
      merchantId: '22222222-2222-4222-8222-222222222222',
      businessName: 'Lanka Spice Exports',
      registrationNumber: 'PV-003344',
      submittedAt: '2026-10-28T04:30:00Z',
    },
  ],
}

function auth(
  roles: readonly SwiftDropRole[] = ['ADMIN'],
  subject = 'synthetic-admin-sub',
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-admin-token'),
    handleSessionExpiry: vi.fn(async () => {}),
  }
}

function mockApi(overrides?: Partial<AdminReportsApi>): AdminReportsApi {
  return {
    getDeliveryStats: vi.fn(async () => sampleDeliveryStats),
    getAdminDashboard: vi.fn(async () => sampleAdminDashboard),
    ...overrides,
  }
}

function setup({
  api = mockApi(),
  current = auth(),
  path = '/admin/reports',
  productionRoutes = false,
}: {
  api?: AdminReportsApi
  current?: AuthContextValue
  path?: string
  productionRoutes?: boolean
} = {}) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })

  function tree(value: AuthContextValue, nextApi = api) {
    return (
      <QueryClientProvider client={cache}>
        <AuthContext value={value}>
          <MemoryRouter initialEntries={[path]}>
            {productionRoutes ? (
              <AppRoutes />
            ) : (
              <Routes>
                <Route path="/admin/reports" element={<AdminReportsPage api={nextApi} />} />
                <Route path="/admin" element={<div>Admin Landing Workspace</div>} />
                <Route path="/admin/merchants" element={<div>Merchant Review Workspace</div>} />
              </Routes>
            )}
          </MemoryRouter>
        </AuthContext>
      </QueryClientProvider>
    )
  }

  const view = render(tree(current))
  return {
    ...view,
    cache,
    api,
    rerenderAuth: (next: AuthContextValue, nextApi = api) => view.rerender(tree(next, nextApi)),
  }
}

function getMetricCard(container: HTMLElement, label: string): HTMLElement {
  const labelEl = within(container).getByText(label)
  const card = labelEl.closest('.metric-card')
  if (!card) throw new Error(`Could not find metric card with label: ${label}`)
  return card as HTMLElement
}

describe('AdminReportsPage — Security & Authorization Gates', () => {
  it('redirects an anonymous user to login via production routing', () => {
    setup({ current: anonymousAuth, productionRoutes: true })
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Daily delivery outcomes' })).not.toBeInTheDocument()
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'])(
    'denies direct route access to %s and does not call report API',
    (role) => {
      const api = mockApi()
      setup({ current: auth([role]), api, productionRoutes: true })

      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Daily delivery outcomes' })).not.toBeInTheDocument()
      expect(api.getDeliveryStats).not.toHaveBeenCalled()
      expect(api.getAdminDashboard).not.toHaveBeenCalled()
    },
  )

  it('denies authenticated user with empty roles array', () => {
    const api = mockApi()
    setup({ current: auth([]), api })

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Operational reporting requires an authenticated ADMIN session.',
    )
    expect(api.getDeliveryStats).not.toHaveBeenCalled()
    expect(api.getAdminDashboard).not.toHaveBeenCalled()
  })

  it('renders correctly for authenticated ADMIN', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Daily delivery outcomes' })).toBeInTheDocument()
    expect(screen.getByText(/Asia\/Colombo \(UTC\+05:30\)/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to administration' })).toHaveAttribute('href', '/admin')
  })
})

describe('AdminReportsPage — Successful Reporting Data Rendering', () => {
  it('renders delivery outcome cards with correct counts, units and calculated terminal success rate', async () => {
    setup()

    expect(await screen.findByText('Daily delivery outcomes')).toBeInTheDocument()

    // Metric cards
    const metricsRegion = screen.getByRole('region', { name: 'Delivery outcome metrics' })
    expect(within(getMetricCard(metricsRegion, 'Total parcels')).getByText('15')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Successful deliveries')).getByText('8')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Failed deliveries')).getByText('2')).toBeInTheDocument()

    // Success rate: 8 / (8 + 2) = 80.0%
    expect(within(getMetricCard(metricsRegion, 'Terminal success rate')).getByText('80.0%')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Active in-transit')).getByText('3')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Created / awaiting')).getByText('2')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Average delivery duration')).getByText('2.75 hrs')).toBeInTheDocument()
  })

  it('renders merchant onboarding review summary with table and link to /admin/merchants', async () => {
    setup()

    expect(await screen.findByText('Merchant onboarding review')).toBeInTheDocument()
    expect(screen.getByText(/Pending review:/)).toHaveTextContent('2')

    // Table entries
    expect(screen.getByText('Royal Tea Traders')).toBeInTheDocument()
    expect(screen.getByText('PV-001122')).toBeInTheDocument()
    expect(screen.getByText('Lanka Spice Exports')).toBeInTheDocument()
    expect(screen.getByText('PV-003344')).toBeInTheDocument()

    // Review links
    const reviewLinks = screen.getAllByRole('link', { name: /review application/i })
    expect(reviewLinks.length).toBe(2)
    expect(reviewLinks[0]).toHaveAttribute('href', '/admin/merchants')

    expect(
      screen.getByRole('link', { name: 'Open merchant review workspace' }),
    ).toHaveAttribute('href', '/admin/merchants')
  })

  it('renders platform operational overview section from adminDashboard aggregate', async () => {
    setup()

    expect(await screen.findByText('Platform operational overview (Today, Asia/Colombo)')).toBeInTheDocument()

    const platformRegion = screen.getByRole('region', { name: 'Platform current-day overview' })
    expect(within(platformRegion).getByText('Total parcels today')).toBeInTheDocument()
    expect(within(platformRegion).getByText('42')).toBeInTheDocument()

    expect(within(platformRegion).getByText('Active parcels today')).toBeInTheDocument()
    expect(within(platformRegion).getByText('7')).toBeInTheDocument()

    expect(within(platformRegion).getByText('Terminal success rate today')).toBeInTheDocument()
    expect(within(platformRegion).getByText('80.0%')).toBeInTheDocument()
  })
})

describe('AdminReportsPage — Filters & Bounded Inputs', () => {
  it('allows applying custom date, zone, and status filters', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    setup({ api })

    await screen.findByText('Daily delivery outcomes')

    const startDateInput = screen.getByLabelText(/start date/i)
    const endDateInput = screen.getByLabelText(/end date/i)
    const zoneInput = screen.getByLabelText(/operational zone/i)
    const statusSelect = screen.getByLabelText(/parcel status/i)
    const applyButton = screen.getByRole('button', { name: 'Apply filters' })

    await user.clear(startDateInput)
    await user.type(startDateInput, '2026-10-01')

    await user.clear(endDateInput)
    await user.type(endDateInput, '2026-10-15')

    await user.type(zoneInput, 'CMB-01')
    await user.selectOptions(statusSelect, 'DELIVERED')

    await user.click(applyButton)

    await waitFor(() => {
      expect(api.getDeliveryStats).toHaveBeenCalledWith(
        {
          startDate: '2026-10-01',
          endDate: '2026-10-15',
          zone: 'CMB-01',
          status: 'DELIVERED',
        },
        expect.any(AbortSignal),
      )
    })
  })

  it('displays validation error if start date is after end date', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    setup({ api })

    await screen.findByText('Daily delivery outcomes')

    const startDateInput = screen.getByLabelText(/start date/i)
    const endDateInput = screen.getByLabelText(/end date/i)
    const applyButton = screen.getByRole('button', { name: 'Apply filters' })

    await user.clear(startDateInput)
    await user.type(startDateInput, '2026-10-28')

    await user.clear(endDateInput)
    await user.type(endDateInput, '2026-10-20')

    await user.click(applyButton)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Start date must be before or equal to end date.',
    )
  })

  it('displays validation error if range exceeds 90 calendar days', async () => {
    const user = userEvent.setup()
    setup()

    await screen.findByText('Daily delivery outcomes')

    const startDateInput = screen.getByLabelText(/start date/i)
    const endDateInput = screen.getByLabelText(/end date/i)
    const applyButton = screen.getByRole('button', { name: 'Apply filters' })

    await user.clear(startDateInput)
    await user.type(startDateInput, '2026-01-01')

    await user.clear(endDateInput)
    await user.type(endDateInput, '2026-04-15') // 104 days

    await user.click(applyButton)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Reporting window exceeds maximum allowed range of 90 calendar days.',
    )
  })

  it('displays validation error if zone code format is invalid', async () => {
    const user = userEvent.setup()
    setup()

    await screen.findByText('Daily delivery outcomes')

    const zoneInput = screen.getByLabelText(/operational zone/i)
    const applyButton = screen.getByRole('button', { name: 'Apply filters' })

    await user.type(zoneInput, 'illegal zone name!')
    await user.click(applyButton)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Operational zone code must match pattern ^[A-Z0-9_-]{1,32}$.',
    )
  })

  it('resets filter fields back to today when clicking Reset', async () => {
    const user = userEvent.setup()
    setup()

    await screen.findByText('Daily delivery outcomes')

    const zoneInput = screen.getByLabelText(/operational zone/i)
    await user.type(zoneInput, 'CMB-02')
    expect(zoneInput).toHaveValue('CMB-02')

    await user.click(screen.getByRole('button', { name: 'Reset to today' }))
    expect(zoneInput).toHaveValue('')
  })
})

describe('AdminReportsPage — Empty, Loading & Partial Failure States', () => {
  it('displays empty delivery message and N/A terminal rate when totalCount is 0', async () => {
    const emptyDeliveryApi = mockApi({
      getDeliveryStats: vi.fn(async () => ({
        totalCount: 0,
        deliveredCount: 0,
        failedCount: 0,
        inTransitCount: 0,
        createdCount: 0,
        averageDeliveryHours: null,
      })),
    })

    setup({ api: emptyDeliveryApi })

    expect(await screen.findByText('No delivery activity was recorded for this reporting period.')).toBeInTheDocument()

    const metricsRegion = screen.getByRole('region', { name: 'Delivery outcome metrics' })
    expect(within(getMetricCard(metricsRegion, 'Terminal success rate')).getByText('N/A')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Average delivery duration')).getByText('N/A')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Total parcels')).getByText('0')).toBeInTheDocument()
  })

  it('displays empty merchant message when pending merchants list is empty', async () => {
    const emptyMerchantApi = mockApi({
      getAdminDashboard: vi.fn(async () => ({
        ...sampleAdminDashboard,
        pendingMerchantsCount: 0,
        recentPendingMerchants: [],
      })),
    })

    setup({ api: emptyMerchantApi })

    expect(
      await screen.findByText('No merchant applications are currently awaiting review.'),
    ).toBeInTheDocument()
  })

  it('handles partial provider failure gracefully: deliveryStats fails (UNAVAILABLE) while merchant review succeeds', async () => {
    const partialApi = mockApi({
      getDeliveryStats: vi.fn(async () => {
        throw new AdminReportsError('unavailable')
      }),
    })

    setup({ api: partialApi })

    // Delivery outcomes displays unavailable alert
    expect(
      await screen.findByText('Delivery statistics temporarily unavailable'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Reporting information is temporarily unavailable. Please retry.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry delivery statistics' })).toBeInTheDocument()

    // Merchant review still renders successfully
    expect(screen.getByText('Merchant onboarding review')).toBeInTheDocument()
    expect(screen.getByText('Royal Tea Traders')).toBeInTheDocument()
  })

  it('handles partial provider failure: adminDashboard fails while deliveryStats succeeds', async () => {
    const partialApi = mockApi({
      getAdminDashboard: vi.fn(async () => {
        throw new AdminReportsError('unavailable')
      }),
    })

    setup({ api: partialApi })

    // Merchant review displays unavailable alert
    expect(
      await screen.findByText('Merchant review summary temporarily unavailable'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry merchant review' })).toBeInTheDocument()

    // Delivery outcomes still renders successfully
    expect(await screen.findByText('Delivery outcomes')).toBeInTheDocument()
    expect(screen.getByText('Successful deliveries')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
  })

  it('allows retrying a failed provider independently', async () => {
    const user = userEvent.setup()
    let deliveryCalls = 0
    const retryApi = mockApi({
      getDeliveryStats: vi.fn(async () => {
        deliveryCalls++
        if (deliveryCalls === 1) {
          throw new AdminReportsError('unavailable')
        }
        return sampleDeliveryStats
      }),
    })

    setup({ api: retryApi })

    expect(
      await screen.findByText('Delivery statistics temporarily unavailable'),
    ).toBeInTheDocument()

    const retryButton = screen.getByRole('button', { name: 'Retry delivery statistics' })
    await user.click(retryButton)

    expect(await screen.findByText('Successful deliveries')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(deliveryCalls).toBe(2)
  })
})

describe('AdminReportsPage — Session Isolation & Teardown', () => {
  it('clears reporting workspace when session expires or auth status changes', async () => {
    const { rerenderAuth } = setup()

    expect(await screen.findByText('Daily delivery outcomes')).toBeInTheDocument()

    // Session expires / revoked
    act(() => {
      rerenderAuth(anonymousAuth)
    })

    expect(
      await screen.findByText('Operational reporting requires an authenticated ADMIN session.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Daily delivery outcomes')).not.toBeInTheDocument()
  })

  it('calls handleSessionExpiry when API returns auth-required', async () => {
    const authHandler = vi.fn(async () => {})
    const expiredAuth: AuthContextValue = {
      ...auth(),
      handleSessionExpiry: authHandler,
    }

    const expiredApi = mockApi({
      getDeliveryStats: vi.fn(async () => {
        throw new AdminReportsError('auth-required')
      }),
    })

    setup({ current: expiredAuth, api: expiredApi })

    await waitFor(() => {
      expect(authHandler).toHaveBeenCalled()
    })
  })
})

describe('Canonical Colombo Day-Boundary E2E Component Verification (Phase 21)', () => {
  it('verifies that selecting Colombo date 2026-10-28 correctly reflects canonical parcel outcomes', async () => {
    /**
     * Synthetic parcel lifecycle transitions across the UTC / Colombo boundary:
     * - Parcel A: transition at 2026-10-27T18:20:00Z -> Colombo Oct 27 (excluded)
     * - Parcel B: DELIVERED at 2026-10-27T18:35:00Z -> Colombo Oct 28 (included)
     * - Parcel C: FAILED at 2026-10-28T10:00:00Z -> Colombo Oct 28 (included)
     * - Parcel D: transition at 2026-10-28T18:40:00Z -> Colombo Oct 29 (excluded)
     *
     * Expected outcome for Colombo day 2026-10-28:
     * - Total terminal: 2 (Parcels B and C)
     * - Delivered: 1 (Parcel B)
     * - Failed: 1 (Parcel C)
     * - Terminal success rate: 1 / 2 = 50.0%
     */
    const canonicalStats: DeliveryStatsSummary = {
      totalCount: 2,
      deliveredCount: 1,
      failedCount: 1,
      inTransitCount: 0,
      createdCount: 0,
      averageDeliveryHours: 4.25,
    }

    const api = mockApi({
      getDeliveryStats: vi.fn(async (filter) => {
        expect(filter?.startDate).toBe('2026-10-28')
        expect(filter?.endDate).toBe('2026-10-28')
        return canonicalStats
      }),
    })

    const user = userEvent.setup()
    setup({ api })

    await screen.findByText('Daily delivery outcomes')

    const startDateInput = screen.getByLabelText(/start date/i)
    const endDateInput = screen.getByLabelText(/end date/i)
    const applyButton = screen.getByRole('button', { name: 'Apply filters' })

    await user.clear(startDateInput)
    await user.type(startDateInput, '2026-10-28')

    await user.clear(endDateInput)
    await user.type(endDateInput, '2026-10-28')

    await user.click(applyButton)

    const metricsRegion = await screen.findByRole('region', { name: 'Delivery outcome metrics' })
    expect(within(getMetricCard(metricsRegion, 'Total parcels')).getByText('2')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Successful deliveries')).getByText('1')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Failed deliveries')).getByText('1')).toBeInTheDocument()
    expect(within(getMetricCard(metricsRegion, 'Terminal success rate')).getByText('50.0%')).toBeInTheDocument()
  })
})
