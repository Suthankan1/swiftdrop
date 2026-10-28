import { afterEach, describe, expect, it } from 'vitest'
import { canonicalParcels } from '../../../test/reportsFixture'
import { addReportingDays, formatReportingDate, isReportingDate, MAX_REPORT_DAYS, reportingDayBounds, reportingDayCount,
  reportingDayOf, reportingDays, reportingToday, validateReportRange } from './reportingPeriod'

const originalTz = process.env.TZ
afterEach(() => { process.env.TZ = originalTz })

describe('Asia/Colombo reporting day boundaries', () => {
  it('assigns instants either side of Colombo midnight (18:30Z) to the correct reporting day', () => {
    expect(reportingDayOf('2026-10-27T18:29:59.999Z')).toBe('2026-10-27')
    expect(reportingDayOf('2026-10-27T18:30:00.000Z')).toBe('2026-10-28')
    expect(reportingDayOf('2026-10-28T00:00:00.000Z')).toBe('2026-10-28')
    expect(reportingDayOf('2026-12-31T18:30:00.000Z')).toBe('2027-01-01')
    expect(() => reportingDayOf('not-a-date')).toThrow(RangeError)
  })

  it('does not depend on the browser timezone', () => {
    for (const tz of ['UTC', 'America/Los_Angeles', 'Pacific/Kiritimati']) {
      process.env.TZ = tz
      expect(reportingToday(new Date('2026-10-27T19:00:00Z'))).toBe('2026-10-28')
      expect(formatReportingDate('2026-10-28')).toBe('Wed, 28 Oct 2026')
    }
  })

  it('produces abutting start-inclusive, end-exclusive UTC bounds with no overlap', () => {
    expect(reportingDayBounds('2026-10-28')).toEqual({ start: '2026-10-27T18:30:00.000Z', end: '2026-10-28T18:30:00.000Z' })
    const days = reportingDays('2026-10-26', '2026-10-28')
    for (let index = 1; index < days.length; index++)
      expect(reportingDayBounds(days[index]!).start).toBe(reportingDayBounds(days[index - 1]!).end)
  })

  it('places every canonical parcel in exactly one reporting day, so no parcel is double counted at midnight', () => {
    const days = reportingDays('2026-10-26', '2026-10-28')
    const perDay = days.map(day => {
      const { start, end } = reportingDayBounds(day)
      return canonicalParcels.filter(parcel => parcel.activityAt >= start && parcel.activityAt < end).map(parcel => parcel.id)
    })
    expect(perDay).toEqual([['p-01'], ['p-02', 'p-03', 'p-04', 'p-05'], ['p-06', 'p-07', 'p-08']])
    expect(perDay.flat()).toHaveLength(canonicalParcels.length)
    expect(new Set(perDay.flat()).size).toBe(canonicalParcels.length)
    // Each parcel's own reporting day agrees with the interval it fell into.
    perDay.forEach((ids, index) => ids.forEach(id => expect(reportingDayOf(canonicalParcels.find(p => p.id === id)!.activityAt)).toBe(days[index])))
  })

  it('counts inclusive days and shifts dates across month and year ends', () => {
    expect(reportingDayCount('2026-10-28', '2026-10-28')).toBe(1)
    expect(reportingDayCount('2026-02-27', '2026-03-01')).toBe(3)
    expect(reportingDayCount('2026-10-28', '2026-10-27')).toBe(0)
    expect(reportingDays('2026-12-30', '2027-01-01')).toEqual(['2026-12-30', '2026-12-31', '2027-01-01'])
    expect(addReportingDays('2026-10-28', -29)).toBe('2026-09-29')
    expect(addReportingDays('2026-12-31', 1)).toBe('2027-01-01')
  })
})

describe('report range validation', () => {
  const today = '2026-10-28'
  it('rejects malformed and impossible calendar dates', () => {
    expect(isReportingDate('2026-02-30')).toBe(false)
    expect(validateReportRange('2026-02-30', today, today)).toBe('format')
    expect(validateReportRange('', today, today)).toBe('format')
    expect(validateReportRange('28/10/2026', today, today)).toBe('format')
  })
  it('rejects reversed, future and excessively long ranges', () => {
    expect(validateReportRange('2026-10-28', '2026-10-27', today)).toBe('order')
    expect(validateReportRange('2026-10-28', '2026-10-29', today)).toBe('future')
    expect(validateReportRange(addReportingDays(today, -MAX_REPORT_DAYS), today, today)).toBe('too-long')
  })
  it('accepts a single day and the maximum 90-day period ending today', () => {
    expect(validateReportRange(today, today, today)).toBeNull()
    expect(validateReportRange(addReportingDays(today, 1 - MAX_REPORT_DAYS), today, today)).toBeNull()
  })
})
