import { describe, expect, it, vi } from 'vitest'
import {
  COURIER_DASHBOARD_SUMMARY_QUERY,
  createCourierDashboardApi,
  isCourierDashboardSummary,
} from './courierDashboard'

describe('courierDashboard API', () => {
  const getAccessToken = vi.fn(async () => 'mock-jwt-token')

  it('validates schema correctly using isCourierDashboardSummary', () => {
    expect(
      isCourierDashboardSummary({
        courierSub: 'sub-123',
        activeAssignedCount: 5,
        todayDeliveredCount: 12,
        todayFailedCount: 1,
      }),
    ).toBe(true)

    expect(isCourierDashboardSummary(null)).toBe(false)
    expect(isCourierDashboardSummary({})).toBe(false)
    expect(
      isCourierDashboardSummary({
        courierSub: '',
        activeAssignedCount: 5,
        todayDeliveredCount: 12,
        todayFailedCount: 1,
      }),
    ).toBe(false)
    expect(
      isCourierDashboardSummary({
        courierSub: 'sub-123',
        activeAssignedCount: -1,
        todayDeliveredCount: 12,
        todayFailedCount: 1,
      }),
    ).toBe(false)
  })

  it('successfully retrieves dashboard summary with Bearer auth', async () => {
    const mockSummary = {
      courierSub: 'sub-courier-42',
      activeAssignedCount: 4,
      todayDeliveredCount: 15,
      todayFailedCount: 2,
    }

    const mockFetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      expect(url).toBe('http://localhost:8080/graphql')
      expect(init?.method).toBe('POST')
      expect((init?.headers as Record<string, string>)?.Authorization).toBe('Bearer mock-jwt-token')
      expect((init?.headers as Record<string, string>)?.['Content-Type']).toBe('application/json')

      const body = JSON.parse(init?.body as string)
      expect(body.query).toBe(COURIER_DASHBOARD_SUMMARY_QUERY)

      return new Response(
        JSON.stringify({
          data: {
            courierDashboard: mockSummary,
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    })

    const api = createCourierDashboardApi({
      getAccessToken,
      gatewayUrl: 'http://localhost:8080',
      fetch: mockFetch as unknown as typeof fetch,
    })

    const result = await api.getSummary()
    expect(result).toEqual(mockSummary)
  })

  it('throws auth-required when getAccessToken returns null or empty', async () => {
    const api = createCourierDashboardApi({
      getAccessToken: vi.fn(async () => null),
      gatewayUrl: 'http://localhost:8080',
      fetch: vi.fn(),
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      name: 'CourierApiError',
      kind: 'auth-required',
    })
  })

  it('maps HTTP 401 to auth-required', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 401 }))
    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'auth-required',
      statusCode: 401,
    })
  })

  it('maps HTTP 403 to forbidden', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 403 }))
    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'forbidden',
      statusCode: 403,
    })
  })

  it('maps HTTP 504 to timeout', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 504 }))
    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'timeout',
      statusCode: 504,
    })
  })

  it('detects GraphQL error extensions even on HTTP 200 without falling back to zero counts', async () => {
    const mockFetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: null,
            errors: [
              {
                message: 'Access denied: not operational courier',
                extensions: { code: 'FORBIDDEN' },
              },
            ],
          }),
          { status: 200 },
        ),
    )

    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'forbidden',
      statusCode: 403,
    })
  })

  it('detects GraphQL UNAUTHENTICATED error on HTTP 200', async () => {
    const mockFetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: null,
            errors: [
              {
                message: 'Token expired',
                extensions: { code: 'UNAUTHENTICATED' },
              },
            ],
          }),
          { status: 200 },
        ),
    )

    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'auth-required',
      statusCode: 401,
    })
  })

  it('detects GraphQL DEADLINE_EXCEEDED error on HTTP 200', async () => {
    const mockFetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: null,
            errors: [
              {
                message: 'Downstream timeout',
                extensions: { code: 'DEADLINE_EXCEEDED' },
              },
            ],
          }),
          { status: 200 },
        ),
    )

    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'timeout',
      statusCode: 504,
    })
  })

  it('rejects malformed response payload with invalid-response', async () => {
    const mockFetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: {
              courierDashboard: {
                courierSub: 'test',
                activeAssignedCount: 'not-a-number',
              },
            },
          }),
          { status: 200 },
        ),
    )

    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })

  it('rejects unparseable non-JSON payload with invalid-response', async () => {
    const mockFetch = vi.fn(async () => new Response('Internal Server Error HTML', { status: 200 }))

    const api = createCourierDashboardApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getSummary()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })
})
