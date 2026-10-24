import { describe, expect, it, vi } from 'vitest'
import {
  CommunicationsError,
  createCommunicationsApi,
  validateWebhookDestination,
} from './webhooks'
import type {
  CreateWebhookSubscriptionRequest,
  WebhookDeliveryLogListResponse,
  WebhookSubscription,
  WebhookSubscriptionCreated,
} from './webhookContracts'

describe('validateWebhookDestination (SSRF & TLS defense)', () => {
  it('accepts valid public HTTPS endpoints', () => {
    expect(validateWebhookDestination('https://api.merchant.com/webhooks')).toEqual({ valid: true })
    expect(validateWebhookDestination('https://sub.domain.co.uk/events/v1')).toEqual({ valid: true })
    expect(validateWebhookDestination('https://callbacks.store.com:8443/swiftdrop')).toEqual({ valid: true })
  })

  it('rejects empty or whitespace destinations', () => {
    const res = validateWebhookDestination('   ')
    expect(res.valid).toBe(false)
    expect(res.error).toMatch(/required/i)
  })

  it('rejects malformed URLs', () => {
    const res = validateWebhookDestination('not-a-valid-url')
    expect(res.valid).toBe(false)
    expect(res.error).toMatch(/well-formed URL/i)
  })

  it('strictly rejects non-HTTPS schemes', () => {
    const httpRes = validateWebhookDestination('http://api.merchant.com/webhooks')
    expect(httpRes.valid).toBe(false)
    expect(httpRes.error).toMatch(/only secure https/i)

    const ftpRes = validateWebhookDestination('ftp://api.merchant.com/webhooks')
    expect(ftpRes.valid).toBe(false)
    expect(ftpRes.error).toMatch(/only secure https/i)
  })

  it('rejects localhost, loopback, and local/internal hostnames', () => {
    const hostnames = [
      'https://localhost/webhooks',
      'https://localhost:3000/webhook',
      'https://127.0.0.1/callback',
      'https://[::1]/callback',
      'https://0.0.0.0/callback',
      'https://service.localhost/hook',
      'https://backend.local/hook',
      'https://internal.internal/hook',
    ]

    for (const url of hostnames) {
      const res = validateWebhookDestination(url)
      expect(res.valid, `Expected ${url} to be rejected`).toBe(false)
      expect(res.error).toMatch(/localhost and private/i)
    }
  })

  it('rejects RFC 1918 private IPv4 and link-local addresses', () => {
    const privateIps = [
      'https://10.0.0.1/hook',
      'https://10.255.0.1/hook',
      'https://172.16.0.1/hook',
      'https://172.31.255.254/hook',
      'https://192.168.1.1/hook',
      'https://192.168.100.50/hook',
      'https://169.254.169.254/metadata', // AWS link-local metadata protection
    ]

    for (const url of privateIps) {
      const res = validateWebhookDestination(url)
      expect(res.valid, `Expected ${url} to be rejected`).toBe(false)
      expect(res.error).toMatch(/private and link-local ip/i)
    }
  })
})

describe('createCommunicationsApi', () => {
  const dummySubscription: WebhookSubscription = {
    subscriptionId: 'sub-uuid-1',
    merchantId: 'merch-uuid-1',
    httpsUrl: 'https://warehouse.example.com/callbacks',
    allowedEventTypes: ['parcel.status.changed.v1'],
    active: true,
    createdAt: '2026-10-24T00:00:00Z',
  }

  const dummyCreated: WebhookSubscriptionCreated = {
    ...dummySubscription,
    hmacSigningSecret: 'mock-whsec-test-secret-123',
  }


  it('listSubscriptions returns subscriptions on 200', async () => {
    const mockFetch = vi.fn(async () =>
      new Response(JSON.stringify({ subscriptions: [dummySubscription] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: mockFetch,
    })

    const list = await api.listSubscriptions()
    expect(list).toEqual([dummySubscription])
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/merchants/webhooks',
      expect.objectContaining({
        method: 'GET',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer test-token',
        },
      }),
    )
  })

  it('listSubscriptions handles 401 unauthenticated and 403 forbidden', async () => {
    const unauthFetch = vi.fn(async () => new Response(null, { status: 401 }))
    const api1 = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: unauthFetch,
    })
    await expect(api1.listSubscriptions()).rejects.toThrow(CommunicationsError)

    const forbiddenFetch = vi.fn(async () => new Response(null, { status: 403 }))
    const api2 = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: forbiddenFetch,
    })
    await expect(api2.listSubscriptions()).rejects.toThrow(CommunicationsError)
  })

  it('createSubscription validates destination before dispatching request', async () => {
    const mockFetch = vi.fn()
    const api = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: mockFetch,
    })

    const invalidRequest: CreateWebhookSubscriptionRequest = {
      httpsUrl: 'http://insecure.example.com',
      allowedEventTypes: ['parcel.status.changed.v1'],
    }

    await expect(api.createSubscription(invalidRequest)).rejects.toThrow(/only secure https/i)
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('createSubscription posts JSON body and returns created secret on 201', async () => {
    const mockFetch = vi.fn(async () =>
      new Response(JSON.stringify(dummyCreated), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: mockFetch,
    })

    const req: CreateWebhookSubscriptionRequest = {
      httpsUrl: 'https://warehouse.example.com/callbacks',
      allowedEventTypes: ['parcel.status.changed.v1'],
    }

    const created = await api.createSubscription(req)
    expect(created.hmacSigningSecret).toBe('mock-whsec-test-secret-123')
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/merchants/webhooks',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          Authorization: 'Bearer test-token',
        },
        body: JSON.stringify(req),
      }),
    )
  })

  it('deleteSubscription issues DELETE request and succeeds on 204', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 204 }))

    const api = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: mockFetch,
    })

    await expect(api.deleteSubscription('sub-uuid-1')).resolves.toBeUndefined()
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/merchants/webhooks/sub-uuid-1',
      expect.objectContaining({
        method: 'DELETE',
        headers: {
          Authorization: 'Bearer test-token',
          Accept: 'application/json',
        },
      }),
    )
  })

  it('listDeliveries fetches paged delivery logs', async () => {
    const dummyDeliveries: WebhookDeliveryLogListResponse = {
      totalElements: 1,
      deliveries: [
        {
          deliveryId: 'deliv-1',
          eventId: 'evt-1',
          parcelId: 'parcel-1',
          status: 'SUCCESS',
          attemptCount: 1,
          lastAttemptAt: '2026-10-24T00:05:00Z',
          lastHttpStatusCode: 200,
          lastResponseTimeMs: 120,
          errorSummary: null,
        },
      ],
    }

    const mockFetch = vi.fn(async () =>
      new Response(JSON.stringify(dummyDeliveries), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createCommunicationsApi({
      getAccessToken: async () => 'test-token',
      fetch: mockFetch,
    })

    const result = await api.listDeliveries('sub-uuid-1', 0, 10)
    expect(result.totalElements).toBe(1)
    expect(result.deliveries[0]?.status).toBe('SUCCESS')

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/merchants/webhooks/sub-uuid-1/deliveries?page=0&size=10',
      expect.objectContaining({ method: 'GET' }),
    )
  })
})
