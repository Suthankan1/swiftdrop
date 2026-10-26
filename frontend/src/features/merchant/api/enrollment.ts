import { normalizeApprovalStatus } from './merchant'
import type {
  MerchantProfileResponse,
  MerchantRegistrationRequest,
} from './enrollmentContracts'
import type { ProblemDetail } from './credentialsContracts'

export type EnrollmentFailure =
  | 'auth-required'
  | 'forbidden'
  | 'conflict'
  | 'validation-error'
  | 'unavailable'
  | 'invalid-response'

export class MerchantEnrollmentError extends Error {
  readonly kind: EnrollmentFailure
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: EnrollmentFailure,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Merchant enrollment request failed.')
    this.name = 'MerchantEnrollmentError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

interface EnrollmentApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function normalizeMerchantProfileResponse(raw: unknown): MerchantProfileResponse | null {
  if (!isRecord(raw)) return null
  const id = typeof raw.id === 'string' ? raw.id : typeof raw.merchantId === 'string' ? raw.merchantId : null
  const keycloakSub = typeof raw.keycloakSub === 'string' ? raw.keycloakSub : ''
  const businessName = typeof raw.businessName === 'string' ? raw.businessName : null
  const registrationNumber = typeof raw.registrationNumber === 'string' ? raw.registrationNumber : null
  const rawStatus = typeof raw.status === 'string' ? raw.status : null
  const createdAt = typeof raw.createdAt === 'string' ? raw.createdAt : new Date().toISOString()
  const updatedAt = typeof raw.updatedAt === 'string' ? raw.updatedAt : createdAt

  if (!id || !businessName || !registrationNumber || !rawStatus) {
    return null
  }

  return {
    id,
    keycloakSub,
    businessName,
    registrationNumber,
    status: normalizeApprovalStatus(rawStatus),
    createdAt,
    updatedAt,
  }
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail | undefined> {
  try {
    const data = await response.json()
    if (isRecord(data) && typeof data.title === 'string' && typeof data.status === 'number') {
      return data as unknown as ProblemDetail
    }
  } catch {
    // Non-JSON response
  }
  return undefined
}

export function createMerchantEnrollmentApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: EnrollmentApiOptions) {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1/merchants`

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new MerchantEnrollmentError('auth-required', 'Authentication required.')
    }
    if (!token?.trim()) {
      throw new MerchantEnrollmentError('auth-required', 'Authentication token is missing.')
    }
    return token
  }

  return {
    /**
     * Submits self-service merchant organization enrollment.
     * Corresponds to POST /api/v1/merchants.
     * Server creates tenant in PENDING status.
     */
    async enrollMerchant(
      request: MerchantRegistrationRequest,
      signal?: AbortSignal,
    ): Promise<MerchantProfileResponse> {
      const trimmedBusinessName = request.businessName?.trim()
      const trimmedRegNumber = request.registrationNumber?.trim()

      if (!trimmedBusinessName || trimmedBusinessName.length < 3) {
        throw new MerchantEnrollmentError('validation-error', 'Business name must have at least 3 non-whitespace characters.')
      }
      if (trimmedBusinessName.length > 255) {
        throw new MerchantEnrollmentError('validation-error', 'Business name cannot exceed 255 characters.')
      }
      if (!trimmedRegNumber || trimmedRegNumber.length < 3) {
        throw new MerchantEnrollmentError('validation-error', 'Registration number must have at least 3 non-whitespace characters.')
      }
      if (trimmedRegNumber.length > 100) {
        throw new MerchantEnrollmentError('validation-error', 'Registration number cannot exceed 100 characters.')
      }

      const token = await resolveAuthToken()

      let response: Response
      try {
        response = await fetchRequest(base, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({
            businessName: trimmedBusinessName,
            registrationNumber: trimmedRegNumber,
          }),
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantEnrollmentError('unavailable', 'Accounts service is unreachable.')
      }

      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError('validation-error', problem?.detail || 'Invalid enrollment request parameters.', problem, 400)
      }
      if (response.status === 401) {
        throw new MerchantEnrollmentError('auth-required', 'Session expired or unauthenticated.')
      }
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError('forbidden', problem?.detail || 'Forbidden to enroll.', problem, 403)
      }
      if (response.status === 409) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError(
          'conflict',
          problem?.detail || 'You already own a merchant application or this registration number is already in use.',
          problem,
          409,
        )
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError('unavailable', problem?.detail || 'Failed to submit merchant enrollment.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantEnrollmentError('invalid-response', 'Server returned invalid JSON on enrollment.')
      }

      const normalized = normalizeMerchantProfileResponse(data)
      if (!normalized) {
        throw new MerchantEnrollmentError('invalid-response', 'Response did not conform to MerchantProfileResponse schema.')
      }

      return normalized
    },

    /**
     * Resolves the merchant tenant bound to the current authenticated caller.
     * Corresponds to GET /api/v1/merchants/me.
     * Returns null if 404 (user is not yet enrolled as a merchant).
     */
    async getCurrentMerchant(signal?: AbortSignal): Promise<MerchantProfileResponse | null> {
      const token = await resolveAuthToken()
      const url = `${base}/me`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantEnrollmentError('unavailable', 'Accounts service is unreachable.')
      }

      if (response.status === 401) throw new MerchantEnrollmentError('auth-required')
      if (response.status === 404) return null
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError('forbidden', problem?.detail || 'Access denied.', problem, 403)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new MerchantEnrollmentError('unavailable', problem?.detail || 'Failed to resolve current merchant.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantEnrollmentError('invalid-response')
      }

      const normalized = normalizeMerchantProfileResponse(data)
      if (!normalized) {
        throw new MerchantEnrollmentError('invalid-response')
      }

      return normalized
    },
  }
}
