import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PublicTrackingResponse, ParcelStatus, TrackingTimelineItem } from '../api/deliveryContracts'
import {
  calculateBackoffWithJitter,
  DEFAULT_RECONNECT_CONFIG,
  isTerminalParcelStatus,
  STABLE_STREAM_MS,
  type PublicTrackingSseEvent,
  type ReconnectConfig,
  type TrackingStreamState,
} from '../api/trackingStreamContracts'
import {
  connectPublicTrackingStream,
  StreamHttpError,
} from '../api/trackingStreamClient'

/** How long a hidden tab keeps its stream before releasing it. Short hide/show flips then cost nothing. */
export const HIDDEN_STREAM_GRACE_MS = 60_000

export interface UsePublicTrackingStreamOptions {
  trackingNumber: string | null
  enabled?: boolean
  initialTracking?: PublicTrackingResponse
  reconcileRest: (signal?: AbortSignal) => Promise<PublicTrackingResponse | null>
  reconnectConfig?: ReconnectConfig
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  randomFn?: () => number
}

interface LiveUpdateState {
  currentStatus: ParcelStatus
  version: number
  timeline: TrackingTimelineItem[]
}

/**
 * Public Server-Sent Events (SSE) tracking hook for anonymous parcel viewers.
 * Adheres strictly to docs/contracts/SSE_REST_RECOVERY.md, contracts/rest/openapi-delivery.yaml,
 * and PublicTrackingController.java:
 * - Anonymous fetch GET /api/v1/tracking/{trackingNumber}/events
 * - Strict client-side version deduplication (aggregateVersion <= lastDeliveredVersion -> drop)
 * - Version gap detection (aggregateVersion > lastDeliveredVersion + 1 -> trigger canonical REST reconcile)
 * - Bounded exponential backoff with full jitter
 * - Visibility and online/offline event management
 * - Zero PII, zero tokens in URLs or queries
 */
