import type { ProblemDetail } from './deliveryContracts'
import type {
  QuoteRequest,
  QuoteResponse,
  SizeCategory,
  ZoneItem,
  ZoneListResponse,
} from './pricingContracts'

export type PricingFailureKind =
  | 'invalid-input'
  | 'unprocessable-zone'
  | 'not-found'
  | 'unavailable'
  | 'invalid-response'

export class PricingError extends Error {
  readonly kind: PricingFailureKind
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: PricingFailureKind,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Pricing service request failed.')
    this.name = 'PricingError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

export interface PricingApiOptions {
  getAccessToken?: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

const VALID_SIZE_CATEGORIES: readonly SizeCategory[] = [
  'SMALL',
  'MEDIUM',
  'LARGE',
  'OVERSIZED',
]

export function isSizeCategory(value: unknown): value is SizeCategory {
  return typeof value === 'string' && VALID_SIZE_CATEGORIES.includes(value as SizeCategory)
}

export function isZoneItem(value: unknown): value is ZoneItem {
  return (
    isRecord(value) &&
    typeof value.zoneCode === 'string' &&
    typeof value.name === 'string' &&
    typeof value.description === 'string' &&
    typeof value.standardTransitHours === 'number'
  )
}

export function isZoneListResponse(value: unknown): value is ZoneListResponse {
  return (
    isRecord(value) &&
    typeof value.activeRateVersion === 'string' &&
    Array.isArray(value.zones) &&
    value.zones.every(isZoneItem)
  )
}

export function isQuoteResponse(value: unknown): value is QuoteResponse {
  return (
    isRecord(value) &&
    typeof value.quoteId === 'string' &&
    typeof value.rateVersion === 'string' &&
    typeof value.pickupZone === 'string' &&
    typeof value.dropoffZone === 'string' &&
    typeof value.weightKg === 'number' &&
    typeof value.volumetricWeightKg === 'number' &&
    typeof value.chargeableWeightKg === 'number' &&
    isSizeCategory(value.sizeCategory) &&
    typeof value.baseFeeMinorUnits === 'number' &&
    typeof value.weightSurchargeMinorUnits === 'number' &&
    typeof value.totalAmountMinorUnits === 'number' &&
    typeof value.currency === 'string' &&
    typeof value.createdAt === 'string' &&
    typeof value.expiresAt === 'string' &&
    typeof value.isExpired === 'boolean'
  )
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

export function createPricingApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: PricingApiOptions = {}) {
  const normalizedGateway = gatewayUrl.replace(/\/$/, '')

  async function resolveOptionalAuthHeaders(): Promise<Record<string, string>> {
    if (!getAccessToken) return {}
    try {
      const token = await getAccessToken()
      if (token?.trim()) {
        return { Authorization: `Bearer ${token.trim()}` }
      }
    } catch {
      // Optional bearer token resolution failure does not block unauthenticated pricing
    }
    return {}
  }

  return {
    /**
     * List supported delivery zones and rate tables (openapi-pricing.yaml).
     */
    async listZones(signal?: AbortSignal): Promise<ZoneListResponse> {
      const authHeaders = await resolveOptionalAuthHeaders()
      const url = `${normalizedGateway}/api/v1/zones`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
            ...authHeaders,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new PricingError('unavailable', 'Pricing zones service is currently unreachable.')
      }

      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new PricingError(
          'unavailable',
          `Pricing zones request failed (${response.status}).`,
          problem,
          response.status,
        )
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new PricingError('invalid-response', 'Invalid JSON received for delivery zones.')
      }

      if (!isZoneListResponse(payload)) {
        throw new PricingError('invalid-response', 'Zones response does not match the approved schema.')
      }

      return payload
    },

    /**
     * Request an authoritative delivery pricing quote (IF-REST-02, openapi-pricing.yaml).
     */
    async calculateQuote(request: QuoteRequest, signal?: AbortSignal): Promise<QuoteResponse> {
      const authHeaders = await resolveOptionalAuthHeaders()
      const url = `${normalizedGateway}/api/v1/quotes`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json, application/problem+json',
            ...authHeaders,
          },
          body: JSON.stringify(request),
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new PricingError('unavailable', 'Pricing quote service is currently unreachable.')
      }

      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new PricingError(
          'invalid-input',
          problem?.detail || 'Invalid parameters supplied for delivery quote.',
          problem,
          400,
        )
      }

      if (response.status === 422) {
        const problem = await parseProblemDetail(response)
        throw new PricingError(
          'unprocessable-zone',
          problem?.detail || 'Unprocessable zone or routing combination.',
          problem,
          422,
        )
      }

      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new PricingError(
          'unavailable',
          `Quote calculation request failed (${response.status}).`,
          problem,
          response.status,
        )
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new PricingError('invalid-response', 'Invalid JSON received for calculated quote.')
      }

      if (!isQuoteResponse(payload)) {
        throw new PricingError('invalid-response', 'Quote response does not match the approved schema.')
      }

      return payload
    },

    /**
     * Retrieve an existing pricing quote by quote ID (openapi-pricing.yaml).
     */
    async getQuoteById(quoteId: string, signal?: AbortSignal): Promise<QuoteResponse> {
      const authHeaders = await resolveOptionalAuthHeaders()
      const url = `${normalizedGateway}/api/v1/quotes/${encodeURIComponent(quoteId)}`

      let response: Response
      try {
        response = await fetchRequest(url, {
          method: 'GET',
          headers: {
            Accept: 'application/json, application/problem+json',
            ...authHeaders,
          },
          credentials: 'omit',
          cache: 'no-store',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new PricingError('unavailable', 'Pricing quote service is currently unreachable.')
      }

      if (response.status === 404) {
        const problem = await parseProblemDetail(response)
        throw new PricingError('not-found', 'Quote not found or expired.', problem, 404)
      }

      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new PricingError(
          'unavailable',
          `Quote retrieval failed (${response.status}).`,
          problem,
          response.status,
        )
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch {
        throw new PricingError('invalid-response', 'Invalid JSON received for quote retrieval.')
      }

      if (!isQuoteResponse(payload)) {
        throw new PricingError('invalid-response', 'Retrieved quote does not match the approved schema.')
      }

      return payload
    },
  }
}
