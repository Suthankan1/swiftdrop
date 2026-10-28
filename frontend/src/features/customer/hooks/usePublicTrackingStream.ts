import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PublicTrackingResponse, ParcelStatus, TrackingTimelineItem } from '../api/deliveryContracts'
import {
  calculateBackoffWithJitter,
  DEFAULT_RECONNECT_CONFIG,
  type PublicTrackingSseEvent,
  type ReconnectConfig,
  type TrackingStreamState,
} from '../api/trackingStreamContracts'
import {
  connectPublicTrackingStream,
  StreamHttpError,
} from '../api/trackingStreamClient'

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
    }
  }, [trackingNumber])

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

  const activeAbortControllerRef = useRef<AbortController | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)
  const isConnectingRef = useRef(false)
  const isReconcilingRef = useRef(false)
  const isConnectedRef = useRef(false)
  const lastDeliveredVersionRef = useRef<number>(0)

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

        // Bounded exponential backoff with full jitter
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
    [enabled, gatewayUrl, handleIncomingEvent, trackingNumber],
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

  // Tab visibility management: disconnect when hidden to conserve edge resources, reconnect when visible
  useEffect(() => {
    if (typeof document === 'undefined') return

    const handleVisibilityChange = () => {
      if (document.hidden) {
        cleanupActiveConnection()
        setStreamState((prev) => ({
          ...prev,
          status: 'idle',
        }))
      } else {
        if (enabled && trackingNumber && isMountedRef.current) {
          connectStreamRef.current(false, 0)
        }
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
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
