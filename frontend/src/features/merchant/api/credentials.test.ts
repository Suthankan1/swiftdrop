import { describe, expect, it, vi } from 'vitest'
import {
  createMerchantCredentialsApi,
  MerchantCredentialsError,
} from './credentials'

describe('Merchant Credentials API Client', () => {
  const merchantId = 'c1a2b3c4-d5e6-7f8a-9b0c-1d2e3f4a5b6c'
  const keyId = 'e5f6a7b8-c9d0-1e2f-3a4b-5c6d7e8f9a0b'
  const validToken = 'test-bearer-token'

  it('listApiKeys retrieves and normalizes key metadata', async () => {
    const mockResponse = {
      keys: [
        {
          keyId,
          name: 'Warehouse ERP Key',
          keyPrefix: 'swd_live_7xK9',
          scopes: ['parcels:create', 'parcels:read'],
          createdAt: '2026-10-24T22:00:00Z',
          expiresAt: '2027-01-22T22:00:00Z',
          isRevoked: false,
        },
      ],
    }

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockResponse,
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const keys = await api.listApiKeys(merchantId)

    expect(keys).toHaveLength(1)
    expect(keys[0]!.keyId).toBe(keyId)
    expect(keys[0]!.name).toBe('Warehouse ERP Key')
    expect(keys[0]!.keyPrefix).toBe('swd_live_7xK9')
    expect(keys[0]!.isRevoked).toBe(false)
    expect(mockFetch).toHaveBeenCalledWith(
      `http://localhost:8080/api/v1/merchants/${merchantId}/api-keys`,
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({
          Authorization: `Bearer ${validToken}`,
          Accept: 'application/json',
        }),
      }),
    )
  })

  it('listApiKeys safely normalizes alternative keyId/id and revokedAt formats', async () => {
    const mockResponse = {
      keys: [
        {
          id: keyId,
          name: 'Legacy Key',
          keyPrefix: 'swd_live_3bA1',
          scopes: ['parcels:read'],
          createdAt: '2026-09-01T10:00:00Z',
          expiresAt: '2026-12-01T10:00:00Z',
          revokedAt: '2026-10-20T14:15:00Z',
        },
      ],
    }

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockResponse,
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const keys = await api.listApiKeys(merchantId)
    expect(keys[0]!.keyId).toBe(keyId)
    expect(keys[0]!.isRevoked).toBe(true)
  })

  it('listApiKeys throws auth-required on missing token or 401 response', async () => {
    const unauthApi = createMerchantCredentialsApi({
      getAccessToken: async () => null,
      fetch: vi.fn(),
    })

    await expect(unauthApi.listApiKeys(merchantId)).rejects.toThrow(MerchantCredentialsError)

    const fetch401 = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    const api401 = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: fetch401,
    })

    await expect(api401.listApiKeys(merchantId)).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('listApiKeys maps 403 Forbidden to forbidden error kind', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ title: 'Forbidden', status: 403, detail: 'Cross-tenant probe denied' }),
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(api.listApiKeys(merchantId)).rejects.toMatchObject({
      kind: 'forbidden',
      statusCode: 403,
      message: 'Cross-tenant probe denied',
    })
  })

  it('generateApiKey validates input before issuing request', async () => {
    const mockFetch = vi.fn()
    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    // Blank name
    await expect(api.generateApiKey(merchantId, { name: '', scopes: ['parcels:create'] })).rejects.toMatchObject({
      kind: 'validation-error',
    })

    // Empty scopes
    await expect(api.generateApiKey(merchantId, { name: 'Valid', scopes: [] })).rejects.toMatchObject({
      kind: 'validation-error',
    })

    // Invalid scope
    await expect(api.generateApiKey(merchantId, { name: 'Valid', scopes: ['invalid:scope'] })).rejects.toMatchObject({
      kind: 'validation-error',
    })

    // Out of range expiration
    await expect(api.generateApiKey(merchantId, { name: 'Valid', scopes: ['parcels:create'], expiresInDays: 400 })).rejects.toMatchObject({
      kind: 'validation-error',
    })

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('generateApiKey submits request and returns one-time plaintext key', async () => {
    const createdResponse = {
      keyId,
      name: 'Integration Bot',
      keyPrefix: 'swd_live_7a8b',
      plaintextKey: 'swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d',
      scopes: ['parcels:create'],
      createdAt: '2026-10-24T22:00:00Z',
      expiresAt: '2027-01-22T22:00:00Z',
    }

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => createdResponse,
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    const result = await api.generateApiKey(merchantId, {
      name: 'Integration Bot',
      scopes: ['parcels:create'],
      expiresInDays: 90,
    })

    expect(result.plaintextKey).toBe('swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d')
    expect(result.keyPrefix).toBe('swd_live_7a8b')
    expect(mockFetch).toHaveBeenCalledWith(
      `http://localhost:8080/api/v1/merchants/${merchantId}/api-keys`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          name: 'Integration Bot',
          scopes: ['parcels:create'],
          expiresInDays: 90,
        }),
      }),
    )
  })

  it('generateApiKey maps 409 Conflict to conflict error kind (key limit exceeded)', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({
        title: 'Conflict',
        status: 409,
        detail: 'Merchant tenant has reached maximum active API key limit of 3.',
      }),
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(
      api.generateApiKey(merchantId, { name: 'Fourth Key', scopes: ['parcels:create'] }),
    ).rejects.toMatchObject({
      kind: 'conflict',
      statusCode: 409,
      message: 'Merchant tenant has reached maximum active API key limit of 3.',
    })
  })

  it('revokeApiKey issues DELETE request and handles 204 No Content', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(api.revokeApiKey(merchantId, keyId)).resolves.toBeUndefined()

    expect(mockFetch).toHaveBeenCalledWith(
      `http://localhost:8080/api/v1/merchants/${merchantId}/api-keys/${keyId}`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          Authorization: `Bearer ${validToken}`,
        }),
      }),
    )
  })

  it('revokeApiKey maps 404 to not-found error kind', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ title: 'Not Found', status: 404, detail: 'API key not found' }),
    })

    const api = createMerchantCredentialsApi({
      getAccessToken: async () => validToken,
      fetch: mockFetch,
    })

    await expect(api.revokeApiKey(merchantId, keyId)).rejects.toMatchObject({
      kind: 'not-found',
    })
  })
})
