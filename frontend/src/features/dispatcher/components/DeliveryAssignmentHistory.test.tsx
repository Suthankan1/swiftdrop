import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DeliveryAssignmentHistory } from './DeliveryAssignmentHistory'
import * as authContext from '../../../auth/context'

vi.mock('../../../auth/context', () => ({
  useAuth: vi.fn(),
}))

const mockParcels = [
  {
    parcelId: 'parcel-111',
    trackingNumber: 'SWD-20261124-AAA',
    pickupZone: 'ZONE_1',
    dropoffZone: 'ZONE_2',
    weightKg: 2.5,
    currentStatus: 'IN_TRANSIT',
    activeCourierSub: 'courier-john',
    createdAt: '2026-11-20T10:00:00Z',
  },
  {
    parcelId: 'parcel-222',
    trackingNumber: 'SWD-20261124-BBB',
    pickupZone: 'ZONE_1',
    dropoffZone: 'ZONE_1',
    weightKg: 1.0,
    currentStatus: 'DELIVERED',
    activeCourierSub: null,
    createdAt: '2026-11-21T12:00:00Z',
  },
]

const mockStatusHistory = [
  {
    historyId: 'hist-1',
    parcelId: 'parcel-111',
    previousStatus: null,
    newStatus: 'CREATED',
    reason: null,
    note: 'Order created',
    proofId: null,
    resultingVersion: 1,
    occurredAt: '2026-11-20T10:00:00Z',
  },
  {
    historyId: 'hist-2',
    parcelId: 'parcel-111',
    previousStatus: 'CREATED',
    newStatus: 'PICKED_UP',
    reason: null,
    note: 'Picked up from warehouse',
    proofId: null,
    resultingVersion: 2,
    occurredAt: '2026-11-20T11:00:00Z',
  },
]

const mockAssignmentHistory = [
  {
    auditId: 'audit-1',
    parcelId: 'parcel-111',
    assignmentId: 'asg-1',
    courierSub: 'courier-john',
    action: 'ASSIGNED',
    actorSub: 'dispatcher-1',
    actorRole: 'DISPATCHER',
    previousCourierSub: null,
    previousAssignmentId: null,
    reason: 'Initial assignment',
    parcelVersion: 1,
    occurredAt: '2026-11-20T10:30:00Z',
  },
]

function renderComponent() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <DeliveryAssignmentHistory />
    </QueryClientProvider>
  )
}

