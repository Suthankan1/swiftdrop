import type {
  CreateWebhookSubscriptionRequest,
  ProblemDetail,
  WebhookDeliveryLogListResponse,
  WebhookSubscription,
  WebhookSubscriptionCreated,
  WebhookSubscriptionListResponse,
} from './webhookContracts'

export type CommunicationsFailure =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'validation-error'
  | 'unavailable'
  | 'invalid-response'

export class CommunicationsError extends Error {
  readonly kind: CommunicationsFailure
  readonly problemDetail?: ProblemDetail
  readonly statusCode?: number

  constructor(
    kind: CommunicationsFailure,
    message?: string,
    problemDetail?: ProblemDetail,
    statusCode?: number,
  ) {
    super(message || problemDetail?.detail || problemDetail?.title || 'Communications service request failed.')
    this.name = 'CommunicationsError'
    this.kind = kind
    this.problemDetail = problemDetail
    this.statusCode = statusCode
  }
}

export interface DestinationValidationResult {
  valid: boolean
  error?: string
}

/**
 * Validates webhook destination URLs per DEC-COM-01 & SSRF protection:
 * - Must be a valid URI starting with https:// (TLS 1.2+).
 * - Plain http:// is rejected.
 * - Localhost, loopback (127.0.0.1, ::1), and RFC 1918 private IPv4/IPv6 ranges are rejected.
 * - Non-routable internal domains (.local, .internal, .localhost) are rejected.
 */
export function validateWebhookDestination(urlString: string): DestinationValidationResult {
  const trimmed = urlString.trim()
  if (!trimmed) {
    return { valid: false, error: 'Destination URL is required.' }
  }

  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return { valid: false, error: 'Destination must be a well-formed URL (e.g., https://api.example.com/webhooks).' }
  }

  if (parsed.protocol !== 'https:') {
    return {
      valid: false,
      error: 'Only secure HTTPS destinations are permitted (TLS 1.2+). Plain HTTP is rejected.',
    }
  }

  const hostname = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, '')

  // Reject localhost and loopback identifiers
  if (
    hostname === 'localhost' ||
    hostname === '127.0.0.1' ||
    hostname === '::1' ||
    hostname === '0.0.0.0' ||
    hostname.endsWith('.localhost') ||
    hostname.endsWith('.local') ||
    hostname.endsWith('.internal')
  ) {
    return {
      valid: false,
      error: 'Localhost and private internal destinations are rejected for security (SSRF prevention).',
    }
  }

  // Reject RFC 1918 private IPv4 addresses and link-local ranges
  // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, 169.254.0.0/16
  const ipv4Pattern = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/
  const match = hostname.match(ipv4Pattern)
  if (match && match[1] && match[2]) {
    const octet1 = parseInt(match[1], 10)
    const octet2 = parseInt(match[2], 10)


    if (
      octet1 === 10 ||
      octet1 === 127 ||
      octet1 === 0 ||
      (octet1 === 172 && octet2 >= 16 && octet2 <= 31) ||
      (octet1 === 192 && octet2 === 168) ||
      (octet1 === 169 && octet2 === 254)
    ) {
      return {
        valid: false,
        error: 'Private and link-local IP addresses are rejected for security (SSRF prevention).',
      }
    }
  }

  // Reject IPv6 private / link-local addresses
  if (
    hostname.startsWith('fe80:') ||
    hostname.startsWith('fc00:') ||
    hostname.startsWith('fd00:')
  ) {
    return {
      valid: false,
      error: 'Private IPv6 addresses are rejected for security (SSRF prevention).',
    }
  }

  return { valid: true }
}

interface CommunicationsOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isWebhookSubscription(value: unknown): value is WebhookSubscription {
  return (
    isRecord(value) &&
    typeof value.subscriptionId === 'string' &&
    typeof value.merchantId === 'string' &&
    typeof value.httpsUrl === 'string' &&
    Array.isArray(value.allowedEventTypes) &&
    value.allowedEventTypes.every((t) => typeof t === 'string') &&
    typeof value.active === 'boolean' &&
    typeof value.createdAt === 'string'
  )
}

