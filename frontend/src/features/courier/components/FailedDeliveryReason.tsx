import { useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import {
  STANDARD_FAILURE_REASONS,
  type FailureReasonCode,
} from '../api/proofContracts'

export interface FailedDeliveryReasonProps {
  parcelId: string
  trackingNumber?: string
  onSubmitReady?: (reason: FailureReasonCode, notes: string) => void
  allowedReasons?: readonly FailureReasonCode[]
  disabled?: boolean
}

export function FailedDeliveryReason({
  parcelId,
  trackingNumber,
  onSubmitReady,
  allowedReasons,
  disabled = false,
}: FailedDeliveryReasonProps) {
  const availableReasons = STANDARD_FAILURE_REASONS.filter(
    ({ code }) => !allowedReasons || allowedReasons.includes(code),
  )
  const [selectedReason, setSelectedReason] = useState<FailureReasonCode>(
    availableReasons[0]?.code || 'RECIPIENT_UNAVAILABLE',
  )
  const [failureNotes, setFailureNotes] = useState('')
  const [isReady, setIsReady] = useState(false)

  const handleRecord = () => {
    setIsReady(true)
    if (onSubmitReady) {
      onSubmitReady(selectedReason, failureNotes.trim())
    }
  }

  return (
    <section className="card failure-reason-card" aria-labelledby="failure-reason-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Delivery Exception</p>
          <h3 id="failure-reason-heading">Record Failed Delivery Reason</h3>
        </div>
        <Badge variant="danger">Exception Reporting</Badge>
      </div>

      <div className="failure-boundary-notice" role="note">
        <strong>Lifecycle Boundary Notice:</strong>
        <p className="muted">
          Delivery exception metadata is prepared locally for your review.
          Transitioning parcel status to <code>FAILED</code> requires the authoritative lifecycle command
          (<code>PATCH /api/v1/parcels/{'{parcelId}'}/status</code> owned by Issue #98 / #153).
          No status change has been committed to the backend.
        </p>
      </div>

      <div className="failure-form-wrap">
        <div className="field">
          <label htmlFor={`failure-reason-select-${parcelId}`} className="field-label">
            Approved Failure Reason
          </label>
          <select
            id={`failure-reason-select-${parcelId}`}
            className="input-select"
            value={selectedReason}
            onChange={(e) => {
              setSelectedReason(e.target.value as FailureReasonCode)
              setIsReady(false)
            }}
            disabled={disabled}
          >
            {availableReasons.map((r) => (
              <option key={r.code} value={r.code}>
                {r.label} ({r.code})
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <div className="field-label-row">
            <label htmlFor={`failure-notes-${parcelId}`} className="field-label">
              Operational Notes (Optional)
            </label>
            <span className="char-count">{failureNotes.length} / 512</span>
          </div>
          <textarea
            id={`failure-notes-${parcelId}`}
            className="input-textarea"
            rows={3}
            maxLength={512}
            value={failureNotes}
            onChange={(e) => {
              setFailureNotes(e.target.value)
              setIsReady(false)
            }}
            placeholder="Add detailed context regarding the delivery attempt (e.g. recipient absent, gate locked)..."
            disabled={disabled}
          />
        </div>

        <div className="failure-actions">
          <Button
            type="button"
            variant="secondary"
            onClick={handleRecord}
            disabled={disabled}
          >
            Prepare Failure Metadata
          </Button>
        </div>

        {isReady && (
          <div className="feedback-banner is-success" role="status" aria-live="polite">
            <span>
              Failure reason prepared locally: <strong>{selectedReason}</strong>
              {trackingNumber ? ` for parcel ${trackingNumber}` : ''}.
              Held in local state for lifecycle dispatch (Issue #98/#153). Not committed to backend.
            </span>
          </div>
        )}
      </div>
    </section>
  )
}
