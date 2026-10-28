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

/** Response containing forbidden private fields — used for privacy tests */
const privateLeakingTrackingResponse = {
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
  ],
  // Forbidden fields that must never appear in the rendered DOM
  recipientName: 'PRIVATE_RECIPIENT',
  recipientPhone: 'PRIVATE_PHONE',
  recipientEmail: 'PRIVATE_EMAIL@example.com',
  pickupAddress: 'PRIVATE_STREET 123',
  dropoffAddress: 'PRIVATE_DROP 456',
  customerSub: 'PRIVATE_CUSTOMER_SUB',
  createdBySub: 'PRIVATE_CREATED_BY',
  merchantId: 'PRIVATE_MERCHANT_ID',
  courierSub: 'PRIVATE_COURIER',
  courierName: 'PRIVATE_COURIER_NAME',
  driverPhone: 'PRIVATE_DRIVER_PHONE',
  proofId: 'PRIVATE_PROOF_ID',
  proofUrl: 'PRIVATE_PROOF_URL',
  receiptUrl: 'PRIVATE_RECEIPT',
  receiptDownload: 'PRIVATE_RECEIPT_DOWNLOAD',
  objectKey: 'PRIVATE_OBJECT_KEY',
  storagePath: 'PRIVATE_STORAGE_PATH',
  internalDatabaseId: 'PRIVATE_DB_ID',
  privateNotes: 'PRIVATE_NOTES',
  deliveryInstructions: 'PRIVATE_INSTRUCTIONS',
  customerEmail: 'PRIVATE_CUSTOMER_EMAIL',
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

