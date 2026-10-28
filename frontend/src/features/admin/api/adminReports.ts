import {
  AdminReportsError,
  type AdminDashboardData,
  type DeliveryStatsFilterInput,
  type DeliveryStatsSummary,
  type PendingMerchantItem,
  validateFilterInput,
} from './adminReportsContracts'

export const DAILY_DELIVERY_REPORT_QUERY = `
  query DailyDeliveryReport($filter: DeliveryStatsFilterInput) {
    deliveryStats(filter: $filter) {
      totalCount
      deliveredCount
      failedCount
      inTransitCount
      createdCount
      averageDeliveryHours
    }
  }
`.trim()

export const ADMIN_REPORT_SUMMARY_QUERY = `
  query AdminReportSummary {
    adminDashboard {
      pendingMerchantsCount
      totalParcelsCount
      activeParcelsCount
      terminalSuccessRate
      recentPendingMerchants {
        merchantId
        businessName
        registrationNumber
        submittedAt
      }
    }
  }
`.trim()

const GRAPHQL_INT_MAX = 2_147_483_647

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isSafeInteger(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= GRAPHQL_INT_MAX
  )
}

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export function validateDeliveryStats(raw: unknown): DeliveryStatsSummary {
  if (!isRecord(raw)) {
    throw new AdminReportsError('invalid-response', 'Expected deliveryStats to be an object.')
  }

  const {
    totalCount,
    deliveredCount,
    failedCount,
    inTransitCount,
    createdCount,
    averageDeliveryHours,
  } = raw

  if (
    !isSafeInteger(totalCount) ||
    !isSafeInteger(deliveredCount) ||
    !isSafeInteger(failedCount) ||
    !isSafeInteger(inTransitCount) ||
    !isSafeInteger(createdCount)
  ) {
    throw new AdminReportsError(
      'invalid-response',
      'Delivery counts must be non-negative safe integers fitting GraphQL Int.',
    )
  }

  let validatedAvgHours: number | null = null
  if (averageDeliveryHours !== null && averageDeliveryHours !== undefined) {
    if (
      typeof averageDeliveryHours !== 'number' ||
      !Number.isFinite(averageDeliveryHours) ||
      averageDeliveryHours < 0
    ) {
      throw new AdminReportsError(
        'invalid-response',
        'averageDeliveryHours must be null or a non-negative finite number.',
      )
    }
    validatedAvgHours = averageDeliveryHours
  }

  return {
    totalCount,
    deliveredCount,
    failedCount,
    inTransitCount,
    createdCount,
    averageDeliveryHours: validatedAvgHours,
  }
}

export function validateAdminDashboard(raw: unknown): AdminDashboardData {
  if (!isRecord(raw)) {
    throw new AdminReportsError('invalid-response', 'Expected adminDashboard to be an object.')
  }

  const {
    pendingMerchantsCount,
    totalParcelsCount,
    activeParcelsCount,
    terminalSuccessRate,
    recentPendingMerchants,
  } = raw

  if (
    !isSafeInteger(pendingMerchantsCount) ||
    !isSafeInteger(totalParcelsCount) ||
    !isSafeInteger(activeParcelsCount)
  ) {
    throw new AdminReportsError(
      'invalid-response',
      'Dashboard counts must be non-negative safe integers fitting GraphQL Int.',
    )
  }

  if (
    typeof terminalSuccessRate !== 'number' ||
    !Number.isFinite(terminalSuccessRate) ||
    terminalSuccessRate < 0.0 ||
    terminalSuccessRate > 1.0
  ) {
    throw new AdminReportsError(
      'invalid-response',
      'terminalSuccessRate must be a finite number between 0.0 and 1.0.',
    )
  }

  if (!Array.isArray(recentPendingMerchants)) {
    throw new AdminReportsError('invalid-response', 'recentPendingMerchants must be an array.')
  }

  if (recentPendingMerchants.length > 50) {
    throw new AdminReportsError('invalid-response', 'recentPendingMerchants exceeds bounded limit of 50 items.')
  }

  const seenIds = new Set<string>()
  const validatedMerchants: PendingMerchantItem[] = []

  for (const item of recentPendingMerchants) {
    if (!isRecord(item)) {
      throw new AdminReportsError('invalid-response', 'Merchant item must be an object.')
    }

    const { merchantId, businessName, registrationNumber, submittedAt } = item

    if (typeof merchantId !== 'string' || !UUID_REGEX.test(merchantId.trim())) {
      throw new AdminReportsError('invalid-response', `Invalid merchant UUID: ${String(merchantId)}`)
    }

    const trimmedId = merchantId.trim()
    if (seenIds.has(trimmedId)) {
      throw new AdminReportsError('invalid-response', `Duplicate merchant ID: ${trimmedId}`)
    }
    seenIds.add(trimmedId)

    if (typeof businessName !== 'string' || businessName.trim() === '') {
      throw new AdminReportsError('invalid-response', 'businessName must be a non-empty string.')
    }

    if (typeof registrationNumber !== 'string' || registrationNumber.trim() === '') {
      throw new AdminReportsError('invalid-response', 'registrationNumber must be a non-empty string.')
    }

    if (typeof submittedAt !== 'string' || isNaN(Date.parse(submittedAt))) {
      throw new AdminReportsError('invalid-response', 'submittedAt must be a valid date string.')
    }

    validatedMerchants.push({
      merchantId: trimmedId,
      businessName: businessName.trim(),
      registrationNumber: registrationNumber.trim(),
      submittedAt: submittedAt.trim(),
    })
  }

  return {
    pendingMerchantsCount,
    totalParcelsCount,
    activeParcelsCount,
    terminalSuccessRate,
    recentPendingMerchants: validatedMerchants,
  }
}

