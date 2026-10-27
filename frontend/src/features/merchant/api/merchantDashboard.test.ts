import { describe, expect, it, vi } from 'vitest'
import {
  createMerchantDashboardApi,
  MERCHANT_DASHBOARD_QUERY,
} from './merchantDashboard'
import { MerchantDashboardError } from './merchantDashboardContracts'

describe('createMerchantDashboardApi', () => {
  const sampleSuccessPayload = {
    data: {
      merchantDashboard: {
        merchantId: 'm-1111-2222-3333-444455556666',
        businessName: 'Apex Wholesale Ltd',
        status: 'APPROVED',
        totalOrdersCount: 42,
        activeOrdersCount: 5,
        deliveredOrdersCount: 35,
        failedOrdersCount: 2,
        recentOrders: [
          {
            parcelId: 'p-aaaa-1111-bbbb-222233334444',
            trackingNumber: 'SWD-20261027-APEX1',
            currentStatus: 'IN_TRANSIT',
            weightKg: 3.5,
            feeMinorUnits: 175000,
            currency: 'LKR',
            createdAt: '2026-10-27T10:00:00Z',
            lastStatusUpdate: '2026-10-27T11:30:00Z',
          },
          {
            parcelId: 'p-cccc-3333-dddd-444455556666',
            trackingNumber: 'SWD-20261027-APEX2',
            currentStatus: 'DELIVERED',
            weightKg: 1.2,
            feeMinorUnits: 65000,
            currency: 'LKR',
            createdAt: '2026-10-26T08:00:00Z',
            lastStatusUpdate: '2026-10-26T14:15:00Z',
          },
        ],
      },
    },
  }

  it('successfully fetches and maps authoritative merchant dashboard data', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify(sampleSuccessPayload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'test-bearer-token',
      gatewayUrl: 'http://gateway.test:8080',
      fetch: fetchMock,
    })

    const data = await api.getDashboard()

    expect(data.merchantId).toBe('m-1111-2222-3333-444455556666')
    expect(data.businessName).toBe('Apex Wholesale Ltd')
    expect(data.status).toBe('APPROVED')
    expect(data.totalOrdersCount).toBe(42)
    expect(data.activeOrdersCount).toBe(5)
    expect(data.deliveredOrdersCount).toBe(35)
    expect(data.failedOrdersCount).toBe(2)
    expect(data.recentOrders).toHaveLength(2)
    expect(data.recentOrders[0]?.trackingNumber).toBe('SWD-20261027-APEX1')
    expect(data.recentOrders[0]?.currentStatus).toBe('IN_TRANSIT')

    expect(fetchMock).toHaveBeenCalledWith(
      'http://gateway.test:8080/graphql',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer test-bearer-token',
          'Content-Type': 'application/json',
        }),
        body: JSON.stringify({ query: MERCHANT_DASHBOARD_QUERY }),
      }),
    )
  })

  it('throws auth-required when getAccessToken returns null or empty', async () => {
    const api = createMerchantDashboardApi({
      getAccessToken: async () => null,
      fetch: vi.fn(),
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('maps HTTP 401 response to auth-required error', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'unauthorized' }), { status: 401 }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'expired-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toThrow(MerchantDashboardError)
    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
      statusCode: 401,
    })
  })

  it('maps HTTP 403 response to forbidden error', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'forbidden' }), { status: 403 }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'non-merchant-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'forbidden',
      statusCode: 403,
    })
  })

  it('maps HTTP 404 response to not-found error', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'not found' }), { status: 404 }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'not-found',
      statusCode: 404,
    })
  })

  it('maps HTTP 504 to timeout error', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'gateway timeout' }), { status: 504 }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'timeout',
      statusCode: 504,
    })
  })

  it('maps HTTP 500/503 to unavailable error', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'server error' }), { status: 503 }),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'unavailable',
      statusCode: 503,
    })
  })

  it('maps GraphQL PERMISSION_DENIED error to forbidden', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          data: null,
          errors: [
            {
              message: 'Merchant is not approved: PENDING_REVIEW',
              extensions: { code: 'PERMISSION_DENIED' },
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'forbidden',
      message: expect.stringContaining('Merchant is not approved'),
    })
  })

  it('maps GraphQL UNAUTHENTICATED error to auth-required', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          data: null,
          errors: [
            {
              message: 'Missing authenticated subject',
              extensions: { code: 'UNAUTHENTICATED' },
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'auth-required',
    })
  })

  it('maps GraphQL DEADLINE_EXCEEDED error to timeout', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          data: null,
          errors: [
            {
              message: 'Accounts provider call timed out',
              extensions: { code: 'DEADLINE_EXCEEDED' },
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'timeout',
    })
  })

  it('rejects malformed GraphQL response payload with invalid-response', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          data: {
            merchantDashboard: {
              merchantId: 'm-1',
              // missing businessName, status, counts
            },
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const api = createMerchantDashboardApi({
      getAccessToken: async () => 'valid-token',
      fetch: fetchMock,
    })

    await expect(api.getDashboard()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })
})
