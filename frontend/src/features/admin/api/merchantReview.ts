/** Presentation models projected from the implemented Accounts admin review contract (#121):
 * GET /api/v1/admin/merchants, GET /api/v1/admin/merchants/{id}, GET /api/v1/admin/merchants/{id}/audit,
 * POST /api/v1/merchants/{id}/approve and POST /api/v1/merchants/{id}/suspend, all through the Gateway.
 * Adapters project only authorized fields, validate responses, abort reads and never retry a command.
 */
export type MerchantReviewStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'UNKNOWN'
export type MerchantReviewFilter = Exclude<MerchantReviewStatus, 'UNKNOWN'> | 'ALL'
export type MerchantReviewAction = 'approve' | 'suspend'

export interface MerchantReviewSummary {
  id: string
  businessName: string
  registrationNumber?: string
  status: MerchantReviewStatus
  submittedAt?: string
}

export interface MerchantReasonPolicy { required: boolean; maxLength: number }

export interface MerchantAuditEntry {
  id: string
  actor: string
  action: string
  occurredAt: string
  previousStatus?: string
  newStatus?: string
  outcome?: string
  reason?: string
}

export interface MerchantReviewDetail extends MerchantReviewSummary {
  /** Opaque server concurrency evidence; never synthesized. */
  reviewToken?: string
  actions: readonly { action: MerchantReviewAction; reason?: MerchantReasonPolicy }[]
  /** Undefined means the audit read was unavailable; [] means an exposed empty result. */
  audit?: readonly MerchantAuditEntry[]
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

/** The authoritative status the server reported after a confirmed command. */
export interface MerchantReviewOutcome { status: MerchantReviewStatus }

export interface MerchantReviewApi {
  list: (page: number, size: number, signal?: AbortSignal, filter?: MerchantReviewFilter) => Promise<MerchantReviewPageData>
  detail: (id: string, signal?: AbortSignal) => Promise<MerchantReviewDetail>
  /** Only actions supported by a reviewed, integrated command may appear here. */
  supportedActions: readonly MerchantReviewAction[]
  /** Resolve only for a confirmed business success, not merely an HTTP 2xx. Never auto-replay. */
  change?: (command: MerchantReviewCommand, signal?: AbortSignal) => Promise<MerchantReviewOutcome>
}

export type MerchantReviewFailure = 'auth-required' | 'forbidden' | 'not-found' | 'conflict' | 'validation'
  | 'rate-limited' | 'unavailable' | 'invalid-response' | 'unconfirmed'

const messages: Record<MerchantReviewFailure, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access to this merchant review is denied.',
  'not-found': 'This merchant is no longer available. Refresh the review list.',
  conflict: 'This merchant changed. Review the refreshed details before trying again.',
  validation: 'The request was rejected. Review the merchant and reason before trying again.',
  'rate-limited': 'Too many merchant review requests. Wait a moment, then refresh before trying again.',
  unavailable: 'Merchant review is temporarily unavailable. Retry the read to check the current state.',
  'invalid-response': 'Merchant review returned an unsupported response. Retry the read.',
  unconfirmed: 'The command outcome could not be confirmed. Refresh and review the current state before trying again. It was not retried.',
}

export class MerchantReviewError extends Error {
  readonly kind: MerchantReviewFailure
  constructor(kind: MerchantReviewFailure) {
    super(messages[kind])
    this.name = 'MerchantReviewError'
    this.kind = kind
  }
}

/** Status-only mapping: ProblemDetail text is never exposed because it may carry internal details. */
export function merchantReviewHttpError(status: number): MerchantReviewError {
  if (status === 401) return new MerchantReviewError('auth-required')
  if (status === 403) return new MerchantReviewError('forbidden')
  if (status === 404) return new MerchantReviewError('not-found')
  if (status === 409 || status === 412) return new MerchantReviewError('conflict')
  if (status === 400 || status === 422) return new MerchantReviewError('validation')
  if (status === 429) return new MerchantReviewError('rate-limited')
  return new MerchantReviewError('unavailable')
}

export function merchantReviewFailure(error: unknown): MerchantReviewError {
  return error instanceof MerchantReviewError ? error : new MerchantReviewError('unavailable')
}

/** Kinds where a command may or may not have been applied by the server. */
export function isAmbiguousCommandFailure(kind: MerchantReviewFailure): boolean {
  return kind === 'unavailable' || kind === 'unconfirmed' || kind === 'invalid-response'
}

/** Client-side reason checks mirroring the advertised policy; Accounts still validates. */
export function merchantReasonProblem(reason: string, policy?: MerchantReasonPolicy): string | null {
  if (!policy) return null
  const trimmed = reason.trim()
  if (policy.required && !trimmed) return 'Enter a reason for this action.'
  if (trimmed.length > policy.maxLength) return `Reason must be ${policy.maxLength} characters or fewer.`
  for (const char of trimmed) {
    const code = char.charCodeAt(0)
    if ((code < 32 && char !== '\n' && char !== '\t') || code === 127) return 'Reason contains unsupported control characters.'
  }
  return null
}

