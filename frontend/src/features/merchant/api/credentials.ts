import type {
  ApiKeyCreatedResponse,
  ApiKeyScope,
  ApiKeySummaryItem,
  CreateApiKeyRequest,
  ProblemDetail,
} from './credentialsContracts'

export type CredentialsFailure =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'validation-error'
  | 'unavailable'
  | 'invalid-response'

export class MerchantCredentialsError extends Error {
  readonly kind: CredentialsFailure
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: CredentialsFailure,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Merchant credentials request failed.')
    this.name = 'MerchantCredentialsError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

export const PERMITTED_API_KEY_SCOPES: readonly ApiKeyScope[] = [
  'parcels:create',
  'parcels:read',
  'webhooks:manage',
]

interface CredentialsApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function normalizeApiKeySummaryItem(raw: unknown): ApiKeySummaryItem | null {
  if (!isRecord(raw)) return null
  const keyId = typeof raw.keyId === 'string' ? raw.keyId : typeof raw.id === 'string' ? raw.id : null
  const name = typeof raw.name === 'string' ? raw.name : null
  const keyPrefix = typeof raw.keyPrefix === 'string' ? raw.keyPrefix : null
  const createdAt = typeof raw.createdAt === 'string' ? raw.createdAt : null
  const expiresAt = typeof raw.expiresAt === 'string' ? raw.expiresAt : null
  const scopes = Array.isArray(raw.scopes) && raw.scopes.every((s) => typeof s === 'string') ? raw.scopes : []

  if (!keyId || !name || !keyPrefix || !createdAt || !expiresAt) {
    return null
  }

  const isRevoked =
    typeof raw.isRevoked === 'boolean'
      ? raw.isRevoked
      : Boolean(raw.revokedAt)

