import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import {
  CourierProofError,
  createCourierProofApi,
} from '../api/proof'
import type {
  ParcelDetailResponse,
  ProofDetailResponse,
} from '../api/proofContracts'
import { FailedDeliveryReason } from './FailedDeliveryReason'
import { ProofCaptureForm } from './ProofCaptureForm'

export type ProofLookupStatus =
  | 'idle'
  | 'checking'
  | 'verified-none'
  | 'verified-exists'
  | 'error'
  | 'forbidden'

export interface CourierParcelDetailsProps {
  parcelId: string
  onClose: () => void
  apiOverride?: ReturnType<typeof createCourierProofApi>
}

export function CourierParcelDetails({
  parcelId,
  onClose,
  apiOverride,
}: CourierParcelDetailsProps) {
  const auth = useAuth()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''

  const api = useMemo(
    () =>
      apiOverride ||
      createCourierProofApi({
        getAccessToken: auth.getAccessToken,
      }),
    [apiOverride, auth.getAccessToken],
  )

  const [parcel, setParcel] = useState<ParcelDetailResponse | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [existingProof, setExistingProof] = useState<ProofDetailResponse | null>(null)
  const [proofLookupStatus, setProofLookupStatus] = useState<ProofLookupStatus>('idle')
  const [proofLookupError, setProofLookupError] = useState<string | null>(null)
  const [showExceptionSection, setShowExceptionSection] = useState(false)

  // Subject and parcel isolation: wipe private details immediately if session or parcelId changes
  const [prevSubject, setPrevSubject] = useState(subject)
  const [prevParcelId, setPrevParcelId] = useState(parcelId)
  if (subject !== prevSubject || parcelId !== prevParcelId) {
    setPrevSubject(subject)
    setPrevParcelId(parcelId)
    setParcel(null)
    setExistingProof(null)
    setProofLookupStatus('checking')
    setProofLookupError(null)
    setError(null)
    setShowExceptionSection(false)
  }

  // Load authorized parcel details and verify proof record
  useEffect(() => {
    if (!parcelId || !subject) return

    let active = true
    const controller = new AbortController()

    void (async () => {
      try {
        setIsLoading(true)
        const parcelData = await api.getParcel(parcelId, controller.signal)
        if (!active) return

        // Object-level client verification: ensure parcel is assigned to current courier if indicated
        if (parcelData.activeCourierSub && parcelData.activeCourierSub !== subject) {
          setError('Access denied: You are not the active courier assigned to this parcel.')
          setParcel(null)
          setProofLookupStatus('forbidden')
          return
        }

        setParcel(parcelData)

        // Authoritative proof lookup
        try {
          const proofData = await api.getProofMetadata(parcelId, controller.signal)
          if (!active) return
          setExistingProof(proofData)
          setProofLookupStatus('verified-exists')
        } catch (proofErr) {
          if (!active || controller.signal.aborted) return
          if (proofErr instanceof CourierProofError) {
            if (proofErr.kind === 'not-found') {
              // 404: Authoritatively confirmed NO proof exists on server
              setExistingProof(null)
              setProofLookupStatus('verified-none')
            } else if (proofErr.kind === 'auth-required' || proofErr.kind === 'forbidden') {
              setExistingProof(null)
              setProofLookupStatus('forbidden')
              setProofLookupError(proofErr.message)
            } else {
              setExistingProof(null)
              setProofLookupStatus('error')
              setProofLookupError(proofErr.message)
            }
          } else {
            setExistingProof(null)
            setProofLookupStatus('error')
            setProofLookupError('Unable to reach server to verify existing proof records.')
          }
        }
      } catch (err) {
        if (!active || controller.signal.aborted) return
        setParcel(null)
        setExistingProof(null)
        setProofLookupStatus('idle')
        if (err instanceof CourierProofError) {
          if (err.statusCode === 403) {
            setError('Access denied: You are not authorized to view this parcel.')
          } else if (err.statusCode === 404) {
            setError('Parcel not found or no longer assigned to you.')
          } else {
            setError(err.message)
          }
        } else {
          setError('Failed to load parcel details from Delivery service.')
        }
      } finally {
        if (active) {
          setIsLoading(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, parcelId, subject])

  const handleRetryProofVerification = async () => {
    if (!parcelId) return
    setProofLookupStatus('checking')
    setProofLookupError(null)
    try {
      const proofData = await api.getProofMetadata(parcelId)
      setExistingProof(proofData)
      setProofLookupStatus('verified-exists')
    } catch (err) {
      if (err instanceof CourierProofError && err.kind === 'not-found') {
        setExistingProof(null)
        setProofLookupStatus('verified-none')
      } else if (err instanceof CourierProofError && (err.kind === 'auth-required' || err.kind === 'forbidden')) {
        setExistingProof(null)
        setProofLookupStatus('forbidden')
        setProofLookupError(err.message)
      } else {
        setExistingProof(null)
        setProofLookupStatus('error')
        setProofLookupError(err instanceof Error ? err.message : 'Server verification failed.')
      }
    }
  }

  return (
    <div className="courier-parcel-detail-view" aria-label="Courier Parcel Detail View">
      <div className="detail-navigation-bar">
        <button
          type="button"
          className="button button-secondary button-small back-button"
          onClick={onClose}
          aria-label="Back to assignments list"
        >
          ← Back to Assignments
        </button>
      </div>

      {isLoading && (
        <div className="loading-state card" role="status" aria-label="Loading parcel details">
          <span className="loading-indicator" aria-hidden="true" />
          <p className="muted">Loading authorized parcel details...</p>
        </div>
      )}

      {error && (
        <div className="feedback-banner is-error card" role="alert">
          <span>{error}</span>
          <button
            type="button"
            className="button button-secondary button-small"
            onClick={onClose}
          >
            Return to Route Queue
          </button>
        </div>
      )}

      {!isLoading && parcel && (
        <>
          {/* Header & Status Card */}
          <article className="card parcel-detail-header-card" aria-labelledby="parcel-detail-heading">
            <div className="section-heading-row">
              <div>
                <p className="eyebrow">Tracking Number</p>
                <h2 id="parcel-detail-heading" className="monospace tracking-number-heading">
                  {parcel.trackingNumber}
                </h2>
              </div>
              <div className="status-badges-group">
                <span className="badge badge-approved">
                  {parcel.currentStatus}
                </span>
                <span className="badge badge-version">
                  v{parcel.version}
                </span>
              </div>
            </div>

            <div className="parcel-specs-grid">
              <div className="spec-item">
                <span className="spec-label">Weight</span>
                <span className="spec-value">
                  {parcel.weightKg !== null && parcel.weightKg !== undefined
                    ? `${parcel.weightKg} kg`
                    : 'Not available'}
                </span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Dimensions</span>
                <span className="spec-value">
                  {parcel.dimensions?.trim() || 'Not available'}
                </span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Created At</span>
                <span className="spec-value">
                  {new Date(parcel.createdAt).toLocaleDateString()}
                </span>
              </div>
              <div className="spec-item">
                <span className="spec-label">Parcel ID</span>
                <span className="spec-value monospace parcel-id-snippet">
                  {parcel.parcelId}
                </span>
              </div>
            </div>
          </article>

          {/* Route & Address Card */}
          <article className="card parcel-route-card" aria-labelledby="parcel-route-heading">
            <h3 id="parcel-route-heading" className="card-subheading">Delivery Route</h3>
            <div className="route-addresses">
              <div className="address-block">
                <span className="address-label">Pickup Address</span>
                <p className="address-text">
                  {parcel.pickupAddress?.trim() || 'Not available'}
                </p>
              </div>
              <div className="route-arrow-down" aria-hidden="true">↓</div>
              <div className="address-block">
                <span className="address-label">Drop-off Address</span>
                <p className="address-text">
                  {parcel.dropoffAddress?.trim() || 'Not available'}
                </p>
              </div>
            </div>
          </article>

          {/* Proof Capture & Exception Workspace */}
          <section className="parcel-proof-workspace" aria-label="Proof of delivery and handover">
            {proofLookupStatus === 'error' && (
              <div className="feedback-banner is-error card" role="alert">
                <span>
                  {proofLookupError ||
                    'Unable to verify existing proof records on server. Proof capture is locked to prevent duplicate or conflicting submissions.'}
                </span>
                <button
                  type="button"
                  className="button button-secondary button-small"
                  onClick={() => {
                    void handleRetryProofVerification()
                  }}
                >
                  Retry Verification
                </button>
              </div>
            )}

            {proofLookupStatus === 'forbidden' && (
              <div className="feedback-banner is-error card" role="alert">
                <span>
                  {proofLookupError ||
                    'Access denied when verifying proof records for this parcel. Proof capture is locked.'}
                </span>
              </div>
            )}

            <ProofCaptureForm
              key={`${subject}-${parcel.parcelId}-${existingProof?.proofId || 'new'}`}
              parcel={parcel}
              api={api}
              subject={subject}
              existingProof={existingProof}
              proofLookupStatus={proofLookupStatus}
              onSuccess={(proof) => {
                if ('contentType' in proof) {
                  setExistingProof(proof as ProofDetailResponse)
                  setProofLookupStatus('verified-exists')
                  return
                }
                void api
                  .getProofMetadata(parcel.parcelId)
                  .then((detail) => {
                    setExistingProof(detail)
                    setProofLookupStatus('verified-exists')
                  })
                  .catch(() => {
                    setProofLookupStatus('verified-exists')
                  })
              }}
            />

            <div className="exception-toggle-wrap">
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={() => setShowExceptionSection(!showExceptionSection)}
                aria-expanded={showExceptionSection}
              >
                {showExceptionSection ? 'Hide Exception Form' : 'Unable to Deliver? Report Exception'}
              </button>
            </div>

            {showExceptionSection && (
              <FailedDeliveryReason
                parcelId={parcel.parcelId}
                trackingNumber={parcel.trackingNumber}
              />
            )}
          </section>
        </>
      )}
    </div>
  )
}
