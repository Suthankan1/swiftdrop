export type ParcelLifecycleStatus =
  | 'CREATED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'FAILED'

export interface HistoricalParcelItem {
  parcelId: string
  trackingNumber: string
  pickupZone?: string
  dropoffZone?: string
  pickupAddress?: string
  dropoffAddress?: string
  weightKg?: number
  currentStatus: ParcelLifecycleStatus | string
  activeCourierSub?: string | null
  createdAt: string
  version?: number
}

export interface ParcelListResponse {
  items: HistoricalParcelItem[]
  currentPage: number
  pageSize: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious?: boolean
}

export interface ParcelStatusHistoryEntry {
  historyId: string
  parcelId: string
  previousStatus: ParcelLifecycleStatus | string | null
  newStatus: ParcelLifecycleStatus | string
  reason: string | null
  note: string | null
  proofId: string | null
  resultingVersion: number
  occurredAt: string
}

export interface ParcelStatusHistoryPageResponse {
  items: ParcelStatusHistoryEntry[]
  currentPage: number
  pageSize: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export interface AssignmentAuditEntry {
  auditId: string
  parcelId: string
  assignmentId: string
  courierSub: string
  action: string // 'ASSIGNED' | 'REASSIGNED' | 'CLOSED' | 'TERMINATED'
  actorSub: string
  actorRole: string
  previousCourierSub: string | null
  previousAssignmentId: string | null
  reason: string | null
  parcelVersion: number | null
  occurredAt: string
}
