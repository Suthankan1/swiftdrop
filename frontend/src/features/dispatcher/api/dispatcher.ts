import { isRecord } from '../../staff/api/contracts'
import type {
  HistoricalParcelItem,
  ParcelListResponse,
  ParcelStatusHistoryEntry,
  ParcelStatusHistoryPageResponse,
  AssignmentAuditEntry,
} from './dispatcherContracts'

export type {
  HistoricalParcelItem,
  ParcelListResponse,
  ParcelStatusHistoryEntry,
  ParcelStatusHistoryPageResponse,
  AssignmentAuditEntry,
}

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
export const dashboardQuery = `query DispatcherQueue($pagination: PaginationInput, $zone: String) {
  dispatcherDashboard(pagination: $pagination, zone: $zone) {
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
export function createDispatcherApi({ getAccessToken, gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080', fetch: request = globalThis.fetch, timeoutMs = 30_000 }: {
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
    async dashboard(page = 0, size = 20, signal?: AbortSignal, zone?: string): Promise<DispatcherDashboard> {
      if (!integer(page) || !integer(size) || size < 1 || size > 50) throw new DispatcherError('invalid-pagination')
      // Zone filtering happens on the server so every page of the queue is filtered, not just this one.
      const zoneCode = zone?.trim().toUpperCase()
      if (zoneCode && !/^[A-Z0-9_]{1,32}$/.test(zoneCode)) throw new DispatcherError('invalid-pagination')
      const variables = { pagination: { page, size }, ...(zoneCode ? { zone: zoneCode } : {}) }
      const result = await post('/graphql', { query: dashboardQuery, variables }, signal)
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
    async getCourierAssignments(courierSub: string, signal?: AbortSignal): Promise<Array<{ assignmentId: string; parcelId: string; courierSub: string; active: boolean; assignedAt: string }>> {
      const token = await getAccessToken()
      if (!token?.trim()) throw new DispatcherError('auth-required')
      try {
        const response = await request(`${gatewayUrl.replace(/\/$/, '')}/api/v1/assignments?activeOnly=true&size=100`, {
          method: 'GET',
          headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
        if (!response.ok) return []
        const data = await response.json()
        const items = isRecord(data) && Array.isArray(data.items)
          ? data.items
          : (isRecord(data) && Array.isArray(data.content) ? data.content : [])
        return items.filter((a: unknown): a is { assignmentId: string; parcelId: string; courierSub: string; active: boolean; assignedAt: string } =>
          isRecord(a) && a.courierSub === courierSub && a.active === true && typeof a.parcelId === 'string'
        )
      } catch {
        return []
      }
    },
    async getParcel(parcelId: string, signal?: AbortSignal): Promise<Record<string, unknown> | null> {
      const token = await getAccessToken()
      if (!token?.trim()) return null
      try {
        const response = await request(`${gatewayUrl.replace(/\/$/, '')}/api/v1/parcels/${parcelId}`, {
          method: 'GET',
          headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
        if (!response.ok) return null
        const data = await response.json()
        return isRecord(data) ? data : null
      } catch {
        return null
      }
    },
    async getParcelList(params: { page?: number; size?: number } = {}, signal?: AbortSignal): Promise<ParcelListResponse> {
      const token = await getAccessToken()
      if (!token?.trim()) throw new DispatcherError('auth-required')
      const page = params.page ?? 0
      const size = params.size ?? 20
      if (!integer(page) || !integer(size) || size < 1 || size > 50) throw new DispatcherError('invalid-pagination')

      const response = await request(`${gatewayUrl.replace(/\/$/, '')}/api/v1/parcels?page=${page}&size=${size}`, {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal,
      })

      if (response.status === 401) throw new DispatcherError('auth-required')
      if (response.status === 403 || response.status === 404) throw new DispatcherError('forbidden')
      if (!response.ok) throw new DispatcherError('unavailable')

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new DispatcherError('invalid-response')
      }

      if (!isRecord(data)) throw new DispatcherError('invalid-response')
      const rawItems = Array.isArray(data.items) ? data.items : (Array.isArray(data.content) ? data.content : null)
      if (!rawItems) throw new DispatcherError('invalid-response')

      const totalElements = typeof data.totalElements === 'number' ? data.totalElements : null
      const totalPages = typeof data.totalPages === 'number' ? data.totalPages : null
      const currentPage = typeof data.currentPage === 'number' ? data.currentPage : (typeof data.page === 'number' ? data.page : null)
      const pageSize = typeof data.pageSize === 'number' ? data.pageSize : (typeof data.size === 'number' ? data.size : null)
      const hasNext = typeof data.hasNext === 'boolean' ? data.hasNext : null

      if (totalElements === null || totalPages === null || currentPage === null || pageSize === null || hasNext === null) {
        throw new DispatcherError('invalid-response')
      }

      const items: HistoricalParcelItem[] = []
      for (const item of rawItems) {
        if (!isRecord(item)) throw new DispatcherError('invalid-response')
        const parcelId = typeof item.parcelId === 'string' && item.parcelId.trim() ? item.parcelId.trim() : null
        const trackingNumber = typeof item.trackingNumber === 'string' && item.trackingNumber.trim() ? item.trackingNumber.trim() : null
        const currentStatus = typeof item.currentStatus === 'string' && item.currentStatus.trim()
          ? item.currentStatus.trim()
          : (typeof item.status === 'string' && item.status.trim() ? item.status.trim() : null)
        const createdAt = typeof item.createdAt === 'string' && Number.isFinite(Date.parse(item.createdAt))
          ? item.createdAt
          : null

        if (!parcelId || !trackingNumber || !currentStatus || !createdAt) {
          throw new DispatcherError('invalid-response')
        }

        items.push({
          parcelId,
          trackingNumber,
          pickupZone: typeof item.pickupZone === 'string' && item.pickupZone.trim() ? item.pickupZone.trim() : undefined,
          dropoffZone: typeof item.dropoffZone === 'string' && item.dropoffZone.trim() ? item.dropoffZone.trim() : undefined,
          pickupAddress: typeof item.pickupAddress === 'string' && item.pickupAddress.trim() ? item.pickupAddress.trim() : undefined,
          dropoffAddress: typeof item.dropoffAddress === 'string' && item.dropoffAddress.trim() ? item.dropoffAddress.trim() : undefined,
          weightKg: typeof item.weightKg === 'number' && Number.isFinite(item.weightKg) ? item.weightKg : undefined,
          currentStatus,
          activeCourierSub: typeof item.activeCourierSub === 'string' && item.activeCourierSub.trim() ? item.activeCourierSub.trim() : null,
          createdAt,
          version: typeof item.version === 'number' && Number.isFinite(item.version) ? item.version : undefined,
        })
      }

      return {
        items,
        currentPage,
        pageSize,
        totalElements,
        totalPages,
        hasNext,
      }
    },
    async getParcelStatusHistory(parcelId: string, params: { page?: number; size?: number } = {}, signal?: AbortSignal): Promise<ParcelStatusHistoryPageResponse> {
      if (!parcelId?.trim()) throw new DispatcherError('rejected')
      const token = await getAccessToken()
      if (!token?.trim()) throw new DispatcherError('auth-required')
      const page = params.page ?? 0
      const size = params.size ?? 50
      if (!integer(page) || !integer(size) || size < 1 || size > 100) throw new DispatcherError('invalid-pagination')

      const response = await request(`${gatewayUrl.replace(/\/$/, '')}/api/v1/parcels/${encodeURIComponent(parcelId)}/history?page=${page}&size=${size}`, {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal,
      })

      if (response.status === 401) throw new DispatcherError('auth-required')
      if (response.status === 403 || response.status === 404) throw new DispatcherError('forbidden')
      if (!response.ok) throw new DispatcherError('unavailable')

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new DispatcherError('invalid-response')
      }

      if (!isRecord(data)) throw new DispatcherError('invalid-response')
      const rawItems = Array.isArray(data.items) ? data.items : (Array.isArray(data.content) ? data.content : null)
      if (!rawItems) throw new DispatcherError('invalid-response')

      const totalElements = typeof data.totalElements === 'number' ? data.totalElements : null
      const totalPages = typeof data.totalPages === 'number' ? data.totalPages : null
      const currentPage = typeof data.currentPage === 'number' ? data.currentPage : null
      const pageSize = typeof data.pageSize === 'number' ? data.pageSize : null
      const hasNext = typeof data.hasNext === 'boolean' ? data.hasNext : null

      if (totalElements === null || totalPages === null || currentPage === null || pageSize === null || hasNext === null) {
        throw new DispatcherError('invalid-response')
      }

      const items: ParcelStatusHistoryEntry[] = []
      for (const entry of rawItems) {
        if (!isRecord(entry)) throw new DispatcherError('invalid-response')
        const historyId = typeof entry.historyId === 'string' && entry.historyId.trim() ? entry.historyId.trim() : null
        const entryParcelId = typeof entry.parcelId === 'string' && entry.parcelId.trim() ? entry.parcelId.trim() : parcelId
        const newStatus = typeof entry.newStatus === 'string' && entry.newStatus.trim() ? entry.newStatus.trim() : null
        const resultingVersion = typeof entry.resultingVersion === 'number' && Number.isFinite(entry.resultingVersion)
          ? entry.resultingVersion
          : null
        const occurredAt = typeof entry.occurredAt === 'string' && Number.isFinite(Date.parse(entry.occurredAt))
          ? entry.occurredAt
          : null

        if (!historyId || !newStatus || resultingVersion === null || !occurredAt) {
          throw new DispatcherError('invalid-response')
        }

        items.push({
          historyId,
          parcelId: entryParcelId,
          previousStatus: typeof entry.previousStatus === 'string' && entry.previousStatus.trim() ? entry.previousStatus.trim() : null,
          newStatus,
          reason: typeof entry.reason === 'string' && entry.reason.trim() ? entry.reason.trim() : null,
          note: typeof entry.note === 'string' && entry.note.trim() ? entry.note.trim() : null,
          proofId: typeof entry.proofId === 'string' && entry.proofId.trim() ? entry.proofId.trim() : null,
          resultingVersion,
          occurredAt,
        })
      }

      // Older events first, newest last as required by section 5 Option A:
      items.sort((a, b) => a.resultingVersion - b.resultingVersion || Date.parse(a.occurredAt) - Date.parse(b.occurredAt))

      return {
        items,
        currentPage,
        pageSize,
        totalElements,
        totalPages,
        hasNext,
      }
    },
    async getAssignmentHistory(parcelId: string, signal?: AbortSignal): Promise<AssignmentAuditEntry[]> {
      if (!parcelId?.trim()) throw new DispatcherError('rejected')
      const token = await getAccessToken()
      if (!token?.trim()) throw new DispatcherError('auth-required')

      const response = await request(`${gatewayUrl.replace(/\/$/, '')}/api/v1/assignments/parcels/${encodeURIComponent(parcelId)}/history`, {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal,
      })

      if (response.status === 401) throw new DispatcherError('auth-required')
      if (response.status === 403 || response.status === 404) throw new DispatcherError('forbidden')
      if (!response.ok) throw new DispatcherError('unavailable')

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new DispatcherError('invalid-response')
      }

      const rawItems = Array.isArray(data)
        ? data
        : (isRecord(data) && Array.isArray(data.items) ? data.items : (isRecord(data) && Array.isArray(data.content) ? data.content : null))

      if (!rawItems) throw new DispatcherError('invalid-response')

      const items: AssignmentAuditEntry[] = []
      for (const entry of rawItems) {
        if (!isRecord(entry)) throw new DispatcherError('invalid-response')
        const auditId = typeof entry.auditId === 'string' && entry.auditId.trim() ? entry.auditId.trim() : null
        const entryParcelId = typeof entry.parcelId === 'string' && entry.parcelId.trim() ? entry.parcelId.trim() : parcelId
        const assignmentId = typeof entry.assignmentId === 'string' && entry.assignmentId.trim() ? entry.assignmentId.trim() : null
        const courierSub = typeof entry.courierSub === 'string' && entry.courierSub.trim() ? entry.courierSub.trim() : null
        const action = typeof entry.action === 'string' && entry.action.trim() ? entry.action.trim() : null
        const actorSub = typeof entry.actorSub === 'string' && entry.actorSub.trim() ? entry.actorSub.trim() : null
        const actorRole = typeof entry.actorRole === 'string' && entry.actorRole.trim() ? entry.actorRole.trim() : ''
        const occurredAt = typeof entry.occurredAt === 'string' && Number.isFinite(Date.parse(entry.occurredAt))
          ? entry.occurredAt
          : null

        if (!auditId || !assignmentId || !courierSub || !action || !actorSub || !occurredAt) {
          throw new DispatcherError('invalid-response')
        }

        items.push({
          auditId,
          parcelId: entryParcelId,
          assignmentId,
          courierSub,
          action,
          actorSub,
          actorRole,
          previousCourierSub: typeof entry.previousCourierSub === 'string' && entry.previousCourierSub.trim() ? entry.previousCourierSub.trim() : null,
          previousAssignmentId: typeof entry.previousAssignmentId === 'string' && entry.previousAssignmentId.trim() ? entry.previousAssignmentId.trim() : null,
          reason: typeof entry.reason === 'string' && entry.reason.trim() ? entry.reason.trim() : null,
          parcelVersion: typeof entry.parcelVersion === 'number' && Number.isFinite(entry.parcelVersion) ? entry.parcelVersion : null,
          occurredAt,
        })
      }

      // Older actions first, newest last for chronological timeline:
      items.sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt))

      return items
    },
  }
}
