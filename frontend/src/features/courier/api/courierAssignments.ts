import {
  type CourierActiveStatus,
  type CourierHistoryStatus,
  type PagedAssignmentsResponse,
  type ParcelDetailResponse,
  CourierApiError,
} from './courierContracts'
import { isAssignmentResponse, isParcelDetailResponse } from './proof'

export interface CourierAssignmentsApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  timeoutMs?: number
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

export function isPagedAssignmentsResponse(value: unknown): value is PagedAssignmentsResponse {
  if (!isRecord(value)) return false
  return (
    Array.isArray(value.items) &&
    value.items.every(isAssignmentResponse) &&
    isNonNegativeInteger(value.currentPage) &&
    isPositiveInteger(value.pageSize) &&
    isNonNegativeInteger(value.totalElements) &&
    isNonNegativeInteger(value.totalPages) &&
    typeof value.hasNext === 'boolean' &&
    typeof value.hasPrevious === 'boolean'
  )
}

export function createCourierAssignmentsApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: CourierAssignmentsApiOptions) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new CourierApiError('auth-required', 'Authentication session is unavailable.')
    }
    if (!token?.trim()) {
      throw new CourierApiError('auth-required', 'Authentication token is missing. Please sign in.')
    }
    return token
  }

  return {
    /**
     * Retrieves paginated courier assignments from Delivery Operations REST API.
     * GET /api/v1/assignments/my?activeOnly=true&page={page}&size={size}
     */
    async getMyAssignmentsPaged({
      activeOnly = true,
      page = 0,
      size = 20,
      signal,
      assignedSince,
      trackingNumber,
      status,
    }: {
      activeOnly?: boolean
      page?: number
      size?: number
      signal?: AbortSignal
      /** Only assignments made at or after this instant (e.g. the courier's local midnight). */
      assignedSince?: Date
      trackingNumber?: string
      status?: CourierActiveStatus
    } = {}): Promise<PagedAssignmentsResponse> {
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 50) {
        throw new CourierApiError('invalid-pagination', 'Page must be 0 or greater and size must be between 1 and 50.')
      }

      const controller = new AbortController()
      const abort = () => controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) controller.abort()

      let timedOut = false
      const timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, timeoutMs)

      let stopWaiting: (() => void) | undefined
      const interrupted = new Promise<never>((_resolve, reject) => {
        stopWaiting = () => reject(new DOMException('Request aborted', 'AbortError'))
        controller.signal.addEventListener('abort', stopWaiting, { once: true })
      })

      try {
        controller.signal.throwIfAborted()
        const token = await Promise.race([resolveAuthToken(), interrupted])

        const since = assignedSince && !Number.isNaN(assignedSince.getTime())
          ? `&assignedSince=${encodeURIComponent(assignedSince.toISOString())}`
          : ''
        const search = trackingNumber?.trim()
          ? `&trackingNumber=${encodeURIComponent(trackingNumber.trim())}`
          : ''
        const statusParam = status ? `&status=${encodeURIComponent(status)}` : ''
        const url = `${normalizedGateway}/api/v1/assignments/my?activeOnly=${activeOnly}&page=${page}&size=${size}${since}${search}${statusParam}`
        const response = await request(url, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'application/json, application/problem+json',
          },
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
        })

        controller.signal.throwIfAborted()

        if (response.status === 401) {
          throw new CourierApiError('auth-required', 'Session expired. Please sign in again.', 401)
        }
        if (response.status === 403) {
          throw new CourierApiError('forbidden', 'Access restricted to active COURIER role.', 403)
        }
        if (response.status === 408 || response.status === 504) {
          throw new CourierApiError('timeout', 'Assignment request timed out.', response.status)
        }
        if (!response.ok) {
          throw new CourierApiError('unavailable', `Assignment service returned HTTP ${response.status}`, response.status)
        }

        let payload: unknown
        try {
          payload = await response.json()
        } catch {
          throw new CourierApiError('invalid-response', 'Failed to parse assignment list JSON response.')
        }

        controller.signal.throwIfAborted()

        if (!isPagedAssignmentsResponse(payload)) {
          throw new CourierApiError('invalid-response', 'Assignment response does not match paginated contract.')
        }

        return payload
      } catch (error) {
        if (signal?.aborted) throw error
        if (timedOut) throw new CourierApiError('timeout', 'Assignment request timed out.')
        if (error instanceof CourierApiError) throw error
        throw new CourierApiError('unavailable', error instanceof Error ? error.message : 'Assignment request failed.')
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
      }
    },

    async getHistoryPaged({
      page = 0,
      size = 20,
      status,
      completedSince,
      completedUntil,
      signal,
    }: {
      page?: number
      size?: number
      status?: CourierHistoryStatus
      completedSince?: Date
      completedUntil?: Date
      signal?: AbortSignal
    } = {}): Promise<PagedAssignmentsResponse> {
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 50) {
        throw new CourierApiError('invalid-pagination', 'Page must be 0 or greater and size must be between 1 and 50.')
      }
      const token = await resolveAuthToken()
      const controller = new AbortController()
      const abort = () => controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) controller.abort()
      try {
        const params = new URLSearchParams({ page: String(page), size: String(size) })
        if (status) params.set('status', status)
        if (completedSince && !Number.isNaN(completedSince.getTime())) params.set('completedSince', completedSince.toISOString())
        if (completedUntil && !Number.isNaN(completedUntil.getTime())) params.set('completedUntil', completedUntil.toISOString())
        const response = await request(`${normalizedGateway}/api/v1/assignments/my/history?${params}`, {
          method: 'GET',
          headers: { Authorization: `Bearer ${token}`, Accept: 'application/json, application/problem+json' },
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
        })
        if (response.status === 401) throw new CourierApiError('auth-required', 'Session expired. Please sign in again.', 401)
        if (response.status === 403) throw new CourierApiError('forbidden', 'Access restricted to your courier history.', 403)
        if (!response.ok) throw new CourierApiError('unavailable', `History service returned HTTP ${response.status}`, response.status)
        const payload: unknown = await response.json()
        if (!isPagedAssignmentsResponse(payload)) throw new CourierApiError('invalid-response', 'History response does not match paginated contract.')
        return payload
      } catch (error) {
        if (signal?.aborted) throw error
        if (error instanceof CourierApiError) throw error
        throw new CourierApiError('unavailable', error instanceof Error ? error.message : 'History request failed.')
      } finally {
        signal?.removeEventListener('abort', abort)
      }
    },

    /**
     * Retrieves authoritative parcel details from Delivery Operations REST API.
     * GET /api/v1/parcels/{parcelId}
     */
    async getParcel(parcelId: string, signal?: AbortSignal): Promise<ParcelDetailResponse> {
      if (!parcelId?.trim()) {
        throw new CourierApiError('not-found', 'Parcel ID is required.')
      }

      const controller = new AbortController()
      const abort = () => controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) controller.abort()

      let timedOut = false
      const timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, timeoutMs)

      let stopWaiting: (() => void) | undefined
      const interrupted = new Promise<never>((_resolve, reject) => {
        stopWaiting = () => reject(new DOMException('Request aborted', 'AbortError'))
        controller.signal.addEventListener('abort', stopWaiting, { once: true })
      })

      try {
        controller.signal.throwIfAborted()
        const token = await Promise.race([resolveAuthToken(), interrupted])

        const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId.trim())}`
        const response = await request(url, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'application/json, application/problem+json',
          },

          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
        })

        controller.signal.throwIfAborted()

        if (response.status === 401) {
          throw new CourierApiError('auth-required', 'Session expired or unauthorized.', 401)
        }
        if (response.status === 403) {
          throw new CourierApiError('forbidden', 'You are not authorized to access this parcel.', 403)
        }
        if (response.status === 404) {
          throw new CourierApiError('not-found', 'Parcel not found or no longer assigned.', 404)
        }
        if (response.status === 408 || response.status === 504) {
          throw new CourierApiError('timeout', 'Parcel details request timed out.', response.status)
        }
        if (!response.ok) {
          throw new CourierApiError('unavailable', `Delivery query returned HTTP ${response.status}`, response.status)
        }

        let payload: unknown
        try {
          payload = await response.json()
        } catch {
          throw new CourierApiError('invalid-response', 'Failed to parse parcel details JSON response.')
        }

        controller.signal.throwIfAborted()

        if (!isParcelDetailResponse(payload)) {
          throw new CourierApiError('invalid-response', 'Parcel details schema mismatch.')
        }

        return payload
      } catch (error) {
        if (signal?.aborted) throw error
        if (timedOut) throw new CourierApiError('timeout', 'Parcel query timed out.')
        if (error instanceof CourierApiError) throw error
        throw new CourierApiError('unavailable', error instanceof Error ? error.message : 'Parcel query failed.')
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
      }
    },

    async getHistoricalParcel(parcelId: string, signal?: AbortSignal): Promise<ParcelDetailResponse> {
      if (!parcelId?.trim()) throw new CourierApiError('not-found', 'Parcel ID is required.')
      const token = await resolveAuthToken()
      const response = await request(
        `${normalizedGateway}/api/v1/parcels/courier-history/${encodeURIComponent(parcelId.trim())}`,
        {
          method: 'GET',
          headers: { Authorization: `Bearer ${token}`, Accept: 'application/json, application/problem+json' },
          signal,
          credentials: 'omit',
          cache: 'no-store',
        },
      )
      if (response.status === 401) throw new CourierApiError('auth-required', 'Session expired. Please sign in again.', 401)
      if (response.status === 403) throw new CourierApiError('forbidden', 'Historical delivery details are not available.', 403)
      if (response.status === 404) throw new CourierApiError('not-found', 'Historical delivery not found.', 404)
      if (!response.ok) throw new CourierApiError('unavailable', `Historical detail request returned HTTP ${response.status}`, response.status)
      const payload: unknown = await response.json()
      if (!isParcelDetailResponse(payload)) throw new CourierApiError('invalid-response', 'Historical detail response does not match contract.')
      return payload
    },
  }
}
