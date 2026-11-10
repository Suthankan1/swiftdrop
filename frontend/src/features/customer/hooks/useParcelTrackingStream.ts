import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ParcelDetailResponse, ParcelStatus } from '../api/deliveryContracts'
import {
  calculateBackoffWithJitter,
  DEFAULT_RECONNECT_CONFIG,
  isTerminalParcelStatus,
  STABLE_STREAM_MS,
  type ReconnectConfig,
  type TrackingStreamState,
} from '../api/trackingStreamContracts'
import {
  connectParcelTrackingStream,
  StreamHttpError,
} from '../api/trackingStreamClient'

export interface UseParcelTrackingStreamOptions {
  parcelId: string | null
  enabled?: boolean
  initialParcel?: ParcelDetailResponse
  getAccessToken: () => Promise<string | null>
  handleSessionExpiry?: () => Promise<void>
  subject?: string | null
  reconcileRest: (signal?: AbortSignal) => Promise<ParcelDetailResponse | null>
  reconnectConfig?: ReconnectConfig
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  randomFn?: () => number
}

interface LiveStatusOverride {
  parcelId: string
  status: ParcelStatus
  version: number
}

/** A stream that ends cleanly (server close, proxy timeout) is retried like any other interruption. */
class StreamClosedError extends Error {
  constructor() {
    super('Live stream closed by the server.')
  }
}

