import { describe, expect, it, vi } from 'vitest'
import { createMerchantReviewApi, MerchantReviewError } from './merchantReview'

describe('createMerchantReviewApi client', () => {
  it('calls GET /api/v1/admin/merchants with pagination and Bearer token', async () => {
    const mockPayload = {
      content: [
        {
          id: 'm-uuid-1',
          businessName: 'Lanka Stores',
          registrationNumber: 'REG-100',
          status: 'PENDING',
          submittedAt: '2026-10-27T10:00:00Z',
          reviewToken: '1',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    }

    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(mockPayload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createMerchantReviewApi({
      getAccessToken: async () => 'test-admin-token',
      gatewayUrl: 'http://localhost:8080',
      fetch: fetchMock,
    })

    const result = await api.list(0, 20)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/admin/merchants?page=0&size=20')
    expect(init.method).toBe('GET')
    expect(init.headers).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer test-admin-token',
    })
    expect(result.items).toHaveLength(1)
    expect(result.items[0]).toEqual({
      id: 'm-uuid-1',
      businessName: 'Lanka Stores',
      registrationNumber: 'REG-100',
      status: 'PENDING',
      submittedAt: '2026-10-27T10:00:00Z',
    })
    expect(result.page).toBe(0)
    expect(result.hasNext).toBe(false)
  })

  it('calls GET /api/v1/admin/merchants/{id} to fetch detail and audit', async () => {
    const mockDetail = {
      id: 'm-uuid-1',
      businessName: 'Lanka Stores',
      registrationNumber: 'REG-100',
      status: 'PENDING',
      submittedAt: '2026-10-27T10:00:00Z',
      reviewToken: '1',
      actions: [{ action: 'approve' }],
      audit: [
        {
          id: 'aud-1',
          actor: 'admin@swiftdrop.lk',
          action: 'ENROLLED',
          occurredAt: '2026-10-27T10:00:00Z',
        },
      ],
    }

    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(mockDetail), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createMerchantReviewApi({
      getAccessToken: async () => 'test-admin-token',
      gatewayUrl: 'http://localhost:8080',
      fetch: fetchMock,
    })

    const detail = await api.detail('m-uuid-1')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/admin/merchants/m-uuid-1')
    expect(init.method).toBe('GET')
    expect(detail.id).toBe('m-uuid-1')
    expect(detail.businessName).toBe('Lanka Stores')
    expect(detail.status).toBe('PENDING')
    expect(detail.reviewToken).toBe('1')
    expect(detail.actions).toHaveLength(1)
    expect(detail.audit).toHaveLength(1)
  })

  it('calls POST /api/v1/merchants/{id}/approve for approve command', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({}), { status: 200 }))

    const api = createMerchantReviewApi({
      getAccessToken: async () => 'test-admin-token',
      gatewayUrl: 'http://localhost:8080',
      fetch: fetchMock,
    })

    await api.change!({
      merchantId: 'm-uuid-1',
      action: 'approve',
      reviewToken: '1',
    }, new AbortController().signal)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/merchants/m-uuid-1/approve')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({
      'Content-Type': 'application/json',
      Accept: 'application/json',
      Authorization: 'Bearer test-admin-token',
    })
    expect(JSON.parse(init.body as string)).toEqual({ reviewToken: '1' })
  })

  it('calls POST /api/v1/merchants/{id}/suspend with reason for suspend command', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({}), { status: 200 }))

    const api = createMerchantReviewApi({
      getAccessToken: async () => 'test-admin-token',
      gatewayUrl: 'http://localhost:8080',
      fetch: fetchMock,
    })

    await api.change!({
      merchantId: 'm-uuid-1',
      action: 'suspend',
      reviewToken: '2',
      reason: 'Repeated billing failure',
    }, new AbortController().signal)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/merchants/m-uuid-1/suspend')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({
      reviewToken: '2',
      reason: 'Repeated billing failure',
    })
  })

  it('maps HTTP errors correctly', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('Forbidden', { status: 403 }))

    const api = createMerchantReviewApi({
      getAccessToken: async () => 'test-admin-token',
      fetch: fetchMock,
    })

    await expect(api.list(0, 20)).rejects.toThrow(MerchantReviewError)
    await expect(api.list(0, 20)).rejects.toMatchObject({ kind: 'forbidden' })
  })

  it('throws auth-required when getAccessToken returns null', async () => {
    const fetchMock = vi.fn()
    const api = createMerchantReviewApi({
      getAccessToken: async () => null,
      fetch: fetchMock,
    })

    await expect(api.list(0, 20)).rejects.toMatchObject({ kind: 'auth-required' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

