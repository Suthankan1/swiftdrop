import { describe, expect, it, vi } from 'vitest'
import {
  createMerchantEnrollmentApi,
  MerchantEnrollmentError,
} from './enrollment'

describe('Merchant Enrollment API Client', () => {
  const validToken = 'test-customer-token'

  it('enrollMerchant submits valid request and returns PENDING profile response', async () => {
    const mockCreated = {
      id: '11111111-2222-3333-4444-555555555555',
      keycloakSub: 'keycloak-sub-customer',
      businessName: 'Lanka Superstore Ltd',
      registrationNumber: 'PV-109923',
      status: 'PENDING',
      createdAt: '2026-10-24T10:00:00Z',
      updatedAt: '2026-10-24T10:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => mockCreated,
    })

    const api = createMerchantEnrollmentApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const result = await api.enrollMerchant({
      businessName: 'Lanka Superstore Ltd',
      registrationNumber: 'PV-109923',
    })

    expect(result.id).toBe('11111111-2222-3333-4444-555555555555')
    expect(result.businessName).toBe('Lanka Superstore Ltd')
    expect(result.status).toBe('PENDING')
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/merchants',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: `Bearer ${validToken}`,
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({
          businessName: 'Lanka Superstore Ltd',
          registrationNumber: 'PV-109923',
        }),
      }),
    )
  })

  it('enrollMerchant pre-validates business fields', async () => {
    const mockFetch = vi.fn()
    const api = createMerchantEnrollmentApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(api.enrollMerchant({ businessName: '  ', registrationNumber: 'PV-123' })).rejects.toBeInstanceOf(
      MerchantEnrollmentError,
    )

    await expect(api.enrollMerchant({ businessName: 'Store', registrationNumber: ' ' })).rejects.toMatchObject({
      kind: 'validation-error',
    })

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('enrollMerchant handles 409 Conflict when user already owns a tenant', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        title: 'Conflict',
        status: 409,
        detail: 'Authenticated user already owns a merchant tenant',
      }),
    })

    const api = createMerchantEnrollmentApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(
      api.enrollMerchant({ businessName: 'Store', registrationNumber: 'PV-123' }),
    ).rejects.toMatchObject({
      kind: 'conflict',
      statusCode: 409,
      message: 'Authenticated user already owns a merchant tenant',
    })
  })

  it('getCurrentMerchant returns merchant profile when bound', async () => {
    const mockRecord = {
      id: '11111111-2222-3333-4444-555555555555',
      keycloakSub: 'keycloak-sub-customer',
      businessName: 'Lanka Superstore Ltd',
      registrationNumber: 'PV-109923',
      status: 'PENDING',
      createdAt: '2026-10-24T10:00:00Z',
      updatedAt: '2026-10-24T10:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockRecord,
    })

    const api = createMerchantEnrollmentApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const profile = await api.getCurrentMerchant()
    expect(profile).not.toBeNull()
    expect(profile?.businessName).toBe('Lanka Superstore Ltd')
    expect(profile?.status).toBe('PENDING')
  })

  it('getCurrentMerchant returns null when user is unbound (404 Not Found)', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ title: 'Not Found', status: 404, detail: 'No merchant profile bound to current identity' }),
    })

    const api = createMerchantEnrollmentApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const profile = await api.getCurrentMerchant()
    expect(profile).toBeNull()
  })
})
