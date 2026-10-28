import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { adminDashboardFixture, deliveryStatsFixture, graphqlData, graphqlError, graphqlFetch, requestBodies } from '../../../test/reportsFixture'

// 10:00Z is 15:30 on 28 Oct 2026 in Asia/Colombo.
const NOW = new Date('2026-10-28T10:00:00Z')

function authenticated(roles: readonly SwiftDropRole[], subject = 'synthetic-report-subject'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-report-token'),
    handleSessionExpiry: vi.fn(async () => {}),
    profileFetch: vi.fn(async () => new Response(null, { status: 404 })),
  }
}
function entry(auth: AuthContextValue, path: string) {
  return <TestProviders auth={auth}><MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter></TestProviders>
}
function stub(handlers: Parameters<typeof graphqlFetch>[0]) {
  const mock = vi.fn<typeof fetch>(graphqlFetch(handlers))
  vi.stubGlobal('fetch', mock)
  return mock
}
const ok = {
  deliveryStats: () => graphqlData('deliveryStats', deliveryStatsFixture()),
  adminDashboard: () => graphqlData('adminDashboard', adminDashboardFixture()),
}
function metric(label: string) {
  const group = screen.getByRole('group', { name: /delivery outcome metrics/i })
  return within(group).getByText(label).closest('.metric-card') as HTMLElement
}
function statsFilters(mock: ReturnType<typeof stub>) {
  return requestBodies(mock).filter(body => body.query.includes('deliveryStats(')).map(body => body.variables?.filter)
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})
afterEach(() => { vi.useRealTimers() })

describe('report route security', () => {
  it.each(['/admin/reports', '/dispatcher/reports'])('redirects anonymous %s to login without a GraphQL request', path => {
    const mock = stub(ok)
    render(entry(anonymousAuth, path))
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(mock).not.toHaveBeenCalled()
  })

  it.each([
    ['CUSTOMER', '/admin/reports'], ['MERCHANT', '/admin/reports'], ['COURIER', '/admin/reports'], ['DISPATCHER', '/admin/reports'],
    ['CUSTOMER', '/dispatcher/reports'], ['MERCHANT', '/dispatcher/reports'], ['COURIER', '/dispatcher/reports'], ['ADMIN', '/dispatcher/reports'],
  ] as const)('denies %s at %s without requesting report data', (role, path) => {
    const mock = stub(ok)
    render(entry(authenticated([role]), path))
    expect(screen.getByRole('heading', { name: 'Access unavailable' })).toBeInTheDocument()
    expect(screen.queryByText(/delivery outcomes/i)).not.toBeInTheDocument()
    expect(mock).not.toHaveBeenCalled()
  })

  it('limits DISPATCHER to server-scoped deliveryStats: no adminDashboard, zone filter or merchant data', async () => {
    const mock = stub({ deliveryStats: ok.deliveryStats })
    render(entry(authenticated(['DISPATCHER']), '/dispatcher/reports'))
    expect(screen.getByRole('heading', { level: 1, name: 'Daily outcomes' })).toBeInTheDocument()
    expect(await screen.findByText('90.0%')).toBeInTheDocument()
    expect(screen.queryByLabelText(/zone code/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/merchant/i)).not.toBeInTheDocument()
    expect(screen.getByText(/limited by the server to your assigned operational zones/i)).toBeInTheDocument()
    const bodies = requestBodies(mock)
    expect(bodies.every(body => !body.query.includes('adminDashboard'))).toBe(true)
    expect(statsFilters(mock)).toEqual([{ startDate: '2026-10-28', endDate: '2026-10-28' }])
  })

  it('removes report content when the server answers FORBIDDEN', async () => {
    stub({ deliveryStats: () => graphqlError('FORBIDDEN'), adminDashboard: ok.adminDashboard })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(await screen.findByRole('heading', { name: 'Report access denied' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /operations overview/i })).not.toBeInTheDocument()
    expect(screen.queryByText('Synthetic Spice Traders')).not.toBeInTheDocument()
  })

  it('delegates an UNAUTHENTICATED answer to shared session expiry', async () => {
    stub({ deliveryStats: () => graphqlError('UNAUTHENTICATED'), adminDashboard: () => graphqlError('UNAUTHENTICATED') })
    const auth = authenticated(['ADMIN'])
    render(entry(auth, '/admin/reports'))
    expect(await screen.findByRole('heading', { name: 'Report access denied' })).toBeInTheDocument()
    await waitFor(() => expect(auth.handleSessionExpiry).toHaveBeenCalled())
  })

  it('offers report entries only from the matching role workspace', () => {
    stub(ok)
    const { unmount } = render(entry(authenticated(['ADMIN']), '/admin'))
    expect(screen.getByRole('link', { name: /view daily outcomes and admin reports/i })).toHaveAttribute('href', '/admin/reports')
    unmount()
    render(entry(authenticated(['DISPATCHER']), '/dispatcher'))
    expect(screen.getByRole('link', { name: /view daily outcomes for your zones/i })).toHaveAttribute('href', '/dispatcher/reports')
  })
})

