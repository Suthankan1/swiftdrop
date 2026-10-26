import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import {
  CourierProofError,
  createCourierProofApi,
} from '../api/proof'
import type {
  AssignmentResponse,
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

export interface CourierProofWorkspaceProps {
  apiOverride?: ReturnType<typeof createCourierProofApi>
}

export function CourierProofWorkspace({ apiOverride }: CourierProofWorkspaceProps) {
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

  const [assignments, setAssignments] = useState<AssignmentResponse[]>([])
  const [isLoadingAssignments, setIsLoadingAssignments] = useState(false)
  const [assignmentError, setAssignmentError] = useState<string | null>(null)
  const [selectedParcelId, setSelectedParcelId] = useState<string | null>(null)

  // Selected parcel state
  const [selectedParcel, setSelectedParcel] = useState<ParcelDetailResponse | null>(null)
  const [isLoadingParcel, setIsLoadingParcel] = useState(false)
  const [parcelError, setParcelError] = useState<string | null>(null)
  const [existingProof, setExistingProof] = useState<ProofDetailResponse | null>(null)
  const [proofLookupStatus, setProofLookupStatus] = useState<ProofLookupStatus>('idle')
  const [proofLookupError, setProofLookupError] = useState<string | null>(null)
  const [showExceptionSection, setShowExceptionSection] = useState(false)

  // Clear stale data immediately on subject switch
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setAssignments([])
    setSelectedParcelId(null)
    setSelectedParcel(null)
    setExistingProof(null)
    setProofLookupStatus('idle')
    setProofLookupError(null)
    setAssignmentError(null)
    setParcelError(null)
    setShowExceptionSection(false)
  }

  // Fetch active assignments on load or subject change
  useEffect(() => {
    if (!subject) return

    let active = true
    const controller = new AbortController()

    void (async () => {
      try {
        setIsLoadingAssignments(true)
        setAssignmentError(null)
        const list = await api.getMyAssignments(true, controller.signal)
        if (active) {
          setAssignments(list)
          if (list.length > 0) {
            setSelectedParcelId((prev) => (prev && list.some((a) => a.parcelId === prev) ? prev : list[0]!.parcelId))
          } else {
            setSelectedParcelId(null)
            setSelectedParcel(null)
            setExistingProof(null)
            setProofLookupStatus('idle')
          }
        }
      } catch (err) {
        if (!active || controller.signal.aborted) return
        if (err instanceof CourierProofError) {
          setAssignmentError(err.message)
        } else {
          setAssignmentError('Unable to load assignments from Delivery service.')
        }
      } finally {
        if (active) {
          setIsLoadingAssignments(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, subject])

  // Fetch selected parcel details & check authoritative existing proof
  useEffect(() => {
    if (!selectedParcelId || !subject) return

    let active = true
    const controller = new AbortController()

    void (async () => {
      try {
        setIsLoadingParcel(true)
        setParcelError(null)
        setProofLookupStatus('checking')
        setProofLookupError(null)

        const parcelData = await api.getParcel(selectedParcelId, controller.signal)
        if (!active) return
        setSelectedParcel(parcelData)

        // Authoritative proof lookup
        try {
          const proofData = await api.getProofMetadata(selectedParcelId, controller.signal)
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
              // Server error / network failure / timeout: UNCERTAIN
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
        setSelectedParcel(null)
        setExistingProof(null)
        setProofLookupStatus('idle')
        if (err instanceof CourierProofError) {
          setParcelError(err.message)
        } else {
          setParcelError('Failed to load parcel details.')
        }
      } finally {
        if (active) {
          setIsLoadingParcel(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, selectedParcelId, subject])

  const handleSelectParcel = (parcelId: string) => {
    if (parcelId === selectedParcelId) return
    setSelectedParcelId(parcelId)
    // Clear old parcel data immediately to prevent rendering previous parcel under new selection
    setSelectedParcel(null)
    setExistingProof(null)
    setProofLookupStatus('checking')
    setProofLookupError(null)
    setParcelError(null)
    setShowExceptionSection(false)
  }

  const handleRetryAssignments = () => {
    setSelectedParcelId(null)
    setSelectedParcel(null)
    setExistingProof(null)
    setProofLookupStatus('idle')
    setIsLoadingAssignments(true)
    setAssignmentError(null)
    void api
      .getMyAssignments(true)
      .then((list) => {
        setAssignments(list)
        if (list.length > 0) setSelectedParcelId(list[0]!.parcelId)
      })
      .catch((err: unknown) => {
        setAssignmentError(err instanceof Error ? err.message : 'Failed to reload assignments.')
      })
      .finally(() => {
        setIsLoadingAssignments(false)
      })
  }

  const handleRetryProofVerification = async () => {
    if (!selectedParcelId) return
    setProofLookupStatus('checking')
    setProofLookupError(null)
    try {
      const proofData = await api.getProofMetadata(selectedParcelId)
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
    <div className="courier-proof-workspace" aria-label="Courier Proof Workspace">
      {/* Assignment selector bar */}
      <section className="card assignment-selector-card" aria-labelledby="assigned-parcels-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Active Shift Assignments</p>
            <h2 id="assigned-parcels-heading">Assigned Deliveries for Proof Capture</h2>
          </div>
          <span className="badge badge-approved">
            {assignments.length} {assignments.length === 1 ? 'Parcel' : 'Parcels'}
          </span>
        </div>

        {isLoadingAssignments && (
          <div className="loading-state" role="status">
            <span className="loading-indicator" aria-hidden="true" />
            <p className="muted">Retrieving your active courier assignments...</p>
          </div>
        )}

        {assignmentError && (
          <div className="feedback-banner is-error" role="alert">
            <span>{assignmentError}</span>
            <button
              type="button"
              className="button button-secondary button-small"
              onClick={handleRetryAssignments}
            >
              Retry
            </button>
          </div>
        )}

        {!isLoadingAssignments && !assignmentError && assignments.length === 0 && (
          <div className="empty-state" role="status">
            <p className="muted">
              No active delivery assignments found for your courier profile.
              When dispatchers assign parcels to your route, they will appear here for handover and proof capture.
            </p>
          </div>
        )}

        {assignments.length > 0 && (
          <div className="assignment-pills-row" role="tablist" aria-label="Assigned parcels list">
            {assignments.map((a) => {
              const isSelected = a.parcelId === selectedParcelId
              return (
                <button
                  key={a.assignmentId}
                  type="button"
                  role="tab"
                  aria-selected={isSelected}
                  className={`assignment-pill ${isSelected ? 'is-selected' : ''}`}
                  onClick={() => handleSelectParcel(a.parcelId)}
                >
                  <span className="pill-id monospace">{a.parcelId.slice(0, 8)}...</span>
                  <span className="pill-status">{isSelected ? 'Active' : 'Select'}</span>
                </button>
              )
            })}
          </div>
        )}
      </section>

      {/* Selected parcel proof capture area */}
      {selectedParcelId && (
        <div className="selected-parcel-workspace">
          {isLoadingParcel && (
            <div className="loading-state card" role="status">
              <span className="loading-indicator" aria-hidden="true" />
              <p className="muted">Loading parcel handover context...</p>
            </div>
          )}

          {parcelError && (
            <div className="feedback-banner is-error card" role="alert">
              <span>{parcelError}</span>
            </div>
          )}

          {!isLoadingParcel && selectedParcel && (
            <>
              {/* Fail-closed proof lookup warning */}
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
                key={`${subject}-${selectedParcel.parcelId}-${existingProof?.proofId || 'new'}`}
                parcel={selectedParcel}
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
                  // Authoritatively fetch metadata from server to populate detail without fabricating fields
                  void api
                    .getProofMetadata(selectedParcel.parcelId)
                    .then((detail) => {
                      setExistingProof(detail)
                      setProofLookupStatus('verified-exists')
                    })
                    .catch(() => {
                      setProofLookupStatus('verified-exists')
                    })
                }}
              />

              {/* Collapsible Delivery Exception Section */}
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
                  parcelId={selectedParcel.parcelId}
                  trackingNumber={selectedParcel.trackingNumber}
                />
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