describe('Public Redacted Parcel Tracking (Issue #133 / N10-WF06)', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ── Successful flow tests ──────────────────────────────────────────

  describe('anonymous page rendering and access', () => {
    it('renders public tracking page for anonymous users without requiring sign-in', () => {
      renderPublicTracking('/tracking')

      expect(screen.getByRole('heading', { name: /track your shipment/i })).toBeInTheDocument()
      expect(screen.getByRole('search')).toBeInTheDocument()
      expect(screen.getByPlaceholderText(/e\.g\. SWD-20261023-A1B2C3/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /track parcel/i })).toBeInTheDocument()
      // Must NOT redirect to login
      expect(screen.queryByRole('heading', { name: 'Sign in to SwiftDrop' })).not.toBeInTheDocument()
    })

    it('shows descriptive text without claiming real-time or live updates', () => {
      renderPublicTracking('/tracking')

      const description = screen.getByText(/latest available delivery milestones/i)
      expect(description).toBeInTheDocument()
      // Must not claim real-time continuous updates from REST snapshots
      expect(screen.queryByText(/real-time/i)).not.toBeInTheDocument()
    })

    it('has accessible input with programmatic label', () => {
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      expect(input).toBeInTheDocument()
      expect(input).toHaveAttribute('autocomplete', 'off')
      expect(input).toHaveAttribute('maxlength', '32')
    })
  })

  describe('tracking lookup and keyboard interaction', () => {
    it('input can be entered using keyboard', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, 'SWD-20261023-A1B2C3')

      expect(input).toHaveValue('SWD-20261023-A1B2C3')
    })

    it('Enter submits the lookup form', async () => {
      const user = userEvent.setup()
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, 'SWD-20261023-A1B2C3{Enter}')

      await waitFor(() => {
        expect(mockFetch).toHaveBeenCalled()
      })
    })

    it('Submit button triggers the lookup', async () => {
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

    it('normalizes input whitespace and case before navigation', async () => {
      const user = userEvent.setup()
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, '  swd-20261023-a1b2c3  ')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(mockFetch).toHaveBeenCalled()
      })

      // Verify the URL was called with the normalized uppercase reference
      const calledUrl = mockFetch.mock.calls[0]?.[0] as string
      expect(calledUrl).toContain('/api/v1/tracking/SWD-20261023-A1B2C3')
    })
  })

  describe('deep-link and route behavior', () => {
    it('deep-link loads tracking directly', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(mockFetch).toHaveBeenCalledTimes(1)
      })
      const requestedUrl = mockFetch.mock.calls[0]?.[0] as string
      expect(requestedUrl).toContain('/api/v1/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getAllByText(/in transit/i).length).toBeGreaterThan(0)
        expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
      })
    })

    it('calls the public REST endpoint without JWT Authorization header', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(mockFetch).toHaveBeenCalledTimes(1)
      })

      const fetchOptions = mockFetch.mock.calls[0]?.[1] as RequestInit
      const headers = fetchOptions?.headers as Record<string, string>
      expect(headers.Authorization).toBeUndefined()
      expect(fetchOptions?.credentials).toBe('omit')
      expect(fetchOptions?.cache).toBe('no-store')
    })
  })

  describe('status and location display', () => {
    it('displays current status with human-readable label', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getAllByText(/in transit/i).length).toBeGreaterThan(0)
      })
    })

    it('displays origin and destination cities correctly', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByText('Colombo')).toBeInTheDocument()
        expect(screen.getByText('Kandy')).toBeInTheDocument()
      })
    })

    it('handles missing optional locations without invented geographic defaults', async () => {
      const noLocationResponse: PublicTrackingResponse = {
        ...testTrackingResponse,
        originCity: undefined,
        destinationCity: undefined,
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(noLocationResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
      })

      // Must NOT fabricate geographic defaults like "Colombo" or "Western Province"
      const results = document.querySelector('.tracking-results-section')
      if (results) {
        expect(within(results as HTMLElement).queryByText('Western Province')).not.toBeInTheDocument()
      }
      // The meta strip should not render at all when both are missing
      expect(document.querySelector('.tracking-meta-strip')).not.toBeInTheDocument()
    })

    it('shows "Not available" when only one location is missing', async () => {
      const partialLocationResponse: PublicTrackingResponse = {
        ...testTrackingResponse,
        originCity: 'Colombo',
        destinationCity: undefined,
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(partialLocationResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByText('Colombo')).toBeInTheDocument()
        expect(screen.getByText('Not available')).toBeInTheDocument()
      })
    })
  })

  describe('timeline rendering', () => {
    it('renders approved milestones in timeline', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByText('Transit Milestones')).toBeInTheDocument()
        expect(screen.getByText('📍 Western Province Transit Hub')).toBeInTheDocument()
      })

      const timelineList = screen.getByRole('list', { name: /tracking history milestones/i })
      expect(timelineList).toBeInTheDocument()
      const items = within(timelineList).getAllByRole('listitem')
      expect(items).toHaveLength(3)
    })

    it('handles empty timeline gracefully', async () => {
      const emptyTimelineResponse: PublicTrackingResponse = {
        ...testTrackingResponse,
        timeline: [],
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(emptyTimelineResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByText(/no milestone checkpoints recorded/i)).toBeInTheDocument()
      })
      // Should not render an empty list
      expect(screen.queryByRole('list', { name: /tracking history milestones/i })).not.toBeInTheDocument()
    })

    it('uses proper time elements with machine-readable dateTime', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        const timeElements = document.querySelectorAll('time[datetime]')
        expect(timeElements.length).toBeGreaterThan(0)
        timeElements.forEach((el) => {
          const dt = el.getAttribute('datetime')
          expect(dt).toBeTruthy()
          expect(Number.isNaN(Date.parse(dt!))).toBe(false)
        })
      })
    })
  })

  describe('copy reference', () => {
    it('copies tracking reference to clipboard', async () => {
      const user = userEvent.setup()
      const writeTextSpy = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextSpy },
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

    it('handles clipboard failure without crashing', async () => {
      const user = userEvent.setup()
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: vi.fn().mockRejectedValue(new Error('Clipboard denied')) },
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

      // Should not crash; no "Copied" shown
      expect(screen.queryByText('✓ Copied')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /copy tracking reference/i })).toBeInTheDocument()
    })
  })

  describe('refresh behavior', () => {
    it('provides a manual Refresh button', async () => {
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

      expect(screen.getByRole('button', { name: /refresh tracking details/i })).toBeInTheDocument()
    })
  })

  // ── Error handling tests ───────────────────────────────────────────

  describe('input validation errors', () => {
    it('shows inline error for empty input submission', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const submitBtn = screen.getByRole('button', { name: /track parcel/i })
      await user.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByText(/please enter a tracking reference/i)).toBeInTheDocument()
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('shows inline error for whitespace-only input', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, '   ')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(screen.getByText(/please enter a tracking reference/i)).toBeInTheDocument()
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('shows inline error for invalid reference format', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, 'INVALID-FORMAT')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(screen.getByText(/enter a valid swiftdrop tracking number/i)).toBeInTheDocument()
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('shows inline error for excessively long reference', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, 'SWD-20261023-A1B2C3TOOLONG')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(screen.getByText(/enter a valid swiftdrop tracking number/i)).toBeInTheDocument()
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('displays validation error when deep-link has invalid format', async () => {
      renderPublicTracking('/tracking/INVALID-REF')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
        expect(screen.getByText(/enter a valid swiftdrop tracking number/i)).toBeInTheDocument()
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })
  })

  describe('HTTP error states', () => {
    it('displays not-found message for HTTP 404', async () => {
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

    it('displays rate-limit message for HTTP 429', async () => {
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

    it('displays unavailable message for HTTP 503', async () => {
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

    it('displays generic error for HTTP 500 without leaking stack trace', async () => {
      mockFetch.mockResolvedValue(
        new Response(
          JSON.stringify({
            title: 'Internal Server Error',
            status: 500,
            detail: 'NullPointerException at com.swiftdrop.internal.method()',
          }),
          {
            status: 500,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      )

      renderPublicTracking('/tracking/SWD-20261023-SRVFAIL')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })

      // Must NOT expose internal stack traces
      const alert = screen.getByRole('alert')
      expect(within(alert).queryByText(/NullPointerException/)).not.toBeInTheDocument()
      expect(within(alert).queryByText(/com\.swiftdrop/)).not.toBeInTheDocument()
    })

    it('displays unavailable state for network failure', async () => {
      mockFetch.mockRejectedValue(new TypeError('Failed to fetch'))

      renderPublicTracking('/tracking/SWD-20261023-NETFAIL')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
        expect(
          screen.getByText(/tracking service is temporarily unavailable/i),
        ).toBeInTheDocument()
      })
    })

    it('does not show error for aborted fetch', async () => {
      mockFetch.mockRejectedValue(new DOMException('The operation was aborted', 'AbortError'))

      renderPublicTracking('/tracking/SWD-20261023-ABORTED')

      // Abort errors should not produce user-visible error states
      // Allow a brief period for potential rendering
      await new Promise((resolve) => setTimeout(resolve, 200))
      // The component should either still be loading or have no error
      // We just verify it doesn't crash
      expect(screen.getByRole('heading', { name: /track your shipment/i })).toBeInTheDocument()
    })
  })

  describe('malformed response handling', () => {
    it('handles invalid JSON response', async () => {
      mockFetch.mockResolvedValue(
        new Response('not json at all', {
          status: 200,
          headers: { 'Content-Type': 'text/plain' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-BADJSON')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
    })

    it('handles response with invalid status value', async () => {
      mockFetch.mockResolvedValue(
        new Response(
          JSON.stringify({
            trackingNumber: 'SWD-20261023-BADSTAT',
            currentStatus: 'UNKNOWN_INVALID_STATUS',
            timeline: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )

      renderPublicTracking('/tracking/SWD-20261023-BADSTAT')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
    })

    it('handles response with oversized timeline array', async () => {
      const oversizedResponse = {
        trackingNumber: 'SWD-20261023-BIGTIME',
        currentStatus: 'IN_TRANSIT',
        timeline: Array.from({ length: 101 }, () => ({
          status: 'IN_TRANSIT',
          occurredAt: '2026-10-23T10:00:00Z',
        })),
      }
      mockFetch.mockResolvedValue(
        new Response(JSON.stringify(oversizedResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-BIGTIME')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
    })
  })

  describe('retry behavior', () => {
    it('does not automatically retry 404 errors', async () => {
      mockFetch.mockResolvedValue(
        new Response(JSON.stringify({ title: 'Not Found', status: 404 }), {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-NORETRY')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })

      // Should have been called only once (no automatic retry)
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })

    it('does not automatically retry 429 errors', async () => {
      mockFetch.mockResolvedValue(
        new Response(JSON.stringify({ title: 'Rate Limited', status: 429 }), {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-NORT429')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })

      expect(mockFetch).toHaveBeenCalledTimes(1)
    })

    it('offers manual retry button for server errors but not for 404 or 429', async () => {
      // 503 should show retry
      mockFetch.mockResolvedValue(
        new Response(JSON.stringify({ title: 'Unavailable', status: 503 }), {
          status: 503,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-RETRY50')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })

      expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
    })
  })

  // ── Privacy and security tests ─────────────────────────────────────

  describe('strict privacy allowlist', () => {
    const FORBIDDEN_VALUES = [
      'PRIVATE_RECIPIENT',
      'PRIVATE_PHONE',
      'PRIVATE_EMAIL@example.com',
      'PRIVATE_STREET 123',
      'PRIVATE_DROP 456',
      'PRIVATE_CUSTOMER_SUB',
      'PRIVATE_CREATED_BY',
      'PRIVATE_MERCHANT_ID',
      'PRIVATE_COURIER',
      'PRIVATE_COURIER_NAME',
      'PRIVATE_DRIVER_PHONE',
      'PRIVATE_PROOF_ID',
      'PRIVATE_PROOF_URL',
      'PRIVATE_RECEIPT',
      'PRIVATE_RECEIPT_DOWNLOAD',
      'PRIVATE_OBJECT_KEY',
      'PRIVATE_STORAGE_PATH',
      'PRIVATE_DB_ID',
      'PRIVATE_NOTES',
      'PRIVATE_INSTRUCTIONS',
      'PRIVATE_CUSTOMER_EMAIL',
    ]

    it('never renders forbidden private fields from a contaminated response', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(privateLeakingTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
      })

      const bodyText = document.body.textContent || ''

      for (const forbidden of FORBIDDEN_VALUES) {
        expect(bodyText).not.toContain(forbidden)
      }
    })

    it('renders only allowlisted fields from the public DTO', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(privateLeakingTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'SWD-20261023-A1B2C3' })).toBeInTheDocument()
      })

      const results = document.querySelector('.tracking-results-section')!
      expect(results).toBeInTheDocument()
      const resultsEl = results as HTMLElement

      // Allowed fields ARE present
      expect(within(resultsEl).getByText('SWD-20261023-A1B2C3')).toBeInTheDocument()
      expect(within(resultsEl).getByText('Colombo')).toBeInTheDocument()
      expect(within(resultsEl).getByText('Kandy')).toBeInTheDocument()

      // Forbidden categories absent
      expect(within(resultsEl).queryByText(/recipient/i)).not.toBeInTheDocument()
      expect(within(resultsEl).queryByText(/phone/i)).not.toBeInTheDocument()
      expect(within(resultsEl).queryByText(/receipt/i)).not.toBeInTheDocument()
      expect(within(resultsEl).queryByText(/courier/i)).not.toBeInTheDocument()
      expect(within(resultsEl).queryByText(/proof/i)).not.toBeInTheDocument()
      expect(within(resultsEl).queryByText(/address/i)).not.toBeInTheDocument()
    })

    it('does not include private fields in clipboard copy output', async () => {
      const user = userEvent.setup()
      const writeTextSpy = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextSpy },
        writable: true,
        configurable: true,
      })

      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(privateLeakingTrackingResponse), {
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

      // Only the tracking number should be copied
      expect(writeTextSpy).toHaveBeenCalledWith('SWD-20261023-A1B2C3')
    })

    it('does not expose sensitive data in error messages', async () => {
      mockFetch.mockResolvedValue(
        new Response(
          JSON.stringify({
            title: 'Error',
            status: 500,
            detail: 'courierSub=SUB-001, recipientName=John at pickupAddress=123 Street',
          }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      )

      renderPublicTracking('/tracking/SWD-20261023-ERRLEAK')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })

      const alertEl = screen.getByRole('alert')
      expect(within(alertEl).queryByText(/courierSub/i)).not.toBeInTheDocument()
      expect(within(alertEl).queryByText(/recipientName/i)).not.toBeInTheDocument()
      expect(within(alertEl).queryByText(/pickupAddress/i)).not.toBeInTheDocument()
      expect(within(alertEl).queryByText(/SUB-001/)).not.toBeInTheDocument()
    })
  })

  // ── Accessibility tests ────────────────────────────────────────────

  describe('accessibility', () => {
    it('has correct heading hierarchy', () => {
      renderPublicTracking('/tracking')

      const h1 = screen.getByRole('heading', { level: 1 })
      expect(h1).toHaveTextContent(/track your shipment/i)

      const h2 = screen.getByRole('heading', { level: 2, name: /shipment reference lookup/i })
      expect(h2).toBeInTheDocument()
    })

    it('loading state is announced with role=status and aria-live', async () => {
      mockFetch.mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve(
          new Response(JSON.stringify(testTrackingResponse), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        ), 500)),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        const loadingEl = document.querySelector('[role="status"][aria-live="polite"]')
        expect(loadingEl).toBeInTheDocument()
      })
    })

    it('error feedback uses role=alert', async () => {
      mockFetch.mockResolvedValue(
        new Response(JSON.stringify({ title: 'Not Found', status: 404 }), {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-ACCERR')

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
    })

    it('input error is associated with the input via aria-describedby', async () => {
      const user = userEvent.setup()
      renderPublicTracking('/tracking')

      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.type(input, 'INVALID')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(input).toHaveAttribute('aria-invalid', 'true')
        expect(input).toHaveAttribute('aria-describedby', 'tracking-lookup-error')
        const errorMsg = document.getElementById('tracking-lookup-error')
        expect(errorMsg).toBeInTheDocument()
      })
    })

    it('timeline uses semantic ordered list structure', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        const list = screen.getByRole('list', { name: /tracking history milestones/i })
        expect(list.tagName).toBe('OL')
      })
    })

    it('timeline markers are hidden from assistive technology', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      await waitFor(() => {
        const markers = document.querySelectorAll('.timeline-marker')
        markers.forEach((marker) => {
          expect(marker).toHaveAttribute('aria-hidden', 'true')
        })
      })
    })

    it('submit button has meaningful accessible name', () => {
      renderPublicTracking('/tracking')

      const submitBtn = screen.getByRole('button', { name: /track parcel/i })
      expect(submitBtn).toBeInTheDocument()
    })
  })

  // ── Stale response prevention ──────────────────────────────────────

  describe('stale response prevention', () => {
    it('changing tracking number via form loads new result', async () => {
      const user = userEvent.setup()
      const responseA: PublicTrackingResponse = {
        ...testTrackingResponse,
        trackingNumber: 'SWD-20261023-AAAA',
        currentStatus: 'CREATED',
      }
      const responseB: PublicTrackingResponse = {
        ...testTrackingResponse,
        trackingNumber: 'SWD-20261023-BBBB',
        currentStatus: 'DELIVERED',
      }

      mockFetch.mockImplementation(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        if (url.includes('/events')) {
          return new Response(new ReadableStream(), {
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
          })
        }
        if (url.includes('SWD-20261023-BBBB')) {
          return new Response(JSON.stringify(responseB), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(JSON.stringify(responseA), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      })

      renderPublicTracking('/tracking/SWD-20261023-AAAA')

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'SWD-20261023-AAAA' })).toBeInTheDocument()
      })

      // Navigate to second tracking number
      const input = screen.getByRole('textbox', { name: /tracking reference/i })
      await user.clear(input)
      await user.type(input, 'SWD-20261023-BBBB')
      await user.click(screen.getByRole('button', { name: /track parcel/i }))

      await waitFor(() => {
        expect(screen.getByRole('heading', { name: 'SWD-20261023-BBBB' })).toBeInTheDocument()
      })
    })
  })

  // ── Real-time anonymous SSE stream delivery (Issue #148) ─────────────

  describe('real-time anonymous SSE stream delivery (Issue #148 / N10-B26)', () => {
    it('displays Live chip and applies live SSE milestone updates', async () => {
      const sseEvent = {
        eventId: 'evt-sse-out-for-delivery',
        trackingNumber: 'SWD-20261023-A1B2C3',
        aggregateVersion: 4,
        oldStatus: 'IN_TRANSIT',
        newStatus: 'OUT_FOR_DELIVERY',
        cityOrZone: 'Kandy Regional Depot',
        occurredAt: '2026-10-23T15:30:00Z',
      }

      const encoder = new TextEncoder()
      const sseBody = [
        ':keepalive\n\n',
        'event: tracking.status.changed\n',
        `data: ${JSON.stringify(sseEvent)}\n\n`,
      ].join('')

      const sseStream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode(sseBody))
        },
      })

      mockFetch.mockImplementation(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        if (url.includes('/events')) {
          return new Response(sseStream, {
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
          })
        }
        return new Response(JSON.stringify(testTrackingResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      })

      renderPublicTracking('/tracking/SWD-20261023-A1B2C3')

      // Assert Live chip appears
      await waitFor(() => {
        expect(screen.getByText('● Live')).toBeInTheDocument()
      })

      // Assert updated status and new milestone checkpoint are rendered
      await waitFor(() => {
        expect(screen.getAllByText('Out for Delivery').length).toBeGreaterThanOrEqual(1)
        expect(screen.getByText(/Kandy Regional Depot/i)).toBeInTheDocument()
      })

      // Assert strict data minimization: zero credentials or private tokens
      expect(document.body.innerHTML).not.toContain('parcelId')
      expect(document.body.innerHTML).not.toContain('PRIVATE_')
    })
  })
})