  return {
    keyId,
    name,
    keyPrefix,
    scopes,
    createdAt,
    expiresAt,
    isRevoked,
  }
}

function normalizeApiKeyCreatedResponse(raw: unknown): ApiKeyCreatedResponse | null {
  if (!isRecord(raw)) return null
  const keyId = typeof raw.keyId === 'string' ? raw.keyId : typeof raw.id === 'string' ? raw.id : null
  const name = typeof raw.name === 'string' ? raw.name : null
  const keyPrefix = typeof raw.keyPrefix === 'string' ? raw.keyPrefix : null
  const plaintextKey =
    typeof raw.plaintextKey === 'string' ? raw.plaintextKey : typeof raw.key === 'string' ? raw.key : null
  const createdAt = typeof raw.createdAt === 'string' ? raw.createdAt : null
  const expiresAt = typeof raw.expiresAt === 'string' ? raw.expiresAt : null
  const scopes = Array.isArray(raw.scopes) && raw.scopes.every((s) => typeof s === 'string') ? raw.scopes : []

  if (!keyId || !name || !keyPrefix || !plaintextKey || !createdAt || !expiresAt) {
    return null
  }

  return {
    keyId,
    name,
    keyPrefix,
    plaintextKey,
    scopes,
    createdAt,
    expiresAt,
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

export function createMerchantCredentialsApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: CredentialsApiOptions) {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1/merchants`

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new MerchantCredentialsError('auth-required', 'Authentication required.')
    }
    if (!token?.trim()) {
      throw new MerchantCredentialsError('auth-required', 'Authentication token is missing.')
    }
    return token
  }

  return {
    /**
     * Lists active and revoked API key metadata for the specified merchant tenant.
     * Corresponds to GET /api/v1/merchants/{id}/api-keys.
     * Plaintext keys are NEVER exposed in this endpoint.
     */
    async listApiKeys(merchantId: string, signal?: AbortSignal): Promise<ApiKeySummaryItem[]> {
      const token = await resolveAuthToken()
      const url = `${base}/${encodeURIComponent(merchantId)}/api-keys`

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
        throw new MerchantCredentialsError('unavailable', 'Accounts service is currently unreachable.')
      }

      if (response.status === 401) throw new MerchantCredentialsError('auth-required', 'Session expired or unauthenticated.')
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('forbidden', problem?.detail || 'Access denied to merchant credentials.', problem, 403)
      }
      if (response.status === 404) throw new MerchantCredentialsError('not-found', 'Merchant tenant not found.', undefined, 404)
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('unavailable', problem?.detail || 'Failed to list API keys.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantCredentialsError('invalid-response', 'Invalid JSON response from server.')
      }

      if (!isRecord(data) || !Array.isArray(data.keys)) {
        throw new MerchantCredentialsError('invalid-response', 'Response did not conform to ApiKeyListResponse schema.')
      }

      const items: ApiKeySummaryItem[] = []
      for (const item of data.keys) {
        const normalized = normalizeApiKeySummaryItem(item)
        if (!normalized) {
          throw new MerchantCredentialsError('invalid-response', 'Invalid API key item in list response.')
        }
        items.push(normalized)
      }

      return items
    },

    /**
     * Generates a new machine API key for an approved merchant tenant.
     * Corresponds to POST /api/v1/merchants/{id}/api-keys.
     * The plaintext secret is returned ONCE in this response.
     */
    async generateApiKey(
      merchantId: string,
      request: CreateApiKeyRequest,
      signal?: AbortSignal,
    ): Promise<ApiKeyCreatedResponse> {
      const trimmedName = request.name?.trim()
      if (!trimmedName) {
        throw new MerchantCredentialsError('validation-error', 'Key name is required.')
      }
      if (trimmedName.length > 100) {
        throw new MerchantCredentialsError('validation-error', 'Key name cannot exceed 100 characters.')
      }
      if (!Array.isArray(request.scopes) || request.scopes.length === 0) {
        throw new MerchantCredentialsError('validation-error', 'At least one scope must be selected.')
      }
      for (const scope of request.scopes) {
        if (!PERMITTED_API_KEY_SCOPES.includes(scope as ApiKeyScope)) {
          throw new MerchantCredentialsError('validation-error', `Invalid scope: ${scope}`)
        }
      }
      if (
        request.expiresInDays !== undefined &&
        (request.expiresInDays < 1 || request.expiresInDays > 365)
      ) {
        throw new MerchantCredentialsError('validation-error', 'Expiration must be between 1 and 365 days.')
      }

      const token = await resolveAuthToken()
      const url = `${base}/${encodeURIComponent(merchantId)}/api-keys`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({
            name: trimmedName,
            scopes: request.scopes,
            expiresInDays: request.expiresInDays ?? 90,
          }),
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantCredentialsError('unavailable', 'Accounts service is unreachable.')
      }

      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('validation-error', problem?.detail || 'Invalid API key request parameters.', problem, 400)
      }
      if (response.status === 401) throw new MerchantCredentialsError('auth-required', 'Session expired.')
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('forbidden', problem?.detail || 'Merchant is not authorized or approval is pending.', problem, 403)
      }
      if (response.status === 404) throw new MerchantCredentialsError('not-found', 'Merchant tenant not found.', undefined, 404)
      if (response.status === 409) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError(
          'conflict',
          problem?.detail || 'Active API key limit reached (maximum 3 keys). Revoke an existing key to issue a replacement.',
          problem,
          409,
        )
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('unavailable', problem?.detail || 'Failed to generate API key.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new MerchantCredentialsError('invalid-response', 'Server returned invalid JSON on key generation.')
      }

      const normalized = normalizeApiKeyCreatedResponse(data)
      if (!normalized) {
        throw new MerchantCredentialsError('invalid-response', 'Response did not conform to ApiKeyCreatedResponse schema.')
      }

      return normalized
    },

    /**
     * Durably revokes an API key for a merchant tenant.
     * Corresponds to DELETE /api/v1/merchants/{id}/api-keys/{keyId}.
     * Idempotent: repeated revocation safely returns 204.
     */
    async revokeApiKey(merchantId: string, keyId: string, signal?: AbortSignal): Promise<void> {
      const token = await resolveAuthToken()
      const url = `${base}/${encodeURIComponent(merchantId)}/api-keys/${encodeURIComponent(keyId)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'DELETE',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'application/json',
          },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new MerchantCredentialsError('unavailable', 'Accounts service is unreachable.')
      }

      if (response.status === 401) throw new MerchantCredentialsError('auth-required')
      if (response.status === 403) throw new MerchantCredentialsError('forbidden', 'Caller does not own this merchant tenant.')
      if (response.status === 404) throw new MerchantCredentialsError('not-found', 'API key not found.')
      if (response.status !== 204 && !response.ok) {
        const problem = await parseProblemDetail(response)
        throw new MerchantCredentialsError('unavailable', problem?.detail || 'Failed to revoke API key.', problem, response.status)
      }
    },
  }
}
