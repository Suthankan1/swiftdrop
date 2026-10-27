import type { ParcelStatus } from '../api/merchantDashboardContracts'
import {
  getStatusPresentation as getBaseStatusPresentation,
  formatCurrency,
  formatDateTime,
  type StatusPresentation,
} from '../../customer/components/customerUiUtils'

export { formatCurrency, formatDateTime }
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
