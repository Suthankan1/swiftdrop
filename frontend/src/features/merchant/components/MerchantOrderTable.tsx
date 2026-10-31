import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../../components/ui/Button'
import type { MerchantOrderSummaryItem } from '../api/merchantDashboardContracts'
import { formatCurrency, formatDateTime, getStatusPresentation } from './merchantUiUtils'

interface MerchantOrderTableProps {
  orders: MerchantOrderSummaryItem[]
  currentPage: number
  pageSize: number
  totalItems: number
  onPageChange: (newPage: number) => void
  onSelectTracking?: (trackingNumber: string) => void
  isLoading?: boolean
  hasFiltersApplied?: boolean
  onResetFilters?: () => void
}

export function MerchantOrderTable({
  orders,
  currentPage,
  pageSize,
  totalItems,
  onPageChange,
  onSelectTracking,
  isLoading,
  hasFiltersApplied,
  onResetFilters,
}: MerchantOrderTableProps) {
  const [copiedId, setCopiedId] = useState<string | null>(null)

  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))
  const startIndex = currentPage * pageSize
  const pagedOrders = orders.slice(startIndex, startIndex + pageSize)

  const handleCopy = async (text: string, id: string) => {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(text)
        setCopiedId(id)
        setTimeout(() => setCopiedId(null), 2000)
      }
    } catch {
      // Ignore clipboard write failures in test or restricted environments
    }
  }

  if (orders.length === 0) {
    return (
      <div className="card empty-state-box merchant-orders-empty" role="status">
        <div className="state-icon" aria-hidden="true">📦</div>
        <h3>No orders found</h3>
        {hasFiltersApplied ? (
          <>
            <p className="muted">
              No orders matched your current status filter or search query.
            </p>
            {onResetFilters && (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={onResetFilters}
              >
                Reset Filters
              </Button>
            )}
          </>
        ) : (
          <p className="muted">
            You do not have any commercial orders booked yet. Shipments booked through your machine API or customer dispatches will appear here.
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="history-table-container merchant-orders-table-container" aria-busy={isLoading}>
      <div className="table-responsive-wrapper">
        <table className="delivery-log-table merchant-orders-table" aria-label="Merchant orders list">
          <thead>
            <tr>
              <th scope="col">Tracking Number</th>
              <th scope="col">Parcel ID</th>
              <th scope="col">Status</th>
              <th scope="col">Weight</th>
              <th scope="col">Delivery Fee</th>
              <th scope="col">Created Date</th>
              <th scope="col">Last Update</th>
              <th scope="col" className="text-right">Action</th>
            </tr>
          </thead>
          <tbody>
            {pagedOrders.map((order) => {
              const statusInfo = getStatusPresentation(order.currentStatus)
              const isTrackingCopied = copiedId === `track-${order.parcelId}`

              return (
                <tr key={order.parcelId} className="merchant-order-row">
                  {/* Tracking Number */}
                  <td className="tracking-number-cell">
                    <div className="tracking-inline-wrap">
                      <span className="monospace tracking-text font-bold">
                        {order.trackingNumber}
                      </span>
                      <button
                        type="button"
                        className="copy-btn-inline"
                        onClick={() => void handleCopy(order.trackingNumber, `track-${order.parcelId}`)}
                        aria-label={`Copy tracking number ${order.trackingNumber}`}
                        title="Copy tracking number"
                      >
                        {isTrackingCopied ? '✓' : '⧉'}
                      </button>
                      {onSelectTracking && (
                        <button
                          type="button"
                          className="safe-track-link-btn"
                          onClick={() => onSelectTracking(order.trackingNumber)}
                          aria-label={`Inspect tracking checkpoint for ${order.trackingNumber}`}
                        >
                          Track ↗
                        </button>
                      )}
                    </div>
                  </td>

                  {/* Parcel ID (Truncated) */}
                  <td className="parcel-id-cell">
                    <span
                      className="monospace parcel-id-snippet"
                      title={order.parcelId}
                      aria-label={`Parcel ID ${order.parcelId}`}
                    >
                      {order.parcelId.length > 12 ? `${order.parcelId.slice(0, 8)}...` : order.parcelId}
                    </span>
                  </td>

                  {/* Status Badge */}
                  <td>
                    <span
                      className={statusInfo.pillClass}
                      title={statusInfo.description}
                      role="status"
                    >
                      {statusInfo.label}
                    </span>
                  </td>

                  {/* Weight */}
                  <td className="monospace">
                    {order.weightKg.toFixed(2)} kg
                  </td>

                  {/* Delivery Fee */}
                  <td className="monospace font-semibold">
                    {formatCurrency(order.feeMinorUnits, order.currency)}
                  </td>

                  {/* Created At */}
                  <td className="muted text-small">
                    {formatDateTime(order.createdAt)}
                  </td>

                  {/* Last Update */}
                  <td className="muted text-small">
                    {formatDateTime(order.lastStatusUpdate)}
                  </td>

                  {/* Actions */}
                  <td className="text-right">
                    <Link
                      to={`/merchant/orders/${encodeURIComponent(order.parcelId)}`}
                      className="button button-secondary button-small view-order-btn"
                      aria-label={`View details for order ${order.trackingNumber}`}
                    >
                      View Details →
                    </Link>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination Footer */}
      {totalPages > 1 && (
        <nav
          className="pagination-controls-row"
          aria-label="Merchant order list pagination"
        >
          <span className="pagination-info muted">
            Showing <strong>{startIndex + 1}</strong> to <strong>{Math.min(startIndex + pageSize, totalItems)}</strong> of <strong>{totalItems}</strong>
          </span>

          <div className="pagination-buttons">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => onPageChange(currentPage - 1)}
              disabled={currentPage === 0 || isLoading}
              aria-label="Previous page"
            >
              ← Previous
            </Button>
            <span className="pagination-current-page" aria-current="page">
              Page {currentPage + 1} of {totalPages}
            </span>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => onPageChange(currentPage + 1)}
              disabled={currentPage >= totalPages - 1 || isLoading}
              aria-label="Next page"
            >
              Next →
            </Button>
          </div>
        </nav>
      )}
    </div>
  )
}
