/** Internal presentation models, NOT an approved Accounts wire contract.
 * #121 must supply the reviewed REST adapter before this port is connected.
 * Adapters must project only authorized fields, validate responses, use the shared
 * in-memory session/Gateway, abort reads and never retry a command automatically.
 */
export type MerchantReviewStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'UNKNOWN'
export type MerchantReviewAction = 'approve' | 'suspend'

export interface MerchantReviewSummary {
  id: string
  businessName: string
  registrationNumber?: string
  status: MerchantReviewStatus
  submittedAt?: string
}

export interface MerchantReviewDetail extends MerchantReviewSummary {
  /** Opaque concurrency evidence from the future provider; never synthesized. */
  reviewToken?: string
  actions: readonly {
    action: MerchantReviewAction
    reason?: { required: boolean; maxLength: number }
  }[]
  /** Undefined means no audit read is exposed; [] means an exposed empty result. */
  audit?: readonly { id: string; actor: string; action: string; occurredAt: string; reason?: string }[]
}

export interface MerchantReviewPageData {
  items: readonly MerchantReviewSummary[]
  page: number
  hasNext: boolean
}

export interface MerchantReviewCommand {
  merchantId: string
  action: MerchantReviewAction
  reviewToken: string
  reason?: string
}

export interface MerchantReviewApi {
  list: (page: number, size: number, signal: AbortSignal) => Promise<MerchantReviewPageData>
  detail: (id: string, signal: AbortSignal) => Promise<MerchantReviewDetail>
  /** Only actions supported by a reviewed, integrated command may appear here. */
  supportedActions: readonly MerchantReviewAction[]
  /** Resolve only for a confirmed business success, not merely an HTTP 200 or
   * a failed/pending identity reconciliation result. Never auto-replay. */
  change?: (command: MerchantReviewCommand, signal: AbortSignal) => Promise<void>
}

export type MerchantReviewFailure = 'auth-required' | 'forbidden' | 'not-found' | 'conflict' | 'validation' | 'unavailable' | 'invalid-response'

const messages: Record<MerchantReviewFailure, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access to this merchant review is denied.',
  'not-found': 'This merchant is no longer available. Refresh the review list.',
  conflict: 'This merchant changed. Review the refreshed details before trying again.',
  validation: 'The request was rejected. Review the merchant and reason before trying again.',
  unavailable: 'Merchant review is temporarily unavailable. Retry the read to check the current state.',
  'invalid-response': 'Merchant review returned an unsupported response. Retry the read.',
}

export class MerchantReviewError extends Error {
  readonly kind: MerchantReviewFailure
  constructor(kind: MerchantReviewFailure) {
    super(messages[kind])
    this.name = 'MerchantReviewError'
    this.kind = kind
  }
}

/** Future adapter status mapping: never expose raw ProblemDetail text or bodies. */
export function merchantReviewHttpError(status: number): MerchantReviewError {
  if (status === 401) return new MerchantReviewError('auth-required')
  if (status === 403) return new MerchantReviewError('forbidden')
  if (status === 404) return new MerchantReviewError('not-found')
  if (status === 409) return new MerchantReviewError('conflict')
  if (status === 400 || status === 422) return new MerchantReviewError('validation')
  return new MerchantReviewError('unavailable')
}

export function merchantReviewFailure(error: unknown): MerchantReviewError {
  return error instanceof MerchantReviewError ? error : new MerchantReviewError('unavailable')
}

// Deliberately no runtime adapter, URL guesses, fixture provider or HTTP calls.
export const merchantReviewApi: MerchantReviewApi | undefined = undefined
