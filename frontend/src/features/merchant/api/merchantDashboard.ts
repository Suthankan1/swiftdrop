import type {
  MerchantDashboardData,
  MerchantOrderSummaryItem,
  ParcelStatus,
} from './merchantDashboardContracts'
import {
  MerchantDashboardError,
} from './merchantDashboardContracts'
export { MerchantDashboardError }
import { normalizeApprovalStatus } from './merchant'

export interface MerchantDashboardApiOptions {
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

const VALID_PARCEL_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

export function isParcelStatus(value: unknown): value is ParcelStatus {
  return typeof value === 'string' && VALID_PARCEL_STATUSES.includes(value as ParcelStatus)
}

export function isMerchantOrderSummaryItem(value: unknown): value is MerchantOrderSummaryItem {
  if (!isRecord(value)) return false
  return (
    typeof value.parcelId === 'string' &&
    value.parcelId.trim().length > 0 &&
    typeof value.trackingNumber === 'string' &&
    value.trackingNumber.trim().length > 0 &&
    isParcelStatus(value.currentStatus) &&
    typeof value.weightKg === 'number' &&
    !Number.isNaN(value.weightKg) &&
    value.weightKg >= 0 &&
    isNonNegativeInteger(value.feeMinorUnits) &&
    typeof value.currency === 'string' &&
    typeof value.createdAt === 'string' &&
    typeof value.lastStatusUpdate === 'string'
  )
}

export function isMerchantDashboardData(value: unknown): value is MerchantDashboardData {
  if (!isRecord(value)) return false
  return (
    typeof value.merchantId === 'string' &&
    value.merchantId.trim().length > 0 &&
    typeof value.businessName === 'string' &&
    typeof value.status === 'string' &&
    isNonNegativeInteger(value.totalOrdersCount) &&
    isNonNegativeInteger(value.activeOrdersCount) &&
    isNonNegativeInteger(value.deliveredOrdersCount) &&
    isNonNegativeInteger(value.failedOrdersCount) &&
    Array.isArray(value.recentOrders) &&
    value.recentOrders.every(isMerchantOrderSummaryItem)
  )
}

export const MERCHANT_DASHBOARD_QUERY = `query MerchantDashboard {
  merchantDashboard {
    merchantId
    businessName
    status
    totalOrdersCount
    activeOrdersCount
    deliveredOrdersCount
    failedOrdersCount
    recentOrders {
      parcelId
      trackingNumber
      currentStatus
      weightKg
      feeMinorUnits
      currency
      createdAt
      lastStatusUpdate
    }
  }
}`

export function createMerchantDashboardApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: MerchantDashboardApiOptions) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new MerchantDashboardError('auth-required', 'Authentication session is unavailable.')
    }
    if (!token?.trim()) {
      throw new MerchantDashboardError('auth-required', 'Authentication token is missing. Please sign in.')
    }
    return token
  }

  return {
    /**
     * Retrieves authoritative merchant operational dashboard from GraphQL Query service via API Gateway.
     * Requires ROLE_MERCHANT and APPROVED status in Accounts service.
     */
    async getDashboard(signal?: AbortSignal): Promise<MerchantDashboardData> {
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
            query: MERCHANT_DASHBOARD_QUERY,
          }),
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
        })

        controller.signal.throwIfAborted()

        if (response.status === 401) {
          throw new MerchantDashboardError('auth-required', 'Session expired. Please sign in again.', 401)
        }
        if (response.status === 403) {
          throw new MerchantDashboardError('forbidden', 'Access restricted to approved merchant accounts.', 403)
        }
        if (response.status === 404) {
          throw new MerchantDashboardError('not-found', 'Merchant dashboard not found.', 404)
        }
        if (response.status === 408 || response.status === 504) {
          throw new MerchantDashboardError('timeout', 'GraphQL query timed out.', response.status)
        }
        if (!response.ok) {
          throw new MerchantDashboardError('unavailable', `GraphQL query service returned HTTP ${response.status}`, response.status)
        }

        let payload: unknown
        try {
          payload = await response.json()
        } catch {
          throw new MerchantDashboardError('invalid-response', 'Failed to parse GraphQL response JSON.')
        }

        controller.signal.throwIfAborted()

        if (!isRecord(payload)) {
          throw new MerchantDashboardError('invalid-response', 'GraphQL response is not an object.')
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
            throw new MerchantDashboardError('auth-required', firstMessage, 401)
          }
          if (codes.includes('FORBIDDEN') || codes.includes('PERMISSION_DENIED')) {
            throw new MerchantDashboardError('forbidden', firstMessage, 403)
          }
          if (codes.includes('NOT_FOUND')) {
            throw new MerchantDashboardError('not-found', firstMessage, 404)
          }
          if (codes.includes('DEADLINE_EXCEEDED')) {
            throw new MerchantDashboardError('timeout', firstMessage, 504)
          }
          throw new MerchantDashboardError('unavailable', firstMessage)
        }

        const rawData = isRecord(payload.data) ? payload.data.merchantDashboard : undefined
        if (!rawData || !isRecord(rawData)) {
          throw new MerchantDashboardError('invalid-response', 'Missing merchantDashboard in GraphQL response.')
        }

        // Normalize status using the canonical normalizeApprovalStatus helper
        const normalizedData = {
          ...rawData,
          status: normalizeApprovalStatus(typeof rawData.status === 'string' ? rawData.status : undefined),
        }

        if (!isMerchantDashboardData(normalizedData)) {
          throw new MerchantDashboardError('invalid-response', 'Merchant dashboard schema mismatch.')
        }

        return normalizedData
      } catch (error) {
        if (signal?.aborted) throw error
        if (timedOut) throw new MerchantDashboardError('timeout', 'GraphQL request timed out.')
        if (error instanceof MerchantDashboardError) throw error
        throw new MerchantDashboardError('unavailable', error instanceof Error ? error.message : 'GraphQL query failed.')
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
      }
    },
  }
}
