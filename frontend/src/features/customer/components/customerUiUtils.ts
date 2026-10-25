import type { ParcelStatus } from '../api/deliveryContracts'

export interface StatusPresentation {
  label: string
  pillClass: string
  description: string
  isTerminal: boolean
  isSuccess: boolean
  isFailed: boolean
}

export function getStatusPresentation(status: string): StatusPresentation {
  switch (status) {
    case 'CREATED':
      return {
        label: 'Created',
        pillClass: 'status-pill status-pill-pending',
        description: 'Booking recorded. Awaiting courier assignment.',
        isTerminal: false,
        isSuccess: false,
        isFailed: false,
      }
    case 'PICKED_UP':
      return {
        label: 'Picked Up',
        pillClass: 'status-pill status-pill-processing',
        description: 'Collected by courier. En route to sorting hub.',
        isTerminal: false,
        isSuccess: false,
        isFailed: false,
      }
    case 'IN_TRANSIT':
      return {
        label: 'In Transit',
        pillClass: 'status-pill status-pill-processing',
        description: 'Package in transit between transit hubs.',
        isTerminal: false,
        isSuccess: false,
        isFailed: false,
      }
    case 'OUT_FOR_DELIVERY':
      return {
        label: 'Out for Delivery',
        pillClass: 'status-pill status-pill-processing',
        description: 'Out for delivery on courier vehicle.',
        isTerminal: false,
        isSuccess: false,
        isFailed: false,
      }
    case 'DELIVERED':
      return {
        label: 'Delivered',
        pillClass: 'status-pill status-pill-success',
        description: 'Successfully delivered to recipient.',
        isTerminal: true,
        isSuccess: true,
        isFailed: false,
      }
    case 'FAILED':
      return {
        label: 'Failed',
        pillClass: 'status-pill status-pill-failed',
        description: 'Delivery could not be completed.',
        isTerminal: true,
        isSuccess: false,
        isFailed: true,
      }
    default:
      return {
        label: status || 'Unknown',
        pillClass: 'status-pill status-pill-exhausted',
        description: 'Parcel status is currently updating.',
        isTerminal: false,
        isSuccess: false,
        isFailed: false,
      }
  }
}

/**
 * Exact decimal currency formatting from integer minor units.
 * Prevents JavaScript floating point issues.
 */
export function formatCurrency(amountMinorUnits: number, currency = 'LKR'): string {
  const isNegative = amountMinorUnits < 0
  const abs = Math.abs(amountMinorUnits)
  const major = Math.floor(abs / 100)
  const minor = abs % 100
  const minorPadded = minor < 10 ? `0${minor}` : `${minor}`
  const prefix = isNegative ? '-' : ''
  return `${prefix}${currency} ${major.toLocaleString('en-US')}.${minorPadded}`
}

/**
 * Format ISO date string into readable local/UTC timestamp.
 */
export function formatDateTime(isoString: string): string {
  try {
    const d = new Date(isoString)
    if (isNaN(d.getTime())) return isoString
    return d.toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      timeZoneName: 'short',
    })
  } catch {
    return isoString
  }
}

export const LIFECYCLE_STEPS: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
]

export function getActiveStepIndex(status: ParcelStatus | string): number {
  const index = LIFECYCLE_STEPS.indexOf(status as ParcelStatus)
  if (index >= 0) return index
  if (status === 'FAILED') return 4 // Terminal failed at last stage
  return 0
}
