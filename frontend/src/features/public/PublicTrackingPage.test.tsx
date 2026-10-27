import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../app/router/routes'
import { anonymousAuth, TestProviders } from '../../test/authFixture'
import type { PublicTrackingResponse } from '../customer/api/deliveryContracts'

const testTrackingResponse: PublicTrackingResponse = {
  trackingNumber: 'SWD-20261023-A1B2C3',
  currentStatus: 'IN_TRANSIT',
  originCity: 'Colombo',
  destinationCity: 'Kandy',
  timeline: [
    {
      status: 'CREATED',
      occurredAt: '2026-10-23T10:00:00Z',
      cityOrZone: 'Colombo',
    },
    {
      status: 'PICKED_UP',
      occurredAt: '2026-10-23T11:30:00Z',
      cityOrZone: 'Colombo Central',
    },
    {
      status: 'IN_TRANSIT',
      occurredAt: '2026-10-23T14:15:00Z',
      cityOrZone: 'Western Province Transit Hub',
    },
  ],
}

function renderPublicTracking(route = '/tracking') {
  return render(
    <TestProviders auth={anonymousAuth}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

describe('Public Redacted Parcel Tracking (Issue #115 / N10-B11)', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders public tracking page for anonymous users without requiring sign-in', () => {
    renderPublicTracking('/tracking')

    expect(screen.getByRole('heading', { name: /track your shipment/i })).toBeInTheDocument()
    expect(screen.getByRole('search')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/e\.g\. SWD-20261023-A1B2C3/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /track parcel/i })).toBeInTheDocument()
    // Must NOT redirect to login
    expect(screen.queryByRole('heading', { name: 'Sign in to SwiftDrop' })).not.toBeInTheDocument()
  })

  it('loads and displays coarse tracking milestones when accessed via route deep-link', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(testTrackingResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

    // Verify request to unauthenticated REST endpoint
    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })
    const requestedUrl = mockFetch.mock.calls[0]?.[0] as string
    expect(requestedUrl).toContain('/api/v1/tracking/SWD-20261023-A1B2C3')

    // Verify status and coarse transit details render
    await waitFor(() => {
      expect(screen.getAllByText(/in transit/i).length).toBeGreaterThan(0)
      expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
      expect(screen.getByText('Colombo')).toBeInTheDocument()
      expect(screen.getByText('Kandy')).toBeInTheDocument()
    })

    // Verify milestone timeline elements
    expect(screen.getByText('Transit Milestones')).toBeInTheDocument()
    expect(screen.getByText('📍 Western Province Transit Hub')).toBeInTheDocument()

    // Strict privacy verification: Ensure sensitive PII is NOT in the tracking results
    const results = document.querySelector('.tracking-results-section')!
    expect(results).toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/customer name/i)).not.toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/courier name/i)).not.toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/recipient/i)).not.toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/phone/i)).not.toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/LKR/i)).not.toBeInTheDocument()
    expect(within(results as HTMLElement).queryByText(/receipt/i)).not.toBeInTheDocument()
  })

  it('displays user-friendly not-found message when tracking reference does not exist (HTTP 404)', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          type: 'https://swiftdrop.app/errors/tracking-not-found',
          title: 'Tracking Reference Not Found',
          status: 404,
          detail: 'Tracking reference SWD-20261023-NOTFOUND does not exist.',
        }),
        {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    )

    renderPublicTracking('/tracking/SWD-20261023-NOTFOUND')

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(
        screen.getByText(/tracking reference 'SWD-20261023-NOTFOUND' was not found/i),
      ).toBeInTheDocument()
    })
  })

  it('displays abuse throttle guidance when rate limit is exceeded (HTTP 429)', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          type: 'https://swiftdrop.app/errors/rate-limit-exceeded',
          title: 'Rate Limit Exceeded',
          status: 429,
          detail: 'Too many tracking requests from this IP address.',
        }),
        {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    )

    renderPublicTracking('/tracking/SWD-20261023-RATELIM')

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(
        screen.getByText(/too many tracking requests from this network/i),
      ).toBeInTheDocument()
    })
  })

  it('displays temporary service unavailable message on network or gateway error (HTTP 503)', async () => {
    mockFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          title: 'Service Unavailable',
          status: 503,
          detail: 'Backend tracking service unreachable.',
        }),
        {
          status: 503,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    )

    renderPublicTracking('/tracking/SWD-20261023-UNAVAIL')

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(
        screen.getByText(/tracking service is temporarily unavailable/i),
      ).toBeInTheDocument()
    })
  })

  it('allows user to enter reference in form and navigate to tracking details', async () => {
    const user = userEvent.setup()

    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(testTrackingResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderPublicTracking('/tracking')

    const input = screen.getByRole('textbox', { name: /tracking reference/i })
    const submitBtn = screen.getByRole('button', { name: /track parcel/i })

    await user.type(input, 'SWD-20261023-A1B2C3')
    await user.click(submitBtn)

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
      expect(screen.getAllByText(/in transit/i).length).toBeGreaterThan(0)
      expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
    })
  })

  it('supports copying tracking reference to clipboard', async () => {
    const user = userEvent.setup()
    const writeTextSpy = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: {
        writeText: writeTextSpy,
      },
      writable: true,
      configurable: true,
    })

    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(testTrackingResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
    })

    const copyBtn = screen.getByRole('button', { name: /copy tracking reference/i })
    await user.click(copyBtn)

    expect(writeTextSpy).toHaveBeenCalledWith('SWD-20261023-A1B2C3')
    expect(screen.getByText('✓ Copied')).toBeInTheDocument()
  })
})
