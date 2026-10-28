export type ParcelStatus =
  | 'CREATED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'FAILED'

export interface CustomerParcelSummaryItem {
  parcelId: string
  trackingNumber: string
  currentStatus: ParcelStatus
  amountMinorUnits: number
  currency: string
  recipientCity: string
  createdAt: string
  updatedAt: string
}

export interface CustomerDashboardData {
  userId: string
  activeDeliveriesCount: number
  completedDeliveriesCount: number
  recentParcels: CustomerParcelSummaryItem[]
}

export type CustomerDashboardFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'unavailable'
  | 'timeout'
  | 'invalid-response'

const DEFAULT_MESSAGES: Record<CustomerDashboardFailureKind, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access denied: Customer dashboard is only accessible by authorized customers.',
  unavailable: 'Deliveries summary service is currently unavailable. Please retry shortly.',
  timeout: 'The request timed out. Please refresh to load current delivery summary.',
  'invalid-response': 'Received an invalid response from the delivery summary provider.',
}

export class CustomerDashboardError extends Error {
  readonly kind: CustomerDashboardFailureKind

  constructor(kind: CustomerDashboardFailureKind, message?: string) {
    super(message || DEFAULT_MESSAGES[kind])
    this.name = 'CustomerDashboardError'
    this.kind = kind
  }
}
