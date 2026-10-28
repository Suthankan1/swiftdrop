/**
 * Approved reporting calendar (LATER-11 Q13-Q16, N10-WF23 decision G): reporting days are
 * Asia/Colombo calendar days, a fixed UTC+05:30 offset with no daylight saving. Filters are
 * sent as YYYY-MM-DD calendar dates; the GraphQL service owns the conversion to UTC instants,
 * so the browser's local timezone never decides a reporting boundary.
 */
export const REPORTING_TIME_ZONE = 'Asia/Colombo'
export const REPORTING_TIME_ZONE_LABEL = 'Asia/Colombo (UTC+05:30)'
/** Provider ceiling is 90 days (LATER-11 Q16); the UI allows at most 90 inclusive calendar days. */
export const MAX_REPORT_DAYS = 90
/** Each breakdown day is a separate authoritative server aggregate, so the fan-out stays small. */
export const MAX_BREAKDOWN_DAYS = 7

const OFFSET_MS = (5 * 60 + 30) * 60_000
const DAY_MS = 86_400_000
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/

function epochDay(value: string): number | null {
  const match = DATE.exec(value)
  if (!match) return null
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3])
  const ms = Date.UTC(year, month - 1, day)
  const check = new Date(ms)
  return check.getUTCFullYear() === year && check.getUTCMonth() === month - 1 && check.getUTCDate() === day ? ms / DAY_MS : null
}
function fromEpochDay(day: number): string { return new Date(day * DAY_MS).toISOString().slice(0, 10) }

export function isReportingDate(value: string): boolean { return epochDay(value) !== null }

/** Asia/Colombo calendar day containing a UTC instant. */
export function reportingDayOf(instant: Date | string): string {
  const ms = typeof instant === 'string' ? Date.parse(instant) : instant.getTime()
  if (!Number.isFinite(ms)) throw new RangeError('Invalid instant')
  return fromEpochDay(Math.floor((ms + OFFSET_MS) / DAY_MS))
}
export function reportingToday(now: Date = new Date()): string { return reportingDayOf(now) }

/** UTC instants for one reporting day: start inclusive, end exclusive (the next day's start). */
export function reportingDayBounds(day: string): { start: string; end: string } {
  const value = epochDay(day)
  if (value === null) throw new RangeError('Invalid reporting date')
  return { start: new Date(value * DAY_MS - OFFSET_MS).toISOString(), end: new Date((value + 1) * DAY_MS - OFFSET_MS).toISOString() }
}

/** Inclusive calendar-day count; 0 when either date is invalid or the range is reversed. */
export function reportingDayCount(startDate: string, endDate: string): number {
  const start = epochDay(startDate), end = epochDay(endDate)
  return start === null || end === null || end < start ? 0 : end - start + 1
}
export function addReportingDays(day: string, delta: number): string {
  const value = epochDay(day)
  if (value === null) throw new RangeError('Invalid reporting date')
  return fromEpochDay(value + delta)
}
export function reportingDays(startDate: string, endDate: string): string[] {
  const start = epochDay(startDate), count = reportingDayCount(startDate, endDate)
  return start === null ? [] : Array.from({ length: count }, (_, index) => fromEpochDay(start + index))
}

export type RangeProblem = 'format' | 'order' | 'future' | 'too-long'
export const rangeProblemMessages: Record<RangeProblem, string> = {
  format: 'Enter valid start and end dates.',
  order: 'The start date must be on or before the end date.',
  future: 'Reports cannot include dates after today in Asia/Colombo.',
  'too-long': `Choose a reporting period of at most ${MAX_REPORT_DAYS} days.`,
}
export function validateReportRange(startDate: string, endDate: string, today: string): RangeProblem | null {
  const start = epochDay(startDate), end = epochDay(endDate), current = epochDay(today)
  if (start === null || end === null || current === null) return 'format'
  if (start > end) return 'order'
  if (end > current) return 'future'
  if (end - start + 1 > MAX_REPORT_DAYS) return 'too-long'
  return null
}

const dateTime = new Intl.DateTimeFormat('en-GB', { timeZone: REPORTING_TIME_ZONE, dateStyle: 'medium', timeStyle: 'short' })
const dateOnly = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
export function formatReportingDateTime(instant: string | number): string { return dateTime.format(new Date(instant)) }
/** Formats a YYYY-MM-DD reporting date without shifting it through the browser's timezone. */
export function formatReportingDate(day: string): string {
  const value = epochDay(day)
  return value === null ? day : dateOnly.format(new Date(value * DAY_MS))
}
