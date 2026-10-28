import type {
  BookParcelRequest,
  CustomerDeliveriesPage,
  CustomerDeliverySummary,
  PaginationParams,
  ParcelDetailResponse,
  ParcelStatus,
  ParcelStatusHistoryItem,
  ParcelStatusHistoryPage,
  ProblemDetail,
  PublicTrackingResponse,
  ReceiptDownloadResult,
  TrackingTimelineItem,
} from './deliveryContracts'

export type { ParcelStatusHistoryItem, ParcelStatusHistoryPage }

export type DeliveryFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'unavailable'
  | 'invalid-response'
  | 'download-failed'
  | 'not-eligible'
  | 'conflict'
  | 'expired'
  | 'validation-error'

export class DeliveryError extends Error {
  readonly kind: DeliveryFailureKind
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: DeliveryFailureKind,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Delivery service request failed.')
    this.name = 'DeliveryError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

export interface DeliveryApiOptions {
  getAccessToken?: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

const VALID_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

export const TRACKING_NUMBER_REGEX = /^SWD-[0-9]{8}-[A-Z0-9]{4,8}$/
export const MIN_TRACKING_LENGTH = 17
export const MAX_TRACKING_LENGTH = 32
export const MAX_TIMELINE_ITEMS = 100

export function isValidTrackingNumber(reference: unknown): boolean {
  if (typeof reference !== 'string') return false
  const trimmed = reference.trim()
  return (
    trimmed.length >= MIN_TRACKING_LENGTH &&
    trimmed.length <= MAX_TRACKING_LENGTH &&
    TRACKING_NUMBER_REGEX.test(trimmed)
  )
}

export function isParcelStatus(value: unknown): value is ParcelStatus {
  return typeof value === 'string' && VALID_STATUSES.includes(value as ParcelStatus)
}

export function isTrackingTimelineItem(value: unknown): value is TrackingTimelineItem {
  if (!isRecord(value)) return false
  if (typeof value.status !== 'string' || !isParcelStatus(value.status)) return false
  if (
    typeof value.occurredAt !== 'string' ||
    value.occurredAt.length > 40 ||
    Number.isNaN(Date.parse(value.occurredAt))
  ) {
    return false
  }
  if (value.cityOrZone !== undefined && (typeof value.cityOrZone !== 'string' || value.cityOrZone.length > 100)) {
    return false
  }
  return true
}

export function isPublicTrackingResponse(value: unknown): value is PublicTrackingResponse {
  if (!isRecord(value)) return false
  if (
    typeof value.trackingNumber !== 'string' ||
    !isValidTrackingNumber(value.trackingNumber)
  ) {
    return false
  }
  if (!isParcelStatus(value.currentStatus)) return false
  if (
    !Array.isArray(value.timeline) ||
    value.timeline.length > MAX_TIMELINE_ITEMS ||
    !value.timeline.every(isTrackingTimelineItem)
  ) {
    return false
  }
  if (value.originCity !== undefined && (typeof value.originCity !== 'string' || value.originCity.length > 100)) {
    return false
  }
  if (value.destinationCity !== undefined && (typeof value.destinationCity !== 'string' || value.destinationCity.length > 100)) {
    return false
  }
  return true
}

export function isParcelDetailResponse(value: unknown): value is ParcelDetailResponse {
  return (
    isRecord(value) &&
    typeof value.parcelId === 'string' &&
    typeof value.trackingNumber === 'string' &&
    typeof value.createdBySub === 'string' &&
    isParcelStatus(value.currentStatus) &&
    typeof value.version === 'number' &&
    typeof value.weightKg === 'number' &&
    typeof value.dimensions === 'string' &&
    typeof value.amountMinorUnits === 'number' &&
    typeof value.currency === 'string' &&
    typeof value.rateVersion === 'string' &&
    typeof value.pickupAddress === 'string' &&
    typeof value.dropoffAddress === 'string' &&
    typeof value.createdAt === 'string' &&
    (value.merchantId === null || typeof value.merchantId === 'string') &&
    (value.activeCourierSub === null || typeof value.activeCourierSub === 'string')
  )
}

function isCustomerDeliverySummary(value: unknown): value is CustomerDeliverySummary {
  return (
    isRecord(value) &&
    typeof value.parcelId === 'string' &&
    typeof value.trackingNumber === 'string' &&
    isParcelStatus(value.currentStatus) &&
    typeof value.amountMinorUnits === 'number' &&
    typeof value.currency === 'string' &&
    typeof value.createdAt === 'string' &&
    (value.pickupAddress === undefined || typeof value.pickupAddress === 'string') &&
    (value.dropoffAddress === undefined || typeof value.dropoffAddress === 'string') &&
    (value.recipientCity === undefined || typeof value.recipientCity === 'string') &&
    (value.updatedAt === undefined || typeof value.updatedAt === 'string')
  )
}

export function isCustomerDeliveriesPage(value: unknown): value is CustomerDeliveriesPage {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isCustomerDeliverySummary) &&
    typeof value.totalElements === 'number' &&
    typeof value.totalPages === 'number' &&
    typeof value.currentPage === 'number' &&
    typeof value.pageSize === 'number' &&
    typeof value.hasNext === 'boolean' &&
    typeof value.hasPrevious === 'boolean'
  )
}

