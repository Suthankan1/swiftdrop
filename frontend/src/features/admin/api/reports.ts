/**
 * Read-only reporting adapter (Issue #147) for the frozen GraphQL contracts:
 * deliveryStats (LATER-11 / #30, DISPATCHER or ADMIN) and adminDashboard (N10-WF23 / #139, ADMIN).
 * Requests go to the single GraphQL Query API through the Gateway. No mutations are sent.
 */
export interface ReportFilter { startDate: string; endDate: string; zone?: string }
export interface DeliveryStatsSummary {
  totalCount: number; deliveredCount: number; failedCount: number; inTransitCount: number; createdCount: number
  averageDeliveryHours: number | null
}
export interface PendingMerchant { merchantId: string; businessName: string; registrationNumber: string; submittedAt: string }
export interface AdminDashboardSummary {
  pendingMerchantsCount: number; totalParcelsCount: number; activeParcelsCount: number
  terminalSuccessRate: number; recentPendingMerchants: PendingMerchant[]
}
export interface ReportsApi {
  deliveryStats(filter: ReportFilter, signal: AbortSignal): Promise<DeliveryStatsSummary>
  adminDashboard(signal: AbortSignal): Promise<AdminDashboardSummary>
}

export type ReportFailure = 'auth-required' | 'forbidden' | 'invalid-filter' | 'timeout' | 'unavailable' | 'invalid-response'
const messages: Record<ReportFailure, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access to this report is denied for your role or operational scope.',
  'invalid-filter': 'The reporting service rejected the selected filters. Check the dates and zone.',
  timeout: 'The reporting service did not respond in time. Retry the report.',
  unavailable: 'The reporting provider is unavailable. No figures are shown until it responds.',
  'invalid-response': 'The reporting service returned an unsupported response. No figures are shown.',
}
export class ReportError extends Error {
  readonly kind: ReportFailure
  constructor(kind: ReportFailure) { super(messages[kind]); this.name = 'ReportError'; this.kind = kind }
}
export function reportFailure(error: unknown): ReportError {
  return error instanceof ReportError ? error : new ReportError('unavailable')
}

/** Pricing & Zones operational zone code format (LATER-11 Q9). */
export const ZONE_PATTERN = /^[A-Z0-9_-]{1,32}$/
export const REPORT_TIMEOUT_MS = 15_000
const INT32_MAX = 2_147_483_647
const PREVIEW_MAX = 50

export const DELIVERY_STATS_QUERY = `query DeliveryStats($filter: DeliveryStatsFilterInput) {
  deliveryStats(filter: $filter) {
    totalCount
    deliveredCount
    failedCount
    inTransitCount
    createdCount
    averageDeliveryHours
  }
}`
export const ADMIN_DASHBOARD_QUERY = `query AdminDashboard {
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
}`

function invalid(): never { throw new ReportError('invalid-response') }
function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid()
}
function count(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= INT32_MAX ? value : invalid()
}
function hasControls(value: string): boolean { return Array.from(value).some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) }
function text(value: unknown, max = 255): string {
  return typeof value === 'string' && value.trim() && value.length <= max && !hasControls(value) ? value.trim() : invalid()
}
function uuid(value: unknown): string {
  const result = text(value, 36)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(result) ? result : invalid()
}
function instant(value: unknown): string {
  const result = text(value, 40)
  return /^\d{4}-\d{2}-\d{2}T/.test(result) && Number.isFinite(Date.parse(result)) ? result : invalid()
}

/** Explicit projection: only contract fields survive; categories are subsets of the period population. */
export function parseDeliveryStats(value: unknown): DeliveryStatsSummary {
  const body = record(value)
  const average = body.averageDeliveryHours
  const result: DeliveryStatsSummary = {
    totalCount: count(body.totalCount), deliveredCount: count(body.deliveredCount), failedCount: count(body.failedCount),
    inTransitCount: count(body.inTransitCount), createdCount: count(body.createdCount),
    averageDeliveryHours: average === null || average === undefined ? null
      : typeof average === 'number' && Number.isFinite(average) && average >= 0 ? average : invalid(),
  }
  // Status groups partition the period's parcels (LATER-11 Q1/Q5/Q6), so they cannot exceed the total.
  if (result.deliveredCount + result.failedCount + result.inTransitCount + result.createdCount > result.totalCount) invalid()
  if (result.deliveredCount === 0 && result.averageDeliveryHours !== null) invalid()
  return result
}
export function parseAdminDashboard(value: unknown): AdminDashboardSummary {
  const body = record(value)
  const rate = body.terminalSuccessRate
  const items = Array.isArray(body.recentPendingMerchants) ? body.recentPendingMerchants : invalid()
  if (items.length > PREVIEW_MAX) invalid()
  const seen = new Set<string>()
  const recentPendingMerchants = items.map((item): PendingMerchant => {
    const row = record(item)
    const merchantId = uuid(row.merchantId)
    if (seen.has(merchantId.toLowerCase())) invalid()
    seen.add(merchantId.toLowerCase())
    return { merchantId, businessName: text(row.businessName), registrationNumber: text(row.registrationNumber, 100), submittedAt: instant(row.submittedAt) }
  })
  const result: AdminDashboardSummary = {
    pendingMerchantsCount: count(body.pendingMerchantsCount), totalParcelsCount: count(body.totalParcelsCount),
    activeParcelsCount: count(body.activeParcelsCount),
    terminalSuccessRate: typeof rate === 'number' && Number.isFinite(rate) && rate >= 0 && rate <= 1 ? rate : invalid(),
    recentPendingMerchants,
  }
  if (result.activeParcelsCount > result.totalParcelsCount || recentPendingMerchants.length > result.pendingMerchantsCount) invalid()
  return result
}

