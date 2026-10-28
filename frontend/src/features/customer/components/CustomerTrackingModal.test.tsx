import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import type { ParcelLifecycleSseEvent } from '../api/trackingStreamContracts'
import { CustomerTrackingModal } from './CustomerTrackingModal'

const testParcel: ParcelDetailResponse = {
  parcelId: '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
  trackingNumber: 'SWD-20261027-A1B2',
  createdBySub: 'customer-sub-1',
  merchantId: null,
  currentStatus: 'CREATED',
  version: 1,
  weightKg: 2.5,
  dimensions: '30x20x15',
  amountMinorUnits: 45000,
  currency: 'LKR',
  rateVersion: 'v1.0.0',
  pickupAddress: '123 Galle Rd, Colombo 03',
  dropoffAddress: '45 Kandy Rd, Gampaha',
  activeCourierSub: null,
  createdAt: '2026-10-27T08:00:00Z',
}

function makeSseChunk(event: ParcelLifecycleSseEvent): string {
  return [
    `id: ${event.eventId}\n`,
    'event: parcel.status.changed\n',
    `data: ${JSON.stringify(event)}\n\n`,
  ].join('')
}

function createMockStream(chunks: string[] = []): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function authFixture(subject = 'customer-sub-1'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles: ['CUSTOMER'] },
    getAccessToken: async () => 'test-bearer-token',
  }
}

describe('CustomerTrackingModal SSE Recovery and Real-Time Experience (Issue #144)', () => {
  const mockDeliveryApi = {
    getParcel: vi.fn().mockResolvedValue(testParcel),
    listDeliveries: vi.fn(),
    downloadReceipt: vi.fn(),
  }

  it('renders initial parcel details and establishes live SSE stream', async () => {
    const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([':keepalive\n\n'])))

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
        />
      </TestProviders>,
    )

    // Wait for initial REST data to populate
    expect(await screen.findByRole('heading', { name: 'SWD-20261027-A1B2' })).toBeInTheDocument()
    expect(screen.getAllByText('Created').length).toBeGreaterThan(0)
    expect(screen.getByText('123 Galle Rd, Colombo 03')).toBeInTheDocument()

    // Verify SSE connects and renders live indicator
    await waitFor(() => {
      expect(screen.getByText('Live stream active')).toBeInTheDocument()
      expect(screen.getByText('● Live')).toBeInTheDocument()
    })
  })

  it('updates modal state and milestones in real time upon receiving SSE event', async () => {
    const eventPickedUp: ParcelLifecycleSseEvent = {
      eventId: 'evt-202',
      parcelId: testParcel.parcelId,
      trackingNumber: testParcel.trackingNumber,
      aggregateVersion: 2,
      oldStatus: 'CREATED',
      newStatus: 'PICKED_UP',
      reason: null,
      occurredAt: '2026-10-27T09:00:00Z',
    }

    const mockFetch = vi.fn().mockImplementation(() =>
      Promise.resolve(createMockStream([makeSseChunk(eventPickedUp)])),
    )

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
        />
      </TestProviders>,
    )

    // Initially Created
    expect(await screen.findByRole('heading', { name: 'SWD-20261027-A1B2' })).toBeInTheDocument()
    expect(screen.getAllByText('Created').length).toBeGreaterThan(0)

    // Live event arrives and updates status pill to Picked Up
    await waitFor(() => {
      expect(screen.getAllByText('Picked Up').length).toBeGreaterThan(0)
    })
  })

  it('displays reconnecting status and preserves existing parcel details on network interruption', async () => {
    const mockFetch = vi.fn().mockImplementation(() =>
      Promise.reject(new TypeError('Network connection failed')),
    )

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
          reconnectConfig={{
            initialDelayMs: 200,
            maxDelayMs: 500,
            backoffMultiplier: 1.5,
            maxRetries: 3,
          }}
        />
      </TestProviders>,
    )

    // Existing parcel remains visible
    expect(await screen.findByRole('heading', { name: 'SWD-20261027-A1B2' })).toBeInTheDocument()
    expect(screen.getByText('123 Galle Rd, Colombo 03')).toBeInTheDocument()

    // Reconnecting feedback is displayed
    await waitFor(() => {
      expect(screen.getByText(/Interrupted — reconnecting/i)).toBeInTheDocument()
    })
  })

  it('displays session expired message on 401 Unauthorized when session cannot be refreshed', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Unauthorized', { status: 401 }))
    const handleSessionExpiry = vi.fn().mockRejectedValue(new Error('Refresh failed'))

    const auth = {
      ...authFixture(),
      handleSessionExpiry,
    }

    render(
      <TestProviders auth={auth}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
        />
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.getByText(/Authentication expired\. Please sign in again\./i)).toBeInTheDocument()
    })
  })

  it('displays access denied message on 403 Forbidden cross-customer check', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Forbidden', { status: 403 }))

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
        />
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.getByText(/Access denied to parcel live stream\./i)).toBeInTheDocument()
    })
  })

  it('displays manual retry button when recovery limit reached and allows customer to retry', async () => {
    let shouldSucceed = false
    const mockFetch = vi.fn().mockImplementation(() => {
      if (!shouldSucceed) {
        return Promise.reject(new Error('Downstream stream unavailable'))
      }
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const user = userEvent.setup()

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={vi.fn()}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
          fetch={mockFetch}
          reconnectConfig={{
            initialDelayMs: 10,
            maxDelayMs: 20,
            backoffMultiplier: 1.5,
            maxRetries: 1,
          }}
        />
      </TestProviders>,
    )

    // Recovery limit is reached
    const retryBtn = await screen.findByRole('button', { name: /retry live updates connection/i })
    expect(retryBtn).toBeInTheDocument()
    expect(screen.getByText(/Automatic recovery limit reached/i)).toBeInTheDocument()

    // Customer clicks retry
    shouldSucceed = true
    await user.click(retryBtn)

    // Stream successfully reconnects
    await waitFor(() => {
      expect(screen.getByText('Live stream active')).toBeInTheDocument()
    })
  })

  it('closes modal on Escape key or close button click', async () => {
    const handleClose = vi.fn()
    const user = userEvent.setup()

    render(
      <TestProviders auth={authFixture()}>
        <CustomerTrackingModal
          parcelId={testParcel.parcelId}
          isOpen={true}
          onClose={handleClose}
          deliveryApi={mockDeliveryApi as unknown as ReturnType<typeof import('../api/delivery').createDeliveryApi>}
          onDownloadReceipt={vi.fn()}
          isDownloadingReceipt={false}
        />
      </TestProviders>,
    )

    const closeBtn = await screen.findByRole('button', { name: /close tracking details modal/i })
    await user.click(closeBtn)
    expect(handleClose).toHaveBeenCalledTimes(1)
  })
})