function isWebhookSubscriptionCreated(value: unknown): value is WebhookSubscriptionCreated {
  return (
    isWebhookSubscription(value) &&
    typeof (value as unknown as Record<string, unknown>).hmacSigningSecret === 'string'
  )
}


function isWebhookSubscriptionListResponse(value: unknown): value is WebhookSubscriptionListResponse {
  return (
    isRecord(value) &&
    Array.isArray(value.subscriptions) &&
    value.subscriptions.every(isWebhookSubscription)
  )
}

function isWebhookDeliveryItem(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.deliveryId === 'string' &&
    typeof value.eventId === 'string' &&
    typeof value.parcelId === 'string' &&
    typeof value.status === 'string' &&
    typeof value.attemptCount === 'number' &&
    typeof value.lastAttemptAt === 'string'
  )
}

function isWebhookDeliveryLogListResponse(value: unknown): value is WebhookDeliveryLogListResponse {
  return (
    isRecord(value) &&
    typeof value.totalElements === 'number' &&
    Array.isArray(value.deliveries) &&
    value.deliveries.every(isWebhookDeliveryItem)
  )
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail | undefined> {
  try {
    const data = await response.json()
    if (isRecord(data) && typeof data.title === 'string' && typeof data.status === 'number') {
      return data as unknown as ProblemDetail
    }
  } catch {
    // Ignore JSON parsing errors for non-JSON bodies
  }
  return undefined
}

export function createCommunicationsApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: CommunicationsOptions) {
  const baseUrl = `${gatewayUrl.replace(/\/$/, '')}/api/v1/merchants/webhooks`

  async function resolveAuthToken(): Promise<string> {
    let token: string | null
    try {
      token = await getAccessToken()
    } catch {
      throw new CommunicationsError('auth-required', 'Authentication required.')
    }
    if (!token?.trim()) {
      throw new CommunicationsError('auth-required', 'Authentication token is missing.')
    }
    return token
  }

  return {
    /**
     * Lists registered webhook endpoints for the calling merchant tenant.
     * Corresponds to IF-REST-11 / GET /api/v1/merchants/webhooks.
     */
    async listSubscriptions(signal?: AbortSignal): Promise<WebhookSubscription[]> {
      const token = await resolveAuthToken()

      let response: Response
      try {
        response = await fetchRequest(baseUrl, {
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
        throw new CommunicationsError('unavailable', 'Communications service is currently unreachable.')
      }

      if (response.status === 401) throw new CommunicationsError('auth-required', 'Session expired or unauthenticated.')
      if (response.status === 403) throw new CommunicationsError('forbidden', 'Access denied. Merchant authorization required.')
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('unavailable', problem?.detail || 'Failed to list webhook subscriptions.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new CommunicationsError('invalid-response', 'Invalid JSON response from server.')
      }

      if (!isWebhookSubscriptionListResponse(data)) {
        throw new CommunicationsError('invalid-response', 'Server response did not match WebhookSubscriptionList schema.')
      }

      return data.subscriptions
    },

    /**
     * Retrieves configuration of a specific webhook subscription.
     * Corresponds to GET /api/v1/merchants/webhooks/{subscriptionId}.
     */
    async getSubscription(subscriptionId: string, signal?: AbortSignal): Promise<WebhookSubscription> {
      const token = await resolveAuthToken()

      let response: Response
      try {
        response = await fetchRequest(`${baseUrl}/${encodeURIComponent(subscriptionId)}`, {
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
        throw new CommunicationsError('unavailable', 'Communications service unreachable.')
      }

      if (response.status === 401) throw new CommunicationsError('auth-required')
      if (response.status === 403) throw new CommunicationsError('forbidden')
      if (response.status === 404) throw new CommunicationsError('not-found', 'Webhook subscription not found.')
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('unavailable', problem?.detail, problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new CommunicationsError('invalid-response')
      }

      if (!isWebhookSubscription(data)) {
        throw new CommunicationsError('invalid-response')
      }

      return data
    },

    /**
     * Registers a new HTTPS webhook destination and returns the shared HMAC signing secret.
     * Corresponds to IF-REST-11 / POST /api/v1/merchants/webhooks.
     */
    async createSubscription(
      request: CreateWebhookSubscriptionRequest,
      signal?: AbortSignal,
    ): Promise<WebhookSubscriptionCreated> {
      // Client-side pre-validation
      const urlCheck = validateWebhookDestination(request.httpsUrl)
      if (!urlCheck.valid) {
        throw new CommunicationsError('validation-error', urlCheck.error)
      }
      if (!Array.isArray(request.allowedEventTypes) || request.allowedEventTypes.length === 0) {
        throw new CommunicationsError('validation-error', 'At least one supported event type must be selected.')
      }

      const token = await resolveAuthToken()

      let response: Response
      try {
        response = await fetchRequest(baseUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify(request),
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new CommunicationsError('unavailable', 'Communications service is unreachable.')
      }

      if (response.status === 400) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('validation-error', problem?.detail || 'Invalid webhook registration request.', problem, 400)
      }
      if (response.status === 401) throw new CommunicationsError('auth-required')
      if (response.status === 403) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('forbidden', problem?.detail || 'Merchant account is not authorized or approval is pending.', problem, 403)
      }
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('unavailable', problem?.detail || 'Failed to create webhook subscription.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new CommunicationsError('invalid-response', 'Server returned invalid JSON on subscription creation.')
      }

      if (!isWebhookSubscriptionCreated(data)) {
        throw new CommunicationsError('invalid-response', 'Response did not conform to WebhookSubscriptionCreated schema.')
      }

      return data
    },

    /**
     * Deactivates or removes a webhook subscription.
     * Corresponds to DELETE /api/v1/merchants/webhooks/{subscriptionId}.
     */
    async deleteSubscription(subscriptionId: string, signal?: AbortSignal): Promise<void> {
      const token = await resolveAuthToken()

      let response: Response
      try {
        response = await fetchRequest(`${baseUrl}/${encodeURIComponent(subscriptionId)}`, {
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
        throw new CommunicationsError('unavailable', 'Communications service unreachable.')
      }

      if (response.status === 401) throw new CommunicationsError('auth-required')
      if (response.status === 403) throw new CommunicationsError('forbidden', 'Caller does not own this subscription.')
      if (response.status === 404) throw new CommunicationsError('not-found', 'Subscription already removed or does not exist.')
      if (response.status !== 204 && !response.ok) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('unavailable', problem?.detail || 'Failed to delete webhook subscription.', problem, response.status)
      }
    },

    /**
     * Inspects historical delivery outcomes and retry execution logs for a subscription.
     * Corresponds to IF-REST-12 / GET /api/v1/merchants/webhooks/{subscriptionId}/deliveries.
     */
    async listDeliveries(
      subscriptionId: string,
      page = 0,
      size = 20,
      signal?: AbortSignal,
    ): Promise<WebhookDeliveryLogListResponse> {
      const token = await resolveAuthToken()

      const url = `${baseUrl}/${encodeURIComponent(subscriptionId)}/deliveries?page=${encodeURIComponent(page)}&size=${encodeURIComponent(size)}`

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
        throw new CommunicationsError('unavailable', 'Communications service unreachable.')
      }

      if (response.status === 401) throw new CommunicationsError('auth-required')
      if (response.status === 403) throw new CommunicationsError('forbidden', 'Access denied to delivery logs.')
      if (response.status === 404) throw new CommunicationsError('not-found', 'Subscription not found.')
      if (!response.ok) {
        const problem = await parseProblemDetail(response)
        throw new CommunicationsError('unavailable', problem?.detail || 'Failed to retrieve delivery logs.', problem, response.status)
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new CommunicationsError('invalid-response')
      }

      if (!isWebhookDeliveryLogListResponse(data)) {
        throw new CommunicationsError('invalid-response', 'Server response did not match WebhookDeliveryLogList schema.')
      }

      return data
    },
  }
}
