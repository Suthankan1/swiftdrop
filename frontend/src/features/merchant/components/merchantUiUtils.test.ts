import { describe, expect, it } from 'vitest'
import { formatDateTime, merchantOrdersCsv } from './merchantUiUtils'
import { parseCsv } from '../api/merchantBooking'

describe('merchant export contents', () => {
  it('round-trips Unicode, quotes, formulas, fees and authoritative timestamps as CSV', () => {
    const csv = merchantOrdersCsv([{
      parcelId: 'parcel-1', trackingNumber: '=SUM(1,2)', currentStatus: 'DELIVERED', weightKg: 2.5,
      feeMinorUnits: 140250, currency: 'LKR', createdAt: '2026-11-14T06:55:00Z', lastStatusUpdate: '2026-11-14T10:09:00Z',
    }])
    expect(csv.startsWith('\uFEFF')).toBe(true)
    const [header, row] = parseCsv(csv.slice(1))
    expect(header).toContain('Last Update (ISO UTC)')
    expect(row).toEqual(["'=SUM(1,2)", 'parcel-1', 'DELIVERED', '2.5', '1402.50', 'LKR', '2026-11-14T06:55:00Z', '2026-11-14T10:09:00Z'])
    expect(parseCsv(merchantOrdersCsv([]).slice(1))).toHaveLength(1)
  })
  it('uses Colombo time and makes missing timestamps explicit', () => {
    expect(formatDateTime('2026-11-14T10:09:00Z')).toMatch(/15:39/)
    expect(formatDateTime('')).toBe('Update time unavailable')
  })
})