export function isParcelStatusHistoryItem(value: unknown): value is ParcelStatusHistoryItem {
  if (!isRecord(value)) return false
  return (
    typeof value.historyId === 'string' &&
    typeof value.parcelId === 'string' &&
    (value.previousStatus === null || isParcelStatus(value.previousStatus)) &&
    isParcelStatus(value.newStatus) &&
    (value.reason === null || typeof value.reason === 'string') &&
    (value.note === null || typeof value.note === 'string') &&
    (value.proofId === null || typeof value.proofId === 'string') &&
    typeof value.resultingVersion === 'number' &&
    typeof value.occurredAt === 'string'
  )
}

export function isParcelStatusHistoryPage(value: unknown): value is ParcelStatusHistoryPage {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isParcelStatusHistoryItem) &&
    typeof value.totalElements === 'number' &&
    typeof value.totalPages === 'number' &&
    typeof value.currentPage === 'number' &&
    typeof value.pageSize === 'number' &&
    typeof value.hasNext === 'boolean' &&
    typeof value.hasPrevious === 'boolean'
  )
}

/**
 * Extracts and sanitizes the PDF filename from the Content-Disposition header.
 * Disallows path traversal characters, guarantees .pdf extension, and applies safe fallback.
 */
export function sanitizeReceiptFilename(headerValue: string | null, fallback: string): string {
  if (!headerValue) return fallback

  // RFC 6266 / RFC 5987 filename parsing
  const match = headerValue.match(/filename\*?=(?:UTF-8'')?["']?([^"';\r\n]+)["']?/i)
  if (!match || !match[1]) return fallback

  const raw = match[1].trim()
  // Strip any directory traversal paths (e.g. ../ or / or \)
  const baseName = raw.split(/[/\\]/).pop() || ''
  // Keep only alphanumeric, hyphens, underscores, dots
  const sanitized = baseName.replace(/[^a-zA-Z0-9._-]/g, '_')

  if (!sanitized) return fallback
  if (!sanitized.toLowerCase().endsWith('.pdf')) {
    return `${sanitized}.pdf`
  }
  return sanitized
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail | undefined> {
  try {
    const data = await response.json()
    if (isRecord(data) && typeof data.title === 'string' && typeof data.status === 'number') {
      return data as unknown as ProblemDetail
    }
  } catch {
    // Non-JSON or malformed error response body
  }
  return undefined
}

export function createDeliveryApi({
  getAccessToken = async () => null,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: DeliveryApiOptions = {}) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new DeliveryError('auth-required', 'Authentication required.')
    }
    if (!token?.trim()) {
      throw new DeliveryError('auth-required', 'Authentication token is missing.')
    }
    return token
  }

  return {
    /**
     * Retrieve full parcel details with authorization check (IF-REST-05).
     * Accessible by parcel creator, assigned courier, or dispatcher/admin.
     */
    async getParcel(parcelId: string, signal?: AbortSignal): Promise<ParcelDetailResponse> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Delivery service is currently unreachable.')
      }

      if (response.status === 401) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('auth-required', 'Session expired or invalid authentication.', problem, 401)
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('forbidden', 'You are not authorized to view this parcel.', problem, 403)
      }
      if (response.status === 404) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('not-found', 'Parcel not found.', problem, 404)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('unavailable', `Delivery service returned status ${response.status}.`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new DeliveryError('invalid-response', 'Invalid JSON received for parcel details.')
      }

      if (!isParcelDetailResponse(payload)) {
        throw new DeliveryError('invalid-response', 'Parcel details response does not match the approved contract.')
      }

      return payload
    },

    /**
     * Retrieve status transition history for a parcel with object-level authorization (IF-REST-05).
     * Connects to GET /api/v1/parcels/{parcelId}/history.
     */
    async getParcelHistory(
      parcelId: string,
      params: PaginationParams = {},
      signal?: AbortSignal,
    ): Promise<ParcelStatusHistoryPage> {
      const token = await resolveAuthToken()
      const page = Math.max(0, params.page ?? 0)
      const size = Math.max(1, Math.min(100, params.size ?? 20))

      const queryParams = new URLSearchParams({
        page: page.toString(),
        size: size.toString(),
      })

      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/history?${queryParams.toString()}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Parcel history service is currently unreachable.')
      }

      if (response.status === 401) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('auth-required', 'Session expired. Please sign in again.', problem, 401)
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('forbidden', 'You do not have permission to view parcel history.', problem, 403)
      }
      if (response.status === 404) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('not-found', 'Parcel not found.', problem, 404)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('unavailable', `Parcel history request failed (${response.status}).`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new DeliveryError('invalid-response', 'Invalid JSON payload received for parcel history.')
      }

      if (isParcelStatusHistoryPage(payload)) {
        return payload
      }

      if (Array.isArray(payload) && payload.every(isParcelStatusHistoryItem)) {
        const total = payload.length
        const totalPages = Math.max(1, Math.ceil(total / size))
        const pagedItems = payload.slice(page * size, (page + 1) * size)
        return {
          items: pagedItems,
          totalElements: total,
          totalPages,
          currentPage: page,
          pageSize: size,
          hasNext: page < totalPages - 1,
          hasPrevious: page > 0,
        }
      }

      throw new DeliveryError('invalid-response', 'Parcel history response does not match the expected schema.')
    },

    /**
     * Public redacted tracking view by tracking number (IF-REST-04).
     * Open to all users anonymously; strictly redacts sensitive PII.
     */
    async getTracking(trackingNumber: string, signal?: AbortSignal): Promise<PublicTrackingResponse> {
      const normalized = (trackingNumber || '').trim().toUpperCase()
      if (!isValidTrackingNumber(normalized)) {
        throw new DeliveryError(
          'validation-error',
          'Enter a valid SwiftDrop tracking number.',
          undefined,
          400,
        )
      }

      const url = `${normalizedGateway}/api/v1/tracking/${encodeURIComponent(normalized)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Tracking service is currently unreachable.')
      }

      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('validation-error', problem?.detail || 'Enter a valid SwiftDrop tracking number.', problem, 400)
      }
      if (response.status === 404) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('not-found', problem?.detail || 'Tracking number not found.', problem, 404)
      }
      if (response.status === 429) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError(
          'unavailable',
          problem?.detail || 'Too many tracking requests from this network. Please wait a moment and try again.',
          problem,
          429,
        )
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('unavailable', problem?.detail || `Tracking service returned status ${response.status}.`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new DeliveryError('invalid-response', 'Invalid JSON received for tracking details.')
      }

      if (!isPublicTrackingResponse(payload)) {
        throw new DeliveryError('invalid-response', 'Tracking response does not match the approved contract.')
      }

      // Strictly allowlisted defense-in-depth projection: omits any extraneous or private properties
      return {
        trackingNumber: payload.trackingNumber,
        currentStatus: payload.currentStatus,
        ...(typeof payload.originCity === 'string' && payload.originCity.trim()
          ? { originCity: payload.originCity.trim() }
          : {}),
        ...(typeof payload.destinationCity === 'string' && payload.destinationCity.trim()
          ? { destinationCity: payload.destinationCity.trim() }
          : {}),
        timeline: payload.timeline.map((item) => ({
          status: item.status,
          occurredAt: item.occurredAt,
          ...(typeof item.cityOrZone === 'string' && item.cityOrZone.trim()
            ? { cityOrZone: item.cityOrZone.trim() }
            : {}),
        })),
      }
    },

    /**
     * Retrieves paginated delivery history for the authenticated customer.
     * Respects server pagination parameters (page, size).
     */
    async getCustomerDeliveries(
      params: PaginationParams = {},
      signal?: AbortSignal,
    ): Promise<CustomerDeliveriesPage> {
      const token = await resolveAuthToken()
      const page = Math.max(0, params.page ?? 0)
      const size = Math.max(1, Math.min(100, params.size ?? 10))

      const queryParams = new URLSearchParams({
        page: page.toString(),
        size: size.toString(),
      })

      const url = `${normalizedGateway}/api/v1/parcels?${queryParams.toString()}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Delivery history service is currently unreachable.')
      }

      if (response.status === 401) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('auth-required', 'Session expired. Please sign in again.', problem, 401)
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('forbidden', 'You do not have permission to view this delivery history.', problem, 403)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('unavailable', `Delivery history request failed (${response.status}).`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new DeliveryError('invalid-response', 'Invalid JSON payload received for delivery history.')
      }

      // Handle standard paginated structure
      if (isCustomerDeliveriesPage(payload)) {
        return payload
      }

      // Handle raw array fallback if backend returns a list
      if (Array.isArray(payload) && payload.every(isCustomerDeliverySummary)) {
        const total = payload.length
        const totalPages = Math.max(1, Math.ceil(total / size))
        const pagedItems = payload.slice(page * size, (page + 1) * size)
        return {
          items: pagedItems,
          totalElements: total,
          totalPages,
          currentPage: page,
          pageSize: size,
          hasNext: page < totalPages - 1,
          hasPrevious: page > 0,
        }
      }

      throw new DeliveryError('invalid-response', 'Delivery history response does not match the expected schema.')
    },

    /**
     * Downloads the official PDF receipt for an authorized parcel (IF-REST-06).
     * Connects directly to the approved Delivery backend adapter (Issue #26 / LATER-09).
     * Validates binary PDF content, size bounds, and extracts safe attachment filename.
     */
    async downloadReceipt(parcelId: string, signal?: AbortSignal): Promise<ReceiptDownloadResult> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/receipt`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/pdf, application/problem+json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Delivery receipt service is currently unreachable.')
      }

      if (response.status === 401) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('auth-required', 'Authentication required to download receipt.', problem, 401)
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('forbidden', 'You are not authorized to download this delivery receipt.', problem, 403)
      }
      if (response.status === 404) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('not-found', 'Receipt is not available or parcel delivery is not yet completed.', problem, 404)
      }
      if (response.status === 422) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('not-eligible', 'Receipt generation limit exceeded or data unprocessable.', problem, 422)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('download-failed', `Receipt request failed with status ${response.status}.`, problem, response.status)
      }

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.toLowerCase().startsWith('application/pdf')) {
        throw new DeliveryError('invalid-response', `Expected application/pdf response but received '${contentType}'.`)
      }

      let blob: Blob
      try {
        blob = await response.blob()
      } catch {
        throw new DeliveryError('download-failed', 'Failed to read receipt document stream.')
      }

      // Backend maximum limit is 1,048,576 bytes (1 MB) per ReceiptRenderLimits
      if (blob.size === 0) {
        throw new DeliveryError('invalid-response', 'Received an empty PDF receipt from backend.')
      }
      if (blob.size > 5 * 1024 * 1024) {
        throw new DeliveryError('invalid-response', 'Receipt document exceeds safety size limits.')
      }

      const contentDisposition = response.headers.get('content-disposition')
      const fallbackFilename = `receipt-${parcelId}.pdf`
      const filename = sanitizeReceiptFilename(contentDisposition, fallbackFilename)

      return { blob, filename }
    },

    /**
     * Books a new parcel delivery using an accepted quote snapshot (IF-REST-03, openapi-delivery.yaml).
     * Dispatches POST /api/v1/parcels with Keycloak CUSTOMER Bearer token and optional Idempotency-Key.
     */
    async bookParcel(
      payload: BookParcelRequest,
      idempotencyKey?: string,
      signal?: AbortSignal,
    ): Promise<ParcelDetailResponse> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels`

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Accept: 'application/json, application/problem+json',
        Authorization: `Bearer ${token}`,
      }
      if (idempotencyKey?.trim()) {
        headers['Idempotency-Key'] = idempotencyKey.trim()
      }

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'POST',
          headers,
          body: JSON.stringify(payload),
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new DeliveryError('unavailable', 'Delivery booking service is currently unreachable.')
      }

      if (response.status === 401) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('auth-required', 'Session expired. Please sign in again.', problem, 401)
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError('forbidden', 'You are not authorized to book a delivery.', problem, 403)
      }
      if (response.status === 409) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError(
          'conflict',
          problem?.detail || 'Quote has already been consumed or an idempotency conflict occurred.',
          problem,
          409,
        )
      }
      if (response.status === 422) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError(
          'expired',
          problem?.detail || 'Quote has expired. Please recalculate your quote to continue.',
          problem,
          422,
        )
      }
      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError(
          'validation-error',
          problem?.detail || 'Invalid booking details provided.',
          problem,
          400,
        )
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new DeliveryError(
          'unavailable',
          `Delivery booking failed with status ${response.status}.`,
          problem,
          response.status,
        )
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new DeliveryError('invalid-response', 'Invalid JSON received from booking response.')
      }

      if (!isParcelDetailResponse(data)) {
        throw new DeliveryError('invalid-response', 'Booking response does not match the approved contract.')
      }

      return data
    },
  }
}

/**
 * Triggers a browser download from a Blob and promptly revokes the temporary Object URL
 * to avoid memory leaks.
 */
export function triggerBlobDownload(blob: Blob, filename: string): void {
  const objectUrl = window.URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = filename
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } finally {
    window.URL.revokeObjectURL(objectUrl)
  }
}
