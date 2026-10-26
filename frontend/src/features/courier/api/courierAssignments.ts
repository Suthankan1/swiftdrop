import {
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
    }: {
      activeOnly?: boolean
      page?: number
      size?: number
      signal?: AbortSignal
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

        const url = `${normalizedGateway}/api/v1/assignments/my?activeOnly=${activeOnly}&page=${page}&size=${size}`
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
  }
}
