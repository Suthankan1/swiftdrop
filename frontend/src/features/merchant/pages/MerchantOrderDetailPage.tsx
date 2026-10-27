import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  createMerchantOrdersApi,
  DeliveryError,
} from '../api/merchantOrders'
import { createDeliveryApi } from '../../customer/api/delivery'
import { MerchantNav } from '../components/MerchantNav'
import {
  formatCurrency,
  formatDateTime,
  getStatusPresentation,
} from '../components/merchantUiUtils'
import { CustomerTrackingModal } from '../../customer/components/CustomerTrackingModal'

export interface MerchantOrderDetailPageProps {
  apiOverride?: ReturnType<typeof createMerchantOrdersApi>
}

export function MerchantOrderDetailPage({ apiOverride }: MerchantOrderDetailPageProps) {
  const { parcelId } = useParams<{ parcelId: string }>()
  const auth = useAuth()
  const isMerchant = auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT')
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''

  const [copiedTracking, setCopiedCopiedTracking] = useState(false)
  const [isTrackingModalOpen, setIsTrackingModalOpen] = useState(false)

  const ordersApi = useMemo(
    () =>
      apiOverride ||
      createMerchantOrdersApi({
        getAccessToken: auth.getAccessToken,
      }),
    [apiOverride, auth.getAccessToken],
  )

  const deliveryApi = useMemo(
    () =>
      createDeliveryApi({
        getAccessToken: auth.getAccessToken,
      }),
    [auth.getAccessToken],
  )

  // Fetch Authoritative Order Detail
  const detailQuery = useQuery({
    queryKey: ['private', 'merchant-order-detail', subject, parcelId],
    enabled: isMerchant && !!parcelId && !!subject,
    queryFn: async ({ signal }) => {
      if (!parcelId) throw new DeliveryError('not-found', 'Missing parcel identifier.')
      return ordersApi.getOrder(parcelId, signal)
    },
    gcTime: 0,
    staleTime: 15_000,
  })

  // Fetch Append-Only Status Transition History
  const historyQuery = useQuery({
    queryKey: ['private', 'merchant-order-history', subject, parcelId],
    enabled: isMerchant && !!parcelId && !!subject && !detailQuery.isError,
    queryFn: async ({ signal }) => {
      if (!parcelId) return null
      return ordersApi.getOrderHistory(parcelId, 0, 50, signal)
    },
    gcTime: 0,
    staleTime: 15_000,
  })

  const handleCopyTracking = async (trackingNum: string) => {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(trackingNum)
        setCopiedCopiedTracking(true)
        setTimeout(() => setCopiedCopiedTracking(false), 2000)
      }
    } catch {
      // Ignore copy error in headless tests
    }
  }

  const order = detailQuery.data
  const statusInfo = order ? getStatusPresentation(order.currentStatus) : null

  return (
    <div className="page-container merchant-page merchant-order-detail-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>Commercial Order Details.<br />Fulfillment &amp; History.</h1>
      </header>

      <MerchantNav />

      {/* Navigation Return Link */}
      <div className="detail-navigation-bar">
        <Link
          to="/merchant/orders"
          className="button button-secondary button-small back-button"
          aria-label="Back to orders dashboard"
        >
          ← Back to Orders Dashboard
        </Link>
      </div>

      {/* Loading State */}
      {detailQuery.isPending && (
        <div className="card loading-history-card" role="status" aria-busy="true">
          <span className="btn-spinner large" aria-hidden="true" />
          <p>Retrieving authorized order details...</p>
        </div>
      )}

      {/* Access Denied / 403 Forbidden State */}
      {detailQuery.isError && detailQuery.error instanceof DeliveryError && detailQuery.error.kind === 'forbidden' && (
        <div className="card state-panel state-panel-error" role="alert">
          <h2>Access Denied</h2>
          <p>
            You are not authorized to inspect this order. The parcel belongs to another merchant tenant or your merchant profile is not active.
          </p>
          <Link to="/merchant/orders" className="button button-secondary">
            Return to Orders Dashboard
          </Link>
        </div>
      )}

      {/* 404 Not Found State */}
      {detailQuery.isError && detailQuery.error instanceof DeliveryError && detailQuery.error.kind === 'not-found' && (
        <div className="card state-panel state-panel-warning" role="alert">
          <h2>Order Not Found</h2>
          <p>
            The requested order ID does not exist or may have been canceled.
          </p>
          <Link to="/merchant/orders" className="button button-secondary">
            Return to Orders Dashboard
          </Link>
        </div>
      )}

      {/* General Service Error State */}
      {detailQuery.isError && !(detailQuery.error instanceof DeliveryError && (detailQuery.error.kind === 'forbidden' || detailQuery.error.kind === 'not-found')) && (
        <div className="card state-panel state-panel-error" role="alert">
          <h2>Unable to Load Order</h2>
          <p>
            {detailQuery.error.message || 'A service or network issue occurred while loading order details.'}
          </p>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => void detailQuery.refetch()}
          >
            Retry Order
          </button>
        </div>
      )}

      {/* Authorized Order Detail Body */}
      {order && statusInfo && (
        <div className="courier-parcel-detail-view merchant-order-detail-view">
          {/* Header Card */}
          <section className="card parcel-detail-header-card" aria-labelledby="order-tracking-heading">
            <div className="section-heading-row">
              <div>
                <p className="eyebrow">Authoritative Shipment Record</p>
                <h2 id="order-tracking-heading" className="tracking-number-heading monospace">
                  {order.trackingNumber}
                </h2>
              </div>
              <div className="status-badges-group">
                <span className={statusInfo.pillClass} role="status" aria-label={statusInfo.description}>
                  {statusInfo.label}
                </span>
                <span className="status-pill badge-version">
                  v{order.version}
                </span>
              </div>
            </div>

            {/* Quick Actions & Tracking Modal Launcher */}
            <div className="tracking-action-bar">
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={() => void handleCopyTracking(order.trackingNumber)}
                aria-label={`Copy tracking number ${order.trackingNumber}`}
              >
                {copiedTracking ? '✓ Copied' : '⧉ Copy Tracking #'}
              </button>
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={() => setIsTrackingModalOpen(true)}
                aria-label="Inspect public tracking checkpoints"
              >
                View Tracking Checkpoints ↗
              </button>
              <span className="muted read-only-tag">
                🔒 Read-Only Merchant View
              </span>
            </div>

            {/* Core Specifications Grid */}
            <div className="parcel-specs-grid">
              <div className="spec-item">
                <span className="spec-label">Parcel ID</span>
                <span className="spec-value monospace text-small">{order.parcelId}</span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Weight</span>
                <span className="spec-value monospace">{order.weightKg.toFixed(2)} kg</span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Dimensions</span>
                <span className="spec-value">{order.dimensions || 'STANDARD'}</span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Delivery Fee</span>
                <span className="spec-value monospace font-bold">
                  {formatCurrency(order.amountMinorUnits, order.currency)}
                </span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Rate Version</span>
                <span className="spec-value monospace">{order.rateVersion || 'Standard'}</span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Booked At</span>
                <span className="spec-value">{formatDateTime(order.createdAt)}</span>
              </div>
            </div>
          </section>

          {/* Logistics & Route Details Card */}
          <section className="card parcel-routing-card" aria-labelledby="route-heading">
            <h3 id="route-heading" className="card-subheading">Routing &amp; Courier Assignment</h3>
            <div className="routing-grid">
              <div className="route-endpoint-box">
                <span className="address-label">Pickup Origin</span>
                <p className="address-text">{order.pickupAddress || 'Commercial Merchant Warehouse'}</p>
              </div>

              <div className="route-arrow-down" aria-hidden="true">↓</div>

              <div className="route-endpoint-box">
                <span className="address-label">Dropoff Destination</span>
                <p className="address-text">{order.dropoffAddress || 'Customer Recipient Address'}</p>
              </div>
            </div>

            <div className="courier-assignment-status">
              <span className="muted">Assigned Courier:</span>
              <span className="font-semibold">
                {order.activeCourierSub ? order.activeCourierSub : 'Pending Dispatch Assignment'}
              </span>
            </div>
          </section>

          {/* Status Transition History Card */}
          <section className="card parcel-history-card" aria-labelledby="history-heading">
            <h3 id="history-heading" className="card-subheading">Status Transition History</h3>
            <p className="muted small-note">
              Append-only audit trail recorded by the delivery state machine.
            </p>

            {historyQuery.isPending && (
              <p className="muted text-small" role="status">Loading transition audit trail...</p>
            )}

            {historyQuery.data && historyQuery.data.items.length > 0 ? (
              <div className="table-responsive-wrapper">
                <table className="delivery-log-table" aria-label="Order status history">
                  <thead>
                    <tr>
                      <th scope="col">Version</th>
                      <th scope="col">Transition</th>
                      <th scope="col">Timestamp</th>
                      <th scope="col">Reason / Note</th>
                    </tr>
                  </thead>
                  <tbody>
                    {historyQuery.data.items.map((item) => {
                      const newStatusInfo = getStatusPresentation(item.newStatus)
                      return (
                        <tr key={item.historyId}>
                          <td className="monospace text-small">v{item.resultingVersion}</td>
                          <td>
                            <div className="transition-status-flow">
                              {item.previousStatus && (
                                <>
                                  <span className="muted text-small">{item.previousStatus}</span>
                                  <span className="transition-arrow">→</span>
                                </>
                              )}
                              <span className={newStatusInfo.pillClass}>
                                {newStatusInfo.label}
                              </span>
                            </div>
                          </td>
                          <td className="muted text-small">{formatDateTime(item.occurredAt)}</td>
                          <td className="text-small">{item.note || item.reason || '—'}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              !historyQuery.isPending && (
                <p className="muted text-small">No lifecycle transitions recorded yet.</p>
              )
            )}
          </section>
        </div>
      )}

      {/* Safe Tracking Modal Dialog */}
      <CustomerTrackingModal
        parcelId={order?.parcelId ?? null}
        trackingNumber={order?.trackingNumber}
        isOpen={isTrackingModalOpen}
        onClose={() => setIsTrackingModalOpen(false)}
        deliveryApi={deliveryApi}
        onDownloadReceipt={async () => {}}
        isDownloadingReceipt={false}
      />
    </div>
  )
}
