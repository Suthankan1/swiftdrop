import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { Button } from '../../../components/ui/Button'
import {
  CourierProofError,
  type createCourierProofApi,
} from '../api/proof'
import type {
  ParcelDetailResponse,
  ProofDetailResponse,
  ProofResponse,
} from '../api/proofContracts'
import {
  formatBytes,
  validateDeliveryNote,
  validateProofFile,
  validateRecipientName,
} from '../utils/proofValidation'
import { ProofPreview } from './ProofPreview'
import { ProofStatus } from './ProofStatus'

export type ProofLookupStatus =
  | 'idle'
  | 'checking'
  | 'verified-none'
  | 'verified-exists'
  | 'error'
  | 'forbidden'

export interface ProofCaptureFormProps {
  parcel: ParcelDetailResponse
  api: ReturnType<typeof createCourierProofApi>
  subject: string
  existingProof?: ProofDetailResponse | null
  proofLookupStatus?: ProofLookupStatus
  onSuccess?: (proof: ProofResponse | ProofDetailResponse) => void
}

export function ProofCaptureForm({
  parcel,
  api,
  subject,
  existingProof = null,
  proofLookupStatus,
  onSuccess,
}: ProofCaptureFormProps) {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [recipientName, setRecipientName] = useState('')
  const [deliveryNote, setDeliveryNote] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  // Upload state
  const [isUploading, setIsUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [isAmbiguousFailure, setIsAmbiguousFailure] = useState(false)
  const [isReconciling, setIsReconciling] = useState(false)
  const [reconciliationMessage, setReconciliationMessage] = useState<string | null>(null)
  const [confirmedProof, setConfirmedProof] = useState<ProofResponse | ProofDetailResponse | null>(
    existingProof,
  )
  const [showConfirmation, setShowConfirmation] = useState(false)

  const abortControllerRef = useRef<AbortController | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const confirmationRef = useRef<HTMLDivElement | null>(null)
  const submitTriggerRef = useRef<HTMLButtonElement | null>(null)
  const confirmationWasOpenRef = useRef(false)

  // Abort in-flight requests on unmount
  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
        abortControllerRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    if (showConfirmation) {
      confirmationWasOpenRef.current = true
      confirmationRef.current?.querySelector<HTMLButtonElement>('button')?.focus()
      return
    }
    if (confirmationWasOpenRef.current && !isUploading) {
      submitTriggerRef.current?.focus()
    }
    confirmationWasOpenRef.current = false
  }, [isUploading, showConfirmation])

  // Lifecycle check: only OUT_FOR_DELIVERY allows proof upload per conservative rule
  const isTerminal = parcel.currentStatus === 'DELIVERED' || parcel.currentStatus === 'FAILED'
  const isEligibleStage = parcel.currentStatus === 'OUT_FOR_DELIVERY'

  const handleFileChange = (file: File | null) => {
    setValidationError(null)
    // NOTE: When an upload outcome is uncertain, changing the selected file
    // MUST NOT clear isAmbiguousFailure or bypass the reconciliation requirement.
    if (!isAmbiguousFailure) {
      setUploadError(null)
      setReconciliationMessage(null)
    }

    if (!file) {
      setSelectedFile(null)
      return
    }

    const result = validateProofFile(file)
    if (!result.valid) {
      setValidationError(result.error || 'Invalid file selected.')
      setSelectedFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      return
    }

    setSelectedFile(file)
  }

  const onFileInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] || null
    handleFileChange(file)
  }

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragOver(false)
    const file = e.dataTransfer.files?.[0] || null
    handleFileChange(file)
  }

  const handleRemoveFile = () => {
    setSelectedFile(null)
    setValidationError(null)
    setShowConfirmation(false)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const handleCancelUpload = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
      abortControllerRef.current = null
    }
    setIsUploading(false)
    setUploadProgress(0)
    setIsAmbiguousFailure(true)
    setUploadError('Upload cancelled mid-flight. Server state is uncertain. Check server status before proceeding.')
  }

  const handleStartSubmit = () => {
    setValidationError(null)
    setUploadError(null)

    if (proofLookupStatus !== undefined && proofLookupStatus !== 'verified-none') {
      setValidationError('Cannot submit proof while server proof state is unverified or locked.')
      return
    }

    if (isAmbiguousFailure) {
      setValidationError('Previous upload outcome was uncertain. Server reconciliation is required before submitting.')
      return
    }

    if (!selectedFile) {
      setValidationError('Please select or capture a proof photo before submitting.')
      return
    }

    const nameCheck = validateRecipientName(recipientName)
    if (!nameCheck.valid) {
      setValidationError(nameCheck.error || 'Recipient name invalid.')
      return
    }

    const noteCheck = validateDeliveryNote(deliveryNote)
    if (!noteCheck.valid) {
      setValidationError(noteCheck.error || 'Delivery note invalid.')
      return
    }

    if (!isEligibleStage) {
      setValidationError(`Proof upload requires OUT_FOR_DELIVERY stage (current: ${parcel.currentStatus}).`)
      return
    }

    setShowConfirmation(true)
  }

  const handleConfirmUpload = async () => {
    if (!selectedFile || isUploading || isAmbiguousFailure) return

    setShowConfirmation(false)
    setIsUploading(true)
    setUploadProgress(0)
    setUploadError(null)
    setIsAmbiguousFailure(false)
    setReconciliationMessage(null)

    const controller = new AbortController()
    abortControllerRef.current = controller

    try {
      const response = await api.uploadProof({
        parcelId: parcel.parcelId,
        file: selectedFile,
        recipientName: recipientName.trim() || undefined,
        deliveryNote: deliveryNote.trim() || undefined,
        onProgress: (percent) => setUploadProgress(percent),
        signal: controller.signal,
      })

      setConfirmedProof(response)
      setSelectedFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      if (onSuccess) onSuccess(response)
    } catch (err) {
      if (controller.signal.aborted) {
        setIsAmbiguousFailure(true)
        setUploadError('Upload was cancelled. Server state is uncertain. Check server status before proceeding.')
        return
      }

      if (err instanceof CourierProofError) {
        if (err.kind === 'conflict') {
          // 409 Conflict: Proof already recorded
          setUploadError('A proof record already exists for this parcel on the server.')
          // Automatically reconcile server proof
          void handleReconcileServerState()
          return
        }
        if (err.kind === 'ambiguous-failure' || err.kind === 'storage-timeout') {
          setIsAmbiguousFailure(true)
          setUploadError(err.message)
          return
        }
        setUploadError(err.message)
      } else {
        setIsAmbiguousFailure(true)
        setUploadError('Unexpected network interruption during upload. Check server status before retrying.')
      }
    } finally {
      setIsUploading(false)
      abortControllerRef.current = null
    }
  }

  const handleReconcileServerState = async () => {
    setIsReconciling(true)
    setReconciliationMessage(null)
    try {
      const serverProof = await api.getProofMetadata(parcel.parcelId)
      setConfirmedProof(serverProof)
      setUploadError(null)
      setIsAmbiguousFailure(false)
      setReconciliationMessage('Server reconciliation confirmed: Proof was successfully recorded!')
      setSelectedFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      if (onSuccess) onSuccess(serverProof)
    } catch (err) {
      if (err instanceof CourierProofError && err.kind === 'not-found') {
        setIsAmbiguousFailure(false)
        setUploadError(null)
        setReconciliationMessage('Server check verified: No proof recorded on server. Safe to retry upload.')
      } else {
        setReconciliationMessage('Unable to reach server to verify proof status. Please check connection.')
      }
    } finally {
      setIsReconciling(false)
    }
  }

  // If proof is already confirmed, show the status display
  if (confirmedProof) {
    return (
      <ProofStatus
        proof={confirmedProof}
        trackingNumber={parcel.trackingNumber}
        onLoadContent={() => api.getProofContent(parcel.parcelId)}
        subject={subject}
      />
    )
  }

  return (
    <section className="card proof-capture-card" aria-labelledby="proof-capture-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Proof of Delivery</p>
          <h2 id="proof-capture-heading">Capture Handover Evidence</h2>
        </div>
        <span
          className={`badge ${
            isEligibleStage
              ? 'badge-approved'
              : isTerminal
                ? 'badge-rejected'
                : 'badge-pending'
          }`}
        >
          {parcel.currentStatus}
        </span>
      </div>

      <div className="parcel-context-strip">
        <div className="context-item">
          <span className="context-label">Tracking Number</span>
          <span className="context-value monospace">{parcel.trackingNumber}</span>
        </div>
        <div className="context-item">
          <span className="context-label">Destination</span>
          <span className="context-value">{parcel.dropoffAddress || 'Not specified'}</span>
        </div>
        <div className="context-item">
          <span className="context-label">Lifecycle Stage</span>
          <span className="context-value">{parcel.currentStatus}</span>
        </div>
      </div>

      {!isEligibleStage && (
        <div
          className={`feedback-banner ${isTerminal ? 'is-error' : 'feedback-banner-pending'}`}
          role="alert"
        >
          <span>
            {isTerminal
              ? `Parcel is in terminal state (${parcel.currentStatus}). Proof capture is closed.`
              : `Proof upload requires parcel status OUT_FOR_DELIVERY. Current status is ${parcel.currentStatus}.`}
          </span>
        </div>
      )}

      {proofLookupStatus === 'checking' && (
        <div className="feedback-banner feedback-banner-pending" role="status">
          <span>Verifying parcel proof records with server...</span>
        </div>
      )}

      {proofLookupStatus === 'error' && (
        <div className="feedback-banner is-error" role="alert">
          <span>
            Unable to verify existing proof records on server (service unavailable or connection failure).
            Proof submission is locked to prevent duplicate or conflicting submissions.
          </span>
        </div>
      )}

      {proofLookupStatus === 'forbidden' && (
        <div className="feedback-banner is-error" role="alert">
          <span>Access denied when verifying proof records for this parcel.</span>
        </div>
      )}

      {/* File Upload / Camera Zone */}
      <div className="form-section">
        <label className="field-label" htmlFor={`proof-file-input-${parcel.parcelId}`}>
          Photo Evidence (JPEG, PNG, WebP · Max 5 MiB)
        </label>

        {!selectedFile ? (
          <div
            className={`file-dropzone ${isDragOver ? 'is-dragover' : ''} ${!isEligibleStage || (proofLookupStatus !== undefined && proofLookupStatus !== 'verified-none') ? 'is-disabled' : ''}`}
            onDragOver={(e) => {
              e.preventDefault()
              if (isEligibleStage && (proofLookupStatus === undefined || proofLookupStatus === 'verified-none')) setIsDragOver(true)
            }}
            onDragLeave={() => setIsDragOver(false)}
            onDrop={isEligibleStage && (proofLookupStatus === undefined || proofLookupStatus === 'verified-none') ? onDrop : undefined}
          >
            <input
              id={`proof-file-input-${parcel.parcelId}`}
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              capture="environment"
              className="file-input-hidden"
              onChange={onFileInputChange}
              disabled={!isEligibleStage || isUploading || (proofLookupStatus !== undefined && proofLookupStatus !== 'verified-none')}
              aria-invalid={Boolean(validationError)}
              aria-describedby={`proof-file-hint-${parcel.parcelId}${validationError ? ` proof-file-error-${parcel.parcelId}` : ''}`}
            />
            <div className="dropzone-content">
              <svg className="dropzone-icon" width="36" height="36" viewBox="0 0 24 24" fill="none">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
              <p className="dropzone-text">
                <strong>Tap to capture with camera</strong> or choose a photo
              </p>
              <p id={`proof-file-hint-${parcel.parcelId}`} className="dropzone-hint muted">JPEG, PNG, or WebP up to 5 MiB</p>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={!isEligibleStage || isUploading || (proofLookupStatus !== undefined && proofLookupStatus !== 'verified-none')}
              >
                Select Photo
              </Button>
            </div>
          </div>
        ) : (
          <div className="selected-preview-wrap">
            <ProofPreview
              source={selectedFile}
              title="Selected Handover Evidence"
              altText="Handover proof preview"
              onRemove={!isUploading ? handleRemoveFile : undefined}
              subject={subject}
            />
          </div>
        )}
      </div>

      {/* Recipient Information Form */}
      <div className="form-section metadata-section">
        <div className="field">
          <div className="field-label-row">
            <label htmlFor={`recipient-name-${parcel.parcelId}`} className="field-label">
              Recipient Name (Optional)
            </label>
            <span className="char-count">{recipientName.length} / 128</span>
          </div>
          <input
            id={`recipient-name-${parcel.parcelId}`}
            type="text"
            className="input-text"
            maxLength={128}
            value={recipientName}
            onChange={(e) => setRecipientName(e.target.value)}
            placeholder="e.g. Jane Doe (or leave blank if contactless drop)"
            disabled={!isEligibleStage || isUploading}
          />
        </div>

        <div className="field">
          <div className="field-label-row">
            <label htmlFor={`delivery-note-${parcel.parcelId}`} className="field-label">
              Delivery Handover Note (Optional)
            </label>
            <span className="char-count">{deliveryNote.length} / 512</span>
          </div>
          <textarea
            id={`delivery-note-${parcel.parcelId}`}
            className="input-textarea"
            rows={2}
            maxLength={512}
            value={deliveryNote}
            onChange={(e) => setDeliveryNote(e.target.value)}
            placeholder="e.g. Left behind plant pot at front porch as requested..."
            disabled={!isEligibleStage || isUploading}
          />
        </div>
      </div>

      {/* Validation Feedback */}
      {validationError && (
        <div id={`proof-file-error-${parcel.parcelId}`} className="feedback-banner is-error" role="alert">
          <span>{validationError}</span>
        </div>
      )}

      {/* Upload Error & Ambiguous Failure Handling */}
      {uploadError && (
        <div className="feedback-banner is-error" role="alert">
          <span>{uploadError}</span>
        </div>
      )}

      {isAmbiguousFailure && (
        <div className="reconciliation-panel" role="region" aria-label="Ambiguous upload reconciliation">
          <p className="muted">
            The server may have received and persisted the proof despite the connection interruption.
            Verify server state to avoid creating duplicate evidence.
          </p>
          <Button
            type="button"
            variant="secondary"
            onClick={handleReconcileServerState}
            disabled={isReconciling}
            isLoading={isReconciling}
            loadingText="Checking Server State..."
          >
            Check Server Status & Reconcile
          </Button>
        </div>
      )}

      {reconciliationMessage && (
        <div className="feedback-banner is-success" role="status">
          <span>{reconciliationMessage}</span>
        </div>
      )}

      {/* Real Upload Progress Bar */}
      {isUploading && (
        <div className="upload-progress-wrap" role="status" aria-live="polite">
          <div className="progress-header">
            <span>Uploading proof photo...</span>
            <span className="progress-percent monospace">{uploadProgress}%</span>
          </div>
          <div
            className="progress-track"
            role="progressbar"
            aria-label="Proof photo upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={uploadProgress}
          >
            <div
              className="progress-fill"
              style={{ width: `${uploadProgress}%` }}
            />
          </div>
          <div className="progress-actions">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={handleCancelUpload}
            >
              Cancel Upload
            </Button>
          </div>
        </div>
      )}

      {/* Confirmation Step Dialog / Modal */}
      {showConfirmation && selectedFile && (
        <div
          className="confirmation-dialog"
          ref={confirmationRef}
          role="dialog"
          aria-labelledby="confirm-heading"
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              setShowConfirmation(false)
              return
            }
            if (event.key !== 'Tab') return
            const buttons = confirmationRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
            const first = buttons?.[0]
            const last = buttons?.[buttons.length - 1]
            if (!first) {
              event.preventDefault()
            } else if (event.shiftKey && document.activeElement === first) {
              event.preventDefault()
              last?.focus()
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault()
              first.focus()
            }
          }}
        >
          <h3 id="confirm-heading">Confirm Proof Submission</h3>
          <p className="muted">
            Please verify the handover details before uploading. Evidence cannot be modified once committed.
          </p>
          <ul className="confirmation-summary">
            <li>
              <strong>Parcel:</strong> {parcel.trackingNumber}
            </li>
            <li>
              <strong>Photo:</strong> {selectedFile.name} ({formatBytes(selectedFile.size)})
            </li>
            {recipientName.trim() && (
              <li>
                <strong>Recipient:</strong> {recipientName.trim()}
              </li>
            )}
            {deliveryNote.trim() && (
              <li>
                <strong>Note:</strong> {deliveryNote.trim()}
              </li>
            )}
          </ul>
          <div className="confirmation-actions">
            <Button
              type="button"
              variant="primary"
              onClick={handleConfirmUpload}
            >
              Yes, Upload Proof
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setShowConfirmation(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}

      {/* Form Submission Action */}
      {!showConfirmation && !isUploading && (
        <div className="form-submit-row">
          <Button
            type="button"
            variant="primary"
            ref={submitTriggerRef}
            onClick={handleStartSubmit}
            disabled={
              !isEligibleStage ||
              !selectedFile ||
              isUploading ||
              isAmbiguousFailure ||
              (proofLookupStatus !== undefined && proofLookupStatus !== 'verified-none')
            }
          >
            Submit Proof of Delivery
          </Button>
        </div>
      )}
    </section>
  )
}
