import type { createDeliveryApi } from '../../customer/api/delivery'
import type { createPricingApi } from '../../customer/api/pricing'
import type { ParcelDetailResponse } from '../../customer/api/deliveryContracts'

/** One merchant order: priced through Pricing, then booked for the merchant tenant. */
export interface MerchantOrderInput {
  pickupZone: string
  dropoffZone: string
  weightKg: number
  lengthCm: number
  widthCm: number
  heightCm: number
  pickupAddress: string
  dropoffAddress: string
  recipientName: string
  recipientPhone: string
  merchantReference: string
  deliveryNotes?: string
  serviceTier?: 'STANDARD' | 'EXPRESS'
}

export const MERCHANT_ORDER_CSV_COLUMNS = [
  'pickupZone', 'dropoffZone', 'weightKg', 'lengthCm', 'widthCm', 'heightCm',
  'pickupAddress', 'dropoffAddress', 'recipientName', 'recipientPhone', 'merchantReference', 'deliveryNotes',
] as const

export const MERCHANT_ORDER_CSV_SAMPLE =
  MERCHANT_ORDER_CSV_COLUMNS.join(',') + '\r\n' +
  'ZONE_1,ZONE_2,2.5,20,15,10,"12 Vauxhall St, Colombo 02","45 Station Rd, Negombo",Nimal Perera,+94771234567,ORD-1001,Leave at gate\r\n' +
  'ZONE_1,ZONE_1,1.2,15,10,5,"Warehouse 4, Colombo 01","88 Galle Rd, Kalutara",Ama Silva,+94770001111,ORD-1002,\r\n'

const PHONE = /^\+?[0-9\s\-()]{7,25}$/

/** Checks one order against the same limits the services enforce; returns a message or null. */
export function merchantOrderProblem(order: MerchantOrderInput): string | null {
  if (!order.pickupZone || !order.dropoffZone) return 'Pickup and drop-off zones are required.'
  if (!Number.isFinite(order.weightKg) || order.weightKg <= 0 || order.weightKg > 50) return 'Weight must be between 0.01 and 50 kg.'
  for (const [name, value] of [['Length', order.lengthCm], ['Width', order.widthCm], ['Height', order.heightCm]] as const) {
    if (!Number.isInteger(value) || value < 1 || value > 300) return `${name} must be a whole number of centimetres between 1 and 300.`
  }
  if (!order.pickupAddress.trim() || order.pickupAddress.length > 2000) return 'Pickup address is required (max 2000 characters).'
  if (!order.dropoffAddress.trim() || order.dropoffAddress.length > 2000) return 'Drop-off address is required (max 2000 characters).'
  if (!order.recipientName.trim() || order.recipientName.length > 255) return 'Recipient name is required (max 255 characters).'
  if (!PHONE.test(order.recipientPhone.trim())) return 'Recipient phone must be a valid phone number.'
  if (!order.merchantReference.trim() || order.merchantReference.length > 255) return 'Order reference is required (max 255 characters).'
  if ((order.deliveryNotes ?? '').length > 2000) return 'Delivery notes cannot exceed 2000 characters.'
  return null
}

/** RFC 4180 CSV parsing: quoted fields, escaped quotes, commas and line breaks inside quotes. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') { field += '"'; i++ }
      else if (char === '"') quoted = false
      else field += char
    } else if (char === '"' && field === '') quoted = true
    else if (char === ',') { row.push(field); field = '' }
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++
      row.push(field); field = ''
      if (row.some(cell => cell.trim() !== '')) rows.push(row)
      row = []
    } else field += char
  }
  row.push(field)
  if (row.some(cell => cell.trim() !== '')) rows.push(row)
  return rows
}

export interface ParsedOrderRow {
  rowNumber: number
  order: MerchantOrderInput
  error: string | null
}

/** Reads the CSV template. The header row is required so column order mistakes are caught. */
export function parseMerchantOrdersCsv(text: string): { rows: ParsedOrderRow[]; error: string | null } {
  const table = parseCsv(text.replace(/^\uFEFF/, ''))
  if (table.length === 0) return { rows: [], error: 'The file is empty.' }
  const header = (table[0] ?? []).map(cell => cell.trim())
  const index = Object.fromEntries(MERCHANT_ORDER_CSV_COLUMNS.map(column => [column, header.indexOf(column)]))
  const missing = MERCHANT_ORDER_CSV_COLUMNS.filter(column => column !== 'deliveryNotes' && index[column] === -1)
  if (missing.length) return { rows: [], error: `Missing columns: ${missing.join(', ')}. Download the template for the expected header.` }
  if (table.length - 1 > 500) return { rows: [], error: 'A single import is limited to 500 orders.' }
  const cell = (row: string[], column: string) => (index[column] !== undefined && index[column] >= 0 ? (row[index[column]] ?? '') : '').trim()
  const rows = table.slice(1).map((row, offset) => {
    const order: MerchantOrderInput = {
      pickupZone: cell(row, 'pickupZone'), dropoffZone: cell(row, 'dropoffZone'),
      weightKg: Number(cell(row, 'weightKg')), lengthCm: Number(cell(row, 'lengthCm')),
      widthCm: Number(cell(row, 'widthCm')), heightCm: Number(cell(row, 'heightCm')),
      pickupAddress: cell(row, 'pickupAddress'), dropoffAddress: cell(row, 'dropoffAddress'),
      recipientName: cell(row, 'recipientName'), recipientPhone: cell(row, 'recipientPhone'),
      merchantReference: cell(row, 'merchantReference'), deliveryNotes: cell(row, 'deliveryNotes') || undefined,
    }
    return { rowNumber: offset + 2, order, error: merchantOrderProblem(order) }
  })
  return { rows, error: null }
}

function newKey(): string {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`
}

/** Per-order retry state: the same key and quote are reused so a retry can never book twice. */
export interface BookingAttempt {
  idempotencyKey: string
  quoteId?: string
}

export function newBookingAttempt(): BookingAttempt {
  return { idempotencyKey: newKey() }
}

/**
 * Quotes (once) and books one order for the merchant tenant. Pass the same attempt object when
 * retrying an order whose outcome is unknown, so a lost response cannot create a duplicate parcel.
 */
export async function bookMerchantOrder(
  apis: { pricing: ReturnType<typeof createPricingApi>; delivery: ReturnType<typeof createDeliveryApi> },
  order: MerchantOrderInput,
  attempt: BookingAttempt = newBookingAttempt(),
  signal?: AbortSignal,
): Promise<ParcelDetailResponse> {
  if (!attempt.quoteId) {
    const quote = await apis.pricing.calculateQuote({
      pickupZone: order.pickupZone, dropoffZone: order.dropoffZone, weightKg: order.weightKg,
      lengthCm: order.lengthCm, widthCm: order.widthCm, heightCm: order.heightCm,
      ...(order.serviceTier ? { serviceTier: order.serviceTier } : {}),
    }, signal)
    attempt.quoteId = quote.quoteId
  }
  return apis.delivery.bookParcel({
    quoteId: attempt.quoteId,
    pickupAddress: order.pickupAddress.trim(),
    dropoffAddress: order.dropoffAddress.trim(),
    recipientName: order.recipientName.trim(),
    recipientPhone: order.recipientPhone.trim(),
    merchantReference: order.merchantReference.trim(),
    ...(order.deliveryNotes?.trim() ? { deliveryNotes: order.deliveryNotes.trim() } : {}),
  }, attempt.idempotencyKey, signal, 'merchant')
}

