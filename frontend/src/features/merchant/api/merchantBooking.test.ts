import { describe, expect, it, vi } from 'vitest'
import { csvField } from '../components/merchantUiUtils'
import {
  bookMerchantOrder,
  MERCHANT_ORDER_CSV_SAMPLE,
  newBookingAttempt,
  parseCsv,
  parseMerchantOrdersCsv,
} from './merchantBooking'

describe('merchant order CSV', () => {
  it('parses quoted fields, escaped quotes and embedded commas and line breaks', () => {
    expect(parseCsv('a,"b, c","say ""hi"""\r\n"multi\nline",x,\n')).toEqual([
      ['a', 'b, c', 'say "hi"'],
      ['multi\nline', 'x', ''],
    ])
  })

  it('accepts the downloadable template and validates every row', () => {
    const { rows, error } = parseMerchantOrdersCsv(MERCHANT_ORDER_CSV_SAMPLE)
    expect(error).toBeNull()
    expect(rows).toHaveLength(2)
    expect(rows.every(row => row.error === null)).toBe(true)
    expect(rows[0]!.order).toMatchObject({ pickupAddress: '12 Vauxhall St, Colombo 02', merchantReference: 'ORD-1001', lengthCm: 20 })
  })

  it('reports missing columns and invalid rows with their file line number', () => {
    expect(parseMerchantOrdersCsv('pickupZone,dropoffZone\nZONE_1,ZONE_2').error).toMatch(/Missing columns/)
    const header = MERCHANT_ORDER_CSV_SAMPLE.split('\r\n')[0]
    const { rows } = parseMerchantOrdersCsv(`${header}\nZONE_1,ZONE_2,99,20,15,10,A,B,Name,+94771234567,REF,`)
    expect(rows[0]).toMatchObject({ rowNumber: 2, error: expect.stringMatching(/Weight/) })
  })
})

describe('csvField', () => {
  it('quotes values, doubles quotes and neutralises spreadsheet formulas', () => {
    expect(csvField('Kandy, "Main"')).toBe('"Kandy, ""Main"""')
    expect(csvField('=HYPERLINK("x")')).toBe('"\'=HYPERLINK(""x"")"')
    expect(csvField(Number.NaN)).toBe('""')
    expect(csvField(12.5)).toBe('"12.5"')
  })
})

describe('bookMerchantOrder', () => {
  it('reuses the first quote and idempotency key when an order is retried', async () => {
    const calculateQuote = vi.fn().mockResolvedValue({ quoteId: 'quote-1' })
    const bookParcel = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue({ parcelId: 'p-1', trackingNumber: 'SWD-1' })
    const apis = { pricing: { calculateQuote }, delivery: { bookParcel } } as unknown as Parameters<typeof bookMerchantOrder>[0]
    const order = parseMerchantOrdersCsv(MERCHANT_ORDER_CSV_SAMPLE).rows[0]!.order
    const attempt = newBookingAttempt()

    await expect(bookMerchantOrder(apis, order, attempt)).rejects.toThrow('network')
    await expect(bookMerchantOrder(apis, order, attempt)).resolves.toMatchObject({ trackingNumber: 'SWD-1' })

    expect(calculateQuote).toHaveBeenCalledOnce()
    expect(bookParcel).toHaveBeenCalledTimes(2)
    expect(bookParcel.mock.calls[0]![1]).toBe(bookParcel.mock.calls[1]![1])
    expect(bookParcel.mock.calls[1]![0]).toMatchObject({ quoteId: 'quote-1', merchantReference: 'ORD-1001' })
    expect(bookParcel.mock.calls[1]![3]).toBe('merchant')
  })
})