export function usePublicTrackingStream({
  trackingNumber,
  enabled = true,
  initialTracking,
  reconcileRest,
  reconnectConfig = DEFAULT_RECONNECT_CONFIG,
  gatewayUrl,
  fetch: customFetch,
  randomFn,
}: UsePublicTrackingStreamOptions) {
  const [liveState, setLiveState] = useState<LiveUpdateState | null>(null)
  const [streamState, setStreamState] = useState<TrackingStreamState>({
    status: 'idle',
    retryAttempt: 0,
    maxRetries: reconnectConfig.maxRetries,
    lastDeliveredVersion: null,
    lastEventId: null,
    lastEventAt: null,
    isOffline: typeof navigator !== 'undefined' ? !navigator.onLine : false,
    errorMessage: undefined,
  })

  const reconcileRestRef = useRef(reconcileRest)
  const reconnectConfigRef = useRef(reconnectConfig)
  const randomFnRef = useRef(randomFn)
  const customFetchRef = useRef(customFetch)

  useEffect(() => {
    reconcileRestRef.current = reconcileRest
    reconnectConfigRef.current = reconnectConfig
    randomFnRef.current = randomFn
    customFetchRef.current = customFetch
  })

  // Reset live overrides when trackingNumber changes
  const prevTrackingNumberRef = useRef(trackingNumber)
  useEffect(() => {
    if (trackingNumber !== prevTrackingNumberRef.current) {
      prevTrackingNumberRef.current = trackingNumber
      setLiveState(null)
      lastDeliveredVersionRef.current = 0
      hasConnectedRef.current = false
    }
  }, [trackingNumber])

  // A fresh REST snapshot (e.g. the Refresh button) is authoritative and replaces live overrides.
  const lastSnapshotRef = useRef(initialTracking)
  useEffect(() => {
    if (initialTracking && initialTracking !== lastSnapshotRef.current) {
      lastSnapshotRef.current = initialTracking
      setLiveState(null)
    }
  }, [initialTracking])

  // Merge initial REST data with live SSE milestones and status updates
  const tracking: PublicTrackingResponse | null = useMemo(() => {
    if (!initialTracking) return null
    if (!liveState) return initialTracking

    return {
      ...initialTracking,
      currentStatus: liveState.currentStatus,
      timeline: liveState.timeline,
    }
  }, [initialTracking, liveState])

  const currentStatusRef = useRef<ParcelStatus | null>(null)
  useEffect(() => {
    currentStatusRef.current = tracking?.currentStatus ?? null
  }, [tracking])

  const activeAbortControllerRef = useRef<AbortController | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)
  const isConnectingRef = useRef(false)
  const isReconcilingRef = useRef(false)
  const isConnectedRef = useRef(false)
  const lastDeliveredVersionRef = useRef<number>(0)
  const hasConnectedRef = useRef(false)

  const connectStreamRef = useRef<(isRetry?: boolean, attempt?: number) => Promise<void>>(
    () => Promise.resolve(),
  )

  const cleanupActiveConnection = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
    }
    if (activeAbortControllerRef.current) {
      activeAbortControllerRef.current.abort()
      activeAbortControllerRef.current = null
    }
    isConnectedRef.current = false
    isConnectingRef.current = false
  }, [])

  const triggerReconciliation = useCallback(async () => {
    if (isReconcilingRef.current || !isMountedRef.current) return
    isReconcilingRef.current = true

    try {
      const refreshed = await reconcileRestRef.current()
      if (refreshed && isMountedRef.current) {
        setLiveState({
          currentStatus: refreshed.currentStatus,
          version: lastDeliveredVersionRef.current,
          timeline: refreshed.timeline,
        })
      }
    } catch {
      // Ignore background reconciliation errors; will retry on subsequent stream events
    } finally {
      isReconcilingRef.current = false
    }
  }, [])

  const handleIncomingEvent = useCallback(
    (event: PublicTrackingSseEvent) => {
      const lastVersion = lastDeliveredVersionRef.current

      // 1. Strict version deduplication: drop duplicate and stale events
      if (event.aggregateVersion <= lastVersion) {
        return
      }

      // 2. Version gap detection: if we missed an intermediate event, reconcile via canonical REST
      if (lastVersion > 0 && event.aggregateVersion > lastVersion + 1) {
        lastDeliveredVersionRef.current = event.aggregateVersion
        triggerReconciliation()
        return
      }

      // 3. Monotonic delivery update
      lastDeliveredVersionRef.current = event.aggregateVersion

      setStreamState((prev) => ({
        ...prev,
        lastDeliveredVersion: event.aggregateVersion,
        lastEventId: event.eventId,
        lastEventAt: event.occurredAt,
      }))

      setLiveState((prev) => {
        const baseTimeline = prev ? prev.timeline : (initialTracking?.timeline ?? [])
        // Avoid duplicate milestone if same status and occurredAt
        const alreadyExists = baseTimeline.some(
          (item) => item.status === event.newStatus && item.occurredAt === event.occurredAt,
        )

        const updatedTimeline = alreadyExists
          ? baseTimeline
          : [
              ...baseTimeline,
              {
                status: event.newStatus,
                occurredAt: event.occurredAt,
                cityOrZone: event.cityOrZone ?? undefined,
              },
            ]

        return {
          currentStatus: event.newStatus,
          version: event.aggregateVersion,
          timeline: updatedTimeline,
        }
      })
    },
    [initialTracking, triggerReconciliation],
  )

  const connectStream = useCallback(
    async (isRetry = false, attempt = 0) => {
      if (!isMountedRef.current || !enabled || !trackingNumber) return
      if (isConnectingRef.current || isConnectedRef.current) return

      if (activeAbortControllerRef.current) {
        activeAbortControllerRef.current.abort()
      }

      const controller = new AbortController()
      activeAbortControllerRef.current = controller
      isConnectingRef.current = true
      let connectedAt: number | null = null
      let closedCleanly = false

      setStreamState((prev) => ({
        ...prev,
        status: isRetry ? 'reconnecting' : 'connecting',
        retryAttempt: attempt,
        errorMessage: undefined,
      }))

      try {
        await connectPublicTrackingStream({
          trackingNumber,
          gatewayUrl,
          fetch: customFetchRef.current,
          signal: controller.signal,
          onConnected: () => {
            if (!isMountedRef.current) return
            isConnectedRef.current = true
            isConnectingRef.current = false
            // Any connection after the first may have missed events: re-read the canonical state.
            if (hasConnectedRef.current || isRetry) void triggerReconciliation()
            hasConnectedRef.current = true
            connectedAt = Date.now()
            setStreamState((prev) => ({
              ...prev,
              status: 'connected',
              retryAttempt: 0,
              errorMessage: undefined,
            }))
          },
          onEvent: (event) => {
            if (!isMountedRef.current) return
            handleIncomingEvent(event)
          },
          onHeartbeat: () => {
            // Heartbeat received
          },
        })
        // 74: a clean server close is an interruption, not a healthy "Live" stream.
        if (!controller.signal.aborted) {
          closedCleanly = true
          throw new Error('Live stream closed by the server.')
        }
      } catch (err) {
        if (!isMountedRef.current || controller.signal.aborted) return

        isConnectedRef.current = false
        isConnectingRef.current = false

        if (err instanceof StreamHttpError) {
          // Terminal non-retryable errors
          if (err.statusCode === 400 || err.statusCode === 404) {
            setStreamState((prev) => ({
              ...prev,
              status: 'error',
              errorMessage: err.message,
            }))
            return
          }
        }

        // Check if client is offline
        const offline = typeof navigator !== 'undefined' ? !navigator.onLine : false
        if (offline) {
          setStreamState((prev) => ({
            ...prev,
            status: 'disconnected',
            isOffline: true,
          }))
          return
        }

        // A finished parcel emits nothing more: do not reopen a stream the server ended.
        if (closedCleanly && isTerminalParcelStatus(currentStatusRef.current)) {
          setStreamState((prev) => ({ ...prev, status: 'idle', retryAttempt: 0, errorMessage: undefined }))
          return
        }

        // Bounded exponential backoff with full jitter. Only a stream that stayed up earns a fresh
        // budget; open-then-close counts as a failed attempt so it can never loop.
        if (connectedAt !== null && Date.now() - connectedAt >= STABLE_STREAM_MS) attempt = 0
        const nextAttempt = attempt + 1
        const cfg = reconnectConfigRef.current
        if (nextAttempt <= cfg.maxRetries) {
          const delay = calculateBackoffWithJitter(attempt, cfg, randomFnRef.current)
          setStreamState((prev) => ({
            ...prev,
            status: 'reconnecting',
            retryAttempt: nextAttempt,
            errorMessage: err instanceof Error ? err.message : 'Connection failed',
          }))

          reconnectTimerRef.current = setTimeout(() => {
            reconnectTimerRef.current = null
            if (isMountedRef.current) {
              connectStreamRef.current(true, nextAttempt)
            }
          }, delay)
        } else {
          setStreamState((prev) => ({
            ...prev,
            status: 'error',
            retryAttempt: attempt,
            errorMessage: 'Maximum reconnection attempts exceeded.',
          }))
        }
      }
    },
    [enabled, gatewayUrl, handleIncomingEvent, trackingNumber, triggerReconciliation],
  )

  connectStreamRef.current = connectStream

  // Main stream connection lifecycle effect
  useEffect(() => {
    isMountedRef.current = true

    if (enabled && trackingNumber) {
      connectStream(false, 0)
    }

    return () => {
      cleanupActiveConnection()
    }
  }, [cleanupActiveConnection, connectStream, enabled, trackingNumber])

  // Tab visibility management. A hidden tab keeps its stream for a grace period, so switching tabs or
  // panels does not spend anonymous rate-limit budget; only a long absence releases the connection.
  useEffect(() => {
    if (typeof document === 'undefined') return
    let releaseTimer: ReturnType<typeof setTimeout> | null = null

    const handleVisibilityChange = () => {
      if (document.hidden) {
        if (releaseTimer === null) {
          releaseTimer = setTimeout(() => {
            releaseTimer = null
            cleanupActiveConnection()
            setStreamState((prev) => ({ ...prev, status: 'idle' }))
          }, HIDDEN_STREAM_GRACE_MS)
        }
      } else {
        if (releaseTimer !== null) {
          clearTimeout(releaseTimer)
          releaseTimer = null
        }
        // Still connected (or connecting): nothing to do. Otherwise reconnect; the reconnect re-reads REST.
        if (enabled && trackingNumber && isMountedRef.current && !isConnectedRef.current && !isConnectingRef.current
            && reconnectTimerRef.current === null) {
          connectStreamRef.current(true, 0)
        }
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      if (releaseTimer !== null) clearTimeout(releaseTimer)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [cleanupActiveConnection, enabled, trackingNumber])

  // Online / Offline network status listeners
  useEffect(() => {
    if (typeof window === 'undefined') return

    const handleOnline = () => {
      setStreamState((prev) => ({ ...prev, isOffline: false }))
      if (enabled && trackingNumber && isMountedRef.current && !isConnectedRef.current) {
        connectStreamRef.current(false, 0)
      }
    }

    const handleOffline = () => {
      cleanupActiveConnection()
      setStreamState((prev) => ({
        ...prev,
        status: 'disconnected',
        isOffline: true,
      }))
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [cleanupActiveConnection, enabled, trackingNumber])

  const reconnect = useCallback(() => {
    cleanupActiveConnection()
    setStreamState((prev) => ({ ...prev, retryAttempt: 0, errorMessage: undefined }))
    return connectStream(false, 0)
  }, [cleanupActiveConnection, connectStream])

  return {
    tracking,
    streamState,
    reconnect,
  }
}