export interface CreateAdminReportsApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof fetch
  timeoutMs?: number
}

export interface AdminReportsApi {
  getDeliveryStats(
    filter?: DeliveryStatsFilterInput,
    signal?: AbortSignal,
  ): Promise<DeliveryStatsSummary>
  getAdminDashboard(signal?: AbortSignal): Promise<AdminDashboardData>
}

export function createAdminReportsApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: CreateAdminReportsApiOptions): AdminReportsApi {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function postGraphQL<T>(
    query: string,
    variables: Record<string, unknown> | undefined,
    signal?: AbortSignal,
  ): Promise<T> {
    const controller = new AbortController()
    const abortHandler = () => controller.abort()
    signal?.addEventListener('abort', abortHandler, { once: true })
    if (signal?.aborted) controller.abort()

    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeoutMs)

    let stopWaiting: (() => void) | undefined
    const interrupted = new Promise<never>((_resolve, reject) => {
      if (controller.signal.aborted) {
        reject(new DOMException('Request aborted', 'AbortError'))
        return
      }
      stopWaiting = () => reject(new DOMException('Request aborted', 'AbortError'))
      controller.signal.addEventListener('abort', stopWaiting, { once: true })
    })

    try {
      controller.signal.throwIfAborted()
      const token = await Promise.race([getAccessToken(), interrupted])
      if (!token?.trim()) {
        throw new AdminReportsError('auth-required')
      }

      const response = await request(`${normalizedGateway}/graphql`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token.trim()}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify({ query, variables }),
        signal: controller.signal,
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
      })

      controller.signal.throwIfAborted()

      if (response.status === 401) {
        throw new AdminReportsError('auth-required')
      }
      if (response.status === 403 || response.status === 404) {
        throw new AdminReportsError('forbidden')
      }
      if (response.status === 408 || response.status === 504) {
        throw new AdminReportsError('timeout')
      }
      if (!response.ok) {
        throw new AdminReportsError('unavailable')
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new AdminReportsError('invalid-response')
      }

      controller.signal.throwIfAborted()

      if (!isRecord(payload)) {
        throw new AdminReportsError('invalid-response')
      }

      if (Array.isArray(payload.errors) && payload.errors.length > 0) {
        const codes = payload.errors
          .map((e) => (isRecord(e) && isRecord(e.extensions) ? e.extensions.code : undefined))
          .filter(Boolean)

        if (codes.includes('UNAUTHENTICATED')) {
          throw new AdminReportsError('auth-required')
        }
        if (codes.includes('FORBIDDEN')) {
          throw new AdminReportsError('forbidden')
        }
        if (codes.includes('DEADLINE_EXCEEDED')) {
          throw new AdminReportsError('timeout')
        }
        if (codes.includes('UNAVAILABLE')) {
          throw new AdminReportsError('unavailable')
        }
        if (codes.includes('BAD_USER_INPUT') || codes.includes('VALIDATION_FAILED')) {
          throw new AdminReportsError('invalid-filter')
        }
        throw new AdminReportsError('unavailable')
      }

      if (!isRecord(payload.data)) {
        throw new AdminReportsError('invalid-response', 'GraphQL response missing data field.')
      }

      return payload.data as T
    } catch (error) {
      if (signal?.aborted) throw error
      if (timedOut) throw new AdminReportsError('timeout')
      if (error instanceof AdminReportsError) throw error
      throw new AdminReportsError('unavailable')
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abortHandler)
      if (stopWaiting) controller.signal.removeEventListener('abort', stopWaiting)
    }
  }

  return {
    async getDeliveryStats(filter, signal) {
      const validation = validateFilterInput(filter)
      if (!validation.valid) {
        throw new AdminReportsError('invalid-filter', validation.error)
      }

      // Sanitize filter variables to omit nullish/empty strings
      const cleanedFilter: Record<string, string> = {}
      if (filter?.startDate?.trim()) cleanedFilter.startDate = filter.startDate.trim()
      if (filter?.endDate?.trim()) cleanedFilter.endDate = filter.endDate.trim()
      if (filter?.zone?.trim()) cleanedFilter.zone = filter.zone.trim()
      if (filter?.status?.trim()) cleanedFilter.status = filter.status.trim()

      const data = await postGraphQL<{ deliveryStats?: unknown }>(
        DAILY_DELIVERY_REPORT_QUERY,
        Object.keys(cleanedFilter).length > 0 ? { filter: cleanedFilter } : undefined,
        signal,
      )

      return validateDeliveryStats(data.deliveryStats)
    },

    async getAdminDashboard(signal) {
      const data = await postGraphQL<{ adminDashboard?: unknown }>(
        ADMIN_REPORT_SUMMARY_QUERY,
        undefined,
        signal,
      )

      return validateAdminDashboard(data.adminDashboard)
    },
  }
}
