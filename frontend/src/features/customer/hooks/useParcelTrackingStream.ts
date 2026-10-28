import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ParcelDetailResponse, ParcelStatus } from '../api/deliveryContracts'
import {
  calculateBackoffWithJitter,
  DEFAULT_RECONNECT_CONFIG,
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
  status: ParcelStatus
  version: number
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
    if (!liveOverride) return initialParcel
    if (liveOverride.version >= initialParcel.version) {
      return {
        ...initialParcel,
        currentStatus: liveOverride.status,
        version: liveOverride.version,
      }
    }
    return initialParcel
  }, [initialParcel, liveOverride, subject])

  const activeAbortControllerRef = useRef<AbortController | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)
  const isConnectingRef = useRef(false)
  const isReconcilingRef = useRef(false)
  const isConnectedRef = useRef(false)
  const lastDeliveredVersionRef = useRef<number>(initialParcel?.version ?? 0)

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
      if (fresh && isMountedRef.current) {
        lastDeliveredVersionRef.current = Math.max(lastDeliveredVersionRef.current, fresh.version)
        setLiveOverride({
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
      } catch (err) {
        isConnectedRef.current = false
        if (!isMountedRef.current || controller.signal.aborted) return

        // 1. Session expired / invalid authentication
        if (err instanceof StreamHttpError && err.statusCode === 401) {
          if (handleSessionExpiryRef.current) {
            try {
              await handleSessionExpiryRef.current()
              const freshToken = await getAccessTokenRef.current()
              if (freshToken?.trim() && isMountedRef.current) {
                void connectStreamRef.current(true, attemptNum)
                return
              }
            } catch {
              // Session refresh failed
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

        // 5. Temporary network disconnection or recoverable failure
        const currentReconnectConfig = reconnectConfigRef.current
        if (attemptNum < currentReconnectConfig.maxRetries) {
          const delay = calculateBackoffWithJitter(
            attemptNum,
            currentReconnectConfig,
            randomFnRef.current,
          )
          if (isMountedRef.current) {
            setStreamState(prev => ({
              ...prev,
              status: 'reconnecting',
              retryAttempt: attemptNum,
              errorMessage: `Connection interrupted. Reconnecting (attempt ${attemptNum}/${currentReconnectConfig.maxRetries})...`,
            }))
          }
          reconnectTimerRef.current = setTimeout(() => {
            if (isMountedRef.current) {
              void connectStreamRef.current(true, attemptNum)
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
    ],
  )

  // Keep connectStreamRef synchronized
  useEffect(() => {
    connectStreamRef.current = connectStream
  }, [connectStream])

  // Manual retry function exposed to UI
  const retry = useCallback(() => {
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
