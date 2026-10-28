import { describe, expect, it, vi } from 'vitest'
import {
  createCustomerDashboardApi,
  isCustomerDashboardData,
  CUSTOMER_DASHBOARD_QUERY,
} from './customerDashboard'
import { CustomerDashboardError } from './customerDashboardContracts'

const validParcel = {
  parcelId: '550e8400-e29b-41d4-a716-446655440000',
  trackingNumber: 'SD-TRK-1001',
  currentStatus: 'IN_TRANSIT',
  amountMinorUnits: 150000,
  currency: 'LKR',
  recipientCity: 'Colombo',
  createdAt: '2026-10-28T04:00:00Z',
  updatedAt: '2026-10-28T05:30:00Z',
}

const validDashboardData = {
  userId: 'user-sub-1234',
  activeDeliveriesCount: 2,
  completedDeliveriesCount: 5,
  recentParcels: [validParcel],
}

describe('isCustomerDashboardData validator', () => {
  it('accepts valid customer dashboard payload', () => {
    expect(isCustomerDashboardData(validDashboardData)).toBe(true)
  })

  it('accepts valid empty recentParcels array', () => {
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [],
      }),
    ).toBe(true)
  })

  it('rejects null or non-object values', () => {
    expect(isCustomerDashboardData(null)).toBe(false)
    expect(isCustomerDashboardData(undefined)).toBe(false)
    expect(isCustomerDashboardData('invalid')).toBe(false)
    expect(isCustomerDashboardData(123)).toBe(false)
  })

  it('rejects missing or blank userId', () => {
    expect(isCustomerDashboardData({ ...validDashboardData, userId: '' })).toBe(false)
    expect(isCustomerDashboardData({ ...validDashboardData, userId: '   ' })).toBe(false)
    expect(isCustomerDashboardData({ ...validDashboardData, userId: null })).toBe(false)
  })

  it('rejects negative counts', () => {
    expect(isCustomerDashboardData({ ...validDashboardData, activeDeliveriesCount: -1 })).toBe(false)
    expect(isCustomerDashboardData({ ...validDashboardData, completedDeliveriesCount: -1 })).toBe(false)
  })

  it('rejects non-integer counts', () => {
    expect(isCustomerDashboardData({ ...validDashboardData, activeDeliveriesCount: 1.5 })).toBe(false)
  })

  it('rejects non-array recentParcels', () => {
    expect(isCustomerDashboardData({ ...validDashboardData, recentParcels: 'not-an-array' })).toBe(false)
  })

  it('rejects recentParcels exceeding maximum bound of 50', () => {
    const oversized = Array.from({ length: 51 }, (_, i) => ({
      ...validParcel,
      parcelId: `550e8400-e29b-41d4-a716-4466554400${i < 10 ? '0' + i : i}`,
      trackingNumber: `SD-TRK-${i}`,
    }))
    expect(isCustomerDashboardData({ ...validDashboardData, recentParcels: oversized })).toBe(false)
  })

  it('rejects invalid parcel status', () => {
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, currentStatus: 'CANCELLED' }],
      }),
    ).toBe(false)
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, currentStatus: 'in_transit' }],
      }),
    ).toBe(false)
  })

  it('rejects invalid timestamps', () => {
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, createdAt: 'not-a-date' }],
      }),
    ).toBe(false)
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, updatedAt: 'invalid' }],
      }),
    ).toBe(false)
  })

  it('rejects negative parcel amount', () => {
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, amountMinorUnits: -100 }],
      }),
    ).toBe(false)
  })

  it('rejects blank parcel fields', () => {
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, trackingNumber: '' }],
      }),
    ).toBe(false)
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, recipientCity: '  ' }],
      }),
    ).toBe(false)
    expect(
      isCustomerDashboardData({
        ...validDashboardData,
        recentParcels: [{ ...validParcel, currency: '' }],
      }),
    ).toBe(false)
  })
})

