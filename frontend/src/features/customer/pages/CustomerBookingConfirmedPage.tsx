import { useMemo, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { createDeliveryApi, DeliveryError } from '../api/delivery'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import { CustomerNav } from '../components/CustomerNav'
import { formatCurrency, formatDateTime, getStatusPresentation } from '../components/customerUiUtils'
import { Button } from '../../../components/ui/Button'

export function CustomerBookingConfirmedPage() {
  const auth = useAuth()
  const { parcelId } = useParams<{ parcelId: string }>()
  const location = useLocation()

  // Grab parcel state passed from navigation if available
  const locationParcel = (location.state as { parcel?: ParcelDetailResponse } | null)?.parcel

  const [copied, setCopied] = useState(false)

  const deliveryApi = useMemo(
    () =>
      createDeliveryApi({
        getAccessToken: auth.getAccessToken,
      }),
    [auth.getAccessToken],
  )

  // Query to support page refresh, deep linking, and bookmarking
  const parcelQuery = useQuery({
    queryKey: ['private', 'parcel-detail', parcelId],
    enabled: Boolean(parcelId) && auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('CUSTOMER'),
    queryFn: async ({ signal }) => {
      if (!parcelId) throw new DeliveryError('not-found', 'Missing parcel reference.')
      return deliveryApi.getParcel(parcelId, signal)
    },
    initialData: locationParcel?.parcelId === parcelId ? locationParcel : undefined,
    staleTime: 30_000,
  })

  const parcel = parcelQuery.data

  async function handleCopyTracking(trackingNumber: string) {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      try {
        await navigator.clipboard.writeText(trackingNumber)
        setCopied(true)
        setTimeout(() => setCopied(false), 2500)
      } catch {
        // Fallback if clipboard permission is denied
      }
    }
  }

  const statusPresentation = parcel ? getStatusPresentation(parcel.currentStatus) : null

  return (
    <div className="page-container customer-page customer-confirmed-page">
      <header className="page-heading">
        <p className="eyebrow">Customer space</p>
        <h1>Booking Confirmed &amp; <br />Scheduled for Dispatch.</h1>
        <p className="muted">
          Your delivery booking has been successfully committed to Delivery Operations.
        </p>
      </header>

      <CustomerNav />

      {/* Loading state */}
      {parcelQuery.isPending && !parcel && (
        <div className="card loading-confirmation-card" aria-busy="true">
          <span className="btn-spinner large" aria-hidden="true" />
          <p>Retrieving authoritative booking details...</p>
        </div>
      )}

      {/* Error state */}
      {parcelQuery.isError && !parcel && (
        <div className="card confirmation-error-card" role="alert">
          <div className="error-icon" aria-hidden="true">⚠️</div>
          <h2>Unable to Load Booking</h2>
          <p className="muted">
            {parcelQuery.error instanceof DeliveryError && parcelQuery.error.kind === 'forbidden'
              ? 'You are not authorized to view this parcel booking. Ensure you are signed in with the owning account.'
              : parcelQuery.error instanceof DeliveryError && parcelQuery.error.kind === 'not-found'
                ? 'The requested parcel booking could not be located in Delivery Operations.'
                : 'A network or service failure prevented loading the booking confirmation.'}
          </p>
          <div className="action-button-group">
            <Button
              variant="secondary"
              onClick={() => void parcelQuery.refetch()}
            >
              Retry
            </Button>
            <Link to="/customer/history" className="button button-primary">
              View Delivery History
            </Link>
          </div>
        </div>
      )}

      {/* Confirmed booking content */}
      {parcel && (
        <div className="confirmed-booking-wrap">
          {/* Success Banner Hero Card */}
          <div className="card confirmed-hero-card" aria-labelledby="confirmation-title">
            <div className="confirmed-badge-row">
              <span className="success-check-badge" aria-hidden="true">✓</span>
              <div>
                <h2 id="confirmation-title">Booking Reference &amp; Status</h2>
                <p className="muted text-sm">
                  Recorded on {formatDateTime(parcel.createdAt)}
                </p>
              </div>
              {statusPresentation && (
                <span className={statusPresentation.pillClass}>
                  {statusPresentation.label}
                </span>
              )}
            </div>

            {/* Tracking Reference Hero */}
            <div className="tracking-reference-hero">
              <span className="eyebrow">Canonical Tracking Number</span>
              <div className="tracking-number-display">
                <code className="font-mono tracking-code">{parcel.trackingNumber}</code>
                <Button
                  variant="secondary"
                  className="copy-tracking-btn"
                  onClick={() => void handleCopyTracking(parcel.trackingNumber)}
                  aria-label={copied ? 'Tracking number copied to clipboard' : 'Copy tracking number to clipboard'}
                >
                  {copied ? '✓ Copied' : 'Copy Tracking Number'}
                </Button>
              </div>
              <p className="tracking-hero-hint muted text-xs">
                Share this reference with the recipient for real-time tracking updates.
              </p>
            </div>
          </div>

          {/* Details Grid */}
          <div className="confirmed-details-grid">
            {/* Card 1: Delivery & Fee Details */}
            <div className="card details-summary-card">
              <div className="card-header">
                <h3>Delivery &amp; Tariff Summary</h3>
              </div>
              <dl className="summary-dl">
                <div className="summary-row">
                  <dt>Confirmed Delivery Fee</dt>
                  <dd className="font-numeric font-bold text-teal">
                    {formatCurrency(parcel.amountMinorUnits, parcel.currency)}
                  </dd>
                </div>
                <div className="summary-row">
                  <dt>Rate Table Version</dt>
                  <dd><code>{parcel.rateVersion}</code></dd>
                </div>
                <div className="summary-row">
                  <dt>Parcel Weight</dt>
                  <dd className="font-numeric">{parcel.weightKg} kg</dd>
                </div>
                <div className="summary-row">
                  <dt>Dimensions (L×W×H)</dt>
                  <dd>{parcel.dimensions} cm</dd>
                </div>
                <div className="summary-row">
                  <dt>Internal Booking UUID</dt>
                  <dd className="font-mono text-xs">{parcel.parcelId}</dd>
                </div>
              </dl>
            </div>

            {/* Card 2: Routing & Addresses */}
            <div className="card routing-summary-card">
              <div className="card-header">
                <h3>Addresses &amp; Logistics</h3>
              </div>
              <div className="route-addresses">
                <div className="address-block">
                  <span className="address-label">Pickup Location</span>
                  <p className="address-text">{parcel.pickupAddress}</p>
                </div>
                <div className="route-arrow-down" aria-hidden="true">↓</div>
                <div className="address-block">
                  <span className="address-label">Destination Address</span>
                  <p className="address-text">{parcel.dropoffAddress}</p>
                </div>
              </div>
              <div className="courier-assignment-status">
                <span className="status-label">Courier Assignment:</span>
                <span className="status-value muted">
                  {parcel.activeCourierSub ? 'Courier Assigned' : 'Awaiting Dispatcher Assignment'}
                </span>
              </div>
            </div>
          </div>

          {/* Next Steps Guidance Card */}
          <div className="card next-steps-card">
            <h3>Next Steps in Your Delivery</h3>
            <ol className="next-steps-list">
              <li>
                <div className="step-number">1</div>
                <div className="step-content">
                  <strong>Courier Collection</strong>
                  <p className="muted text-sm">
                    A dispatcher will assign a courier to collect your parcel from the specified pickup address.
                  </p>
                </div>
              </li>
              <li>
                <div className="step-number">2</div>
                <div className="step-content">
                  <strong>Milestone Tracking</strong>
                  <p className="muted text-sm">
                    Track parcel transit through Western Province hubs in your Delivery History or public tracking.
                  </p>
                </div>
              </li>
              <li>
                <div className="step-number">3</div>
                <div className="step-content">
                  <strong>Official PDF Delivery Receipt</strong>
                  <p className="muted text-sm">
                    Once marked as DELIVERED, an authorized PDF receipt with tamper-evident fee snapshot is available for download.
                  </p>
                </div>
              </li>
            </ol>

            <div className="confirmed-actions-row">
              <Link to="/customer/history" className="button button-primary">
                View in Delivery History
              </Link>
              <Link to="/customer/quote" className="button button-secondary">
                Book Another Delivery
              </Link>
              <Link to="/customer" className="button button-secondary">
                Return to Profile
              </Link>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
