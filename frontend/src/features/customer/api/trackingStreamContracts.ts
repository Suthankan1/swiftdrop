import type { ParcelStatus } from './deliveryContracts'

/**
 * Domain SSE event wire payload emitted by Delivery Operations.
 * Adheres strictly to contracts/events/parcel.status.changed.v1.schema.json,
 * ParcelLifecycleSseEvent.java, and docs/contracts/SSE_REST_RECOVERY.md.
 */
export interface ParcelLifecycleSseEvent {
  eventId: string
  parcelId: string
  trackingNumber: string
  aggregateVersion: number
  oldStatus: ParcelStatus | null
  newStatus: ParcelStatus
  reason: string | null
  occurredAt: string
}

/**
 * Public redacted SSE event wire payload emitted by Delivery Operations.
 * Adheres strictly to contracts/rest/openapi-delivery.yaml (#/components/schemas/PublicTrackingSseEvent)
 * and docs/contracts/SSE_REST_RECOVERY.md section 3.3.
 * Strictly excludes parcelId, PII, notes, reason, recipient details, and actor identifiers.
 */
export interface PublicTrackingSseEvent {
  eventId: string
  trackingNumber: string
  aggregateVersion: number
  oldStatus: ParcelStatus | null
  newStatus: ParcelStatus
  cityOrZone: string | null
  occurredAt: string
}


export type StreamConnectionStatus =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'disconnected'
  | 'auth-required'
  | 'forbidden'
  | 'error'

export interface ReconnectConfig {
  initialDelayMs: number
  maxDelayMs: number
  backoffMultiplier: number
  maxRetries: number
}

/**
 * Canonical SwiftDrop SSE Reconnection Configuration
 * Defined in docs/contracts/SSE_REST_RECOVERY.md section 4.2
 */
export const DEFAULT_RECONNECT_CONFIG: ReconnectConfig = {
  initialDelayMs: 1000,
  maxDelayMs: 30000,
  backoffMultiplier: 2.0,
  maxRetries: 10,
}

export interface TrackingStreamState {
  status: StreamConnectionStatus
  retryAttempt: number
  maxRetries: number
  lastDeliveredVersion: number | null
  lastEventId: string | null
  lastEventAt: string | null
  isOffline: boolean
  errorMessage?: string
}

const VALID_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Runtime type guard for incoming SSE payload to ensure defense-in-depth against
 * malformed or untrusted server payloads.
 */
export function isParcelLifecycleSseEvent(value: unknown): value is ParcelLifecycleSseEvent {
  if (!isRecord(value)) return false
  if (typeof value.eventId !== 'string' || !value.eventId.trim()) return false
  if (typeof value.parcelId !== 'string' || !value.parcelId.trim()) return false
  if (typeof value.trackingNumber !== 'string' || !value.trackingNumber.trim()) return false
  if (typeof value.aggregateVersion !== 'number' || !Number.isInteger(value.aggregateVersion) || value.aggregateVersion < 1) {
    return false
  }
  if (typeof value.newStatus !== 'string' || !VALID_STATUSES.includes(value.newStatus as ParcelStatus)) {
    return false
  }
  if (value.oldStatus !== null && (typeof value.oldStatus !== 'string' || !VALID_STATUSES.includes(value.oldStatus as ParcelStatus))) {
    return false
  }
  if (value.reason !== null && typeof value.reason !== 'string') {
    return false
  }
  if (typeof value.occurredAt !== 'string' || Number.isNaN(Date.parse(value.occurredAt))) {
    return false
  }
  return true
}

/**
 * Runtime type guard for incoming public redacted SSE payload.
 * Verifies all required fields and ensures sensitive internal properties are not expected.
 */
export function isPublicTrackingSseEvent(value: unknown): value is PublicTrackingSseEvent {
  if (!isRecord(value)) return false
  if (typeof value.eventId !== 'string' || !value.eventId.trim()) return false
  if (typeof value.trackingNumber !== 'string' || !value.trackingNumber.trim()) return false
  if (typeof value.aggregateVersion !== 'number' || !Number.isInteger(value.aggregateVersion) || value.aggregateVersion < 1) {
    return false
  }
  if (typeof value.newStatus !== 'string' || !VALID_STATUSES.includes(value.newStatus as ParcelStatus)) {
    return false
  }
  if (value.oldStatus !== null && (typeof value.oldStatus !== 'string' || !VALID_STATUSES.includes(value.oldStatus as ParcelStatus))) {
    return false
  }
  if (value.cityOrZone !== null && value.cityOrZone !== undefined && typeof value.cityOrZone !== 'string') {
    return false
  }
  if (typeof value.occurredAt !== 'string' || Number.isNaN(Date.parse(value.occurredAt))) {
    return false
  }
  return true
}


/**
 * Calculates bounded exponential backoff with full jitter (0 to exponential ceiling).
 * Formula defined in docs/contracts/SSE_REST_RECOVERY.md section 4.2.
 */
export function calculateBackoffWithJitter(
  attempt: number,
  config: ReconnectConfig = DEFAULT_RECONNECT_CONFIG,
  randomFn: () => number = Math.random,
): number {
  const exponential = Math.min(
    config.maxDelayMs,
    config.initialDelayMs * Math.pow(config.backoffMultiplier, attempt),
  )
  return Math.floor(randomFn() * exponential)
}