describe('admin daily outcomes and report screens', () => {
  it('shows loading indicators before data arrives', async () => {
    stub(ok)
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(screen.getByRole('region', { name: 'Loading delivery outcomes' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Loading operations overview' })).toBeInTheDocument()
    expect(await screen.findByText('90.0%')).toBeInTheDocument()
  })

  it('renders labelled outcome metrics, rates, timezone, period and status distribution from deliveryStats', async () => {
    const mock = stub(ok)
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByRole('group', { name: /delivery outcome metrics/i })
    expect(within(metric('Total parcels')).getByText('40')).toBeInTheDocument()
    expect(within(metric('Delivered')).getByText('18')).toBeInTheDocument()
    expect(within(metric('Failed')).getByText('2')).toBeInTheDocument()
    expect(within(metric('Success rate')).getByText('90.0%')).toBeInTheDocument()
    expect(within(metric('Success rate')).getByText('Delivered ÷ 20 terminal outcomes')).toBeInTheDocument()
    expect(within(metric('Failure rate')).getByText('10.0%')).toBeInTheDocument()
    expect(within(metric('Average delivery time')).getByText('5.25 h')).toBeInTheDocument()
    expect(screen.getByText(/reporting period:/i).parentElement).toHaveTextContent('Wed, 28 Oct 2026 (1 day) · Asia/Colombo (UTC+05:30) · All authorized zones')
    expect(screen.getByText(/today's figures are day to date/i)).toBeInTheDocument()
    const distribution = screen.getByRole('table', { name: 'Status distribution for the period' })
    expect(within(distribution).getByRole('row', { name: /awaiting pickup/i })).toHaveTextContent('820.0%')
    expect(within(distribution).getByRole('row', { name: /in progress/i })).toHaveTextContent('1230.0%')
    expect(within(distribution).queryByText(/not classified/i)).not.toBeInTheDocument()
    expect(statsFilters(mock)).toEqual([{ startDate: '2026-10-28', endDate: '2026-10-28' }])
  })

  it('renders the adminDashboard overview and a safe merchant review summary', async () => {
    stub({ ...ok, adminDashboard: () => graphqlData('adminDashboard', {
      ...adminDashboardFixture(),
      recentPendingMerchants: adminDashboardFixture().recentPendingMerchants.map(item => ({ ...item, email: 'owner@synthetic.invalid', phone: '+94000000000' })),
    }) })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    const overview = await screen.findByRole('group', { name: /today's operations metrics/i })
    expect(within(overview).getByText('27')).toBeInTheDocument()
    expect(within(overview).getByText('9')).toBeInTheDocument()
    expect(within(overview).getByText('93.3%')).toBeInTheDocument()
    expect(within(overview).getByText('3')).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Pending merchant applications' })
    expect(within(table).getByText('Synthetic Spice Traders')).toBeInTheDocument()
    expect(within(table).getByText('PV-000101')).toBeInTheDocument()
    // 18:45Z on 27 Oct is 00:15 on 28 Oct in Colombo.
    expect(within(table).getByText(/28 Oct 2026, 00:15/)).toBeInTheDocument()
    expect(screen.getByText('Showing 2 of 3 pending applications. Contact details and credentials are not part of this summary.')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/owner@synthetic\.invalid|\+94000000000|6f1c2b8e/)
    expect(screen.getByRole('link', { name: 'Open merchant review' })).toHaveAttribute('href', '/admin/merchants')
  })

  it('shows an empty state for zero deliveries and an empty review queue, not missing data', async () => {
    stub({
      deliveryStats: () => graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 0, deliveredCount: 0, failedCount: 0, inTransitCount: 0, createdCount: 0, averageDeliveryHours: null })),
      adminDashboard: () => graphqlData('adminDashboard', adminDashboardFixture({ pendingMerchantsCount: 0, recentPendingMerchants: [] })),
    })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(await screen.findByRole('region', { name: 'No delivery activity in this period' })).toBeInTheDocument()
    expect(await screen.findByText('No merchant applications are awaiting review.')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/)
  })

  it('shows rates as not available when no parcel reached a terminal outcome', async () => {
    stub({ ...ok, deliveryStats: () => graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 5, deliveredCount: 0, failedCount: 0, inTransitCount: 3, createdCount: 2, averageDeliveryHours: null })) })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByRole('group', { name: /delivery outcome metrics/i })
    expect(within(metric('Success rate')).getByText('Not available')).toBeInTheDocument()
    expect(within(metric('Failure rate')).getByText('Not available')).toBeInTheDocument()
    expect(within(metric('Success rate')).getByText('No delivered or failed parcels in this period')).toBeInTheDocument()
    expect(within(metric('Average delivery time')).getByText('Not available')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/)
  })

  it('shows a distinct partial-failure state while deliveryStats has no provider (current backend)', async () => {
    stub({ deliveryStats: () => graphqlError('UNAVAILABLE'), adminDashboard: ok.adminDashboard })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(await screen.findByRole('region', { name: 'Delivery outcomes unavailable' })).toHaveTextContent('The reporting provider is unavailable. No figures are shown until it responds.')
    expect(await screen.findByText(/partial report: some sections could not be retrieved/i)).toBeInTheDocument()
    expect(screen.getByRole('group', { name: /today's operations metrics/i })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /delivery outcome metrics/i })).not.toBeInTheDocument()
  })

  it('shows a timeout error with a retry that recovers without automatic replay', async () => {
    let calls = 0
    const mock = stub({ ...ok, deliveryStats: () => ++calls === 1 ? graphqlError('DEADLINE_EXCEEDED') : ok.deliveryStats() })
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    const error = await screen.findByRole('region', { name: 'Delivery outcomes unavailable' })
    expect(error).toHaveTextContent('did not respond in time')
    expect(statsFilters(mock)).toHaveLength(1)
    await user.click(within(error).getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('90.0%')).toBeInTheDocument()
    expect(statsFilters(mock)).toHaveLength(2)
  })

  it('treats an invalid response as an error instead of rendering figures', async () => {
    stub({ ...ok, deliveryStats: () => graphqlData('deliveryStats', deliveryStatsFixture({ deliveredCount: -4 })) })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(await screen.findByRole('region', { name: 'Delivery outcomes unavailable' })).toHaveTextContent('unsupported response')
    expect(screen.queryByText('-4')).not.toBeInTheDocument()
  })

  it('hides previously shown figures when a refresh fails, so stale data is not presented as current', async () => {
    let calls = 0
    stub({ ...ok, deliveryStats: () => ++calls === 1 ? ok.deliveryStats() : graphqlError('UNAVAILABLE') })
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    expect(await screen.findByText('90.0%')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Refresh outcomes' }))
    expect(await screen.findByRole('region', { name: 'Delivery outcomes unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('90.0%')).not.toBeInTheDocument()
  })
})

describe('report filters', () => {
  it('applies a validated date range, then requests per-day server aggregates with a partial-failure state', async () => {
    const mock = stub({ ...ok, deliveryStats: variables => {
      const filter = (variables?.filter ?? {}) as { startDate: string; endDate: string }
      if (filter.startDate !== filter.endDate) return graphqlData('deliveryStats', deliveryStatsFixture())
      if (filter.startDate === '2026-10-27') return graphqlError('UNAVAILABLE')
      return graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 10, deliveredCount: 3, failedCount: 1, inTransitCount: 4, createdCount: 2 }))
    } })
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    // The initial single-day summary is itself a one-day aggregate, so it has no breakdown.
    await screen.findByText('75.0%')
    expect(screen.queryByRole('heading', { name: 'Daily outcomes by reporting day' })).not.toBeInTheDocument()
    const start = screen.getByLabelText('Start date')
    await user.clear(start); await user.type(start, '2026-10-26')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    expect(await screen.findByText('90.0%')).toBeInTheDocument()
    const breakdown = await screen.findByRole('table', { name: /daily outcomes \(asia\/colombo/i })
    await waitFor(() => expect(within(breakdown).getAllByText('75.0%')).toHaveLength(2))
    expect(within(breakdown).getByRole('row', { name: /tue, 27 oct 2026/i })).toHaveTextContent('The reporting provider is unavailable')
    expect(screen.getByText('Partial breakdown: 1 of 3 days could not be retrieved. Their figures are not shown or estimated.')).toBeInTheDocument()
    expect(screen.getByText(/reporting period:/i).parentElement).toHaveTextContent('Mon, 26 Oct 2026 to Wed, 28 Oct 2026 (3 days)')
    expect(statsFilters(mock)).toEqual(expect.arrayContaining([
      { startDate: '2026-10-26', endDate: '2026-10-28' },
      { startDate: '2026-10-26', endDate: '2026-10-26' }, { startDate: '2026-10-27', endDate: '2026-10-27' }, { startDate: '2026-10-28', endDate: '2026-10-28' },
    ]))
    expect(screen.getByRole('button', { name: 'Retry unavailable days' })).toBeEnabled()
  })

  it.each([
    ['2026-10-29', '2026-10-28', 'The start date must be on or before the end date.'],
    ['2026-10-28', '2026-10-29', 'Reports cannot include dates after today in Asia/Colombo.'],
    ['2026-07-29', '2026-10-28', 'Choose a reporting period of at most 90 days.'],
  ])('rejects start %s and end %s before any request', async (startDate, endDate, message) => {
    const mock = stub(ok)
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByText('90.0%')
    const before = mock.mock.calls.length
    const start = screen.getByLabelText('Start date'), end = screen.getByLabelText('End date')
    await user.clear(start); await user.type(start, startDate)
    await user.clear(end); await user.type(end, endDate)
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    expect(screen.getByRole('alert')).toHaveTextContent(message)
    expect(mock.mock.calls.length).toBe(before)
  })

  it('normalizes a valid zone code and rejects an invalid one', async () => {
    const mock = stub(ok)
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByText('90.0%')
    const zone = screen.getByLabelText(/zone code/i)
    await user.type(zone, 'CMB 01; DROP')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/zone codes use/i)
    await user.clear(zone); await user.type(zone, ' cmb-01 ')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    await waitFor(() => expect(statsFilters(mock).at(-1)).toEqual({ startDate: '2026-10-28', endDate: '2026-10-28', zone: 'CMB-01' }))
    await screen.findByText('90.0%')
    expect(screen.getByText(/reporting period:/i).parentElement).toHaveTextContent('Zone CMB-01')
  })

  it('uses quick periods and skips the per-day breakdown beyond seven days', async () => {
    const mock = stub(ok)
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByText('90.0%')
    await user.click(screen.getByRole('button', { name: 'Last 30 days' }))
    expect(await screen.findByText(/day-by-day breakdown is available for periods of up to 7 days/i)).toBeInTheDocument()
    expect(statsFilters(mock)).toEqual([{ startDate: '2026-10-28', endDate: '2026-10-28' }, { startDate: '2026-09-29', endDate: '2026-10-28' }])
  })

  it('never shows a late response for a superseded filter', async () => {
    let releaseFirst: (response: Response) => void = () => {}
    const first = new Promise<Response>(resolve => { releaseFirst = resolve })
    let calls = 0
    stub({ ...ok, deliveryStats: () => ++calls === 1 ? first : graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 41 })) })
    const user = userEvent.setup()
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await user.click(screen.getByRole('button', { name: 'Last 30 days' }))
    expect(await screen.findByText('41')).toBeInTheDocument()
    releaseFirst(graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 777, deliveredCount: 7, failedCount: 0, inTransitCount: 0, createdCount: 0 })))
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(screen.queryByText('777')).not.toBeInTheDocument()
    expect(screen.getByText('41')).toBeInTheDocument()
  })
})

describe('report accessibility', () => {
  it('uses one page heading, labelled filters, captioned tables and keyboard-focusable scroll regions', async () => {
    stub({ ...ok })
    render(entry(authenticated(['ADMIN']), '/admin/reports'))
    await screen.findByText('90.0%')
    await screen.findByRole('table', { name: 'Pending merchant applications' })
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByLabelText('Start date')).toHaveAttribute('type', 'date')
    expect(screen.getByLabelText('End date')).toHaveAttribute('max', '2026-10-28')
    for (const region of screen.getAllByRole('region', { name: /^scrollable/i })) expect(region).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('group', { name: 'Quick reporting periods' })).toBeInTheDocument()
  })
})
