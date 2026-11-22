import type { MerchantOrderSummaryItem, ParcelStatus } from '../api/merchantDashboardContracts'
import {
  getStatusPresentation as getBaseStatusPresentation,
  formatCurrency,
  type StatusPresentation,
} from '../../customer/components/customerUiUtils'

export { formatCurrency }

/** Merchant operations use Asia/Colombo consistently; CSV keeps ISO UTC values. */
export function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Update time unavailable' : date.toLocaleString('en-GB', {
    timeZone: 'Asia/Colombo', year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
  })
}
export type { StatusPresentation }

export function getStatusPresentation(status: string): StatusPresentation {
  return getBaseStatusPresentation(status)
}

export const CANONICAL_PARCEL_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

export const ACTIVE_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
]

/** Quotes every CSV field and neutralises spreadsheet formulas (=, +, -, @, tab, CR). */
export function csvField(value: unknown): string {
  let text = value === null || value === undefined || (typeof value === 'number' && !Number.isFinite(value)) ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return `"${text.replaceAll('"', '""')}"`
}

/** Exports exactly the filtered, loaded rows; ISO timestamps carry their timezone. */
export function merchantOrdersCsv(orders: readonly MerchantOrderSummaryItem[]): string {
  const header = ['Tracking Number', 'Parcel ID', 'Status', 'Weight (kg)', 'Fee', 'Currency', 'Created At (ISO UTC)', 'Last Update (ISO UTC)']
  const rows = orders.map(order => [
    order.trackingNumber, order.parcelId, order.currentStatus,
    Number.isFinite(order.weightKg) ? order.weightKg : '',
    (order.feeMinorUnits / 100).toFixed(2), order.currency,
    order.createdAt, order.lastStatusUpdate || 'Unavailable',
  ])
  return '\uFEFF' + [header, ...rows].map(row => row.map(csvField).join(',')).join('\r\n')
}
