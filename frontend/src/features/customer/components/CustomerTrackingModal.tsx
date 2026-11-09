import { useContext, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AuthContext } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import type { createDeliveryApi } from '../api/delivery'
import { DeliveryError } from '../api/delivery'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import {
  formatCurrency,
  formatDateTime,
  formatDimensions,
  formatWeight,
  getActiveStepIndex,
  getFailedStepIndex,
  getStatusPresentation,
  LIFECYCLE_STEPS,
} from './customerUiUtils'
import { ReceiptDownloadButton } from './ReceiptDownloadButton'
import { useParcelTrackingStream } from '../hooks/useParcelTrackingStream'
import type { ReconnectConfig } from '../api/trackingStreamContracts'

interface CustomerTrackingModalProps {
  parcelId: string | null
  trackingNumber?: string
  isOpen: boolean
  onClose: () => void
  deliveryApi: ReturnType<typeof createDeliveryApi>
  onDownloadReceipt: (parcelId: string) => Promise<void>
  isDownloadingReceipt: boolean
  getAccessToken?: () => Promise<string | null>
  handleSessionExpiry?: () => Promise<void>
  subject?: string | null
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  reconnectConfig?: ReconnectConfig
  randomFn?: () => number
}

export function CustomerTrackingModal({
  parcelId,
  trackingNumber: initialTrackingNumber,
  isOpen,
  onClose,
  deliveryApi,
  onDownloadReceipt,
  isDownloadingReceipt,
  getAccessToken: propGetAccessToken,
  handleSessionExpiry: propHandleSessionExpiry,
  subject: propSubject,
  gatewayUrl,
  fetch: customFetch,
  reconnectConfig,
  randomFn,
}: CustomerTrackingModalProps) {
  const [copyFeedback, setCopyFeedback] = useState(false)
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const modalRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const auth = useContext(AuthContext)
  const effectiveGetAccessToken = propGetAccessToken ?? auth?.getAccessToken
  const effectiveHandleSessionExpiry = propHandleSessionExpiry ?? auth?.handleSessionExpiry
  const effectiveSubject =
    propSubject ?? (auth?.snapshot.status === 'authenticated' ? auth.snapshot.subject : null)

  const query = useQuery({
    queryKey: ['private', 'parcel-detail', parcelId],
    enabled: isOpen && !!parcelId,
    queryFn: async ({ signal }) => {
      if (!parcelId) throw new DeliveryError('not-found', 'No parcel identifier provided.')
      return deliveryApi.getParcel(parcelId, signal)
    },
    staleTime: 10_000,
  })

  const historyQuery = useQuery({
    queryKey: ['private', 'parcel-history', parcelId, query.data?.version],
    enabled: isOpen && !!parcelId && !!query.data && typeof deliveryApi.getParcelHistory === 'function',
    queryFn: async ({ signal }) => {
      if (!parcelId) throw new DeliveryError('not-found', 'No parcel identifier provided.')
      return deliveryApi.getParcelHistory(parcelId, { page: 0, size: 50 }, signal)
    },
    staleTime: 10_000,
  })

  const {
    parcel: liveParcel,
    streamState,
    retry: retryStream,
  } = useParcelTrackingStream({
    parcelId,
    enabled: isOpen && !!parcelId && !!effectiveSubject && !!effectiveGetAccessToken && !!query.data,
    initialParcel: query.data,
    getAccessToken: effectiveGetAccessToken ?? (async () => null),
    handleSessionExpiry: effectiveHandleSessionExpiry,
    subject: effectiveSubject,
    reconcileRest: async signal => {
      if (!parcelId) return null
      try {
        const fresh = await deliveryApi.getParcel(parcelId, signal)
        queryClient.setQueryData(['private', 'parcel-detail', parcelId], fresh)
        return fresh
      } catch {
        return null
      }
    },
    gatewayUrl,
    fetch: customFetch,
    reconnectConfig,
    randomFn,
  })

  // Keyboard navigation & focus management (audit 93): focus moves into the dialog on open, Tab stays inside it,
  // Escape closes it, and focus returns to whatever opened it. Runs once per opening, not on every re-render.
  const onCloseRef = useRef(onClose)
  useEffect(() => { onCloseRef.current = onClose })
  useEffect(() => {
    if (!isOpen) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeButtonRef.current?.focus()
    const handleKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onCloseRef.current()
        return
      }
      if (e.key !== 'Tab' || !modalRef.current) return
      const focusable = Array.from(modalRef.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ))
      if (focusable.length === 0) return
      const first = focusable[0]!
      const last = focusable[focusable.length - 1]!
      const active = document.activeElement
      if (e.shiftKey && (active === first || !modalRef.current.contains(active))) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && (active === last || !modalRef.current.contains(active))) {
        e.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
      if (opener?.isConnected) opener.focus()
    }
  }, [isOpen])

  if (!isOpen) return null

  const parcel: ParcelDetailResponse | undefined = liveParcel || query.data
  const activeStatus = parcel?.currentStatus || 'CREATED'
  const presentation = getStatusPresentation(activeStatus)
  const activeStep = getActiveStepIndex(activeStatus)
  const failureEvent = historyQuery.data?.items.find(item => item.newStatus === 'FAILED')
  const failedStep = getFailedStepIndex(failureEvent?.previousStatus)
  const displayTracking = parcel?.trackingNumber || initialTrackingNumber || 'Tracking'

  async function handleCopyTracking() {
    if (!parcel?.trackingNumber) return
    try {
      await navigator.clipboard.writeText(parcel.trackingNumber)
      setCopyFeedback(true)
      setTimeout(() => setCopyFeedback(false), 2000)
    } catch {
      // Clipboard write failure ignored gracefully
    }
  }

  function handleBackdropClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={handleBackdropClick}
    >
      <div
        ref={modalRef}
        className="modal-dialog tracking-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="tracking-modal-title"
      >
        <header className="modal-header">
          <div>
            <p className="eyebrow">PARCEL TRACKING &amp; STATUS</p>
            <h2 id="tracking-modal-title" className="modal-title">
              {displayTracking}
            </h2>
          </div>
          <button
            ref={closeButtonRef}
            type="button"
            className="modal-close-button"
            onClick={onClose}
            aria-label="Close tracking details modal"
          >
            ✕
          </button>
        </header>

        <div className="modal-body">
          {query.isPending && !parcel && (
            <div className="tracking-loading-state" aria-busy="true">
              <span className="btn-spinner large" aria-hidden="true" />
              <p>Fetching authoritative parcel state...</p>
            </div>
          )}

          {query.isError && !parcel && (
            <div className="state-panel state-panel-error" role="alert">
              <h3>Unable to load parcel details</h3>
              <p>
                {query.error instanceof DeliveryError && query.error.kind === 'forbidden'
                  ? 'Access denied. You do not have permission to view this parcel.'
                  : query.error instanceof DeliveryError && query.error.kind === 'not-found'
                    ? 'The requested parcel could not be found.'
                    : 'A temporary network or server error occurred. Please try again.'}
              </p>
              <Button
                variant="secondary"
                onClick={() => void query.refetch()}
              >
                Retry
              </Button>
            </div>
          )}

          {parcel && (
            <div className="tracking-content">
              {/* Status Banner */}
              <div className="tracking-status-banner">
                <div className="banner-left">
                  <span className={presentation.pillClass}>{presentation.label}</span>
                  {streamState.status === 'connected' && (
                    <span className="live-stream-chip" title="Live stream active" aria-label="Live updates active">
                      ● Live
                    </span>
                  )}
                  <p className="banner-description">{presentation.description}</p>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  className="copy-tracking-btn"
                  onClick={handleCopyTracking}
                  aria-label="Copy tracking number to clipboard"
                >
                  {copyFeedback ? '✓ Copied' : 'Copy Tracking #'}
                </Button>
              </div>

              {/* Progress Milestones Tracker */}
              <ol className="timeline-stepper" aria-label="Delivery progress timeline">
                {LIFECYCLE_STEPS.map((step, idx) => {
                  const stepInfo = getStatusPresentation(step)
                  const isCurrent = idx === activeStep && !presentation.isFailed
                  // A failed parcel only ticks the steps it actually reached (from its event history).
                  const isPast = presentation.isFailed
                    ? failedStep >= 0 && idx < failedStep
                    : idx < activeStep || presentation.isSuccess
                  const isStepFailed = presentation.isFailed && idx === failedStep

                  let stepClass = 'stepper-node'
                  if (isPast) stepClass += ' is-past'
                  if (isCurrent) stepClass += ' is-current'
                  if (isStepFailed) stepClass += ' is-failed'

                  return (
                    <li key={step} className={stepClass}
                      aria-label={`${stepInfo.label}: ${isPast ? 'completed' : isStepFailed ? 'failed' : isCurrent ? 'current' : 'not reached'}`}
                      aria-current={isCurrent ? 'step' : undefined}>
                      <div className="stepper-dot" aria-hidden="true">
                        {isPast ? '✓' : isStepFailed ? '✕' : idx + 1}
                      </div>
                      <span className="stepper-label">{isStepFailed ? 'Failed' : stepInfo.label}</span>
                    </li>
                  )
                })}
              </ol>

              {/* Event history with notes and failure reasons */}
              {historyQuery.isError && <p className="muted" role="status">The event history is unavailable right now.</p>}
              {historyQuery.data && historyQuery.data.items.length > 0 && (
                <section className="tracking-card" aria-labelledby="parcel-history-heading">
                  <h3 id="parcel-history-heading" className="card-subtitle">Event history</h3>
                  <ol className="parcel-history-list">
                    {[...historyQuery.data.items]
                      .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
                      .map(item => (
                        <li key={item.historyId}>
                          <strong>{getStatusPresentation(item.newStatus).label}</strong>
                          {' · '}<time dateTime={item.occurredAt}>{formatDateTime(item.occurredAt)}</time>
                          {item.reason && <p>Reason: {item.reason.replaceAll('_', ' ').toLowerCase()}</p>}
                          {item.note && <p>Note: {item.note}</p>}
                        </li>
                      ))}
                  </ol>
                </section>
              )}

              {/* Address Route Card */}
              <div className="tracking-grid">
                <div className="tracking-card">
                  <h3 className="card-subtitle">Pickup Location</h3>
                  <p className="address-text">{parcel.pickupAddress || 'Not recorded'}</p>
                </div>
                <div className="tracking-card">
                  <h3 className="card-subtitle">Destination Location</h3>
                  <p className="address-text">{parcel.dropoffAddress || 'Not recorded'}</p>
                </div>
              </div>

              {/* Package Details & Financial Summary */}
              <div className="tracking-meta-strip">
                <div>
                  <span className="meta-label">Weight</span>
                  <span className="meta-value">{formatWeight(parcel.weightKg)}</span>
                </div>
                <div>
                  <span className="meta-label">Dimensions</span>
                  <span className="meta-value">{formatDimensions(parcel.dimensions)}</span>
                </div>
                <div>
                  <span className="meta-label">Delivery Fee</span>
                  <span className="meta-value">
                    {formatCurrency(parcel.amountMinorUnits, parcel.currency)}
                  </span>
                </div>
                <div>
                  <span className="meta-label">Booked At</span>
                  <span className="meta-value">{formatDateTime(parcel.createdAt)}</span>
                </div>
              </div>

              {/* Receipt Action Card */}
              <div className="tracking-receipt-card">
                <div>
                  <h3 className="receipt-heading">Official Delivery Receipt</h3>
                  <p className="receipt-subtext">
                    {parcel.currentStatus === 'DELIVERED'
                      ? 'Official PDF receipt with immutable fee snapshot and completion metadata.'
                      : parcel.currentStatus === 'FAILED'
                        ? 'Receipts are only issued for delivered parcels; this delivery failed.'
                        : 'Official PDF receipt will become available immediately upon successful delivery.'}
                  </p>
                </div>
                <ReceiptDownloadButton
                  parcelId={parcel.parcelId}
                  trackingNumber={parcel.trackingNumber}
                  currentStatus={parcel.currentStatus}
                  isDownloading={isDownloadingReceipt}
                  onDownload={onDownloadReceipt}
                  variant="primary"
                />
              </div>

              {/* SSE Stream Protocol Notice & Live Status */}
              <aside className="sse-notice" aria-label="Streaming note">
                <span
                  className={`sse-dot ${
                    streamState.status === 'connected'
                      ? 'is-live'
                      : streamState.status === 'reconnecting'
                        ? 'is-reconnecting'
                        : streamState.status === 'error' || streamState.status === 'forbidden'
                          ? 'is-error'
                          : 'is-disconnected'
                  }`}
                  aria-hidden="true"
                />
                <div className="sse-notice-body">
                  <p>
                    <strong>Real-Time Updates:</strong>{' '}
                    {streamState.status === 'connected' && (
                      <span className="sse-status-badge is-live">Live stream active</span>
                    )}
                    {streamState.status === 'reconnecting' && (
                      <span className="sse-status-badge is-reconnecting">
                        Interrupted — reconnecting (attempt {streamState.retryAttempt}/{streamState.maxRetries})...
                      </span>
                    )}
                    {streamState.status === 'connecting' && (
                      <span className="sse-status-badge is-connecting">Connecting live stream...</span>
                    )}
                    {streamState.status === 'disconnected' && (
                      <span className="sse-status-badge is-disconnected">
                        {streamState.isOffline ? 'Offline — updates paused' : 'Disconnected'}
                      </span>
                    )}
                    {streamState.status === 'auth-required' && (
                      <span className="sse-status-badge is-error">Authentication expired. Please sign in again.</span>
                    )}
                    {streamState.status === 'forbidden' && (
                      <span className="sse-status-badge is-error">Access denied to parcel live stream.</span>
                    )}
                    {streamState.status === 'error' && (
                      <span className="sse-status-badge is-error">
                        {streamState.errorMessage || 'Connection recovery failed.'}
                      </span>
                    )}
                    {streamState.status === 'idle' && (
                      <span>Live stream initialized for authenticated viewer.</span>
                    )}
                  </p>
                  {streamState.status === 'error' && (
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      className="sse-retry-btn"
                      onClick={retryStream}
                      aria-label="Retry live updates connection"
                    >
                      Retry Connection
                    </Button>
                  )}
                </div>
              </aside>
            </div>
          )}
        </div>

        <footer className="modal-footer">
          <Button type="button" variant="secondary" onClick={onClose}>
            Close
          </Button>
        </footer>
      </div>
    </div>
  )
}
