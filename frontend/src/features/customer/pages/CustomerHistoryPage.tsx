import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { createDeliveryApi, DeliveryError, triggerBlobDownload } from '../api/delivery'
import { CustomerNav } from '../components/CustomerNav'
import { CustomerHistoryTable } from '../components/CustomerHistoryTable'
import { CustomerTrackingModal } from '../components/CustomerTrackingModal'

export function CustomerHistoryPage() {
  const auth = useAuth()
  const [currentPage, setCurrentPage] = useState(0)
  const [selectedParcelId, setSelectedParcelId] = useState<string | null>(null)
  const [selectedTrackingNumber, setSelectedTrackingNumber] = useState<string | undefined>()
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [downloadingParcelId, setDownloadingParcelId] = useState<string | null>(null)
  const [receiptFeedback, setReceiptFeedback] = useState<{
    type: 'success' | 'error'
    message: string
  } | null>(null)
  const [searchTrackingInput, setSearchTrackingInput] = useState('')

  const deliveryApi = createDeliveryApi({
    getAccessToken: auth.getAccessToken,
  })

  const historyQuery = useQuery({
    queryKey: ['private', 'customer-deliveries', currentPage],
    enabled: auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('CUSTOMER'),
    queryFn: async ({ signal }) => {
      return deliveryApi.getCustomerDeliveries({ page: currentPage, size: 10 }, signal)
    },
    staleTime: 15_000,
  })

  async function handleDownloadReceipt(parcelId: string) {
    if (downloadingParcelId) return
    setDownloadingParcelId(parcelId)
    setReceiptFeedback(null)

    try {
      const { blob, filename } = await deliveryApi.downloadReceipt(parcelId)
      triggerBlobDownload(blob, filename)
      setReceiptFeedback({
        type: 'success',
        message: `Official receipt (${filename}) downloaded successfully.`,
      })
    } catch (err) {
      let message = 'Failed to download delivery receipt.'
      if (err instanceof DeliveryError) {
        if (err.kind === 'forbidden') {
          message = 'You are not authorized to download this receipt (ownership mismatch).'
        } else if (err.kind === 'not-found') {
          message = 'Receipt is unavailable or delivery is not yet completed.'
        } else if (err.kind === 'auth-required') {
          message = 'Your session has expired. Please sign in again.'
        } else {
          message = err.message
        }
      }
      setReceiptFeedback({ type: 'error', message })
    } finally {
      setDownloadingParcelId(null)
    }
  }

  function handleSelectParcel(parcelId: string, trackingNumber?: string) {
    setSelectedParcelId(parcelId)
    setSelectedTrackingNumber(trackingNumber)
    setIsModalOpen(true)
  }

  function handleSearchSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = searchTrackingInput.trim()
    if (!trimmed) return

    // If UUID pattern, use as parcelId, otherwise as trackingNumber
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    if (uuidRegex.test(trimmed)) {
      handleSelectParcel(trimmed)
    } else {
      setSelectedParcelId(trimmed)
      setSelectedTrackingNumber(trimmed)
      setIsModalOpen(true)
    }
  }

  return (
    <div className="page-container customer-page customer-history-page">
      <header className="page-heading">
        <p className="eyebrow">Customer space</p>
        <h1>Delivery History &amp;<br />Live Tracking.</h1>
        <p className="muted">
          Review your booked shipments, monitor milestone transitions, and download official PDF receipts.
        </p>
      </header>

      <CustomerNav />

      {/* Global Receipt Download Feedback Banner */}
      {receiptFeedback && (
        <div
          className={`feedback-banner ${receiptFeedback.type === 'success' ? 'is-success' : 'is-error'}`}
          role="alert"
        >
          <span>{receiptFeedback.message}</span>
          <button
            type="button"
            className="feedback-dismiss"
            onClick={() => setReceiptFeedback(null)}
            aria-label="Dismiss receipt download message"
          >
            ✕
          </button>
        </div>
      )}

      {/* Quick Tracking Search Bar */}
      <section className="card tracking-lookup-card" aria-labelledby="lookup-heading">
        <div className="lookup-header">
          <h2 id="lookup-heading">Quick Tracking Lookup</h2>
          <p className="muted small-note">
            Inspect real-time milestone checkpoints by tracking number (e.g., SWD-20261025-XXXX).
          </p>
        </div>
        <form onSubmit={handleSearchSubmit} className="lookup-form" role="search">
          <input
            type="text"
            className="lookup-input"
            placeholder="Enter tracking number (e.g. SWD-20261025-A1B2C3)"
            value={searchTrackingInput}
            onChange={(e) => setSearchTrackingInput(e.target.value)}
            aria-label="Tracking number or parcel reference"
          />
          <button type="submit" className="button button-primary lookup-submit">
            Track Shipment
          </button>
        </form>
      </section>

      {/* Main Delivery History Table */}
      <section className="history-content-section" aria-labelledby="history-heading">
        <div className="section-title-wrap">
          <h2 id="history-heading">Your Recent Deliveries</h2>
          {historyQuery.data && (
            <span className="badge-count">
              {historyQuery.data.totalElements} total shipments
            </span>
          )}
        </div>

        {historyQuery.isPending && (
          <div className="card loading-history-card" aria-busy="true">
            <span className="btn-spinner large" aria-hidden="true" />
            <p>Loading your delivery records...</p>
          </div>
        )}

        {historyQuery.isError && (
          <div className="card state-panel state-panel-error" role="alert">
            <h2>Unable to load delivery history</h2>
            <p>
              {historyQuery.error instanceof DeliveryError && historyQuery.error.kind === 'forbidden'
                ? 'You do not have permission to view these delivery records.'
                : 'A temporary connection or backend error occurred while fetching delivery records.'}
            </p>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => void historyQuery.refetch()}
            >
              Retry
            </button>
          </div>
        )}

        {historyQuery.data && (
          <CustomerHistoryTable
            deliveries={historyQuery.data.items}
            currentPage={historyQuery.data.currentPage}
            totalPages={historyQuery.data.totalPages}
            totalElements={historyQuery.data.totalElements}
            pageSize={historyQuery.data.pageSize}
            hasNext={historyQuery.data.hasNext}
            hasPrevious={historyQuery.data.hasPrevious}
            onPageChange={(newPage) => setCurrentPage(newPage)}
            onSelectParcel={handleSelectParcel}
            onDownloadReceipt={handleDownloadReceipt}
            downloadingParcelId={downloadingParcelId}
            isLoading={historyQuery.isFetching}
          />
        )}
      </section>

      {/* Detailed Tracking Modal Dialog */}
      <CustomerTrackingModal
        parcelId={selectedParcelId}
        trackingNumber={selectedTrackingNumber}
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        deliveryApi={deliveryApi}
        onDownloadReceipt={handleDownloadReceipt}
        isDownloadingReceipt={downloadingParcelId === selectedParcelId}
      />
    </div>
  )
}
