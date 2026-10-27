import type { ParcelStatus } from '../../customer/api/deliveryContracts'
import type { MerchantApprovalStatus } from './merchant'

export type { ParcelStatus, MerchantApprovalStatus }

export interface MerchantOrderSummaryItem {
  parcelId: string
  trackingNumber: string
  currentStatus: ParcelStatus
  weightKg: number
  feeMinorUnits: number
  currency: string
  createdAt: string
  lastStatusUpdate: string
}

export interface MerchantDashboardData {
  merchantId: string
  businessName: string
  status: MerchantApprovalStatus
  totalOrdersCount: number
  activeOrdersCount: number
  deliveredOrdersCount: number
  failedOrdersCount: number
  recentOrders: MerchantOrderSummaryItem[]
}

export type MerchantDashboardFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'timeout'
  | 'unavailable'
  | 'invalid-response'

export class MerchantDashboardError extends Error {
  readonly kind: MerchantDashboardFailureKind
  readonly statusCode?: number

  constructor(kind: MerchantDashboardFailureKind, message?: string, statusCode?: number) {
    super(message || 'Merchant dashboard request failed.')
    this.name = 'MerchantDashboardError'
    this.kind = kind
    this.statusCode = statusCode
  }
}
