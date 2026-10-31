import type { CustomerDeliverySummary } from '../api/deliveryContracts'
import { Button } from '../../../components/ui/Button'
import { formatCurrency, formatDateTime, getStatusPresentation } from './customerUiUtils'
import { ReceiptDownloadButton } from './ReceiptDownloadButton'

interface CustomerHistoryTableProps {
  deliveries: CustomerDeliverySummary[]
  currentPage: number
  totalPages: number
  totalElements: number
  pageSize: number
  hasNext: boolean
  hasPrevious: boolean
  onPageChange: (newPage: number) => void
  onSelectParcel: (parcelId: string, trackingNumber?: string) => void
  onDownloadReceipt: (parcelId: string) => Promise<void>
  downloadingParcelId: string | null
  isLoading?: boolean
}

export function CustomerHistoryTable({
  deliveries,
  currentPage,
  totalPages,
  totalElements,
  hasNext,
  hasPrevious,
  onPageChange,
  onSelectParcel,
  onDownloadReceipt,
  downloadingParcelId,
  isLoading = false,
}: CustomerHistoryTableProps) {
  if (deliveries.length === 0) {
    return (
      <div className="card empty-history-card" aria-label="No delivery history">
        <div className="empty-icon-wrap" aria-hidden="true">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
            <path d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
          </svg>
        </div>
        <h3>No delivery history yet</h3>
        <p className="muted">
          Your booked deliveries, live journey milestones, and official PDF receipts will be recorded here.
        </p>
      </div>
    )
  }

  return (
    <div className="history-table-container">
      <div className="table-responsive-wrapper">
        <table className="delivery-log-table" aria-label="Customer Delivery History">
          <thead>
            <tr>
              <th scope="col">Tracking Number</th>
              <th scope="col">Date Booked</th>
              <th scope="col">Route</th>
              <th scope="col">Status</th>
              <th scope="col">Fee</th>
              <th scope="col" className="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {deliveries.map((item) => {
              const presentation = getStatusPresentation(item.currentStatus)
              const isDownloading = downloadingParcelId === item.parcelId

              return (
                <tr key={item.parcelId} className="history-row">
                  <td className="font-mono">
                    <button
                      type="button"
                      className="tracking-link-btn"
                      onClick={() => onSelectParcel(item.parcelId, item.trackingNumber)}
                      aria-label={`View tracking details for ${item.trackingNumber}`}
                    >
                      {item.trackingNumber}
                    </button>
                  </td>
                  <td className="text-muted">{formatDateTime(item.createdAt)}</td>
                  <td>
                    <span className="route-summary">
                      {item.pickupAddress || 'Origin'} &rarr; {item.dropoffAddress || item.recipientCity || 'Destination'}
                    </span>
                  </td>
                  <td>
                    <span className={presentation.pillClass}>{presentation.label}</span>
                  </td>
                  <td className="font-numeric">
                    {formatCurrency(item.amountMinorUnits, item.currency)}
                  </td>
                  <td className="text-right">
                    <div className="action-button-group">
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={() => onSelectParcel(item.parcelId, item.trackingNumber)}
                        aria-label={`Track parcel ${item.trackingNumber}`}
                      >
                        Track
                      </Button>
                      <ReceiptDownloadButton
                        parcelId={item.parcelId}
                        trackingNumber={item.trackingNumber}
                        currentStatus={item.currentStatus}
                        isDownloading={isDownloading}
                        onDownload={onDownloadReceipt}
                        variant="small"
                      />
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination Controls */}
      <nav className="history-pagination" aria-label="Delivery history pagination">
        <span className="pagination-info">
          Page <strong>{currentPage + 1}</strong> of <strong>{Math.max(1, totalPages)}</strong> ({totalElements} total deliveries)
        </span>
        <div className="pagination-buttons">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => onPageChange(currentPage - 1)}
            disabled={!hasPrevious || isLoading}
            aria-label="Previous page of deliveries"
          >
            &larr; Previous
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => onPageChange(currentPage + 1)}
            disabled={!hasNext || isLoading}
            aria-label="Next page of deliveries"
          >
            Next &rarr;
          </Button>
        </div>
      </nav>
    </div>
  )
}
