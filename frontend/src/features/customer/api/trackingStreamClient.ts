import {
  isParcelLifecycleSseEvent,
  type ParcelLifecycleSseEvent,
} from './trackingStreamContracts'

export class StreamHttpError extends Error {
  readonly statusCode: number

  constructor(statusCode: number, message?: string) {
    super(message || `SSE stream connection failed with HTTP status ${statusCode}.`)
    this.name = 'StreamHttpError'
    this.statusCode = statusCode
  }
}

export interface ConnectTrackingStreamOptions {
  parcelId: string
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  signal: AbortSignal
  onEvent: (event: ParcelLifecycleSseEvent) => void
  onHeartbeat?: () => void
  onConnected?: () => void
}

/**
 * Establishes an authenticated fetch-based Server-Sent Events (SSE) stream for a parcel.
 * Uses HTTP Bearer Authorization header; strictly avoids exposing tokens in query parameters or URLs.
 * Conforms to docs/contracts/SSE_REST_RECOVERY.md and ParcelSseController.java:
 * GET /api/v1/parcels/{parcelId}/events (Accept: text/event-stream)
 */
export async function connectParcelTrackingStream({
  parcelId,
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
  signal,
  onEvent,
  onHeartbeat,
  onConnected,
}: ConnectTrackingStreamOptions): Promise<void> {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')
  const token = await getAccessToken()

  if (!token?.trim()) {
    throw new StreamHttpError(401, 'Authentication token is required to connect to parcel event stream.')
  }

  const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/events`

  let response: Response
  try {
    response = await fetchRequest(url, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${token.trim()}`,
        'Cache-Control': 'no-cache',
      },
      credentials: 'omit',
      cache: 'no-store',
      signal,
    })
  } catch (err) {
    if (signal.aborted) return
    throw err
  }

  if (response.status === 401) {
    throw new StreamHttpError(401, 'Session expired or authentication invalid.')
  }
  if (response.status === 403) {
    throw new StreamHttpError(403, 'Cross-customer access denied. You are not authorized to view this parcel stream.')
  }
  if (response.status === 404) {
    throw new StreamHttpError(404, 'Parcel not found.')
  }
  if (response.status === 429) {
    throw new StreamHttpError(429, 'Per-user or global SSE connection limit reached.')
  }
  if (!response.ok) {
    throw new StreamHttpError(response.status, `Stream endpoint returned status ${response.status}.`)
  }

  if (!response.body) {
    throw new Error('ReadableStream body is not available on SSE response.')
  }

  onConnected?.()

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  let currentEventName: string | null = null
  let currentDataLines: string[] = []

  const dispatchEvent = () => {
    if (currentDataLines.length > 0) {
      const rawData = currentDataLines.join('\n')
      try {
        const parsed = JSON.parse(rawData)
        if (isParcelLifecycleSseEvent(parsed)) {
          if (!currentEventName || currentEventName === 'parcel.status.changed') {
            onEvent(parsed)
          }
        }
      } catch {
        // Drop malformed payloads safely without crashing stream processor
      }
    }
    currentEventName = null
    currentDataLines = []
  }

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r\n|\r|\n/)
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (line === '') {
          // Empty line indicates event boundary in SSE protocol
          dispatchEvent()
        } else if (line.startsWith(':')) {
          // SSE comment / heartbeat line (e.g. :keepalive)
          onHeartbeat?.()
        } else if (line.startsWith('event:')) {
          currentEventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          currentDataLines.push(line.slice(5).trim())
        }
      }
    }
    if (currentDataLines.length > 0) {
      dispatchEvent()
    }
  } finally {
    try {
      await reader.cancel()
    } catch {
      // Reader cancellation error ignored on cleanup
    }
  }
}
