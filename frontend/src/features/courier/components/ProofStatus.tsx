import { useState } from 'react'
import type { ProofDetailResponse, ProofResponse } from '../api/proofContracts'
import { formatBytes } from '../utils/proofValidation'
import { ProofPreview } from './ProofPreview'

export interface ProofStatusProps {
  proof: ProofResponse | ProofDetailResponse
  trackingNumber?: string
  onLoadContent?: () => Promise<Blob>
  subject?: string | null
}

export function ProofStatus({
  proof,
  trackingNumber,
  onLoadContent,
  subject,
}: ProofStatusProps) {
  const [evidenceBlob, setEvidenceBlob] = useState<Blob | null>(null)
  const [isLoadingContent, setIsLoadingContent] = useState(false)
  const [contentError, setContentError] = useState<string | null>(null)

  const handleInspectEvidence = async () => {
    if (!onLoadContent) return
    setIsLoadingContent(true)
    setContentError(null)
    try {
      const blob = await onLoadContent()
      setEvidenceBlob(blob)
    } catch {
      setContentError('Unable to load private evidence photo. Please verify your session.')
    } finally {
      setIsLoadingContent(false)
    }
  }

  const detailProof = 'contentType' in proof ? (proof as ProofDetailResponse) : null
  const formattedDate = new Date(proof.uploadedAt).toLocaleString()

  return (
    <section className="card proof-status-card" aria-labelledby="proof-status-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Proof Recorded</p>
          <h3 id="proof-status-heading">
            Proof of Delivery Confirmed
            {trackingNumber ? ` (${trackingNumber})` : ''}
          </h3>
        </div>
        <span className="badge badge-approved">Evidence Secured</span>
      </div>

      <div className="feedback-banner is-success" role="status" aria-live="polite">
        <span>
          Proof photo and handover metadata successfully persisted in private storage.
          Handover evidence is recorded. Final status transition to DELIVERED is managed via lifecycle status command.
        </span>
      </div>

      <div className="proof-status-grid">
        <div className="field">
          <span className="field-label">Proof ID</span>
          <p className="field-value monospace">{proof.proofId}</p>
        </div>

        <div className="field">
          <span className="field-label">Recorded At</span>
          <p className="field-value">{formattedDate}</p>
        </div>

        <div className="field">
          <span className="field-label">Courier Actor</span>
          <p className="field-value monospace">{proof.uploadedBySub}</p>
        </div>

        {detailProof?.recipientName && (
          <div className="field">
            <span className="field-label">Recipient Name</span>
            <p className="field-value">{detailProof.recipientName}</p>
          </div>
        )}

        {detailProof?.deliveryNote && (
          <div className="field">
            <span className="field-label">Delivery Note</span>
            <p className="field-value">{detailProof.deliveryNote}</p>
          </div>
        )}

        {detailProof?.contentLength && (
          <div className="field">
            <span className="field-label">Photo Size</span>
            <p className="field-value">{formatBytes(detailProof.contentLength)}</p>
          </div>
        )}
      </div>

      <div className="proof-policy-notice" role="note">
        <strong>Conservative Delivery Policy:</strong>
        <p className="muted">
          Per platform integrity rules, verified delivery evidence cannot be arbitrarily overwritten or replaced.
          Final status transition to <code>DELIVERED</code> is committed via separate status command (Issue #23 / #98).
          Uploading proof records evidence but does not automatically transition the parcel status.
        </p>
      </div>

      <div className="proof-status-actions">
        {onLoadContent && !evidenceBlob && (
          <button
            type="button"
            className="button button-secondary"
            onClick={handleInspectEvidence}
            disabled={isLoadingContent}
          >
            {isLoadingContent ? 'Streaming Private Evidence...' : 'Inspect Stored Evidence'}
          </button>
        )}
      </div>

      {contentError && (
        <div className="feedback-banner is-error" role="alert">
          <span>{contentError}</span>
        </div>
      )}

      {evidenceBlob && (
        <div className="proof-stored-preview-wrap">
          <ProofPreview
            source={evidenceBlob}
            title="Stored Proof Evidence (Private Stream)"
            altText={`Stored delivery proof for parcel ${proof.parcelId}`}
            onClose={() => setEvidenceBlob(null)}
            subject={subject}
          />
        </div>
      )}
    </section>
  )
}
