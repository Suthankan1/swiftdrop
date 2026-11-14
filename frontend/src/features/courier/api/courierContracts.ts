import type { AssignmentResponse, ParcelDetailResponse, ParcelStatus } from './proofContracts'

export type { AssignmentResponse, ParcelDetailResponse, ParcelStatus }

export interface CourierDashboardSummary {
  courierSub: string
  activeAssignedCount: number
  todayDeliveredCount: number
  todayFailedCount: number
}

export interface PagedAssignmentsResponse {
  items: AssignmentResponse[]
  currentPage: number
  pageSize: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

export type CourierActiveStatus = 'CREATED' | 'PICKED_UP' | 'IN_TRANSIT' | 'OUT_FOR_DELIVERY'
export type CourierHistoryStatus = 'DELIVERED' | 'FAILED'

export type CourierFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'timeout'
  | 'unavailable'
  | 'invalid-response'
  | 'invalid-pagination'

export class CourierApiError extends Error {
  readonly kind: CourierFailureKind
  readonly statusCode?: number

  constructor(kind: CourierFailureKind, message?: string, statusCode?: number) {
    super(message || 'Courier API operation failed.')
    this.name = 'CourierApiError'
    this.kind = kind
    this.statusCode = statusCode
  }
}
