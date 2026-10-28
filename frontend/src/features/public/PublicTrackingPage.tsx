import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { createDeliveryApi, DeliveryError, isValidTrackingNumber } from '../customer/api/delivery'
import type { PublicTrackingResponse } from '../customer/api/deliveryContracts'
import { formatDateTime, getStatusPresentation } from '../customer/components/customerUiUtils'
import { usePublicTrackingStream } from '../customer/hooks/usePublicTrackingStream'

function safeDateTime(isoString: string): string {
  try {
    const timestamp = Date.parse(isoString)
    if (Number.isNaN(timestamp)) return 'Date unavailable'
    return formatDateTime(isoString)
  } catch {
    return 'Date unavailable'
  }
}

export function PublicTrackingPage() {
  const { trackingNumber: routeTrackingNumber } = useParams<{ trackingNumber?: string }>()
  const navigate = useNavigate()
  const [searchInput, setSearchInput] = useState(routeTrackingNumber || '')
  const [prevRouteTrackingNumber, setPrevRouteTrackingNumber] = useState(routeTrackingNumber)
  const [formError, setFormError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  if (routeTrackingNumber !== prevRouteTrackingNumber) {
    setPrevRouteTrackingNumber(routeTrackingNumber)
    setSearchInput(routeTrackingNumber || '')
    setFormError(null)
  }

  // Set privacy search indexing restrictions
  useEffect(() => {
    let metaRobots = document.querySelector('meta[name="robots"]')
    let created = false
    if (!metaRobots) {
      metaRobots = document.createElement('meta')
      metaRobots.setAttribute('name', 'robots')
      document.head.appendChild(metaRobots)
      created = true
    }
    metaRobots.setAttribute('content', 'noindex, nofollow')

    return () => {
      if (created && metaRobots?.parentNode) {
        metaRobots.parentNode.removeChild(metaRobots)
      }
    }
  }, [])

  const activeTrackingNumber = routeTrackingNumber ? routeTrackingNumber.trim().toUpperCase() : null
  const deliveryApi = createDeliveryApi()

  const trackingQuery = useQuery({
    queryKey: ['public', 'tracking', activeTrackingNumber],
    enabled: !!activeTrackingNumber,
    queryFn: async ({ signal }) => {
      if (!activeTrackingNumber) {
        throw new DeliveryError('not-found', 'No tracking number provided.', undefined, 404)
      }
      if (!isValidTrackingNumber(activeTrackingNumber)) {
        throw new DeliveryError(
          'validation-error',
          'Enter a valid SwiftDrop tracking number (format: SWD-YYYYMMDD-XXXX).',
          undefined,
          400,
        )
      }
      return deliveryApi.getTracking(activeTrackingNumber, signal)
    },
    staleTime: 15_000,
    retry: false,
    refetchOnWindowFocus: false,
  })

  function handleSearchSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = searchInput.trim().toUpperCase()
    if (!trimmed) {
      setFormError('Please enter a tracking reference.')
      return
    }
    if (!isValidTrackingNumber(trimmed)) {
      setFormError('Enter a valid SwiftDrop tracking number (format: SWD-YYYYMMDD-XXXX).')
      return
    }
    setFormError(null)
    navigate(`/tracking/${encodeURIComponent(trimmed)}`)
  }

  async function handleCopy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const { tracking: streamTracking, streamState } = usePublicTrackingStream({
    trackingNumber: activeTrackingNumber,
    enabled: !!activeTrackingNumber && !!trackingQuery.data,
    initialTracking: trackingQuery.data,
    reconcileRest: async (signal) => {
      if (!activeTrackingNumber) return null
      return deliveryApi.getTracking(activeTrackingNumber, signal)
    },
  })

  const tracking: PublicTrackingResponse | undefined = streamTracking ?? trackingQuery.data
  const activeStatus = tracking?.currentStatus || 'CREATED'
  const presentation = getStatusPresentation(activeStatus)

  return (
    <div className="page-container public-tracking-page">
      <header className="page-heading">
        <p className="eyebrow">Public parcel tracking</p>
        <h1>Track Your Shipment.</h1>
        <p className="muted">
          View the latest available delivery milestones and coarse transit updates without login.
        </p>
      </header>

      {/* Tracking Search Input Card */}
      <section className="card tracking-lookup-card" aria-labelledby="public-lookup-heading">
        <div className="lookup-header">
          <h2 id="public-lookup-heading">Shipment Reference Lookup</h2>
          <p className="muted small-note">
            Enter your SwiftDrop tracking number (format: SWD-YYYYMMDD-XXXX).
          </p>
        </div>
        <form onSubmit={handleSearchSubmit} className="lookup-form" role="search" noValidate>
          <div className="lookup-input-group">
            <label htmlFor="tracking-lookup-input" className="sr-only">
              Tracking reference
            </label>
            <input
              id="tracking-lookup-input"
              type="text"
              className={`lookup-input ${formError ? 'has-error' : ''}`}
              placeholder="e.g. SWD-20261023-A1B2C3"
              value={searchInput}
              onChange={(e) => {
                setSearchInput(e.target.value)
                if (formError) setFormError(null)
              }}
              aria-label="Tracking reference"
              aria-invalid={formError ? 'true' : 'false'}
              aria-describedby={formError ? 'tracking-lookup-error' : undefined}
              autoComplete="off"
              spellCheck="false"
              maxLength={32}
            />
            {formError && (
              <p id="tracking-lookup-error" className="input-error-msg" role="alert">
                {formError}
              </p>
            )}
          </div>
          <button
            type="submit"
            className="button button-primary lookup-submit"
            disabled={trackingQuery.isFetching}
          >
            {trackingQuery.isFetching ? 'Locating...' : 'Track Parcel'}
          </button>
        </form>
      </section>

      {/* Loading State */}
      {trackingQuery.isPending && activeTrackingNumber && (
        <div className="card loading-history-card" role="status" aria-live="polite" aria-busy="true">
          <span className="btn-spinner large" aria-hidden="true" />
          <p>Retrieving verified transit milestones for {activeTrackingNumber}...</p>
        </div>
      )}

      {/* Error / Safe Disclosure State */}
      {trackingQuery.isError && (
        <div className="card state-panel state-panel-error" role="alert">
          <h3>Unable to Track Shipment</h3>
          <p>
            {trackingQuery.error instanceof DeliveryError &&
            (trackingQuery.error.statusCode === 404 || trackingQuery.error.kind === 'not-found')
              ? `Tracking reference '${activeTrackingNumber}' was not found. Please verify the number and try again.`
              : trackingQuery.error instanceof DeliveryError && trackingQuery.error.statusCode === 429
              ? 'Too many tracking requests from this network. Please wait a moment and try again.'
              : trackingQuery.error instanceof DeliveryError && trackingQuery.error.kind === 'validation-error'
              ? 'Enter a valid SwiftDrop tracking number (format: SWD-YYYYMMDD-XXXX).'
              : trackingQuery.error instanceof DeliveryError && trackingQuery.error.kind === 'invalid-response'
              ? 'Tracking information is temporarily unavailable due to an unexpected response format. Please try again later.'
              : trackingQuery.error instanceof DeliveryError && trackingQuery.error.kind === 'unavailable'
              ? 'Tracking service is temporarily unavailable. Please retry in a few moments.'
              : trackingQuery.error.message || 'An error occurred while tracking the shipment.'}
          </p>
          {trackingQuery.error instanceof DeliveryError &&
            trackingQuery.error.statusCode !== 404 &&
            trackingQuery.error.statusCode !== 429 &&
            trackingQuery.error.kind !== 'validation-error' && (
              <button
                type="button"
                className="button button-secondary retry-btn"
                onClick={() => trackingQuery.refetch()}
              >
                Retry
              </button>
            )}
        </div>
      )}

      {/* Successful Tracking Milestones */}
      {tracking && (
        <section className="tracking-results-section" aria-labelledby="tracking-details-heading">
          {/* Status Header Strip */}
          <div className="tracking-status-banner">
            <div className="status-summary-group">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                <span className={presentation.pillClass}>{presentation.label}</span>
                {streamState.status === 'connected' && (
                  <span className="live-stream-chip" title="Live stream active" aria-label="Live updates active">
                    ● Live
                  </span>
                )}
                {streamState.status === 'reconnecting' && (
                  <span className="live-stream-chip reconnecting" title="Reconnecting to real-time stream...">
                    Reconnecting...
                  </span>
                )}
                {streamState.isOffline && (
                  <span className="live-stream-chip offline" title="Network offline">
                    Offline
                  </span>
                )}
              </div>
              <h2 id="tracking-details-heading" className="monospace tracking-number-heading">
                {tracking.trackingNumber}
              </h2>
              <p className="muted banner-description">{presentation.description}</p>
            </div>

            <div className="tracking-status-banner-actions">
              <button
                type="button"
                className="button button-secondary copy-tracking-btn"
                onClick={() => handleCopy(tracking.trackingNumber)}
                aria-label={copied ? 'Reference copied' : 'Copy tracking reference'}
              >
                {copied ? '✓ Copied' : 'Copy reference'}
              </button>
              <button
                type="button"
                className="button button-secondary refresh-tracking-btn"
                onClick={() => trackingQuery.refetch()}
                disabled={trackingQuery.isFetching}
                aria-label="Refresh tracking details"
              >
                {trackingQuery.isFetching ? 'Refreshing...' : '↻ Refresh'}
              </button>
            </div>
          </div>

          {/* Transit Route Summary */}
          {(tracking.originCity || tracking.destinationCity) && (
            <div className="tracking-meta-strip" style={{ marginTop: '16px' }}>
              <div>
                <span className="meta-label">Origin Hub</span>
                <strong className="meta-value">{tracking.originCity || 'Not available'}</strong>
              </div>
              <div>
                <span className="meta-label">Destination</span>
                <strong className="meta-value">{tracking.destinationCity || 'Not available'}</strong>
              </div>
            </div>
          )}

          {/* Chronological Milestone Timeline */}
          <div className="card tracking-card" style={{ marginTop: '16px' }}>
            <h3>Transit Milestones</h3>
            {tracking.timeline.length === 0 ? (
              <p className="muted text-sm empty-timeline-notice">
                No milestone checkpoints recorded yet for this shipment.
              </p>
            ) : (
              <ol className="timeline-list" aria-label="Tracking history milestones">
                {tracking.timeline.map((item, idx) => {
                  const itemPresentation = getStatusPresentation(item.status)
                  const hasValidTime = !Number.isNaN(Date.parse(item.occurredAt))
                  return (
                    <li key={`${item.status}-${item.occurredAt}-${idx}`} className="timeline-item">
                      <div className="timeline-marker" aria-hidden="true" />
                      <div className="timeline-content">
                        <div className="timeline-header">
                          <span className={itemPresentation.pillClass}>{itemPresentation.label}</span>
                          <time
                            dateTime={hasValidTime ? item.occurredAt : undefined}
                            className="muted text-sm"
                          >
                            {safeDateTime(item.occurredAt)}
                          </time>
                        </div>
                        {item.cityOrZone && (
                          <p className="timeline-location muted text-sm">
                            📍 {item.cityOrZone}
                          </p>
                        )}
                      </div>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        </section>
      )}
    </div>
  )
}
