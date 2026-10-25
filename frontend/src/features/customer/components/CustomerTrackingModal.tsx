import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { createDeliveryApi } from '../api/delivery'
import { DeliveryError } from '../api/delivery'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import {
  formatCurrency,
  formatDateTime,
  getActiveStepIndex,
  getStatusPresentation,
  LIFECYCLE_STEPS,
} from './customerUiUtils'
import { ReceiptDownloadButton } from './ReceiptDownloadButton'

interface CustomerTrackingModalProps {
  parcelId: string | null
  trackingNumber?: string
  isOpen: boolean
  onClose: () => void
  deliveryApi: ReturnType<typeof createDeliveryApi>
  onDownloadReceipt: (parcelId: string) => Promise<void>
  isDownloadingReceipt: boolean
}

export function CustomerTrackingModal({
  parcelId,
  trackingNumber: initialTrackingNumber,
  isOpen,
  onClose,
  deliveryApi,
  onDownloadReceipt,
  isDownloadingReceipt,
}: CustomerTrackingModalProps) {
  const [copyFeedback, setCopyFeedback] = useState(false)
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const modalRef = useRef<HTMLDivElement>(null)

  const query = useQuery({
    queryKey: ['private', 'parcel-detail', parcelId],
    enabled: isOpen && !!parcelId,
    queryFn: async ({ signal }) => {
      if (!parcelId) throw new DeliveryError('not-found', 'No parcel identifier provided.')
      return deliveryApi.getParcel(parcelId, signal)
    },
    staleTime: 10_000,
  })

  // Keyboard navigation & focus management
  useEffect(() => {
    if (isOpen) {
      closeButtonRef.current?.focus()
      const handleKeyDown = (e: globalThis.KeyboardEvent) => {
        if (e.key === 'Escape') {
          onClose()
        }
      }
      window.addEventListener('keydown', handleKeyDown)
      return () => window.removeEventListener('keydown', handleKeyDown)
    }
  }, [isOpen, onClose])

  if (!isOpen) return null

  const parcel: ParcelDetailResponse | undefined = query.data
  const activeStatus = parcel?.currentStatus || 'CREATED'
  const presentation = getStatusPresentation(activeStatus)
  const activeStep = getActiveStepIndex(activeStatus)
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
          {query.isPending && (
            <div className="tracking-loading-state" aria-busy="true">
              <span className="btn-spinner large" aria-hidden="true" />
              <p>Fetching authoritative parcel state...</p>
            </div>
          )}

          {query.isError && (
            <div className="state-panel state-panel-error" role="alert">
              <h3>Unable to load parcel details</h3>
              <p>
                {query.error instanceof DeliveryError && query.error.kind === 'forbidden'
                  ? 'Access denied. You do not have permission to view this parcel.'
                  : query.error instanceof DeliveryError && query.error.kind === 'not-found'
                    ? 'The requested parcel could not be found.'
                    : 'A temporary network or server error occurred. Please try again.'}
              </p>
              <button
                type="button"
                className="button button-secondary"
                onClick={() => void query.refetch()}
              >
                Retry
              </button>
            </div>
          )}

          {parcel && (
            <div className="tracking-content">
              {/* Status Banner */}
              <div className="tracking-status-banner">
                <div className="banner-left">
                  <span className={presentation.pillClass}>{presentation.label}</span>
                  <p className="banner-description">{presentation.description}</p>
                </div>
                <button
                  type="button"
                  className="copy-tracking-btn"
                  onClick={handleCopyTracking}
                  aria-label="Copy tracking number to clipboard"
                >
                  {copyFeedback ? '✓ Copied' : 'Copy Tracking #'}
                </button>
              </div>

              {/* Progress Milestones Tracker */}
              <div className="timeline-stepper" aria-label="Delivery progress timeline">
                {LIFECYCLE_STEPS.map((step, idx) => {
                  const stepInfo = getStatusPresentation(step)
                  const isCurrent = idx === activeStep && !presentation.isFailed
                  const isPast = idx < activeStep || presentation.isSuccess
                  const isStepFailed = presentation.isFailed && idx === 4

                  let stepClass = 'stepper-node'
                  if (isPast) stepClass += ' is-past'
                  if (isCurrent) stepClass += ' is-current'
                  if (isStepFailed) stepClass += ' is-failed'

                  return (
                    <div key={step} className={stepClass}>
                      <div className="stepper-dot" aria-hidden="true">
                        {isPast ? '✓' : isStepFailed ? '✕' : idx + 1}
                      </div>
                      <span className="stepper-label">{isStepFailed ? 'Failed' : stepInfo.label}</span>
                    </div>
                  )
                })}
              </div>

              {/* Address Route Card */}
              <div className="tracking-grid">
                <div className="tracking-card">
                  <h3 className="card-subtitle">Pickup Location</h3>
                  <p className="address-text">{parcel.pickupAddress || 'Standard Origin Depot'}</p>
                </div>
                <div className="tracking-card">
                  <h3 className="card-subtitle">Destination Location</h3>
                  <p className="address-text">{parcel.dropoffAddress || 'Customer Destination'}</p>
                </div>
              </div>

              {/* Package Details & Financial Summary */}
              <div className="tracking-meta-strip">
                <div>
                  <span className="meta-label">Weight</span>
                  <span className="meta-value">{parcel.weightKg} kg</span>
                </div>
                <div>
                  <span className="meta-label">Dimensions</span>
                  <span className="meta-value">{parcel.dimensions} cm</span>
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
                        ? 'Receipt is not available because the delivery was cancelled or could not be completed.'
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

              {/* SSE Stream Protocol Notice */}
              <aside className="sse-notice" aria-label="Streaming note">
                <span className="sse-dot" aria-hidden="true" />
                <p>
                  <strong>Real-Time Updates:</strong> Live updates are refreshed via secure REST on demand.
                  Server-Sent Events (SSE) live streaming integration is scheduled for the Phase 3b platform cutover.
                </p>
              </aside>
            </div>
          )}
        </div>

        <footer className="modal-footer">
          <button type="button" className="button button-secondary" onClick={onClose}>
            Close
          </button>
        </footer>
      </div>
    </div>
  )
}
