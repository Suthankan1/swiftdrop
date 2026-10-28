import {
  type CustomerDashboardData,
  type CustomerParcelSummaryItem,
  type ParcelStatus,
  CustomerDashboardError,
} from './customerDashboardContracts'

export interface CustomerDashboardApiOptions {
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

const VALID_STATUSES: Set<ParcelStatus> = new Set([
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
])

function isValidIsoDate(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function isValidParcelSummary(value: unknown): value is CustomerParcelSummaryItem {
  if (!isRecord(value)) return false
  return (
    typeof value.parcelId === 'string' &&
    value.parcelId.trim().length > 0 &&
    typeof value.trackingNumber === 'string' &&
    value.trackingNumber.trim().length > 0 &&
    typeof value.currentStatus === 'string' &&
    VALID_STATUSES.has(value.currentStatus as ParcelStatus) &&
    isNonNegativeInteger(value.amountMinorUnits) &&
    typeof value.currency === 'string' &&
    value.currency.trim().length > 0 &&
    typeof value.recipientCity === 'string' &&
    value.recipientCity.trim().length > 0 &&
    isValidIsoDate(value.createdAt) &&
    isValidIsoDate(value.updatedAt)
  )
}

export function isCustomerDashboardData(value: unknown): value is CustomerDashboardData {
  if (!isRecord(value)) return false
  return (
    typeof value.userId === 'string' &&
    value.userId.trim().length > 0 &&
    isNonNegativeInteger(value.activeDeliveriesCount) &&
    isNonNegativeInteger(value.completedDeliveriesCount) &&
    Array.isArray(value.recentParcels) &&
    value.recentParcels.length <= 50 &&
    value.recentParcels.every(isValidParcelSummary)
  )
}

export const CUSTOMER_DASHBOARD_QUERY = `query CustomerDashboard {
  customerDashboard {
    userId
    activeDeliveriesCount
    completedDeliveriesCount
    recentParcels {
      parcelId
      trackingNumber
      currentStatus
      amountMinorUnits
      currency
      recipientCity
      createdAt
      updatedAt
    }
  }
}`

export function createCustomerDashboardApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: CustomerDashboardApiOptions) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveAuthToken(interrupted: Promise<never>): Promise<string> {
    let token: string | null
    try {
      token = await Promise.race([getAccessToken(), interrupted])
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        throw err
      }
      throw new CustomerDashboardError('auth-required', 'Authentication session is unavailable.')
    }
    if (!token?.trim()) {
      throw new CustomerDashboardError('auth-required', 'Authentication token is missing. Please sign in.')
    }
    return token
  }

  return {
    async getDashboard(signal?: AbortSignal): Promise<CustomerDashboardData> {
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
        const token = await resolveAuthToken(interrupted)

        const response = await request(`${normalizedGateway}/graphql`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify({
            query: CUSTOMER_DASHBOARD_QUERY,
          }),
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
        })

        controller.signal.throwIfAborted()

        if (response.status === 401) {
          throw new CustomerDashboardError('auth-required')
        }
        if ([403, 404].includes(response.status)) {
          throw new CustomerDashboardError('forbidden')
        }
        if ([408, 504].includes(response.status)) {
          throw new CustomerDashboardError('timeout')
        }
        if (!response.ok) {
          throw new CustomerDashboardError('unavailable')
        }

        let payload: unknown
        try {
          payload = await response.json()
        } catch {
          throw new CustomerDashboardError('invalid-response')
        }

        controller.signal.throwIfAborted()

        if (!isRecord(payload)) {
          throw new CustomerDashboardError('invalid-response')
        }

        if (Array.isArray(payload.errors) && payload.errors.length > 0) {
          const codes = payload.errors.map((e) =>
            isRecord(e) && isRecord(e.extensions) ? e.extensions.code : undefined,
          )
          if (codes.includes('UNAUTHENTICATED')) {
            throw new CustomerDashboardError('auth-required')
          }
          if (codes.includes('FORBIDDEN') || codes.includes('NOT_FOUND')) {
            throw new CustomerDashboardError('forbidden')
          }
          if (codes.includes('DEADLINE_EXCEEDED')) {
            throw new CustomerDashboardError('timeout')
          }
          throw new CustomerDashboardError('unavailable')
        }

        const data = isRecord(payload.data) ? payload.data.customerDashboard : undefined
        if (!isCustomerDashboardData(data)) {
          throw new CustomerDashboardError('invalid-response')
        }

        return data
      } catch (error) {
        if (signal?.aborted) throw error
        if (timedOut) throw new CustomerDashboardError('timeout')
        if (error instanceof CustomerDashboardError) throw error
        throw new CustomerDashboardError('unavailable')
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
      }
    },
  }
}
