import {
  type CourierDashboardSummary,
  CourierApiError,
} from './courierContracts'

export interface CourierDashboardApiOptions {
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

export const COURIER_DASHBOARD_SUMMARY_QUERY = `query CourierDashboardSummary {
  courierDashboard {
    courierSub
    activeAssignedCount
    todayDeliveredCount
    todayFailedCount
  }
}`

export function isCourierDashboardSummary(value: unknown): value is CourierDashboardSummary {
  if (!isRecord(value)) return false
  return (
    typeof value.courierSub === 'string' &&
    value.courierSub.trim().length > 0 &&
    isNonNegativeInteger(value.activeAssignedCount) &&
    isNonNegativeInteger(value.todayDeliveredCount) &&
    isNonNegativeInteger(value.todayFailedCount)
  )
}

export function createCourierDashboardApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: CourierDashboardApiOptions) {
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
     * Retrieves authoritative daily operational summary from GraphQL Query service via API Gateway.
     */
    async getSummary(signal?: AbortSignal): Promise<CourierDashboardSummary> {
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

        const response = await request(`${normalizedGateway}/graphql`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify({
            query: COURIER_DASHBOARD_SUMMARY_QUERY,
          }),
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
        })

        controller.signal.throwIfAborted()

        if (response.status === 401) {
          throw new CourierApiError('auth-required', 'Session expired. Please sign in again.', 401)
        }
        if (response.status === 403) {
          throw new CourierApiError('forbidden', 'Access restricted to authorized active Courier.', 403)
        }
        if (response.status === 404) {
          throw new CourierApiError('not-found', 'Courier profile or operational dashboard not found.', 404)
        }
        if (response.status === 408 || response.status === 504) {
          throw new CourierApiError('timeout', 'GraphQL query timed out.', response.status)
        }
        if (!response.ok) {
          throw new CourierApiError('unavailable', `GraphQL query service returned HTTP ${response.status}`, response.status)
        }

        let payload: unknown
        try {
          payload = await response.json()
        } catch {
          throw new CourierApiError('invalid-response', 'Failed to parse GraphQL response JSON.')
        }

        controller.signal.throwIfAborted()

        if (!isRecord(payload)) {
          throw new CourierApiError('invalid-response', 'GraphQL response is not an object.')
        }

        if (Array.isArray(payload.errors) && payload.errors.length > 0) {
          const codes = payload.errors.map((e) =>
            isRecord(e) && isRecord(e.extensions) ? e.extensions.code : undefined,
          )
          const firstMessage =
            isRecord(payload.errors[0]) && typeof payload.errors[0].message === 'string'
              ? payload.errors[0].message
              : 'GraphQL query error'

          if (codes.includes('UNAUTHENTICATED')) {
            throw new CourierApiError('auth-required', firstMessage, 401)
          }
          if (codes.includes('FORBIDDEN')) {
            throw new CourierApiError('forbidden', firstMessage, 403)
          }
          if (codes.includes('NOT_FOUND')) {
            throw new CourierApiError('not-found', firstMessage, 404)
          }
          if (codes.includes('DEADLINE_EXCEEDED')) {
            throw new CourierApiError('timeout', firstMessage, 504)
          }
          throw new CourierApiError('unavailable', firstMessage)
        }

        const data = isRecord(payload.data) ? payload.data.courierDashboard : undefined
        if (!isCourierDashboardSummary(data)) {
          throw new CourierApiError('invalid-response', 'Courier dashboard summary schema mismatch.')
        }

        return data
      } catch (error) {
        if (signal?.aborted) throw error
        if (timedOut) throw new CourierApiError('timeout', 'GraphQL request timed out.')
        if (error instanceof CourierApiError) throw error
        throw new CourierApiError('unavailable', error instanceof Error ? error.message : 'GraphQL query failed.')
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
      }
    },
  }
}
