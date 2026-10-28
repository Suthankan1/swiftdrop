import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useParcelTrackingStream } from './useParcelTrackingStream'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import type { ParcelLifecycleSseEvent } from '../api/trackingStreamContracts'

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

const fastReconnectConfig = {
  initialDelayMs: 20,
  maxDelayMs: 80,
  backoffMultiplier: 1.5,
  maxRetries: 2,
}

describe('useParcelTrackingStream', () => {
  const parcelId = testParcel.parcelId
  const subject = 'customer-sub-1'

  it('connects to SSE stream and establishes connected status', async () => {
    const mockFetch = vi.fn().mockResolvedValue(createMockStream([':keepalive\n\n']))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(result.current.parcel?.currentStatus).toBe('CREATED')
    expect(result.current.parcel?.version).toBe(1)
  })

  it('applies sequential in-order events and updates visible parcel status and version', async () => {
    const event1: ParcelLifecycleSseEvent = {
      eventId: 'evt-101',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 2,
      oldStatus: 'CREATED',
      newStatus: 'PICKED_UP',
      reason: null,
      occurredAt: '2026-10-27T09:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue(createMockStream([makeSseChunk(event1)]))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.parcel?.currentStatus).toBe('PICKED_UP')
    })

    expect(result.current.parcel?.version).toBe(2)
    expect(result.current.streamState.lastDeliveredVersion).toBe(2)
    expect(result.current.streamState.lastEventId).toBe('evt-101')
  })

  it('rejects and drops stale or duplicate events where aggregateVersion <= currentVersion', async () => {
    const staleEvent: ParcelLifecycleSseEvent = {
      eventId: 'evt-stale',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 1, // already at version 1
      oldStatus: null,
      newStatus: 'CREATED',
      reason: null,
      occurredAt: '2026-10-27T08:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue(createMockStream([makeSseChunk(staleEvent)]))
    const mockReconcile = vi.fn()

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: mockReconcile,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(result.current.parcel?.currentStatus).toBe('CREATED')
    expect(result.current.parcel?.version).toBe(1)
    expect(mockReconcile).not.toHaveBeenCalled()
  })

  it('detects version gaps (aggregateVersion > currentVersion + 1) and triggers immediate REST recovery', async () => {
    const gapEvent: ParcelLifecycleSseEvent = {
      eventId: 'evt-gap',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 4,
      oldStatus: 'IN_TRANSIT',
      newStatus: 'OUT_FOR_DELIVERY',
      reason: null,
      occurredAt: '2026-10-27T11:00:00Z',
    }

    const freshRestParcel: ParcelDetailResponse = {
      ...testParcel,
      currentStatus: 'OUT_FOR_DELIVERY',
      version: 4,
    }

    const mockFetch = vi.fn().mockResolvedValue(createMockStream([makeSseChunk(gapEvent)]))
    const mockReconcile = vi.fn().mockResolvedValue(freshRestParcel)

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: mockReconcile,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.parcel?.currentStatus).toBe('OUT_FOR_DELIVERY')
    })

    expect(mockReconcile).toHaveBeenCalledTimes(1)
    expect(result.current.parcel?.version).toBe(4)
  })

  it('reconnects with exponential backoff on temporary network disruption and refreshes REST', async () => {
    let callCount = 0
    const mockReconcile = vi.fn().mockResolvedValue({
      ...testParcel,
      currentStatus: 'IN_TRANSIT',
      version: 2,
    })

    const mockFetch = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.reject(new TypeError('Failed to fetch (network error)'))
      }
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: mockReconcile,
        reconnectConfig: fastReconnectConfig,
        randomFn: () => 0.5,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(mockFetch).toHaveBeenCalledTimes(2)
    expect(mockReconcile).toHaveBeenCalledTimes(1)
    expect(result.current.parcel?.currentStatus).toBe('IN_TRANSIT')
  })


  it('stops retrying and transitions to error when retry limit is reached', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new TypeError('Network connection lost'))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        reconnectConfig: {
          initialDelayMs: 15,
          maxDelayMs: 40,
          backoffMultiplier: 1.5,
          maxRetries: 2,
        },
        randomFn: () => 0.5,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('error')
    })

    expect(result.current.streamState.errorMessage).toContain('recovery limit reached')
    expect(result.current.parcel).toEqual(testParcel) // preserved existing state
  })

  it('allows user to manually trigger retry after recovery failure', async () => {
    let shouldSucceed = false
    const mockReconcile = vi.fn().mockResolvedValue(testParcel)

    const mockFetch = vi.fn().mockImplementation(() => {
      if (!shouldSucceed) {
        return Promise.reject(new Error('Connection failure'))
      }
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: mockReconcile,
        reconnectConfig: {
          initialDelayMs: 10,
          maxDelayMs: 20,
          backoffMultiplier: 1.5,
          maxRetries: 1,
        },
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('error')
    })

    shouldSucceed = true
    result.current.retry()

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(result.current.streamState.retryAttempt).toBe(0)
  })

  it('refreshes session and reconnects on 401 Unauthorized', async () => {
    let callCount = 0
    let token = 'expired-token'
    const handleSessionExpiry = vi.fn().mockImplementation(async () => {
      token = 'refreshed-token'
    })

    const mockFetch = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.resolve(new Response('Unauthorized', { status: 401 }))
      }
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => token,
        handleSessionExpiry,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(handleSessionExpiry).toHaveBeenCalledTimes(1)
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('halts retries and transitions to auth-required when session refresh fails', async () => {
    const handleSessionExpiry = vi.fn().mockRejectedValue(new Error('Refresh failed'))
    const mockFetch = vi.fn().mockResolvedValue(new Response('Unauthorized', { status: 401 }))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'expired-token',
        handleSessionExpiry,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('auth-required')
    })

    expect(result.current.streamState.errorMessage).toContain('Session expired')
    expect(mockFetch).toHaveBeenCalledTimes(1) // does not storm server
  })

  it('stops retrying immediately on 403 Forbidden (cross-customer access denial)', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Forbidden', { status: 403 }))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('forbidden')
    })

    expect(result.current.streamState.errorMessage).toContain('Access denied')
    expect(mockFetch).toHaveBeenCalledTimes(1) // never retries 403
  })

  it('terminates active stream when customer logs out or subject changes', async () => {
    let wasAborted = false
    const mockFetch = vi.fn().mockImplementation((_url, init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => {
        wasAborted = true
      })
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { rerender } = renderHook(
      ({ currentSubject }) =>
        useParcelTrackingStream({
          parcelId,
          subject: currentSubject,
          initialParcel: testParcel,
          getAccessToken: async () => 'valid-jwt-token',
          reconcileRest: vi.fn(),
          fetch: mockFetch,
        }),
      {
        initialProps: { currentSubject: 'customer-sub-1' as string | null },
      },
    )

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })

    rerender({ currentSubject: null })
    expect(wasAborted).toBe(true)
  })

  it('reconnects when tab returns to foreground if stream was interrupted', async () => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    let callCount = 0
    const mockFetch = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.reject(new Error('Network drop'))
      }
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        reconnectConfig: {
          initialDelayMs: 5000,
          maxDelayMs: 10000,
          backoffMultiplier: 2,
          maxRetries: 2,
        },
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('reconnecting')
    })

    // Tab returns to foreground
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(mockFetch).toHaveBeenCalledTimes(2)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })

  it('pauses when browser goes offline and reconnects when browser returns online', async () => {
    const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([':keepalive\n\n'])))

    const { result } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    // Browser goes offline
    window.dispatchEvent(new Event('offline'))
    await waitFor(() => {
      expect(result.current.streamState.status).toBe('disconnected')
      expect(result.current.streamState.isOffline).toBe(true)
    })

    // Browser returns online
    window.dispatchEvent(new Event('online'))
    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
      expect(result.current.streamState.isOffline).toBe(false)
    })
  })

  it('handles back-forward cache restoration (pageshow persisted)', async () => {
    const mockReconcile = vi.fn().mockResolvedValue(testParcel)
    const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([':keepalive\n\n'])))

    renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: mockReconcile,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })

    // Simulate bfcache restore
    const pageShowEvent = new Event('pageshow') as PageTransitionEvent
    Object.defineProperty(pageShowEvent, 'persisted', { value: true })
    window.dispatchEvent(pageShowEvent)

    await waitFor(() => {
      expect(mockReconcile).toHaveBeenCalled()
      expect(mockFetch).toHaveBeenCalledTimes(2)
    })
  })

  it('cleans up stream on unmount', async () => {
    let wasAborted = false
    const mockFetch = vi.fn().mockImplementation((_url, init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => {
        wasAborted = true
      })
      return Promise.resolve(createMockStream([':keepalive\n\n']))
    })

    const { unmount } = renderHook(() =>
      useParcelTrackingStream({
        parcelId,
        subject,
        initialParcel: testParcel,
        getAccessToken: async () => 'valid-jwt-token',
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })
    expect(wasAborted).toBe(false)

    unmount()
    expect(wasAborted).toBe(true)
  })
})
