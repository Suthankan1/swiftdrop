import type {
  AssignmentResponse,
  ParcelDetailResponse,
  ParcelStatus,
  ProblemDetail,
  ProofDetailResponse,
  ProofResponse,
  UploadProofParams,
} from './proofContracts'

export type ProofFailureKind =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'validation-failed'
  | 'conflict'
  | 'payload-too-large'
  | 'illegal-stage'
  | 'service-unavailable'
  | 'storage-timeout'
  | 'ambiguous-failure'
  | 'invalid-response'

export class CourierProofError extends Error {
  readonly kind: ProofFailureKind
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: ProofFailureKind,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Proof operation failed.')
    this.name = 'CourierProofError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

export interface CourierProofApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  xhrFactory?: () => XMLHttpRequest
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

export function isParcelStatus(value: unknown): value is ParcelStatus {
  return typeof value === 'string' && VALID_STATUSES.includes(value as ParcelStatus)
}

export function isProofResponse(value: unknown): value is ProofResponse {
  return (
    isRecord(value) &&
    typeof value.proofId === 'string' &&
    typeof value.parcelId === 'string' &&
    typeof value.uploadedBySub === 'string' &&
    typeof value.uploadedAt === 'string'
  )
}

export function isProofDetailResponse(value: unknown): value is ProofDetailResponse {
  return (
    isRecord(value) &&
    typeof value.proofId === 'string' &&
    typeof value.parcelId === 'string' &&
    typeof value.objectReference === 'string' &&
    typeof value.evidenceType === 'string' &&
    typeof value.contentType === 'string' &&
    typeof value.uploadedBySub === 'string' &&
    typeof value.uploadedAt === 'string'
  )
}

export function isAssignmentResponse(value: unknown): value is AssignmentResponse {
  return (
    isRecord(value) &&
    typeof value.assignmentId === 'string' &&
    typeof value.parcelId === 'string' &&
    typeof value.courierSub === 'string' &&
    typeof value.active === 'boolean' &&
    typeof value.assignedAt === 'string' &&
    typeof value.assignedBy === 'string'
  )
}

export function isParcelDetailResponse(value: unknown): value is ParcelDetailResponse {
  return (
    isRecord(value) &&
    typeof value.parcelId === 'string' &&
    typeof value.trackingNumber === 'string' &&
    typeof value.createdBySub === 'string' &&
    isParcelStatus(value.currentStatus) &&
    typeof value.version === 'number' &&
    typeof value.createdAt === 'string'
  )
}

function parseProblemJson(text: string): ProblemDetail | undefined {
  try {
    const data = JSON.parse(text)
    if (isRecord(data) && typeof data.title === 'string' && typeof data.status === 'number') {
      return data as unknown as ProblemDetail
    }
  } catch {
    // Malformed or non-JSON problem body
  }
  return undefined
}

export function createCourierProofApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
  xhrFactory = () => new XMLHttpRequest(),
}: CourierProofApiOptions) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new CourierProofError('auth-required', 'Authentication session is unavailable.')
    }
    if (!token?.trim()) {
      throw new CourierProofError('auth-required', 'Authentication token is missing. Please sign in.')
    }
    return token
  }

  return {
    /**
     * Lists active courier assignments from Delivery Operations (GET /api/v1/assignments/my).
     */
    async getMyAssignments(activeOnly = true, signal?: AbortSignal): Promise<AssignmentResponse[]> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/assignments/my?activeOnly=${activeOnly}`

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
      } catch (err) {
        if (signal?.aborted) throw err
        throw new CourierProofError('service-unavailable', 'Could not reach assignment service.')
      }

      if (response.status === 401) {
        throw new CourierProofError('auth-required', 'Session expired. Please sign in again.', undefined, 401)
      }
      if (response.status === 403) {
        throw new CourierProofError('forbidden', 'Access restricted to active COURIER role.', undefined, 403)
      }
      if (!response.ok) {
        const text = await response.text()
        const problem = parseProblemJson(text)
        throw new CourierProofError('service-unavailable', `Assignment service returned HTTP ${response.status}`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new CourierProofError('invalid-response', 'Failed to parse assignment list JSON response.')
      }

      // Handle paged items { items: [...] } or direct array [...]
      if (isRecord(payload) && Array.isArray(payload.items) && payload.items.every(isAssignmentResponse)) {
        return payload.items
      }
      if (Array.isArray(payload) && payload.every(isAssignmentResponse)) {
        return payload
      }

      throw new CourierProofError('invalid-response', 'Assignment response schema mismatch.')
    },

    /**
     * Retrieves authoritative parcel details (GET /api/v1/parcels/{parcelId}).
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
      } catch (err) {
        if (signal?.aborted) throw err
        throw new CourierProofError('service-unavailable', 'Could not reach delivery service.')
      }

      if (response.status === 401) {
        throw new CourierProofError('auth-required', 'Session expired or unauthorized.', undefined, 401)
      }
      if (response.status === 403) {
        throw new CourierProofError('forbidden', 'You are not authorized to access this parcel.', undefined, 403)
      }
      if (response.status === 404) {
        throw new CourierProofError('not-found', 'Parcel not found.', undefined, 404)
      }
      if (!response.ok) {
        const text = await response.text()
        const problem = parseProblemJson(text)
        throw new CourierProofError('service-unavailable', `Delivery query returned HTTP ${response.status}`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new CourierProofError('invalid-response', 'Failed to parse parcel details JSON response.')
      }

      if (!isParcelDetailResponse(payload)) {
        throw new CourierProofError('invalid-response', 'Parcel details schema mismatch.')
      }

      return payload
    },

    /**
     * Reads recorded proof metadata for a parcel (GET /api/v1/parcels/{parcelId}/proof).
     * Used for verification, display, and read-back reconciliation after ambiguous failures.
     */
    async getProofMetadata(parcelId: string, signal?: AbortSignal): Promise<ProofDetailResponse> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/proof`

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
      } catch (err) {
        if (signal?.aborted) throw err
        throw new CourierProofError('service-unavailable', 'Could not reach proof metadata service.')
      }

      if (response.status === 401) {
        throw new CourierProofError('auth-required', 'Session expired or unauthorized.', undefined, 401)
      }
      if (response.status === 403) {
        throw new CourierProofError('forbidden', 'You are not authorized to access proof for this parcel.', undefined, 403)
      }
      if (response.status === 404) {
        throw new CourierProofError('not-found', 'No proof recorded for this parcel.', undefined, 404)
      }
      if (!response.ok) {
        const text = await response.text()
        const problem = parseProblemJson(text)
        throw new CourierProofError('service-unavailable', `Proof metadata service returned HTTP ${response.status}`, problem, response.status)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new CourierProofError('invalid-response', 'Failed to parse proof metadata JSON response.')
      }

      if (!isProofDetailResponse(payload)) {
        throw new CourierProofError('invalid-response', 'Proof metadata schema mismatch.')
      }

      return payload
    },

    /**
     * Streams private evidence image from Delivery Operations (GET /api/v1/parcels/{parcelId}/proof/content).
     * Returns a local Blob. Zero public URLs or storage keys exposed.
     */
    async getProofContent(parcelId: string, signal?: AbortSignal): Promise<Blob> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/proof/content`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'image/jpeg, image/png, image/webp',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (err) {
        if (signal?.aborted) throw err
        throw new CourierProofError('service-unavailable', 'Could not reach proof content service.')
      }

      if (response.status === 401) {
        throw new CourierProofError('auth-required', 'Session expired or unauthorized.', undefined, 401)
      }
      if (response.status === 403) {
        throw new CourierProofError('forbidden', 'You are not authorized to view this proof image.', undefined, 403)
      }
      if (response.status === 404) {
        throw new CourierProofError('not-found', 'Proof content not found.', undefined, 404)
      }
      if (!response.ok) {
        throw new CourierProofError('service-unavailable', `Proof content service returned HTTP ${response.status}`, undefined, response.status)
      }

      try {
        return await response.blob()
      } catch {
        throw new CourierProofError('invalid-response', 'Failed to read binary proof content stream.')
      }
    },

    /**
     * Uploads proof photo and metadata via multipart/form-data with genuine progress tracking.
     * Transport: XMLHttpRequest (guarantees real upload progress without fabricated percentages).
     * Zero automatic retry on ambiguous network failure or 401.
     */
    async uploadProof({
      parcelId,
      file,
      recipientName,
      deliveryNote,
      onProgress,
      signal,
    }: UploadProofParams): Promise<ProofResponse> {
      const token = await resolveAuthToken()
      const url = `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId)}/proof`

      const formData = new FormData()
      formData.append('file', file)
      if (recipientName?.trim()) {
        formData.append('recipientName', recipientName.trim())
      }
      if (deliveryNote?.trim()) {
        formData.append('deliveryNote', deliveryNote.trim())
      }

      return new Promise<ProofResponse>((resolve, reject) => {
        const xhr = xhrFactory()

        const cleanupSignal = () => {
          if (signal) {
            signal.removeEventListener('abort', onAbort)
          }
        }

        const onAbort = () => {
          cleanupSignal()
          xhr.abort()
          reject(new CourierProofError('ambiguous-failure', 'Upload operation was cancelled by caller.'))
        }

        if (signal) {
          if (signal.aborted) {
            reject(new CourierProofError('ambiguous-failure', 'Upload aborted before start.'))
            return
          }
          signal.addEventListener('abort', onAbort)
        }

        if (xhr.upload && onProgress) {
          xhr.upload.onprogress = (evt) => {
            if (evt.lengthComputable && evt.total > 0) {
              const percent = Math.min(100, Math.max(0, Math.round((evt.loaded / evt.total) * 100)))
              onProgress(percent)
            }
          }
        }

        xhr.open('POST', url, true)
        xhr.setRequestHeader('Authorization', `Bearer ${token}`)
        xhr.setRequestHeader('Accept', 'application/json, application/problem+json')

        xhr.onload = () => {
          cleanupSignal()
          const status = xhr.status
          const responseText = xhr.responseText || ''
          const problem = parseProblemJson(responseText)

          if (status === 201 || status === 200) {
            try {
              const payload = JSON.parse(responseText)
              if (isProofResponse(payload)) {
                resolve(payload)
                return
              }
            } catch {
              // Parse error
            }
            reject(new CourierProofError('invalid-response', 'Server returned invalid proof response payload.'))
            return
          }

          if (status === 400) {
            reject(new CourierProofError('validation-failed', problem?.detail || 'Invalid proof upload parameters or file.', problem, 400))
            return
          }
          if (status === 401) {
            reject(new CourierProofError('auth-required', 'Session expired during upload. Please re-authenticate.', problem, 401))
            return
          }
          if (status === 403) {
            reject(new CourierProofError('forbidden', problem?.detail || 'Forbidden: You are not assigned to this parcel.', problem, 403))
            return
          }
          if (status === 404) {
            reject(new CourierProofError('not-found', 'Parcel not found.', problem, 404))
            return
          }
          if (status === 409) {
            reject(new CourierProofError('conflict', problem?.detail || 'Proof has already been recorded for this parcel.', problem, 409))
            return
          }
          if (status === 413) {
            reject(new CourierProofError('payload-too-large', 'Proof photo exceeds maximum allowable payload size (5 MiB).', problem, 413))
            return
          }
          if (status === 422) {
            reject(new CourierProofError('illegal-stage', problem?.detail || 'Parcel is not in an eligible lifecycle stage for proof capture.', problem, 422))
            return
          }
          if (status === 503) {
            reject(new CourierProofError('service-unavailable', 'Private storage or delivery service is temporarily unavailable.', problem, 503))
            return
          }
          if (status === 504) {
            reject(new CourierProofError('storage-timeout', 'Storage service timed out while persisting proof photo.', problem, 504))
            return
          }

          reject(new CourierProofError('ambiguous-failure', `Upload failed with HTTP status ${status}.`, problem, status))
        }

        xhr.onerror = () => {
          cleanupSignal()
          reject(new CourierProofError('ambiguous-failure', 'Network connection interrupted during proof upload. Check server status before retrying.'))
        }

        xhr.ontimeout = () => {
          cleanupSignal()
          reject(new CourierProofError('ambiguous-failure', 'Proof upload timed out. Verify server state before retrying.'))
        }

        xhr.send(formData)
      })
    },
  }
}
