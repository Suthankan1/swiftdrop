import { describe, expect, it, vi } from 'vitest'
import {
  connectParcelTrackingStream,
  StreamHttpError,
} from './trackingStreamClient'
import type { ParcelLifecycleSseEvent } from './trackingStreamContracts'

function createSseStreamResponse(chunks: string[], status = 200, statusText = 'OK'): Response {
  const encoder = new TextEncoder()
  let index = 0

  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index]))
        index++
      } else {
        controller.close()
      }
    },
  })

  return new Response(stream, {
    status,
    statusText,
    headers: {
      'Content-Type': 'text/event-stream',
    },
  })
}

describe('connectParcelTrackingStream', () => {
  const parcelId = '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d'
  const mockToken = 'mock-keycloak-token-xyz'

  it('connects via fetch with Authorization header and never exposes token in URL', async () => {
    let capturedUrl = ''
    let capturedHeaders: HeadersInit | undefined

    const mockFetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      capturedUrl = url
      capturedHeaders = init?.headers
      return Promise.resolve(createSseStreamResponse([]))
    })

    const controller = new AbortController()

    await connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      gatewayUrl: 'https://gateway.swiftdrop.internal',
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: vi.fn(),
    })

    expect(capturedUrl).toBe(`https://gateway.swiftdrop.internal/api/v1/parcels/${parcelId}/events`)
    expect(capturedUrl).not.toContain(mockToken)
    expect(capturedUrl).not.toContain('token=')
    expect(capturedUrl).not.toContain('access_token=')

    const headers = capturedHeaders as Record<string, string>
    expect(headers['Authorization']).toBe(`Bearer ${mockToken}`)
    expect(headers['Accept']).toBe('text/event-stream')
  })

  it('parses valid SSE messages and dispatches onEvent', async () => {
    const rawEventPayload: ParcelLifecycleSseEvent = {
      eventId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 2,
      oldStatus: 'CREATED',
      newStatus: 'PICKED_UP',
      reason: null,
      occurredAt: '2026-10-27T09:15:30Z',
    }

    const sseChunk = [
      ':keepalive\n\n',
      `id: ${rawEventPayload.eventId}\n`,
      'event: parcel.status.changed\n',
      `data: ${JSON.stringify(rawEventPayload)}\n\n`,
    ].join('')

    const mockFetch = vi.fn().mockResolvedValue(createSseStreamResponse([sseChunk]))
    const receivedEvents: ParcelLifecycleSseEvent[] = []
    const onHeartbeat = vi.fn()
    const onConnected = vi.fn()

    const controller = new AbortController()

    await connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: evt => receivedEvents.push(evt),
      onHeartbeat,
      onConnected,
    })

    expect(onConnected).toHaveBeenCalledTimes(1)
    expect(onHeartbeat).toHaveBeenCalledTimes(1)
    expect(receivedEvents).toHaveLength(1)
    expect(receivedEvents[0]).toEqual(rawEventPayload)
  })

  it('safely handles chunks split across TCP packet boundaries', async () => {
    const rawEventPayload: ParcelLifecycleSseEvent = {
      eventId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 3,
      oldStatus: 'PICKED_UP',
      newStatus: 'IN_TRANSIT',
      reason: null,
      occurredAt: '2026-10-27T10:00:00Z',
    }

    const jsonStr = JSON.stringify(rawEventPayload)
    const part1 = jsonStr.slice(0, 30)
    const part2 = jsonStr.slice(30)

    const chunks = [
      'id: 123\nevent: parcel.',
      'status.changed\ndata: ',
      part1,
      part2,
      '\n\n',
    ]

    const mockFetch = vi.fn().mockResolvedValue(createSseStreamResponse(chunks))
    const receivedEvents: ParcelLifecycleSseEvent[] = []
    const controller = new AbortController()

    await connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: evt => receivedEvents.push(evt),
    })

    expect(receivedEvents).toHaveLength(1)
    expect(receivedEvents[0]?.newStatus).toBe('IN_TRANSIT')
    expect(receivedEvents[0]?.aggregateVersion).toBe(3)
  })

  it('safely drops malformed or invalid events without breaking subsequent events', async () => {
    const validEvent: ParcelLifecycleSseEvent = {
      eventId: '8d0e7780-8536-41ef-055c-f18fd2f01bf8',
      parcelId,
      trackingNumber: 'SWD-20261027-A1B2',
      aggregateVersion: 4,
      oldStatus: 'IN_TRANSIT',
      newStatus: 'DELIVERED',
      reason: null,
      occurredAt: '2026-10-27T12:00:00Z',
    }

    const chunks = [
      'data: { not valid json\n\n',
      'data: {"missingFields": true}\n\n',
      `data: ${JSON.stringify(validEvent)}\n\n`,
    ]

    const mockFetch = vi.fn().mockResolvedValue(createSseStreamResponse(chunks))
    const receivedEvents: ParcelLifecycleSseEvent[] = []
    const controller = new AbortController()

    await connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: evt => receivedEvents.push(evt),
    })

    expect(receivedEvents).toHaveLength(1)
    expect(receivedEvents[0]?.aggregateVersion).toBe(4)
    expect(receivedEvents[0]?.newStatus).toBe('DELIVERED')
  })

  it('throws StreamHttpError(401) on unauthorized response', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Unauthorized', { status: 401 }))
    const controller = new AbortController()

    await expect(
      connectParcelTrackingStream({
        parcelId,
        getAccessToken: async () => mockToken,
        fetch: mockFetch,
        signal: controller.signal,
        onEvent: vi.fn(),
      }),
    ).rejects.toThrow(StreamHttpError)
  })

  it('throws StreamHttpError(403) on cross-customer access denial', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Forbidden', { status: 403 }))
    const controller = new AbortController()

    const promise = connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: vi.fn(),
    })

    await expect(promise).rejects.toMatchObject({ statusCode: 403 })
  })

  it('throws StreamHttpError(429) when rate-limited / connection limit exceeded', async () => {
    const mockFetch = vi.fn().mockResolvedValue(new Response('Too Many Requests', { status: 429 }))
    const controller = new AbortController()

    const promise = connectParcelTrackingStream({
      parcelId,
      getAccessToken: async () => mockToken,
      fetch: mockFetch,
      signal: controller.signal,
      onEvent: vi.fn(),
    })

    await expect(promise).rejects.toMatchObject({ statusCode: 429 })
  })

  it('fails immediately if getAccessToken returns null or empty string', async () => {
    const controller = new AbortController()

    await expect(
      connectParcelTrackingStream({
        parcelId,
        getAccessToken: async () => null,
        signal: controller.signal,
        onEvent: vi.fn(),
      }),
    ).rejects.toThrow(StreamHttpError)
  })
})
