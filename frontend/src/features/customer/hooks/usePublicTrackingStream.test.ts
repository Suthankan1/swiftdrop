import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { HIDDEN_STREAM_GRACE_MS, usePublicTrackingStream } from './usePublicTrackingStream'
import type { PublicTrackingResponse } from '../api/deliveryContracts'
import type { PublicTrackingSseEvent } from '../api/trackingStreamContracts'

const testTracking: PublicTrackingResponse = {
  trackingNumber: 'SWD-20261023-A1B2C3',
  currentStatus: 'CREATED',
  originCity: 'Colombo',
  destinationCity: 'Gampaha',
  timeline: [
    {
      status: 'CREATED',
      occurredAt: '2026-10-23T08:00:00Z',
      cityOrZone: 'Colombo Central Sorting Hub',
    },
  ],
}

function makePublicSseChunk(event: PublicTrackingSseEvent): string {
  return [
    `id: ${event.eventId}\n`,
    'event: parcel.status.changed\n',
    `data: ${JSON.stringify(event)}\n\n`,
  ].join('')
}

function createMockStream(chunks: string[] = [], { close = false }: { close?: boolean } = {}): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      if (close) controller.close()
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

describe('usePublicTrackingStream', () => {
  const trackingNumber = testTracking.trackingNumber

  it('connects to public SSE stream anonymously and reaches connected status', async () => {
    let capturedUrl = ''
    let capturedHeaders: HeadersInit | undefined
    let capturedCredentials: RequestCredentials | undefined

    const mockFetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      capturedUrl = url
      capturedHeaders = init?.headers
      capturedCredentials = init?.credentials
      return Promise.resolve(createMockStream([]))
    })

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('connected')
    })

    expect(capturedUrl).toContain(`/api/v1/tracking/${trackingNumber}/events`)
    expect(capturedCredentials).toBe('omit')
    const headers = capturedHeaders as Record<string, string>
    expect(headers['Authorization']).toBeUndefined()
    expect(headers['Accept']).toBe('text/event-stream')
  })

  it('applies live milestone and updates tracking timeline and currentStatus', async () => {
    const liveEvent: PublicTrackingSseEvent = {
      eventId: 'evt-pickup-2',
      trackingNumber,
      aggregateVersion: 2,
      oldStatus: 'CREATED',
      newStatus: 'PICKED_UP',
      cityOrZone: 'Colombo Sorting Hub',
      occurredAt: '2026-10-23T10:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue(createMockStream([makePublicSseChunk(liveEvent)]))

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.tracking?.currentStatus).toBe('PICKED_UP')
      expect(result.current.tracking?.timeline).toHaveLength(2)
      expect(result.current.tracking?.timeline[1]?.status).toBe('PICKED_UP')
      expect(result.current.tracking?.timeline[1]?.cityOrZone).toBe('Colombo Sorting Hub')
      expect(result.current.streamState.lastDeliveredVersion).toBe(2)
    })
  })

  it('drops duplicate and stale events without modifying timeline', async () => {
    const liveEventV2: PublicTrackingSseEvent = {
      eventId: 'evt-2',
      trackingNumber,
      aggregateVersion: 2,
      oldStatus: 'CREATED',
      newStatus: 'PICKED_UP',
      cityOrZone: 'Colombo Sorting Hub',
      occurredAt: '2026-10-23T10:00:00Z',
    }
    const duplicateV2: PublicTrackingSseEvent = {
      ...liveEventV2,
      eventId: 'evt-2-dup',
    }

    const mockFetch = vi.fn().mockResolvedValue(
      createMockStream([makePublicSseChunk(liveEventV2), makePublicSseChunk(duplicateV2)]),
    )

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.tracking?.currentStatus).toBe('PICKED_UP')
      expect(result.current.tracking?.timeline).toHaveLength(2)
    })
  })

  it('triggers canonical REST reconciliation when a version gap is detected', async () => {
    // Gap: version jumps from 0 (baseline) to 3 (missing 2)
    const gapEvent: PublicTrackingSseEvent = {
      eventId: 'evt-gap-3',
      trackingNumber,
      aggregateVersion: 3,
      oldStatus: 'PICKED_UP',
      newStatus: 'IN_TRANSIT',
      cityOrZone: 'Western Province Sorting Hub',
      occurredAt: '2026-10-23T12:00:00Z',
    }

    const canonicalRestRefreshed: PublicTrackingResponse = {
      ...testTracking,
      currentStatus: 'IN_TRANSIT',
      timeline: [
        ...testTracking.timeline,
        {
          status: 'PICKED_UP',
          occurredAt: '2026-10-23T10:00:00Z',
          cityOrZone: 'Colombo Sorting Hub',
        },
        {
          status: 'IN_TRANSIT',
          occurredAt: '2026-10-23T12:00:00Z',
          cityOrZone: 'Western Province Sorting Hub',
        },
      ],
    }

    const reconcileRest = vi.fn().mockResolvedValue(canonicalRestRefreshed)

    // First deliver version 1 so lastVersion becomes 1
    const eventV1: PublicTrackingSseEvent = {
      eventId: 'evt-1',
      trackingNumber,
      aggregateVersion: 1,
      oldStatus: null,
      newStatus: 'CREATED',
      cityOrZone: 'Colombo Central Sorting Hub',
      occurredAt: '2026-10-23T08:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue(
      createMockStream([makePublicSseChunk(eventV1), makePublicSseChunk(gapEvent)]),
    )

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(reconcileRest).toHaveBeenCalledTimes(1)
      expect(result.current.tracking?.timeline).toHaveLength(3)
      expect(result.current.tracking?.currentStatus).toBe('IN_TRANSIT')
    })
  })

  it('transitions immediately to error on terminal 404 without retrying', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Not Found', { status: 404 }))

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest: vi.fn(),
        reconnectConfig: fastReconnectConfig,
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(result.current.streamState.status).toBe('error')
      expect(result.current.streamState.retryAttempt).toBe(0)
    })
  })

  it('aborts active connection cleanly upon unmount', async () => {
    let capturedSignal: AbortSignal | null | undefined
    const mockFetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      capturedSignal = init?.signal
      return Promise.resolve(createMockStream([]))
    })

    const { unmount } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest: vi.fn(),
        fetch: mockFetch,
      }),
    )

    await waitFor(() => {
      expect(capturedSignal).toBeDefined()
    })

    unmount()
    expect(capturedSignal?.aborted).toBe(true)
  })

  it('reconnects after a clean server close and re-reads the canonical tracking state', async () => {
    const mockFetch = vi.fn()
      .mockResolvedValueOnce(createMockStream([], { close: true }))
      .mockResolvedValue(createMockStream([]))
    const refreshed: PublicTrackingResponse = { ...testTracking, currentStatus: 'IN_TRANSIT' }
    const reconcileRest = vi.fn().mockResolvedValue(refreshed)

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber,
        initialTracking: testTracking,
        reconcileRest,
        fetch: mockFetch,
        reconnectConfig: { initialDelayMs: 10, maxDelayMs: 20, backoffMultiplier: 1.5, maxRetries: 2 },
        randomFn: () => 0,
      }),
    )

    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(result.current.tracking?.currentStatus).toBe('IN_TRANSIT'))
    expect(result.current.streamState.status).toBe('connected')
  })

  it('lets a fresh REST snapshot (Refresh) replace a stale live status', async () => {
    const event: PublicTrackingSseEvent = {
      eventId: 'evt-1', trackingNumber, aggregateVersion: 2, oldStatus: 'CREATED', newStatus: 'PICKED_UP',
      occurredAt: '2026-10-23T09:00:00Z', cityOrZone: null,
    }
    const mockFetch = vi.fn().mockResolvedValue(createMockStream([makePublicSseChunk(event)]))

    const { result, rerender } = renderHook(
      ({ initial }) =>
        usePublicTrackingStream({ trackingNumber, initialTracking: initial, reconcileRest: vi.fn(), fetch: mockFetch }),
      { initialProps: { initial: testTracking } },
    )

    await waitFor(() => expect(result.current.tracking?.currentStatus).toBe('PICKED_UP'))
    const refreshed: PublicTrackingResponse = { ...testTracking, currentStatus: 'DELIVERED' }
    rerender({ initial: refreshed })
    await waitFor(() => expect(result.current.tracking?.currentStatus).toBe('DELIVERED'))
  })

  it('never loops when the server keeps opening and immediately closing the stream', async () => {
    const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([], { close: true })))
    const config = { initialDelayMs: 10, maxDelayMs: 20, backoffMultiplier: 1.5, maxRetries: 2 }

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber, initialTracking: testTracking, reconcileRest: vi.fn().mockResolvedValue(null),
        fetch: mockFetch, reconnectConfig: config, randomFn: () => 0,
      }),
    )

    await waitFor(() => expect(result.current.streamState.status).toBe('error'))
    await new Promise(resolve => setTimeout(resolve, 200))
    expect(mockFetch).toHaveBeenCalledTimes(config.maxRetries + 1)
  })

  it('does not reopen the stream once the parcel is delivered', async () => {
    const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([], { close: true })))
    const delivered: PublicTrackingResponse = { ...testTracking, currentStatus: 'DELIVERED' } // stable, like React Query data

    const { result } = renderHook(() =>
      usePublicTrackingStream({
        trackingNumber, initialTracking: delivered, reconcileRest: vi.fn(),
        fetch: mockFetch, reconnectConfig: { initialDelayMs: 10, maxDelayMs: 20, backoffMultiplier: 1.5, maxRetries: 2 },
      }),
    )

    await waitFor(() => expect(result.current.streamState.status).toBe('idle'))
    await new Promise(resolve => setTimeout(resolve, 200))
    expect(mockFetch).not.toHaveBeenCalled() // finished parcels never open a stream
  })

  describe('tab visibility', () => {
    const setHidden = (hidden: boolean) => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
      document.dispatchEvent(new Event('visibilitychange'))
    }

    it('keeps the stream through short hide/show flips without new requests', async () => {
      const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([])))
      const { result, unmount } = renderHook(() =>
        usePublicTrackingStream({ trackingNumber, initialTracking: testTracking, reconcileRest: vi.fn(), fetch: mockFetch }),
      )
      await waitFor(() => expect(result.current.streamState.status).toBe('connected'))

      for (let i = 0; i < 5; i++) {
        setHidden(true)
        setHidden(false)
      }

      expect(mockFetch).toHaveBeenCalledTimes(1)
      expect(result.current.streamState.status).toBe('connected')
      unmount()
      setHidden(false)
    })

    it('releases the stream after a long hide and reconnects once when shown again', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        const mockFetch = vi.fn().mockImplementation(() => Promise.resolve(createMockStream([])))
        const reconcileRest = vi.fn().mockResolvedValue(testTracking)
        const { result, unmount } = renderHook(() =>
          usePublicTrackingStream({ trackingNumber, initialTracking: testTracking, reconcileRest, fetch: mockFetch }),
        )
        await waitFor(() => expect(result.current.streamState.status).toBe('connected'))

        setHidden(true)
        await vi.advanceTimersByTimeAsync(HIDDEN_STREAM_GRACE_MS + 1)
        await waitFor(() => expect(result.current.streamState.status).toBe('idle'))

        setHidden(false)
        await waitFor(() => expect(result.current.streamState.status).toBe('connected'))
        expect(mockFetch).toHaveBeenCalledTimes(2)
        expect(reconcileRest).toHaveBeenCalled() // missed events while away are re-read
        unmount()
      } finally {
        setHidden(false)
        vi.useRealTimers()
      }
    })
  })
})