/** Approved denominator (N10-WF23 decision D): terminal outcomes = delivered + failed. */
export function outcomeRates(stats: Pick<DeliveryStatsSummary, 'deliveredCount' | 'failedCount'>) {
  const terminal = stats.deliveredCount + stats.failedCount
  return {
    terminal,
    successRate: terminal === 0 ? null : stats.deliveredCount / terminal,
    failureRate: terminal === 0 ? null : stats.failedCount / terminal,
  }
}
/** Contract display format for 0..1 ratios: one decimal place. Missing ratios are never shown as 0. */
export function formatRate(rate: number | null): string {
  return rate === null || !Number.isFinite(rate) ? 'Not available' : `${(rate * 100).toFixed(1)}%`
}
export function formatCount(value: number): string { return value.toLocaleString('en-US') }

const errorKinds: Record<string, ReportFailure> = {
  UNAUTHENTICATED: 'auth-required', FORBIDDEN: 'forbidden', PERMISSION_DENIED: 'forbidden',
  INVALID_ARGUMENT: 'invalid-filter', BAD_REQUEST: 'invalid-filter', BAD_USER_INPUT: 'invalid-filter',
  DEADLINE_EXCEEDED: 'timeout',
}
function graphqlFailure(errors: unknown[]): ReportError {
  const codes = errors.map(error => {
    const extensions = error && typeof error === 'object' ? (error as Record<string, unknown>).extensions : undefined
    const code = extensions && typeof extensions === 'object' ? (extensions as Record<string, unknown>).code : undefined
    return typeof code === 'string' ? code : ''
  })
  // Most restrictive outcome wins; provider messages are never rendered.
  for (const kind of ['auth-required', 'forbidden', 'invalid-filter', 'timeout'] as const)
    if (codes.some(code => errorKinds[code] === kind)) return new ReportError(kind)
  return new ReportError('unavailable')
}
const httpKinds: Record<number, ReportFailure> = { 400: 'invalid-filter', 401: 'auth-required', 403: 'forbidden', 408: 'timeout', 504: 'timeout' }

export function createReportsApi({ getAccessToken, gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080', fetch: fetchRequest = (...args) => globalThis.fetch(...args), timeoutMs = REPORT_TIMEOUT_MS }: {
  getAccessToken: () => Promise<string | null>; gatewayUrl?: string; fetch?: typeof globalThis.fetch; timeoutMs?: number
}): ReportsApi {
  const endpoint = `${gatewayUrl.replace(/\/$/, '')}/graphql`
  async function query(document: string, field: string, variables: Record<string, unknown> | undefined, signal: AbortSignal): Promise<unknown> {
    signal.throwIfAborted()
    let token: string | null
    try { token = await getAccessToken() } catch { throw new ReportError('auth-required') }
    signal.throwIfAborted()
    if (!token?.trim()) throw new ReportError('auth-required')
    const deadline = AbortSignal.timeout(timeoutMs)
    const bounded = AbortSignal.any([signal, deadline])
    let response: Response
    try {
      response = await fetchRequest(endpoint, {
        method: 'POST', signal: bounded, credentials: 'omit', cache: 'no-store', redirect: 'error',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify(variables ? { query: document, variables } : { query: document }),
      })
    } catch (error) {
      if (signal.aborted) throw error
      throw new ReportError(deadline.aborted ? 'timeout' : 'unavailable')
    }
    signal.throwIfAborted()
    if (!response.ok) throw new ReportError(httpKinds[response.status] ?? 'unavailable')
    let payload: Record<string, unknown>
    try { payload = record(await response.json()) } catch (error) {
      if (signal.aborted) throw error
      throw new ReportError(deadline.aborted ? 'timeout' : 'invalid-response')
    }
    signal.throwIfAborted()
    if (Array.isArray(payload.errors) && payload.errors.length) throw graphqlFailure(payload.errors)
    const data = payload.data === null || payload.data === undefined ? invalid() : record(payload.data)
    return data[field] ?? invalid()
  }
  return {
    async deliveryStats(filter, signal) {
      const variables = { filter: { startDate: filter.startDate, endDate: filter.endDate, ...(filter.zone ? { zone: filter.zone } : {}) } }
      return parseDeliveryStats(await query(DELIVERY_STATS_QUERY, 'deliveryStats', variables, signal))
    },
    async adminDashboard(signal) {
      return parseAdminDashboard(await query(ADMIN_DASHBOARD_QUERY, 'adminDashboard', undefined, signal))
    },
  }
}
