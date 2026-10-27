import type { ParcelStatus, ProblemDetail } from './proofContracts'
import {
  CourierStatusError,
  type CourierTransitionStatus,
  type ParcelStatusChangeResponse,
  type UpdateStatusRequest,
} from './statusContracts'

export interface CourierStatusApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
  timeoutMs?: number
}

const PARCEL_STATUSES: readonly ParcelStatus[] = [
  'CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

const TRANSITION_STATUSES: readonly CourierTransitionStatus[] = [
  'PICKED_UP',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'FAILED',
]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isParcelStatus(value: unknown): value is ParcelStatus {
  return typeof value === 'string' && PARCEL_STATUSES.includes(value as ParcelStatus)
}

function isTransitionStatus(value: unknown): value is CourierTransitionStatus {
  return typeof value === 'string' && TRANSITION_STATUSES.includes(value as CourierTransitionStatus)
}

export function isParcelStatusChangeResponse(value: unknown): value is ParcelStatusChangeResponse {
  return (
    isRecord(value) &&
    typeof value.parcelId === 'string' &&
    typeof value.trackingNumber === 'string' &&
    isParcelStatus(value.previousStatus) &&
    isTransitionStatus(value.currentStatus) &&
    Number.isSafeInteger(value.stateVersion) &&
    Number.isSafeInteger(value.updatedParcelVersion) &&
    typeof value.occurredAt === 'string'
  )
}

function parseProblem(value: unknown): ProblemDetail | undefined {
  if (
    isRecord(value) &&
    typeof value.title === 'string' &&
    typeof value.status === 'number'
  ) {
    return value as ProblemDetail
  }
  return undefined
}

async function readProblem(response: Response): Promise<ProblemDetail | undefined> {
  try {
    return parseProblem(await response.json())
  } catch {
    return undefined
  }
}

export function createCourierStatusApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: request = globalThis.fetch,
  timeoutMs = 10_000,
}: CourierStatusApiOptions) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  return {
    async updateStatus(
      parcelId: string,
      command: UpdateStatusRequest,
      signal?: AbortSignal,
    ): Promise<ParcelStatusChangeResponse> {
      if (!parcelId.trim() || !Number.isSafeInteger(command.expectedVersion) || command.expectedVersion < 0) {
        throw new CourierStatusError('validation-failed', 'A parcel and current version are required.')
      }

      let token: string | null
      try {
        token = await getAccessToken()
      } catch {
        throw new CourierStatusError('auth-required', 'Authentication session is unavailable.')
      }
      if (!token?.trim()) {
        throw new CourierStatusError('auth-required', 'Authentication token is missing. Please sign in again.')
      }

      const controller = new AbortController()
      const abort = () => controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) controller.abort()
      let timedOut = false
      const timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, timeoutMs)

      const trimmedNote = command.note?.trim()
      const body: UpdateStatusRequest = {
        newStatus: command.newStatus,
        expectedVersion: command.expectedVersion,
        ...(trimmedNote ? { note: trimmedNote } : {}),
        ...(command.reason ? { reason: command.reason } : {}),
        ...(command.proofId ? { proofId: command.proofId } : {}),
      }

      let response: Response
      try {
        response = await request(
          `${normalizedGateway}/api/v1/parcels/${encodeURIComponent(parcelId.trim())}/status`,
          {
            method: 'PATCH',
            headers: {
              Accept: 'application/json, application/problem+json',
              Authorization: `Bearer ${token}`,
              'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
            credentials: 'omit',
            cache: 'no-store',
            signal: controller.signal,
          },
        )
      } catch {
        throw new CourierStatusError(
          'ambiguous-outcome',
          timedOut
            ? 'The status request timed out after submission. Its outcome must be verified.'
            : 'The connection ended after submission. Its outcome must be verified.',
        )
      } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
      }

      if (!response.ok) {
        const problem = await readProblem(response)
        const detail = problem?.detail || problem?.title
        if (response.status === 400) {
          throw new CourierStatusError('validation-failed', detail || 'The status command was invalid.', 400, problem)
        }
        if (response.status === 401) {
          throw new CourierStatusError('auth-required', 'Session expired. Please sign in again.', 401, problem)
        }
        if (response.status === 403) {
          throw new CourierStatusError('forbidden', detail || 'You are no longer authorized for this parcel.', 403, problem)
        }
        if (response.status === 404) {
          throw new CourierStatusError('not-found', 'Parcel not found or no longer accessible.', 404, problem)
        }
        if (response.status === 409) {
          throw new CourierStatusError('conflict', detail || 'The parcel was updated elsewhere.', 409, problem)
        }
        if (response.status === 422) {
          throw new CourierStatusError('illegal-transition', detail || 'That lifecycle transition is no longer permitted.', 422, problem)
        }
        throw new CourierStatusError(
          'service-unavailable',
          detail || `Delivery service returned HTTP ${response.status}.`,
          response.status,
          problem,
        )
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new CourierStatusError(
          'ambiguous-outcome',
          'The server accepted the request but returned invalid JSON. Its outcome must be verified.',
          response.status,
        )
      }
      if (!isParcelStatusChangeResponse(payload) || payload.parcelId !== parcelId) {
        throw new CourierStatusError(
          'ambiguous-outcome',
          'The server response did not match the status contract. Its outcome must be verified.',
          response.status,
        )
      }
      return payload
    },
  }
}