const knownStatuses: readonly MerchantReviewStatus[] = ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED']
const commandResult: Record<MerchantReviewAction, MerchantReviewStatus> = { approve: 'APPROVED', suspend: 'SUSPENDED' }

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}
function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}
function toStatus(value: unknown): MerchantReviewStatus {
  return knownStatuses.find(status => status === value) ?? 'UNKNOWN'
}
function toSummary(value: unknown): MerchantReviewSummary {
  const item = record(value)
  const id = text(item?.id)
  const businessName = text(item?.businessName)
  if (!item || !id || !businessName) throw new MerchantReviewError('invalid-response')
  return {
    id, businessName, status: toStatus(item.status),
    ...(text(item.registrationNumber) ? { registrationNumber: text(item.registrationNumber) } : {}),
    ...(text(item.submittedAt) ? { submittedAt: text(item.submittedAt) } : {}),
  }
}
/** Unknown actions or malformed reason policies are dropped so they are never offered. */
function toActions(value: unknown): MerchantReviewDetail['actions'] {
  if (!Array.isArray(value)) return []
  return value.flatMap(entry => {
    const item = record(entry)
    const action = item?.action
    if (action !== 'approve' && action !== 'suspend') return []
    if (item?.reason === null || item?.reason === undefined) return [{ action }]
    const reason = record(item.reason)
    const maxLength = reason?.maxLength
    if (!reason || typeof reason.required !== 'boolean' || !Number.isInteger(maxLength) || (maxLength as number) < 1) return []
    return [{ action, reason: { required: reason.required, maxLength: maxLength as number } }]
  })
}
/** Returns undefined for any malformed audit payload so the UI reports it as unavailable. */
function toAudit(value: unknown): MerchantAuditEntry[] | undefined {
  if (!Array.isArray(value)) return undefined
  const entries: MerchantAuditEntry[] = []
  for (const entry of value) {
    const item = record(entry)
    const id = text(item?.id)
    const actor = text(item?.actor)
    const action = text(item?.action)
    const occurredAt = text(item?.occurredAt)
    if (!item || !id || !actor || !action || !occurredAt) return undefined
    entries.push({
      id, actor, action, occurredAt,
      ...(text(item.previousStatus) ? { previousStatus: text(item.previousStatus) } : {}),
      ...(text(item.newStatus) ? { newStatus: text(item.newStatus) } : {}),
      ...(text(item.outcome) ? { outcome: text(item.outcome) } : {}),
      ...(text(item.reason) ? { reason: text(item.reason) } : {}),
    })
  }
  return entries
}

export interface MerchantReviewApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

export function createMerchantReviewApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = (...args) => globalThis.fetch(...args),
}: MerchantReviewApiOptions): MerchantReviewApi {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1`

  async function request(path: string, signal?: AbortSignal, body?: Record<string, unknown>): Promise<unknown> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new MerchantReviewError('auth-required')
    }
    if (!token?.trim()) throw new MerchantReviewError('auth-required')
    signal?.throwIfAborted()

    let response: Response
    try {
      response = await fetchRequest(`${base}${path}`, {
        method: body ? 'POST' : 'GET',
        headers: {
          ...(body ? { 'Content-Type': 'application/json' } : {}),
          Accept: 'application/json',
          Authorization: `Bearer ${token}`,
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal,
      })
    } catch (error) {
      if (signal?.aborted) throw error
      throw new MerchantReviewError('unavailable')
    }
    if (!response.ok) throw merchantReviewHttpError(response.status)
    try {
      return await response.json()
    } catch (error) {
      if (signal?.aborted) throw error
      // A command that succeeded at HTTP level but returned no readable body is still unconfirmed.
      throw new MerchantReviewError(body ? 'unconfirmed' : 'invalid-response')
    }
  }

  return {
    supportedActions: ['approve', 'suspend'],

    async list(page, size, signal, filter = 'PENDING') {
      const status = filter === 'ALL' ? '' : `&status=${encodeURIComponent(filter)}`
      const data = record(await request(`/admin/merchants?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}${status}`, signal))
      if (!data || !Array.isArray(data.content) || data.page !== page || typeof data.last !== 'boolean') {
        throw new MerchantReviewError('invalid-response')
      }
      return { items: data.content.map(toSummary), page, hasNext: !data.last }
    },

    async detail(id, signal) {
      const path = `/admin/merchants/${encodeURIComponent(id)}`
      const [body, audit] = await Promise.all([
        request(path, signal),
        // Audit is read independently: its failure leaves details usable and reports audit as unavailable.
        request(`${path}/audit`, signal).then(toAudit, (error: unknown) => {
          if (signal?.aborted) throw error
          return undefined
        }),
      ])
      const data = record(body)
      const summary = toSummary(data)
      if (summary.id !== id) throw new MerchantReviewError('invalid-response')
      return {
        ...summary,
        ...(text(data?.reviewToken) ? { reviewToken: text(data?.reviewToken) } : {}),
        actions: toActions(data?.actions),
        ...(audit ? { audit } : {}),
      }
    },

    async change(command, signal) {
      const body = await request(`/merchants/${encodeURIComponent(command.merchantId)}/${command.action}`, signal, {
        reviewToken: command.reviewToken,
        ...(command.reason ? { reason: command.reason } : {}),
      })
      const data = record(body)
      const status = toStatus(data?.status)
      if (data?.id !== command.merchantId || status !== commandResult[command.action]) {
        throw new MerchantReviewError('unconfirmed')
      }
      return { status }
    },
  }
}
