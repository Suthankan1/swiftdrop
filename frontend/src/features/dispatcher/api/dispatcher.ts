import { isRecord } from '../../staff/api/contracts'

export interface UnassignedParcel {
  parcelId: string
  trackingNumber: string
  pickupZone: string
  dropoffZone: string
  weightKg: number
  sizeCategory: string
  createdAt: string
  version: number
}
export interface CourierWorkload {
  courierSub: string
  fullName: string
  activeAssignmentsCount: number
  maxCapacity: number
  eligible: boolean
}
export interface DispatcherDashboard {
  unassignedParcels: UnassignedParcel[]
  availableCouriers: CourierWorkload[]
  pageInfo: { currentPage: number; pageSize: number; totalElements: number; totalPages: number; hasNext: boolean }
}
export interface AssignmentCommand { parcelId: string; courierSub: string; expectedVersion: number }
export interface AssignmentResponse {
  assignmentId: string; parcelId: string; courierSub: string; active: boolean
  assignedAt: string; assignedBy: string; newParcelVersion: number
}
export type DispatcherFailure = 'auth-required' | 'forbidden' | 'conflict' | 'rejected' | 'unavailable' | 'timeout' | 'invalid-response' | 'invalid-pagination'
const messages: Record<DispatcherFailure, string> = {
  'auth-required': 'Your session expired. Sign in again.',
  forbidden: 'Access denied or resource unavailable.',
  conflict: 'This parcel changed or was already assigned. Review the refreshed queue before trying again.',
  rejected: 'Assignment rejected. The parcel state or courier eligibility may have changed. Review the refreshed queue.',
  unavailable: 'Operations provider unavailable. Retry to load current data.',
  timeout: 'The request timed out. For an assignment, the outcome is unknown. Refresh and review before trying again.',
  'invalid-response': 'The provider returned an invalid response. Refresh and review before continuing.',
  'invalid-pagination': 'Page must be zero or greater and page size must be between 1 and 50.',
}
export class DispatcherError extends Error {
  readonly kind: DispatcherFailure
  constructor(kind: DispatcherFailure) { super(messages[kind]); this.name = 'DispatcherError'; this.kind = kind }
}
export const dashboardQuery = `query DispatcherQueue($pagination: PaginationInput) {
  dispatcherDashboard(pagination: $pagination) {
    unassignedParcels { parcelId trackingNumber pickupZone dropoffZone weightKg sizeCategory createdAt version }
    pageInfo { currentPage pageSize totalElements totalPages hasNext }
    availableCouriers { courierSub fullName activeAssignmentsCount maxCapacity eligible }
  }
}`
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const textFields = (v: Record<string, unknown>, fields: string[]) => fields.every(k => typeof v[k] === 'string' && (v[k] as string).trim().length > 0)
function isDashboard(v: unknown): v is DispatcherDashboard {
  if (!isRecord(v) || !isRecord(v.pageInfo) || !Array.isArray(v.unassignedParcels) || !Array.isArray(v.availableCouriers)) return false
  const p = v.pageInfo
  return integer(p.currentPage) && integer(p.pageSize) && p.pageSize >= 1 && p.pageSize <= 50 &&
    integer(p.totalElements) && integer(p.totalPages) && typeof p.hasNext === 'boolean' &&
    v.unassignedParcels.length <= p.pageSize && v.availableCouriers.length <= 50 &&
    v.unassignedParcels.every(x => isRecord(x) && textFields(x, ['parcelId', 'trackingNumber', 'pickupZone', 'dropoffZone', 'sizeCategory', 'createdAt']) &&
      integer(x.version) && typeof x.weightKg === 'number' && Number.isFinite(x.weightKg) && x.weightKg > 0 && Number.isFinite(Date.parse(x.createdAt as string))) &&
    v.availableCouriers.every(x => isRecord(x) && textFields(x, ['courierSub', 'fullName']) && integer(x.activeAssignmentsCount) && integer(x.maxCapacity) && typeof x.eligible === 'boolean') &&
    new Set(v.unassignedParcels.map(x => x.parcelId)).size === v.unassignedParcels.length &&
    new Set(v.availableCouriers.map(x => x.courierSub)).size === v.availableCouriers.length
}
export function createDispatcherApi({ getAccessToken, gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080', fetch: request = globalThis.fetch, timeoutMs = 10_000 }: {
  getAccessToken: () => Promise<string | null>; gatewayUrl?: string; fetch?: typeof fetch; timeoutMs?: number
}) {
  async function post(path: string, body: unknown, signal?: AbortSignal): Promise<unknown> {
    const controller = new AbortController()
    const abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) controller.abort()
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    let stopWaiting: (() => void) | undefined
    const interrupted = new Promise<never>((_resolve, reject) => {
      stopWaiting = () => reject(new DOMException('Request aborted', 'AbortError'))
      controller.signal.addEventListener('abort', stopWaiting, { once: true })
    })
    try {
      controller.signal.throwIfAborted()
      const token = await Promise.race([getAccessToken(), interrupted])
      if (!token?.trim()) throw new DispatcherError('auth-required')
      const response = await request(`${gatewayUrl.replace(/\/$/, '')}${path}`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body), signal: controller.signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      })
      controller.signal.throwIfAborted()
      if (response.status === 401) throw new DispatcherError('auth-required')
      if ([403, 404].includes(response.status)) throw new DispatcherError('forbidden')
      if (response.status === 409) throw new DispatcherError('conflict')
      if ([400, 422].includes(response.status)) throw new DispatcherError('rejected')
      if ([408, 504].includes(response.status)) throw new DispatcherError('timeout')
      if (!response.ok) throw new DispatcherError('unavailable')
      let payload: unknown
      try { payload = await response.json() } catch { throw new DispatcherError('invalid-response') }
      controller.signal.throwIfAborted()
      return payload
    } catch (error) {
      if (signal?.aborted) throw error
      if (timedOut) throw new DispatcherError('timeout')
      if (error instanceof DispatcherError) throw error
      throw new DispatcherError('unavailable')
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
    }
  }
  return {
    async dashboard(page = 0, size = 20, signal?: AbortSignal): Promise<DispatcherDashboard> {
      if (!integer(page) || !integer(size) || size < 1 || size > 50) throw new DispatcherError('invalid-pagination')
      const result = await post('/graphql', { query: dashboardQuery, variables: { pagination: { page, size } } }, signal)
      if (!isRecord(result)) throw new DispatcherError('invalid-response')
      if (Array.isArray(result.errors) && result.errors.length) {
        const codes = result.errors.map(e => isRecord(e) && isRecord(e.extensions) ? e.extensions.code : undefined)
        if (codes.includes('UNAUTHENTICATED')) throw new DispatcherError('auth-required')
        if (codes.includes('FORBIDDEN') || codes.includes('NOT_FOUND')) throw new DispatcherError('forbidden')
        if (codes.includes('DEADLINE_EXCEEDED')) throw new DispatcherError('timeout')
        if (codes.includes('INVALID_ARGUMENT')) throw new DispatcherError('invalid-pagination')
        throw new DispatcherError('unavailable')
      }
      const data = isRecord(result.data) ? result.data.dispatcherDashboard : undefined
      if (!isDashboard(data) || data.pageInfo.currentPage !== page || data.pageInfo.pageSize !== size) throw new DispatcherError('invalid-response')
      return data
    },
    async assign(command: AssignmentCommand, signal?: AbortSignal): Promise<AssignmentResponse> {
      if (!command.parcelId.trim() || !command.courierSub.trim() || !integer(command.expectedVersion)) throw new DispatcherError('rejected')
      const result = await post('/api/v1/assignments', command, signal)
      if (!isRecord(result) || !textFields(result, ['assignmentId', 'parcelId', 'courierSub', 'assignedAt', 'assignedBy']) ||
        result.parcelId !== command.parcelId || result.courierSub !== command.courierSub || result.active !== true ||
        !integer(result.newParcelVersion) || result.newParcelVersion <= command.expectedVersion) throw new DispatcherError('invalid-response')
      return result as unknown as AssignmentResponse
    },
  }
}
