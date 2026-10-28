/**
 * Contracts and validation rules for Admin Daily Outcomes & Reporting.
 * Strictly adheres to contracts/graphql/schema.graphql and docs/contracts/LATER_11_GRAPHQL_DELIVERY_STATS.md.
 */

export const COLOMBO_TIMEZONE = 'Asia/Colombo'
export const COLOMBO_OFFSET = '+05:30'
export const MAX_REPORTING_WINDOW_DAYS = 90
export const ZONE_REGEX = /^[A-Z0-9_-]{1,32}$/

export const parcelStatuses = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
] as const

export type ParcelStatus = typeof parcelStatuses[number]

export interface DeliveryStatsFilterInput {
  startDate?: string | null
  endDate?: string | null
  zone?: string | null
  status?: ParcelStatus | null
}

export interface DeliveryStatsSummary {
  totalCount: number
  deliveredCount: number
  failedCount: number
  inTransitCount: number
  createdCount: number
  averageDeliveryHours: number | null
}

export interface PendingMerchantItem {
  merchantId: string
  businessName: string
  registrationNumber: string
  submittedAt: string
}

export interface AdminDashboardData {
  pendingMerchantsCount: number
  totalParcelsCount: number
  activeParcelsCount: number
  terminalSuccessRate: number
  recentPendingMerchants: readonly PendingMerchantItem[]
}

export type AdminReportsErrorKind =
  | 'auth-required'
  | 'forbidden'
  | 'timeout'
  | 'unavailable'
  | 'invalid-response'
  | 'invalid-filter'

const ERROR_MESSAGES: Record<AdminReportsErrorKind, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access to operational reports is restricted to authorized administrators.',
  timeout: 'The reporting query timed out. Please retry.',
  unavailable: 'Reporting information is temporarily unavailable. Please retry.',
  'invalid-response': 'The reporting service returned an unsupported response.',
  'invalid-filter': 'Invalid reporting filter. Check the dates, zone code, and status.',
}

export class AdminReportsError extends Error {
  readonly kind: AdminReportsErrorKind

  constructor(kind: AdminReportsErrorKind, customMessage?: string) {
    super(customMessage || ERROR_MESSAGES[kind])
    this.name = 'AdminReportsError'
    this.kind = kind
  }
}

export function isParcelStatus(value: unknown): value is ParcelStatus {
  return typeof value === 'string' && (parcelStatuses as readonly string[]).includes(value)
}

/**
 * Validates a calendar date string in YYYY-MM-DD format with real Gregorian calendar validity.
 */
export function isValidCalendarDate(dateStr: string): boolean {
  if (typeof dateStr !== 'string') return false
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr.trim())
  if (!match) return false

  const year = parseInt(match[1]!, 10)
  const month = parseInt(match[2]!, 10)
  const day = parseInt(match[3]!, 10)

  if (year < 1970 || year > 2100) return false
  if (month < 1 || month > 12) return false

  // Days in month validation (accounting for leap years)
  const daysInMonth = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day >= 1 && day <= (daysInMonth[month - 1] ?? 0)
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}

/**
 * Calculates calendar day difference between two YYYY-MM-DD strings.
 * Returns negative if d1 > d2.
 */
export function calendarDayDifference(startDate: string, endDate: string): number {
  const [y1, m1, d1] = startDate.split('-').map(Number) as [number, number, number]
  const [y2, m2, d2] = endDate.split('-').map(Number) as [number, number, number]

  const utc1 = Date.UTC(y1, m1 - 1, d1)
  const utc2 = Date.UTC(y2, m2 - 1, d2)
  const msPerDay = 1000 * 60 * 60 * 24

  return Math.round((utc2 - utc1) / msPerDay)
}

/**
 * Validates the reporting filter fields according to frozen contract rules.
 */
export function validateFilterInput(filter?: DeliveryStatsFilterInput): {
  valid: boolean
  error?: string
} {
  if (!filter) return { valid: true }

  const { startDate, endDate, zone, status } = filter

  if (startDate != null && startDate.trim() !== '') {
    if (!isValidCalendarDate(startDate)) {
      return { valid: false, error: 'Start date must be a valid calendar date in YYYY-MM-DD format.' }
    }
  }

  if (endDate != null && endDate.trim() !== '') {
    if (!isValidCalendarDate(endDate)) {
      return { valid: false, error: 'End date must be a valid calendar date in YYYY-MM-DD format.' }
    }
  }

  if (startDate != null && startDate.trim() !== '' && endDate != null && endDate.trim() !== '') {
    const diff = calendarDayDifference(startDate.trim(), endDate.trim())
    if (diff < 0) {
      return { valid: false, error: 'Start date must be before or equal to end date.' }
    }
    if (diff > MAX_REPORTING_WINDOW_DAYS) {
      return {
        valid: false,
        error: `Reporting window exceeds maximum allowed range of ${MAX_REPORTING_WINDOW_DAYS} calendar days.`,
      }
    }
  }

  if (zone != null && zone.trim() !== '') {
    if (!ZONE_REGEX.test(zone.trim())) {
      return {
        valid: false,
        error: 'Operational zone code must match pattern ^[A-Z0-9_-]{1,32}$.',
      }
    }
  }

  if (status != null && (status as string).trim() !== '') {
    if (!isParcelStatus(status.trim())) {
      return {
        valid: false,
        error: `Parcel status must be one of: ${parcelStatuses.join(', ')}.`,
      }
    }
  }

  return { valid: true }
}

/**
 * Returns today's calendar date in Asia/Colombo (UTC+05:30) as YYYY-MM-DD.
 */
export function getTodayColomboDate(referenceDate: Date = new Date()): string {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: COLOMBO_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return formatter.format(referenceDate)
}

/**
 * Formats a UTC ISO-8601 timestamp string into Asia/Colombo calendar date (YYYY-MM-DD).
 */
export function toColomboDateString(utcTimestamp: string | Date): string {
  const dateObj = typeof utcTimestamp === 'string' ? new Date(utcTimestamp) : utcTimestamp
  if (isNaN(dateObj.getTime())) {
    throw new Error(`Invalid timestamp: ${String(utcTimestamp)}`)
  }
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: COLOMBO_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return formatter.format(dateObj)
}

/**
 * Determines whether a given UTC ISO-8601 timestamp occurs on the specified Colombo calendar day (YYYY-MM-DD).
 */
export function isTimestampInColomboDay(utcTimestamp: string | Date, colomboDay: string): boolean {
  if (!isValidCalendarDate(colomboDay)) return false
  try {
    return toColomboDateString(utcTimestamp) === colomboDay.trim()
  } catch {
    return false
  }
}

/**
 * Calculates the terminal success rate:
 * deliveredCount / (deliveredCount + failedCount)
 * Returns null if no terminal outcomes exist (deliveredCount + failedCount === 0).
 */
export function computeTerminalSuccessRate(
  deliveredCount: number,
  failedCount: number,
): number | null {
  const denominator = deliveredCount + failedCount
  if (denominator === 0) return null
  const rate = (deliveredCount / denominator) * 100
  return Math.round(rate * 10) / 10
}

/**
 * Formats terminal success rate for display: e.g. "80.0%" or "N/A" if no completed outcomes.
 */
export function formatTerminalSuccessRate(
  deliveredCount: number,
  failedCount: number,
): string {
  const rate = computeTerminalSuccessRate(deliveredCount, failedCount)
  if (rate === null) return 'N/A'
  return `${rate.toFixed(1)}%`
}
