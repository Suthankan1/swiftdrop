import { useState } from 'react'
import {
  STANDARD_FAILURE_REASONS,
  type FailureReasonCode,
} from '../api/proofContracts'

export interface FailedDeliveryReasonProps {
  parcelId: string
  trackingNumber?: string
  onSubmitReady?: (reason: FailureReasonCode, notes: string) => void
  disabled?: boolean
}

export function FailedDeliveryReason({
  parcelId,
  trackingNumber,
  onSubmitReady,
  disabled = false,
}: FailedDeliveryReasonProps) {
  const [selectedReason, setSelectedReason] = useState<FailureReasonCode>('RECIPIENT_UNAVAILABLE')
  const [failureNotes, setFailureNotes] = useState('')
  const [isReady, setIsReady] = useState(false)

  const handleRecord = () => {
    setIsReady(true)
    if (onSubmitReady) {
      onSubmitReady(selectedReason, failureNotes)
    }
  }

  return (
    <section className="card failure-reason-card" aria-labelledby="failure-reason-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Delivery Exception</p>
          <h3 id="failure-reason-heading">Record Failed Delivery Reason</h3>
        </div>
        <span className="badge badge-rejected">Exception Reporting</span>
      </div>

      <div className="failure-boundary-notice" role="note">
        <strong>Lifecycle Boundary Notice:</strong>
        <p className="muted">
          Delivery exception metadata is recorded locally in preparation for lifecycle dispatch.
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
            {STANDARD_FAILURE_REASONS.map((r) => (
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
          <button
            type="button"
            className="button button-secondary"
            onClick={handleRecord}
            disabled={disabled}
          >
            Prepare Failure Metadata
          </button>
        </div>

        {isReady && (
          <div className="feedback-banner is-success" role="status" aria-live="polite">
            <span>
              Failure reason recorded locally: <strong>{selectedReason}</strong>
              {trackingNumber ? ` for parcel ${trackingNumber}` : ''}.
              Held in local state for lifecycle dispatch (Issue #98/#153). Not committed to backend.
            </span>
          </div>
        )}
      </div>
    </section>
  )
}