describe('createCustomerDashboardApi client', () => {
  it('executes GraphQL query and returns valid customer dashboard data', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          data: { customerDashboard: validDashboardData },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('valid-test-token'),
      gatewayUrl: 'http://localhost:8080',
      fetch: mockFetch,
    })

    const result = await api.getDashboard()
    expect(result).toEqual(validDashboardData)
    expect(mockFetch).toHaveBeenCalledTimes(1)
    const call = mockFetch.mock.calls[0]
    expect(call).toBeDefined()
    const [url, init] = call as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/graphql')
    expect(init.method).toBe('POST')
    expect(init.headers).toBeDefined()
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer valid-test-token')
    const body = JSON.parse(init.body as string)
    expect(body.query).toBe(CUSTOMER_DASHBOARD_QUERY)
  })

  it('throws auth-required if access token is missing or blank', async () => {
    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue(''),
      fetch: vi.fn(),
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('throws auth-required when token getter throws error', async () => {
    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockRejectedValue(new Error('Session dead')),
      fetch: vi.fn(),
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('maps HTTP 401 to auth-required error', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response('Unauthorized', { status: 401 }),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('maps HTTP 403 and 404 to forbidden error', async () => {
    const mockFetch403 = vi.fn().mockResolvedValue(
      new Response('Forbidden', { status: 403 }),
    )
    const api403 = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch403,
    })
    await expect(api403.getDashboard()).rejects.toMatchObject({
      kind: 'forbidden',
    })

    const mockFetch404 = vi.fn().mockResolvedValue(
      new Response('Not Found', { status: 404 }),
    )
    const api404 = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch404,
    })
    await expect(api404.getDashboard()).rejects.toMatchObject({
      kind: 'forbidden',
    })
  })

  it('maps HTTP 408 and 504 to timeout error', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response('Gateway Timeout', { status: 504 }),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'timeout',
    })
  })

  it('maps HTTP 500 to unavailable error', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response('Server Error', { status: 500 }),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'unavailable',
    })
  })

  it('throws invalid-response when response is not valid JSON', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response('<!DOCTYPE html><html><body>Error</body></html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html' },
      }),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })

  it('maps GraphQL UNAUTHENTICATED error code to auth-required', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          errors: [
            {
              message: 'Unauthenticated caller',
              extensions: { code: 'UNAUTHENTICATED' },
            },
          ],
        }),
        { status: 200 },
      ),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('maps GraphQL FORBIDDEN error code to forbidden', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          errors: [
            {
              message: 'Access denied: role CUSTOMER required',
              extensions: { code: 'FORBIDDEN' },
            },
          ],
        }),
        { status: 200 },
      ),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'forbidden',
    })
  })

  it('maps GraphQL DEADLINE_EXCEEDED error code to timeout', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          errors: [
            {
              message: 'Upstream deadline exceeded',
              extensions: { code: 'DEADLINE_EXCEEDED' },
            },
          ],
        }),
        { status: 200 },
      ),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'timeout',
    })
  })

  it('throws invalid-response when GraphQL data payload violates schema', async () => {
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          data: {
            customerDashboard: {
              ...validDashboardData,
              activeDeliveriesCount: -99, // invalid negative count
            },
          },
        }),
        { status: 200 },
      ),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })

  it('re-throws when caller aborts signal', async () => {
    const controller = new AbortController()
    controller.abort()

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: vi.fn(),
    })

    await expect(api.getDashboard(controller.signal)).rejects.toThrow()
  })

  it('handles client timeout after timeoutMs', async () => {
    const mockFetch = vi.fn().mockImplementation(
      (_url, init) =>
        new Promise((_, reject) => {
          init.signal.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'))
          })
        }),
    )

    const api = createCustomerDashboardApi({
      getAccessToken: vi.fn().mockResolvedValue('token'),
      fetch: mockFetch,
      timeoutMs: 50,
    })

    await expect(api.getDashboard()).rejects.toThrow(CustomerDashboardError)
  })
})
