import { useState } from 'react'
import { useParams, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { createDeliveryApi, DeliveryError } from '../customer/api/delivery'
import type { PublicTrackingResponse } from '../customer/api/deliveryContracts'
import { formatDateTime, getStatusPresentation } from '../customer/components/customerUiUtils'

export function PublicTrackingPage() {
  const { trackingNumber: routeTrackingNumber } = useParams<{ trackingNumber?: string }>()
  const navigate = useNavigate()
  const [searchInput, setSearchInput] = useState(routeTrackingNumber || '')
  const [prevRouteTrackingNumber, setPrevRouteTrackingNumber] = useState(routeTrackingNumber)
  const [copied, setCopied] = useState(false)

  if (routeTrackingNumber !== prevRouteTrackingNumber) {
    setPrevRouteTrackingNumber(routeTrackingNumber)
    setSearchInput(routeTrackingNumber || '')
  }

  const activeTrackingNumber = routeTrackingNumber || null
  const deliveryApi = createDeliveryApi()

  const trackingQuery = useQuery({
    queryKey: ['public', 'tracking', activeTrackingNumber],
    enabled: !!activeTrackingNumber,
    queryFn: async ({ signal }) => {
      if (!activeTrackingNumber) throw new DeliveryError('not-found', 'No tracking number provided.')
      return deliveryApi.getTracking(activeTrackingNumber, signal)
    },
    staleTime: 15_000,
  })

  function handleSearchSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = searchInput.trim().toUpperCase()
    if (!trimmed) return
    navigate(`/tracking/${encodeURIComponent(trimmed)}`)
  }

  async function handleCopy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard error
    }
  }

  const tracking: PublicTrackingResponse | undefined = trackingQuery.data
  const activeStatus = tracking?.currentStatus || 'CREATED'
  const presentation = getStatusPresentation(activeStatus)

  return (
    <div className="page-container public-tracking-page">
      <header className="page-heading">
        <p className="eyebrow">Public parcel tracking</p>
        <h1>Track Your Shipment.</h1>
        <p className="muted">
          Real-time milestone checkpoints and coarse transit updates without login.
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
        <form onSubmit={handleSearchSubmit} className="lookup-form" role="search">
          <input
            type="text"
            className="lookup-input"
            placeholder="e.g. SWD-20261023-A1B2C3"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            aria-label="Tracking reference"
            required
            pattern="^SWD-[0-9]{8}-[A-Z0-9]{4,8}$"
            title="Tracking number format: SWD-YYYYMMDD-XXXX"
          />
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
        <div className="card loading-history-card" aria-busy="true">
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
              : trackingQuery.error instanceof DeliveryError && trackingQuery.error.kind === 'unavailable'
              ? 'Tracking service is temporarily unavailable. Please retry in a few moments.'
              : trackingQuery.error.message || 'An error occurred while tracking the shipment.'}
          </p>
        </div>
      )}

      {/* Successful Tracking Milestones */}
      {tracking && (
        <section className="tracking-results-section" aria-labelledby="tracking-details-heading">
          {/* Status Header Strip */}
          <div className="tracking-status-banner">
            <div>
              <span className={presentation.pillClass}>{presentation.label}</span>
              <h2 id="tracking-details-heading" className="monospace tracking-number-heading">
                {tracking.trackingNumber}
              </h2>
              <p className="muted">{presentation.description}</p>
            </div>
            <button
              type="button"
              className="button button-secondary copy-tracking-btn"
              onClick={() => handleCopy(tracking.trackingNumber)}
              aria-label={copied ? 'Reference copied' : 'Copy tracking reference'}
            >
              {copied ? '✓ Copied' : 'Copy reference'}
            </button>
          </div>

          {/* Transit Route Summary */}
          {(tracking.originCity || tracking.destinationCity) && (
            <div className="tracking-meta-strip" style={{ marginTop: '16px' }}>
              <div>
                <span className="meta-label">Origin Hub</span>
                <strong className="meta-value">{tracking.originCity || 'Colombo'}</strong>
              </div>
              <div>
                <span className="meta-label">Destination</span>
                <strong className="meta-value">{tracking.destinationCity || 'Western Province'}</strong>
              </div>
            </div>
          )}

          {/* Chronological Milestone Timeline */}
          <div className="card tracking-card" style={{ marginTop: '16px' }}>
            <h3>Transit Milestones</h3>
            <ol className="timeline-list" aria-label="Tracking history milestones">
              {tracking.timeline.map((item, idx) => {
                const itemPresentation = getStatusPresentation(item.status)
                return (
                  <li key={`${item.status}-${idx}`} className="timeline-item">
                    <div className="timeline-marker" aria-hidden="true" />
                    <div className="timeline-content">
                      <div className="timeline-header">
                        <span className={itemPresentation.pillClass}>{itemPresentation.label}</span>
                        <time dateTime={item.occurredAt} className="muted text-sm">
                          {formatDateTime(item.occurredAt)}
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
          </div>
        </section>
      )}
    </div>
  )
}
