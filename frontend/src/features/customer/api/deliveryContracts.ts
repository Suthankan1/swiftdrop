/**
 * Authoritative contracts for SwiftDrop Delivery Operations
 * Grounded in:
 * - contracts/rest/openapi-delivery.yaml v1.0.0
 * - contracts/rest/problem-detail.yaml
 * - contracts/graphql/schema.graphql v1.0.0
 * - docs/contracts/SERVICE_INTERFACE_MATRIX.md (IF-REST-04, IF-REST-05, IF-REST-06)
 */

export type ParcelStatus =
  | 'CREATED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'FAILED'

export interface ProblemDetail {
  type?: string
  title: string
  status: number
  detail?: string
  instance?: string
  [key: string]: unknown
}

export interface TrackingTimelineItem {
  status: string
  occurredAt: string
  cityOrZone?: string
}

export interface PublicTrackingResponse {
  trackingNumber: string
  currentStatus: ParcelStatus
  originCity?: string
  destinationCity?: string
  timeline: TrackingTimelineItem[]
}

export interface ParcelDetailResponse {
  parcelId: string
  trackingNumber: string
  createdBySub: string
  merchantId: string | null
  currentStatus: ParcelStatus
  version: number
  weightKg: number
  dimensions: string
  amountMinorUnits: number
  currency: string
  rateVersion: string
  pickupAddress: string
  dropoffAddress: string
  activeCourierSub: string | null
  createdAt: string
}

/**
 * Customer delivery summary model for paginated history listings.
 * Conforms to GraphQL CustomerParcelSummary and REST parcel projection.
 */
export interface CustomerDeliverySummary {
  parcelId: string
  trackingNumber: string
  currentStatus: ParcelStatus
  amountMinorUnits: number
  currency: string
  pickupAddress?: string
  dropoffAddress?: string
  recipientCity?: string
  createdAt: string
  updatedAt?: string
}

export interface PaginationParams {
  page?: number
  size?: number
}

export interface CustomerDeliveriesPage {
  items: CustomerDeliverySummary[]
  totalElements: number
  totalPages: number
  currentPage: number
  pageSize: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface ReceiptDownloadResult {
  blob: Blob
  filename: string
}
