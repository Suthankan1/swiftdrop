import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../../components/ui/Button'
import { ButtonLink } from '../../../components/ui/ButtonLink'
import type { MerchantOrderSummaryItem } from '../api/merchantDashboardContracts'
import { formatCurrency, formatDateTime, getStatusPresentation } from './merchantUiUtils'

interface MerchantOrderTableProps {
  orders: MerchantOrderSummaryItem[]
  currentPage: number
  pageSize: number
  totalItems: number
  onPageChange: (newPage: number) => void
  onSelectTracking?: (trackingNumber: string, parcelId: string) => void
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
  const [copyError, setCopyError] = useState(false)

  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))
  const page = Math.min(currentPage, totalPages - 1)
  const startIndex = page * pageSize
  const pagedOrders = orders.slice(startIndex, startIndex + pageSize)

  const handleCopy = async (text: string, id: string) => {
    try {
      setCopyError(false)
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(text)
        setCopiedId(id)
        setTimeout(() => setCopiedId(null), 2000)
      }
    } catch {
      setCopyError(true)
    }
  }

  if (orders.length === 0) {
    return (
      <div className="card empty-state-box merchant-orders-empty" role="status">
        <h3>{hasFiltersApplied ? 'No orders found' : 'No orders yet'}</h3>
        {hasFiltersApplied ? (
          <>
            <p className="muted">No orders match these filters.</p>
            {onResetFilters && <Button variant="secondary" size="sm" onClick={onResetFilters}>Clear filters</Button>}
          </>
        ) : (
          <ButtonLink to="/merchant/orders/new" size="sm">Create first order</ButtonLink>
        )}
      </div>
    )
  }

  return (
    <div className="history-table-container merchant-orders-table-container" aria-busy={isLoading}>
      {copyError && <p role="alert">The reference could not be copied. Select it and copy it manually.</p>}
      <div className="table-responsive-wrapper">
        <table role="table" className="delivery-log-table merchant-orders-table" aria-label="Merchant orders list">
          <thead role="rowgroup">
            <tr role="row">
              <th role="columnheader" scope="col">Tracking number</th>
              <th role="columnheader" scope="col">Status</th>
              <th role="columnheader" scope="col">Last update</th>
              <th role="columnheader" scope="col">Fee</th>
              <th role="columnheader" scope="col" className="text-right">Order</th>
            </tr>
          </thead>
          <tbody role="rowgroup">
            {pagedOrders.map((order) => {
              const statusInfo = getStatusPresentation(order.currentStatus)
              const isTrackingCopied = copiedId === `track-${order.parcelId}`

              return (
                <tr role="row" key={order.parcelId} className="merchant-order-row">
                  {/* Tracking Number */}
                  <td role="cell" className="tracking-number-cell">
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
                          onClick={() => onSelectTracking(order.trackingNumber, order.parcelId)}
                          aria-label={`Inspect tracking checkpoint for ${order.trackingNumber}`}
                        >
                          Track
                        </button>
                      )}
                    </div>
                  </td>

                  <td role="cell" className="orders-row-status">
                    <span className={statusInfo.pillClass} title={statusInfo.description} role="status">
                      {statusInfo.label}
                    </span>
                  </td>
                  <td role="cell" className="orders-row-updated" data-label="Last update">
                    {formatDateTime(order.lastStatusUpdate)}
                  </td>
                  <td role="cell" className="orders-row-fee" data-label="Fee">
                    {formatCurrency(order.feeMinorUnits, order.currency)}
                  </td>
                  {/* Actions */}
                  <td role="cell" className="text-right orders-row-action">
                    <Link
                      to={`/merchant/orders/${encodeURIComponent(order.parcelId)}`}
                      className="button button-secondary button-small view-order-btn"
                      aria-label={`View details for order ${order.trackingNumber}`}
                    >
                      View order
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
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0 || isLoading}
              aria-label="Previous page"
            >
              ← Previous
            </Button>
            <span className="pagination-current-page" aria-current="page">
              Page {page + 1} of {totalPages}
            </span>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => onPageChange(page + 1)}
              disabled={page >= totalPages - 1 || isLoading}
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