export function useParcelTrackingStream({
  parcelId,
  enabled = true,
  initialParcel,
  getAccessToken,
  handleSessionExpiry,
  subject,
  reconcileRest,
  reconnectConfig = DEFAULT_RECONNECT_CONFIG,
  gatewayUrl,
  fetch: customFetch,
  randomFn,
}: UseParcelTrackingStreamOptions) {
  const [liveOverride, setLiveOverride] = useState<LiveStatusOverride | null>(null)
  const [trackedParcelId, setTrackedParcelId] = useState(parcelId)
  const [streamState, setStreamState] = useState<TrackingStreamState>({
    status: 'idle',
    retryAttempt: 0,
    maxRetries: reconnectConfig.maxRetries,
    lastDeliveredVersion: initialParcel?.version ?? null,
    lastEventId: null,
    lastEventAt: null,
    isOffline: typeof navigator !== 'undefined' ? !navigator.onLine : false,
    errorMessage: undefined,
  })

  // Synchronize latest callback functions and configurations in refs to ensure stable connectStream references
  // Switching parcels clears every live value from the previous parcel before it can render.
  if (parcelId !== trackedParcelId) {
    setTrackedParcelId(parcelId)
    setLiveOverride(null)
    setStreamState(prev => ({
      ...prev,
      status: 'idle',
      retryAttempt: 0,
      lastDeliveredVersion: initialParcel?.parcelId === parcelId ? initialParcel.version : null,
      lastEventId: null,
      lastEventAt: null,
      errorMessage: undefined,
    }))
  }

  const reconcileRestRef = useRef(reconcileRest)
  const getAccessTokenRef = useRef(getAccessToken)
  const handleSessionExpiryRef = useRef(handleSessionExpiry)
  const reconnectConfigRef = useRef(reconnectConfig)
  const randomFnRef = useRef(randomFn)
  const customFetchRef = useRef(customFetch)

  useEffect(() => {
    reconcileRestRef.current = reconcileRest
    getAccessTokenRef.current = getAccessToken
    handleSessionExpiryRef.current = handleSessionExpiry
    reconnectConfigRef.current = reconnectConfig
    randomFnRef.current = randomFn
    customFetchRef.current = customFetch
  })

  // Compute authoritative parcel by merging initial REST data with real-time SSE overrides
  const parcel: ParcelDetailResponse | null = useMemo(() => {
    if (!subject || !initialParcel) return null
    if (!liveOverride || liveOverride.parcelId !== initialParcel.parcelId) return initialParcel
    if (liveOverride.version >= initialParcel.version) {
      return {
        ...initialParcel,
        currentStatus: liveOverride.status,
        version: liveOverride.version,
      }
    }
    return initialParcel
  }, [initialParcel, liveOverride, subject])

  const currentStatusRef = useRef<ParcelStatus | null>(null)
  useEffect(() => {
    currentStatusRef.current = parcel?.currentStatus ?? null
  }, [parcel])

  const activeAbortControllerRef = useRef<AbortController | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)
  const isConnectingRef = useRef(false)
  const isReconcilingRef = useRef(false)
  const isConnectedRef = useRef(false)
  const lastDeliveredVersionRef = useRef<number>(initialParcel?.version ?? 0)
  const authRetriedRef = useRef(false)

  // Reset the version cursor for a new parcel, and raise it when a fresher REST snapshot arrives.
  const versionParcelRef = useRef(parcelId)
  useEffect(() => {
    if (versionParcelRef.current !== parcelId) {
      versionParcelRef.current = parcelId
      lastDeliveredVersionRef.current = 0
      authRetriedRef.current = false
    }
    if (initialParcel && initialParcel.parcelId === parcelId) {
      lastDeliveredVersionRef.current = Math.max(lastDeliveredVersionRef.current, initialParcel.version)
    }
  }, [parcelId, initialParcel])

  // Forward ref for connectStream to avoid circular declaration access
  const connectStreamRef = useRef<(isRetry?: boolean, attempt?: number) => Promise<void>>(
    () => Promise.resolve(),
  )

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
    }
  }, [])

  const abortActiveStream = useCallback(() => {
    if (activeAbortControllerRef.current) {
      activeAbortControllerRef.current.abort()
      activeAbortControllerRef.current = null
    }
    isConnectingRef.current = false
    isConnectedRef.current = false
  }, [])

  // Authoritative REST synchronization on reconnect or version gap
  const doReconcileRest = useCallback(async () => {
    if (isReconcilingRef.current || !parcelId) return
    isReconcilingRef.current = true
    try {
      const fresh = await reconcileRestRef.current()
      if (fresh && isMountedRef.current && fresh.parcelId === parcelId) {
        lastDeliveredVersionRef.current = Math.max(lastDeliveredVersionRef.current, fresh.version)
        setLiveOverride({
          parcelId: fresh.parcelId,
          status: fresh.currentStatus,
          version: fresh.version,
        })
        setStreamState(prev => ({
          ...prev,
          lastDeliveredVersion: Math.max(prev.lastDeliveredVersion ?? 0, fresh.version),
        }))
      }
    } catch {
      // Reconcile failure handled gracefully
    } finally {
      isReconcilingRef.current = false
    }
  }, [parcelId])

  const connectStream = useCallback(
    async (isRetryAttempt = false, currentAttempt = 0) => {
      if (!isMountedRef.current || !enabled || !parcelId) {
        return
      }

      // A delivered or failed parcel emits nothing more: show its final state without opening a stream.
      if (isTerminalParcelStatus(currentStatusRef.current ?? initialParcel?.currentStatus)) {
        abortActiveStream()
        clearReconnectTimer()
        setStreamState(prev => ({ ...prev, status: 'idle', retryAttempt: 0, errorMessage: undefined }))
        return
      }

      // Check offline status
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        if (isMountedRef.current) {
          setStreamState(prev => ({
            ...prev,
            status: 'disconnected',
            isOffline: true,
            errorMessage: 'Browser is offline. Real-time updates paused.',
          }))
        }
        return
      }

      // Prevent overlapping connections
      abortActiveStream()
      clearReconnectTimer()

      const controller = new AbortController()
      activeAbortControllerRef.current = controller
      isConnectingRef.current = true

      const attemptNum = isRetryAttempt ? currentAttempt + 1 : 0
      let connectedAt: number | null = null

      if (isMountedRef.current) {
        setStreamState(prev => ({
          ...prev,
          status: isRetryAttempt ? 'reconnecting' : 'connecting',
          retryAttempt: attemptNum,
          errorMessage: undefined,
        }))
      }

      try {
        await connectParcelTrackingStream({
          parcelId,
          getAccessToken: () => getAccessTokenRef.current(),
          gatewayUrl,
          fetch: customFetchRef.current,
          signal: controller.signal,
          onConnected: () => {
            if (!isMountedRef.current || controller.signal.aborted) return
            isConnectedRef.current = true
            connectedAt = Date.now()
            authRetriedRef.current = false
            setStreamState(prev => ({
              ...prev,
              status: 'connected',
              retryAttempt: 0,
              errorMessage: undefined,
            }))
            // Reconcile REST snapshot after reconnecting to catch any missed updates
            if (isRetryAttempt) {
              void doReconcileRest()
            }
          },
          onEvent: event => {
            if (!isMountedRef.current || controller.signal.aborted) return

            const currentVersion = lastDeliveredVersionRef.current

            // 1. Stale / duplicate check
            if (event.aggregateVersion <= currentVersion) {
              // Discard duplicate or older version without mutating state
              return
            }

            // 2. Sequential in-order event application
            if (event.aggregateVersion === currentVersion + 1) {
              lastDeliveredVersionRef.current = event.aggregateVersion

              setLiveOverride({
                parcelId,
                status: event.newStatus,
                version: event.aggregateVersion,
              })

              setStreamState(prev => ({
                ...prev,
                lastDeliveredVersion: event.aggregateVersion,
                lastEventId: event.eventId,
                lastEventAt: event.occurredAt,
              }))
              return
            }

            // 3. Gap detected (event.aggregateVersion > currentVersion + 1)
            // Trigger canonical REST recovery to fetch full authoritative state
            lastDeliveredVersionRef.current = event.aggregateVersion
            void doReconcileRest()
          },
        })
        if (!controller.signal.aborted) throw new StreamClosedError()
      } catch (err) {
        isConnectedRef.current = false
        if (!isMountedRef.current || controller.signal.aborted) return

        // 1. Session expired / invalid authentication
        if (err instanceof StreamHttpError && err.statusCode === 401) {
          // First 401: the token may have expired in flight. Retry once with a freshly obtained token
          // before declaring the session over, because expiring the session signs out every tab.
          if (!authRetriedRef.current) {
            authRetriedRef.current = true
            try {
              const freshToken = await getAccessTokenRef.current()
              if (freshToken?.trim() && isMountedRef.current && !controller.signal.aborted) {
                void connectStreamRef.current(true, attemptNum)
                return
              }
            } catch {
              // fall through to session expiry
            }
          }
          if (handleSessionExpiryRef.current) {
            try {
              await handleSessionExpiryRef.current()
            } catch {
              // ignore
            }
          }
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'auth-required',
              errorMessage: 'Session expired. Please sign in again to receive live updates.',
            }))
          }
          return
        }

        // 2. Cross-customer access denied (403 Forbidden)
        if (err instanceof StreamHttpError && err.statusCode === 403) {
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'forbidden',
              errorMessage: 'Access denied. You are not authorized to view real-time tracking for this parcel.',
            }))
          }
          return
        }

        // 3. Parcel not found (404)
        if (err instanceof StreamHttpError && err.statusCode === 404) {
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'error',
              errorMessage: 'Parcel not found.',
            }))
          }
          return
        }

        // 4. Rate limited / connection limit (429)
        if (err instanceof StreamHttpError && err.statusCode === 429) {
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'error',
              errorMessage: 'Connection limit reached. Please wait a moment before retrying.',
            }))
          }
          return
        }

        // 5a. A finished parcel emits nothing more: do not reopen a stream the server ended.
        if (err instanceof StreamClosedError && isTerminalParcelStatus(currentStatusRef.current)) {
          setStreamState(prev => ({ ...prev, status: 'idle', retryAttempt: 0, errorMessage: undefined }))
          return
        }

        // 5b. Temporary network disconnection or recoverable failure
        const currentReconnectConfig = reconnectConfigRef.current
        // Only a stream that stayed up starts a fresh retry budget; open-then-close counts as a failure.
        const retryBase = connectedAt !== null && Date.now() - connectedAt >= STABLE_STREAM_MS ? 0 : attemptNum
        if (retryBase < currentReconnectConfig.maxRetries) {
          const delay = calculateBackoffWithJitter(
            retryBase,
            currentReconnectConfig,
            randomFnRef.current,
          )
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'reconnecting',
              retryAttempt: retryBase,
              errorMessage: `Connection interrupted. Reconnecting (attempt ${retryBase}/${currentReconnectConfig.maxRetries})...`,
            }))
          }
          reconnectTimerRef.current = setTimeout(() => {
            if (isMountedRef.current) {
              void connectStreamRef.current(true, retryBase)
            }
          }, delay)
        } else {
          // Bounded retry ceiling reached
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'error',
              retryAttempt: attemptNum,
              errorMessage: 'Live connection lost. Automatic recovery limit reached. You can retry manually.',
            }))
          }
        }
      } finally {
        isConnectingRef.current = false
      }
    },
    [
      abortActiveStream,
      clearReconnectTimer,
      doReconcileRest,
      enabled,
      gatewayUrl,
      parcelId,
      initialParcel?.currentStatus,
    ],
  )

  // Keep connectStreamRef synchronized
  useEffect(() => {
    connectStreamRef.current = connectStream
  }, [connectStream])

  // Manual retry function exposed to UI
  const retry = useCallback(() => {
    authRetriedRef.current = false
    clearReconnectTimer()
    abortActiveStream()
    void doReconcileRest()
    void connectStream(false, 0)
  }, [abortActiveStream, clearReconnectTimer, connectStream, doReconcileRest])

  // Subject / logout change: terminate stream when user identity changes
  useEffect(() => {
    abortActiveStream()
    clearReconnectTimer()
  }, [subject, abortActiveStream, clearReconnectTimer])

  // Primary stream effect
  useEffect(() => {
    isMountedRef.current = true

    if (enabled && parcelId && subject) {
      void connectStream(false, 0)
    }

    return () => {
      isMountedRef.current = false
      abortActiveStream()
      clearReconnectTimer()
    }
  }, [parcelId, enabled, subject, connectStream, abortActiveStream, clearReconnectTimer])

  // Browser lifecycle event handlers
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible' && enabled && parcelId) {
        if (!isConnectedRef.current) {
          clearReconnectTimer()
          void connectStreamRef.current(true, 0)
        }
      }
    }

    const handleOnline = () => {
      if (enabled && parcelId) {
        setStreamState(prev => ({ ...prev, isOffline: false }))
        clearReconnectTimer()
        void connectStreamRef.current(true, 0)
      }
    }

    const handleOffline = () => {
      abortActiveStream()
      clearReconnectTimer()
      setStreamState(prev => ({
        ...prev,
        status: 'disconnected',
        isOffline: true,
        errorMessage: 'Network connection lost.',
      }))
    }

    const handlePageShow = (e: PageTransitionEvent) => {
      // Back-Forward cache restoration
      if (e.persisted && enabled && parcelId) {
        clearReconnectTimer()
        void doReconcileRest()
        void connectStreamRef.current(true, 0)
      }
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pageshow', handlePageShow)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('pageshow', handlePageShow)
    }
  }, [
    enabled,
    parcelId,
    clearReconnectTimer,
    abortActiveStream,
    doReconcileRest,
  ])

  return {
    parcel,
    streamState,
    retry,
    reconcileRest: doReconcileRest,
  }
}
