import { useEffect, useRef, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import type { createCourierProofApi } from '../api/proof'
import { CourierProofError } from '../api/proof'
import type {
  FailureReasonCode,
  ParcelDetailResponse,
  ParcelStatus,
  ProofDetailResponse,
} from '../api/proofContracts'
import type { createCourierStatusApi } from '../api/status'
import { CourierStatusError } from '../api/statusContracts'
import type { CourierTransitionStatus } from '../api/statusContracts'
import type { ProofLookupStatus } from './CourierParcelDetails'
import { FailedDeliveryReason } from './FailedDeliveryReason'

const NEXT_STAGE: Partial<Record<ParcelStatus, CourierTransitionStatus>> = {
  CREATED: 'PICKED_UP',
  PICKED_UP: 'IN_TRANSIT',
  IN_TRANSIT: 'OUT_FOR_DELIVERY',
  OUT_FOR_DELIVERY: 'DELIVERED',
}

const ACTION_LABEL: Record<CourierTransitionStatus, string> = {
  PICKED_UP: 'Confirm Pickup',
  IN_TRANSIT: 'Mark In Transit',
  OUT_FOR_DELIVERY: 'Mark Out for Delivery',
  DELIVERED: 'Complete Delivery',
  FAILED: 'Report Delivery Failure',
}

const COURIER_FAILURE_REASONS: Partial<Record<ParcelStatus, readonly FailureReasonCode[]>> = {
  PICKED_UP: ['PARCEL_DAMAGED', 'FORCE_MAJEURE'],
  IN_TRANSIT: ['PARCEL_DAMAGED', 'FORCE_MAJEURE'],
  OUT_FOR_DELIVERY: [
    'RECIPIENT_UNAVAILABLE',
    'INCORRECT_ADDRESS',
    'REFUSED_BY_RECIPIENT',
  ],
}

interface PreparedFailure {
  reason: FailureReasonCode
  note: string
}

export interface CourierStageActionsProps {
  parcel: ParcelDetailResponse
  subject: string
  proof: ProofDetailResponse | null
  proofLookupStatus: ProofLookupStatus
  proofApi: ReturnType<typeof createCourierProofApi>
  statusApi: ReturnType<typeof createCourierStatusApi>
  onParcelRefresh: (parcel: ParcelDetailResponse) => void
  onProofRefresh: (proof: ProofDetailResponse | null, status: ProofLookupStatus) => void
  onAssignmentLost: (message: string) => void
}

export function CourierStageActions({
  parcel,
  subject,
  proof,
  proofLookupStatus,
  proofApi,
  statusApi,
  onParcelRefresh,
  onProofRefresh,
  onAssignmentLost,
}: CourierStageActionsProps) {
  const [note, setNote] = useState('')
  const [preparedFailure, setPreparedFailure] = useState<PreparedFailure | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isLocked, setIsLocked] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [messageKind, setMessageKind] = useState<'success' | 'error' | 'notice'>('notice')
  const operationRef = useRef<AbortController | null>(null)
  const previousStatusRef = useRef(parcel.currentStatus)

  useEffect(() => () => operationRef.current?.abort(), [])
  useEffect(() => {
    if (previousStatusRef.current !== parcel.currentStatus) {
      previousStatusRef.current = parcel.currentStatus
      setNote('')
      setPreparedFailure(null)
    }
  }, [parcel.currentStatus])

  const nextStage = NEXT_STAGE[parcel.currentStatus]
  const failureReasons = COURIER_FAILURE_REASONS[parcel.currentStatus]
  const isTerminal = parcel.currentStatus === 'DELIVERED' || parcel.currentStatus === 'FAILED'
  const isAssigned = parcel.activeCourierSub === subject
  const deliveryProofReady =
    proofLookupStatus === 'verified-exists' &&
    proof?.parcelId === parcel.parcelId &&
    Boolean(proof.proofId)

  const announce = (kind: 'success' | 'error' | 'notice', text: string) => {
    setMessageKind(kind)
    setMessage(text)
  }

  const reconcile = async (signal?: AbortSignal) => {
    const current = await proofApi.getParcel(parcel.parcelId, signal)
    if (current.activeCourierSub !== subject) {
      onAssignmentLost('This parcel is no longer assigned to you. The route queue has been refreshed.')
      return null
    }
    onParcelRefresh(current)
    return current
  }

  const reconcileProof = async (signal?: AbortSignal) => {
    try {
      const currentProof = await proofApi.getProofMetadata(parcel.parcelId, signal)
      if (currentProof.parcelId !== parcel.parcelId) {
        onProofRefresh(null, 'error')
        return null
      }
      onProofRefresh(currentProof, 'verified-exists')
      return currentProof
    } catch (error) {
      if (error instanceof CourierProofError && error.kind === 'not-found') {
        onProofRefresh(null, 'verified-none')
        return null
      }
      onProofRefresh(null, error instanceof CourierProofError && error.kind === 'forbidden' ? 'forbidden' : 'error')
      throw error
    }
  }

  const verifyAfterUncertainOutcome = async (
    attemptedStatus: CourierTransitionStatus,
    attemptedVersion: number,
    signal: AbortSignal,
  ) => {
    try {
      const current = await reconcile(signal)
      if (!current) return
      if (current.currentStatus === attemptedStatus && current.version > attemptedVersion) {
        setIsLocked(false)
        setPreparedFailure(null)
        setNote('')
        announce('success', `${ACTION_LABEL[attemptedStatus]} was confirmed by the authoritative parcel state.`)
        return
      }
      if (current.currentStatus === 'DELIVERED' || current.currentStatus === 'FAILED') {
        announce('notice', 'The parcel now has a terminal status. No further action is available.')
        return
      }
      setIsLocked(false)
      announce('notice', 'The attempted change was not confirmed. Review the refreshed status before deliberately submitting again.')
    } catch {
      announce('error', 'The outcome is still uncertain. Refresh the parcel status before attempting another command.')
    }
  }

  const submit = async (
    target: CourierTransitionStatus,
    commandNote: string,
    reason?: FailureReasonCode,
  ) => {
    if (isSubmitting || isLocked || !isAssigned || isTerminal) return

    const controller = new AbortController()
    operationRef.current?.abort()
    operationRef.current = controller
    setIsSubmitting(true)
    setMessage(null)
    let attemptedVersion = parcel.version

    try {
      const current = await reconcile(controller.signal)
      if (!current) return
      attemptedVersion = current.version
      if (
        current.currentStatus !== parcel.currentStatus ||
        (NEXT_STAGE[current.currentStatus] !== target && target !== 'FAILED')
      ) {
        announce('notice', 'The parcel was updated elsewhere. Review the refreshed stage before submitting a new command.')
        return
      }
      if (target === 'FAILED' && !COURIER_FAILURE_REASONS[current.currentStatus]?.includes(reason!)) {
        setPreparedFailure(null)
        announce('error', 'That failure reason is not permitted for the refreshed parcel stage.')
        return
      }

      let proofId: string | undefined
      if (target === 'DELIVERED') {
        const verifiedProof = await reconcileProof(controller.signal)
        if (!verifiedProof) {
          announce('error', 'A server-confirmed proof record is required before delivery can be completed.')
          return
        }
        proofId = verifiedProof.proofId
      }

      const response = await statusApi.updateStatus(
        parcel.parcelId,
        {
          newStatus: target,
          expectedVersion: current.version,
          note: commandNote,
          reason,
          proofId,
        },
        controller.signal,
      )

      const confirmed = await reconcile(controller.signal)
      if (!confirmed) return
      if (
        confirmed.currentStatus !== response.currentStatus ||
        confirmed.version !== response.updatedParcelVersion
      ) {
        setIsLocked(true)
        announce('error', 'The command response could not be reconciled with the parcel record. Refresh before taking another action.')
        return
      }

      setNote('')
      setPreparedFailure(null)
      announce('success', `${ACTION_LABEL[target]} confirmed. Parcel is now ${response.currentStatus}.`)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof CourierStatusError) {
        if (error.kind === 'forbidden' || error.kind === 'not-found') {
          onAssignmentLost('Access to this parcel was revoked or reassigned. The route queue has been refreshed.')
          return
        }
        if (error.kind === 'conflict') {
          setIsLocked(true)
          try {
            const current = await reconcile(controller.signal)
            if (current && current.currentStatus !== 'DELIVERED' && current.currentStatus !== 'FAILED') {
              setIsLocked(false)
            }
          } catch {
            // Remain locked until an explicit refresh succeeds.
          }
          announce('notice', 'The parcel was updated elsewhere. No command was repeated; review the refreshed status and submit again manually if still valid.')
          return
        }
        if (error.kind === 'ambiguous-outcome') {
          setIsLocked(true)
          announce('notice', error.message)
          await verifyAfterUncertainOutcome(target, attemptedVersion, controller.signal)
          return
        }
        announce('error', error.message)
        return
      }
      if (error instanceof CourierProofError && (error.kind === 'forbidden' || error.kind === 'not-found')) {
        onAssignmentLost('Access to this parcel or its proof was revoked. The route queue has been refreshed.')
        return
      }
      announce('error', error instanceof Error ? error.message : 'The lifecycle command could not be completed.')
    } finally {
      if (operationRef.current === controller) operationRef.current = null
      setIsSubmitting(false)
    }
  }

  const handleExplicitRefresh = async () => {
    if (isSubmitting) return
    setIsSubmitting(true)
    try {
      const current = await reconcile()
      if (!current) return
      setIsLocked(current.currentStatus === 'DELIVERED' || current.currentStatus === 'FAILED')
      announce('notice', 'Authoritative parcel status refreshed. Review the available actions before submitting.')
    } catch (error) {
      if (error instanceof CourierProofError && (error.kind === 'forbidden' || error.kind === 'not-found')) {
        onAssignmentLost('Access to this parcel was revoked or reassigned. The route queue has been refreshed.')
        return
      }
      announce('error', error instanceof Error ? error.message : 'Unable to refresh parcel status.')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!isAssigned) {
    return (
      <div className="feedback-banner is-error" role="alert">
        Status actions are unavailable because an active assignment could not be verified.
      </div>
    )
  }

  return (
    <section className="card courier-stage-actions" aria-labelledby="courier-stage-actions-heading">
      <div className="section-heading-row">
        <div>
          <p className="eyebrow">Parcel lifecycle</p>
          <h3 id="courier-stage-actions-heading">Stage and operational notes</h3>
        </div>
        <span className="badge badge-version">Authoritative version {parcel.version}</span>
      </div>

      {message && (
        <div
          className={`feedback-banner ${messageKind === 'success' ? 'is-success' : messageKind === 'error' ? 'is-error' : 'is-notice'}`}
          role={messageKind === 'error' ? 'alert' : 'status'}
          aria-live="polite"
        >
          <span>{message}</span>
        </div>
      )}

      {isTerminal ? (
        <p className="muted">This parcel is terminal. No further courier lifecycle actions are permitted.</p>
      ) : (
        <>
          {nextStage && (
            <div className="stage-command-panel">
              <p>
                Next permitted stage: <strong>{nextStage}</strong>
              </p>
              <label className="field-label" htmlFor={`status-note-${parcel.parcelId}`}>
                Operational notes (optional)
              </label>
              <textarea
                id={`status-note-${parcel.parcelId}`}
                className="input-textarea"
                rows={3}
                value={note}
                onChange={(event) => setNote(event.target.value)}
                disabled={isSubmitting || isLocked}
                placeholder="Add useful handover or route context."
              />
              {nextStage === 'DELIVERED' && (
                <p className="muted" role="note">
                  Completing delivery is terminal and requires a server-confirmed proof record.
                </p>
              )}
              <Button
                type="button"
                variant="primary"
                disabled={
                  isSubmitting ||
                  isLocked ||
                  (nextStage === 'DELIVERED' && !deliveryProofReady)
                }
                isLoading={isSubmitting}
                loadingText="Submitting..."
                onClick={() => void submit(nextStage, note)}
              >
                {ACTION_LABEL[nextStage]}
              </Button>
              {nextStage === 'DELIVERED' && !deliveryProofReady && (
                <p className="field-error" role="status">
                  Delivery completion is locked until proof metadata is verified by the server.
                </p>
              )}
            </div>
          )}

          {failureReasons && (
            <div className="stage-failure-panel">
              <FailedDeliveryReason
                key={`${parcel.parcelId}-${parcel.currentStatus}`}
                parcelId={parcel.parcelId}
                trackingNumber={parcel.trackingNumber}
                allowedReasons={failureReasons}
                disabled={isSubmitting || isLocked}
                onSubmitReady={(reason, failureNote) => {
                  setPreparedFailure({ reason, note: failureNote })
                  setMessage(null)
                }}
              />
              {preparedFailure && (
                <div className="terminal-confirmation-panel" role="note">
                  <p>
                    Review and confirm this terminal action. Preparing metadata did not change the parcel.
                  </p>
                  <Button
                    type="button"
                    variant="danger"
                    disabled={isSubmitting || isLocked}
                    isLoading={isSubmitting}
                    loadingText="Submitting..."
                    onClick={() => void submit('FAILED', preparedFailure.note, preparedFailure.reason)}
                  >
                    Confirm Failed Status
                  </Button>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {(isLocked || messageKind !== 'success') && (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={isSubmitting}
          onClick={() => void handleExplicitRefresh()}
        >
          Refresh Parcel Status
        </Button>
      )}
    </section>
  )
}
