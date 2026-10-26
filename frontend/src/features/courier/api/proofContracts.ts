/**
 * Authoritative contracts for SwiftDrop Courier Proof-of-Delivery Integration
 * Grounded in:
 * - Issue #29 [LATER-05]
 * - Issue #23 / #95 / #28 / PR #218
 * - contracts/rest/openapi-delivery.yaml v1.0.0
 * - contracts/rest/problem-detail.yaml
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

export interface AssignmentResponse {
  assignmentId: string
  parcelId: string
  courierSub: string
  active: boolean
  assignedAt: string
  assignedBy: string
  newParcelVersion?: number | null
}

export interface ParcelDetailResponse {
  parcelId: string
  trackingNumber: string
  createdBySub: string
  merchantId: string | null
  currentStatus: ParcelStatus
  version: number
  weightKg?: number | null
  dimensions?: string | null
  amountMinorUnits?: number | null
  currency?: string | null
  rateVersion?: string | null
  pickupAddress?: string | null
  dropoffAddress?: string | null
  activeCourierSub?: string | null
  createdAt: string
}

export interface ProofResponse {
  proofId: string
  parcelId: string
  uploadedBySub: string
  uploadedAt: string
}

export interface ProofDetailResponse {
  proofId: string
  parcelId: string
  /** Internal backend storage reference - NEVER expose in UI, URLs, or client logs */
  objectReference: string
  evidenceType: string
  contentType: string
  contentLength: number | null
  recipientName: string | null
  deliveryNote: string | null
  uploadedBySub: string
  uploadedAt: string
}

export const ALLOWED_PROOF_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

export type AllowedProofMimeType = (typeof ALLOWED_PROOF_MIME_TYPES)[number]

/** Maximum binary evidence size: 5,242,880 bytes (5 MiB) */
export const MAX_PROOF_SIZE_BYTES = 5 * 1024 * 1024

/** Maximum characters for recipient name per OpenAPI / DB schema */
export const MAX_RECIPIENT_NAME_LENGTH = 128

/** Maximum characters for delivery note per OpenAPI / DB schema */
export const MAX_DELIVERY_NOTE_LENGTH = 512

export interface FileValidationResult {
  valid: boolean
  error?: string
}

export interface UploadProofParams {
  parcelId: string
  file: File
  recipientName?: string
  deliveryNote?: string
  onProgress?: (percent: number) => void
  signal?: AbortSignal
}

export const STANDARD_FAILURE_REASONS = [
  { code: 'RECIPIENT_UNAVAILABLE', label: 'Recipient not available at address' },
  { code: 'INCORRECT_ADDRESS', label: 'Address could not be located or invalid' },
  { code: 'ACCESS_RESTRICTED', label: 'Premises locked, gate code required, or access denied' },
  { code: 'CUSTOMER_REJECTED', label: 'Recipient rejected delivery of the parcel' },
  { code: 'DAMAGED_IN_TRANSIT', label: 'Parcel package damaged before handover' },
  { code: 'SAFETY_CONCERN', label: 'Safety hazard or security restriction at location' },
  { code: 'OTHER', label: 'Other operational reason' },
] as const

export type FailureReasonCode = (typeof STANDARD_FAILURE_REASONS)[number]['code']