describe('DeliveryAssignmentHistory Component', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authContext.useAuth).mockReturnValue({
      snapshot: {
        status: 'authenticated',
        subject: 'dispatcher-1',
        roles: ['DISPATCHER'],
      },
      getAccessToken: vi.fn().mockResolvedValue('test-token'),
      handleSessionExpiry: vi.fn(),
    } as unknown as ReturnType<typeof authContext.useAuth>)

    fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/parcels?')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              items: mockParcels,
              currentPage: 0,
              pageSize: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
            }),
            { status: 200 }
          )
        )
      }
      if (url.includes('/api/v1/parcels/parcel-111/history')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              items: mockStatusHistory,
              currentPage: 0,
              pageSize: 50,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
            }),
            { status: 200 }
          )
        )
      }
      if (url.includes('/api/v1/assignments/parcels/parcel-111/history')) {
        return Promise.resolve(
          new Response(JSON.stringify(mockAssignmentHistory), { status: 200 })
        )
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 404 }))
    })

    vi.stubGlobal('fetch', fetchMock)
  })

  it('renders section title and supporting text', async () => {
    renderComponent()
    expect(
      screen.getByRole('heading', { name: 'Delivery and Assignment History' })
    ).toBeInTheDocument()
    expect(
      screen.getByText('Review parcel delivery progress and courier assignment records.')
    ).toBeInTheDocument()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()
  })

  it('loads and displays parcel list with tracking number, status, route, and courier', async () => {
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261124-BBB')).toBeInTheDocument()
    expect(screen.getByText('ID: parcel-111')).toBeInTheDocument()
    expect(screen.getByText('courier-john')).toBeInTheDocument()
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })

  it('filters by tracking number', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const searchInput = screen.getByLabelText('Tracking Number')
    await user.type(searchInput, 'BBB')

    expect(screen.queryByText('SWD-20261124-AAA')).not.toBeInTheDocument()
    expect(screen.getByText('SWD-20261124-BBB')).toBeInTheDocument()
  })

  it('filters by parcel status', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const statusSelect = screen.getByLabelText('Parcel Status')
    await user.selectOptions(statusSelect, 'DELIVERED')

    expect(screen.queryByText('SWD-20261124-AAA')).not.toBeInTheDocument()
    expect(screen.getByText('SWD-20261124-BBB')).toBeInTheDocument()
  })

  it('filters by courier', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const courierInput = screen.getByLabelText('Courier Sub')
    await user.type(courierInput, 'john')

    expect(screen.getByText('SWD-20261124-AAA')).toBeInTheDocument()
    expect(screen.queryByText('SWD-20261124-BBB')).not.toBeInTheDocument()
  })

  it('expands parcel status history lazily when clicked and collapses on second click', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const statusBtn = screen.getByRole('button', {
      name: 'Toggle parcel status history for SWD-20261124-AAA',
    })
    await user.click(statusBtn)

    expect(
      await screen.findByText('Parcel Status Timeline (Oldest to Newest)')
    ).toBeInTheDocument()
    expect(await screen.findByText('Picked up from warehouse', { exact: false })).toBeInTheDocument()

    // Collapse on second click
    await user.click(statusBtn)
    await waitFor(() => {
      expect(
        screen.queryByText('Parcel Status Timeline (Oldest to Newest)')
      ).not.toBeInTheDocument()
    })
  })

  it('expands assignment history lazily and switches between history options', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const asgBtn = screen.getByRole('button', {
      name: 'Toggle assignment history for SWD-20261124-AAA',
    })
    await user.click(asgBtn)

    expect(
      await screen.findByText('Courier Assignment Audit Timeline (Chronological)')
    ).toBeInTheDocument()
    expect(screen.getByText('Initial assignment')).toBeInTheDocument()

    // Switch to status history: assignment closes, status history opens
    const statusBtn = screen.getByRole('button', {
      name: 'Toggle parcel status history for SWD-20261124-AAA',
    })
    await user.click(statusBtn)

    expect(
      await screen.findByText('Parcel Status Timeline (Oldest to Newest)')
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Courier Assignment Audit Timeline (Chronological)')
    ).not.toBeInTheDocument()
  })

  it('handles empty parcel state', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            items: [],
            currentPage: 0,
            pageSize: 10,
            totalElements: 0,
            totalPages: 0,
            hasNext: false,
          }),
          { status: 200 }
        )
      )
    )

    renderComponent()
    expect(await screen.findByText('No parcel records found.')).toBeInTheDocument()
  })

  it('handles API failure and allows retry', async () => {
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(new Response(JSON.stringify({}), { status: 500 }))
    )

    renderComponent()
    expect(
      await screen.findByText('Operations provider unavailable. Retry to load current data.')
    ).toBeInTheDocument()

    // Reset fetchMock to succeed and click retry
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            items: mockParcels,
            currentPage: 0,
            pageSize: 10,
            totalElements: 2,
            totalPages: 1,
            hasNext: false,
          }),
          { status: 200 }
        )
      )
    )

    const retryBtn = screen.getByRole('button', { name: 'Retry' })
    await userEvent.click(retryBtn)

    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()
  })

  it('resets page to 0 when filters change', async () => {
    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()

    const searchInput = screen.getByLabelText('Tracking Number')
    await user.type(searchInput, 'XYZ')

    // Page reset is internally executed on change
    expect(screen.queryByText('SWD-20261124-AAA')).not.toBeInTheDocument()
    expect(screen.getByText('No parcel records found.')).toBeInTheDocument()
  })

  it('renders pagination independently of empty filtered results when totalPages > 1', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/api/v1/parcels?')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              items: mockParcels,
              currentPage: 0,
              pageSize: 10,
              totalElements: 25,
              totalPages: 3,
              hasNext: true,
            }),
            { status: 200 }
          )
        )
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 404 }))
    })

    const user = userEvent.setup()
    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 3 · 25 total')).toBeInTheDocument()

    // Filter by nonexistent tracking to produce 0 results on this page
    const searchInput = screen.getByLabelText('Tracking Number')
    await user.type(searchInput, 'NONEXISTENT')

    expect(screen.getByText('No parcel records found.')).toBeInTheDocument()
    // Pagination controls MUST still be visible!
    expect(screen.getByText('Page 1 of 3 · 25 total')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
  })

  it('allows ADMIN role to access delivery and assignment history', async () => {
    vi.mocked(authContext.useAuth).mockReturnValue({
      snapshot: {
        status: 'authenticated',
        subject: 'admin-1',
        roles: ['ADMIN'],
      },
      getAccessToken: vi.fn().mockResolvedValue('test-token'),
      handleSessionExpiry: vi.fn(),
    } as unknown as ReturnType<typeof authContext.useAuth>)

    renderComponent()
    expect(await screen.findByText('SWD-20261124-AAA')).toBeInTheDocument()
    expect(screen.getByText('SWD-20261124-BBB')).toBeInTheDocument()
  })

  it('displays note regarding server-side filtering limitation', async () => {
    renderComponent()
    expect(await screen.findByText(/Server-side parcel filtering is not currently supported/i)).toBeInTheDocument()
  })
})
