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
  list: (page: number, size: number, signal?: AbortSignal) => Promise<MerchantReviewPageData>
  detail: (id: string, signal?: AbortSignal) => Promise<MerchantReviewDetail>
  /** Only actions supported by a reviewed, integrated command may appear here. */
  supportedActions: readonly MerchantReviewAction[]
  /** Resolve only for a confirmed business success, not merely an HTTP 200 or
   * a failed/pending identity reconciliation result. Never auto-replay. */
  change?: (command: MerchantReviewCommand, signal?: AbortSignal) => Promise<void>
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

export interface MerchantReviewApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

export function createMerchantReviewApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: MerchantReviewApiOptions): MerchantReviewApi {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1`

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new MerchantReviewError('auth-required')
    }
    if (!token?.trim()) {
      throw new MerchantReviewError('auth-required')
    }
    return token
  }

  return {
    supportedActions: ['approve', 'suspend'] as const,

    async list(page: number, size: number, signal?: AbortSignal): Promise<MerchantReviewPageData> {
      const token = await resolveAuthToken()
      const url = `${base}/admin/merchants?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantReviewError('unavailable')
      }

      if (!response.ok) {
        throw merchantReviewHttpError(response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantReviewError('invalid-response')
      }

      if (!data || typeof data !== 'object' || !Array.isArray((data as Record<string, unknown>).content)) {
        throw new MerchantReviewError('invalid-response')
      }

      const raw = data as { content: unknown[]; page?: number; last?: boolean }
      const items: MerchantReviewSummary[] = raw.content.map((item: unknown) => {
        const m = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
        const statusStr = String(m.status || '')
        const status: MerchantReviewStatus = ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED'].includes(statusStr)
          ? (statusStr as MerchantReviewStatus)
          : 'UNKNOWN'
        return {
          id: String(m.id || ''),
          businessName: String(m.businessName || ''),
          registrationNumber: m.registrationNumber ? String(m.registrationNumber) : undefined,
          status,
          submittedAt: m.submittedAt ? String(m.submittedAt) : undefined,
        }
      })

      return {
        items,
        page: typeof raw.page === 'number' ? raw.page : page,
        hasNext: typeof raw.last === 'boolean' ? !raw.last : false,
      }
    },

    async detail(id: string, signal?: AbortSignal): Promise<MerchantReviewDetail> {
      const token = await resolveAuthToken()
      const url = `${base}/admin/merchants/${encodeURIComponent(id)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantReviewError('unavailable')
      }

      if (!response.ok) {
        throw merchantReviewHttpError(response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantReviewError('invalid-response')
      }

      if (!data || typeof data !== 'object') {
        throw new MerchantReviewError('invalid-response')
      }

      const m = data as Record<string, unknown>
      if (!m.id || !m.businessName) {
        throw new MerchantReviewError('invalid-response')
      }

      const statusStr = String(m.status || '')
      const status: MerchantReviewStatus = ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED'].includes(statusStr)
        ? (statusStr as MerchantReviewStatus)
        : 'UNKNOWN'

      const actions = Array.isArray(m.actions)
        ? m.actions.map((act: unknown) => {
            const a = (act && typeof act === 'object' ? act : {}) as Record<string, unknown>
            const action = a.action as MerchantReviewAction
            const reason = a.reason && typeof a.reason === 'object'
              ? {
                  required: Boolean((a.reason as Record<string, unknown>).required),
                  maxLength: Number((a.reason as Record<string, unknown>).maxLength) || 500,
                }
              : undefined
            return { action, ...(reason ? { reason } : {}) }
          })
        : []

      const audit = Array.isArray(m.audit)
        ? m.audit.map((aud: unknown) => {
            const entry = (aud && typeof aud === 'object' ? aud : {}) as Record<string, unknown>
            return {
              id: String(entry.id || ''),
              actor: String(entry.actor || 'System'),
              action: String(entry.action || 'REVIEW'),
              occurredAt: String(entry.occurredAt || ''),
              ...(entry.reason ? { reason: String(entry.reason) } : {}),
            }
          })
        : undefined

      return {
        id: String(m.id),
        businessName: String(m.businessName),
        registrationNumber: m.registrationNumber ? String(m.registrationNumber) : undefined,
        status,
        submittedAt: m.submittedAt ? String(m.submittedAt) : undefined,
        reviewToken: m.reviewToken ? String(m.reviewToken) : undefined,
        actions,
        audit,
      }
    },

    async change(command: MerchantReviewCommand, signal?: AbortSignal): Promise<void> {
      const token = await resolveAuthToken()
      const pathSegment = command.action === 'approve' ? 'approve' : 'suspend'
      const url = `${base}/merchants/${encodeURIComponent(command.merchantId)}/${pathSegment}`

      const payload: Record<string, unknown> = {
        reviewToken: command.reviewToken,
      }
      if (command.reason) {
        payload.reason = command.reason
      }

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify(payload),
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantReviewError('unavailable')
      }

      if (!response.ok) {
        throw merchantReviewHttpError(response.status)
      }
    },
  }
}

export const merchantReviewApi: MerchantReviewApi | undefined = undefined
