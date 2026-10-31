import type { MouseEvent } from 'react'
import { Button } from '../../../components/ui/Button'

interface ReceiptDownloadButtonProps {
  parcelId: string
  trackingNumber?: string
  currentStatus: string
  isDownloading: boolean
  onDownload: (parcelId: string) => void | Promise<void>
  buttonText?: string
  variant?: 'primary' | 'secondary' | 'small'
}

export function ReceiptDownloadButton({
  parcelId,
  trackingNumber,
  currentStatus,
  isDownloading,
  onDownload,
  buttonText,
  variant = 'secondary',
}: ReceiptDownloadButtonProps) {
  const isEligible = currentStatus === 'DELIVERED'
  const isFailed = currentStatus === 'FAILED'

  function handleClick(e: MouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    if (!isEligible || isDownloading) return
    void onDownload(parcelId)
  }

  if (isFailed) {
    return (
      <span
        className="receipt-ineligible-badge is-failed"
        title="Delivery receipts are not issued for failed deliveries."
        aria-label="Receipt unavailable for failed delivery"
      >
        Receipt Unavailable
      </span>
    )
  }

  if (!isEligible) {
    return (
      <span
        className="receipt-ineligible-badge is-pending"
        title="Official delivery receipt becomes available once the parcel is delivered."
        aria-label="Receipt not yet eligible; available upon delivery"
      >
        Not Yet Eligible
      </span>
    )
  }

  return (
    <Button
      variant={variant === 'primary' ? 'primary' : 'secondary'}
      size={variant === 'small' ? 'sm' : 'md'}
      isLoading={isDownloading}
      loadingText="Downloading..."
      onClick={handleClick}
      disabled={isDownloading}
      aria-label={`Download PDF receipt for delivery ${trackingNumber || parcelId}`}
      aria-busy={isDownloading}
      className={variant === 'small' ? 'receipt-btn-small' : ''}
      leftIcon={
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
          className="receipt-icon"
        >
          <path d="M12 3v12m0 0 4-4m-4 4-4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
        </svg>
      }
    >
      {buttonText || 'Download Receipt'}
    </Button>
  )
}
