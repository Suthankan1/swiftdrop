import type {
  FailureReasonCode,
  ParcelStatus,
  ProblemDetail,
} from './proofContracts'

export type CourierTransitionStatus = Exclude<ParcelStatus, 'CREATED'>

export interface UpdateStatusRequest {
  newStatus: CourierTransitionStatus
  expectedVersion: number
  note?: string
  reason?: FailureReasonCode
  proofId?: string
}

export interface ParcelStatusChangeResponse {
  parcelId: string
  trackingNumber: string
  previousStatus: ParcelStatus
  currentStatus: CourierTransitionStatus
  stateVersion: number
  updatedParcelVersion: number
  occurredAt: string
}

export type StatusFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'validation-failed'
  | 'conflict'
  | 'illegal-transition'
  | 'service-unavailable'
  | 'ambiguous-outcome'

export class CourierStatusError extends Error {
  readonly kind: StatusFailureKind
  readonly statusCode?: number
  readonly problemDetail?: ProblemDetail

  constructor(
    kind: StatusFailureKind,
    message: string,
    statusCode?: number,
    problemDetail?: ProblemDetail,
  ) {
    super(message)
    this.name = 'CourierStatusError'
    this.kind = kind
    this.statusCode = statusCode
    this.problemDetail = problemDetail
  }
}
