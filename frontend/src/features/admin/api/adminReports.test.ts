import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAdminReportsApi,
  DAILY_DELIVERY_REPORT_QUERY,
  ADMIN_REPORT_SUMMARY_QUERY,
} from './adminReports'
import {
  AdminReportsError,
  calendarDayDifference,
  computeTerminalSuccessRate,
  formatTerminalSuccessRate,
  getTodayColomboDate,
  isTimestampInColomboDay,
  isValidCalendarDate,
  toColomboDateString,
  validateFilterInput,
  type DeliveryStatsSummary,
  type AdminDashboardData,
} from './adminReportsContracts'

const SYNTHETIC_TOKEN = 'synthetic-admin-jwt-token'

const validDeliveryStatsResponse: DeliveryStatsSummary = {
  totalCount: 15,
  deliveredCount: 10,
  failedCount: 2,
  inTransitCount: 2,
  createdCount: 1,
  averageDeliveryHours: 3.45,
}

const validAdminDashboardResponse: AdminDashboardData = {
  pendingMerchantsCount: 3,
  totalParcelsCount: 24,
  activeParcelsCount: 5,
  terminalSuccessRate: 0.8333,
  recentPendingMerchants: [
    {
      merchantId: 'a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d',
      businessName: 'Colombo Fresh Goods',
      registrationNumber: 'PV-123456',
      submittedAt: '2026-10-28T04:30:00Z',
    },
    {
      merchantId: 'b2c3d4e5-f6a1-4b2c-9d3e-4f5a6b7c8d9e',
      businessName: 'Kandy Express Logistics',
      registrationNumber: 'PV-987654',
      submittedAt: '2026-10-28T05:15:00Z',
    },
  ],
}

describe('adminReports API Adapter', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    mockFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', mockFetch)
  })

  function createClient(options?: {
    token?: string | null
    gatewayUrl?: string
    timeoutMs?: number
  }) {
    return createAdminReportsApi({
      getAccessToken: vi.fn(async () => (options?.token !== undefined ? options.token : SYNTHETIC_TOKEN)),
      gatewayUrl: options?.gatewayUrl ?? 'https://api.swiftdrop.test',
      fetch: mockFetch,
      timeoutMs: options?.timeoutMs ?? 2000,
    })
  }

  async function expectAdminReportsError(promise: Promise<unknown>): Promise<AdminReportsError> {
    try {
      await promise
      throw new Error('Expected promise to reject with AdminReportsError, but it resolved')
    } catch (e) {
      if (e instanceof AdminReportsError) {
        return e
      }
      throw e
    }
  }

  // 1 & 2: Correct GraphQL endpoint and POST method
  it('sends POST request to /graphql endpoint', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { deliveryStats: validDeliveryStatsResponse } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const client = createClient()
    await client.getDeliveryStats()

    expect(mockFetch).toHaveBeenCalledTimes(1)
    const [url, init] = mockFetch.mock.calls[0]!
    expect(url).toBe('https://api.swiftdrop.test/graphql')
    expect(init?.method).toBe('POST')
  })

  // 3: Correct Authorization header
  it('includes Authorization Bearer header with in-memory token', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { deliveryStats: validDeliveryStatsResponse } }), {
        status: 200,
      }),
    )

    const client = createClient({ token: 'test-admin-secret-token' })
    await client.getDeliveryStats()

    const [, init] = mockFetch.mock.calls[0]!
    expect((init?.headers as Record<string, string>)?.Authorization).toBe(
      'Bearer test-admin-secret-token',
    )
  })

  // 4: No credentials sent in cookies
  it('omits cookies and sets cache: no-store, redirect: error', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { deliveryStats: validDeliveryStatsResponse } }), {
        status: 200,
      }),
    )

    const client = createClient()
    await client.getDeliveryStats()

    const [, init] = mockFetch.mock.calls[0]!
    expect(init?.credentials).toBe('omit')
    expect(init?.cache).toBe('no-store')
    expect(init?.redirect).toBe('error')
  })

  // 5: Correct query shape
  it('transmits exact approved GraphQL query documents', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { deliveryStats: validDeliveryStatsResponse } }), {
        status: 200,
      }),
    )
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { adminDashboard: validAdminDashboardResponse } }), {
        status: 200,
      }),
    )

    const client = createClient()
    await client.getDeliveryStats()
    await client.getAdminDashboard()

    const body1 = JSON.parse(mockFetch.mock.calls[0]![1]!.body as string)
    expect(body1.query).toBe(DAILY_DELIVERY_REPORT_QUERY)

    const body2 = JSON.parse(mockFetch.mock.calls[1]![1]!.body as string)
    expect(body2.query).toBe(ADMIN_REPORT_SUMMARY_QUERY)
  })

  // 6, 7 & 8: Correct GraphQL variables, date filters, and status enum
  it('transmits sanitized filter variables to GraphQL query', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { deliveryStats: validDeliveryStatsResponse } }), {
        status: 200,
      }),
    )

    const client = createClient()
    await client.getDeliveryStats({
      startDate: '2026-10-20',
      endDate: '2026-10-28',
      zone: 'CMB-01',
      status: 'DELIVERED',
    })

    const body = JSON.parse(mockFetch.mock.calls[0]![1]!.body as string)
    expect(body.variables).toEqual({
      filter: {
        startDate: '2026-10-20',
        endDate: '2026-10-28',
        zone: 'CMB-01',
        status: 'DELIVERED',
      },
    })
  })

  // 9: Zone validation before request
  it('rejects invalid zone format without making network request', async () => {
    const client = createClient()

    await expect(
      client.getDeliveryStats({ zone: 'invalid zone with spaces!' }),
    ).rejects.toThrow(AdminReportsError)

    await expect(
      client.getDeliveryStats({ zone: 'zone-name-that-is-way-too-long-and-exceeds-32-chars-limit' }),
    ).rejects.toThrow(/Operational zone code must match pattern/)

    expect(mockFetch).not.toHaveBeenCalled()
  })

  // 10 & 11: Missing or blank token throws auth-required
  it('rejects with auth-required when getAccessToken returns null or blank string', async () => {
    const clientNull = createClient({ token: null })
    await expect(clientNull.getDeliveryStats()).rejects.toThrow('Your session expired')

    const clientEmpty = createClient({ token: '   ' })
    await expect(clientEmpty.getDeliveryStats()).rejects.toThrow('Your session expired')

    expect(mockFetch).not.toHaveBeenCalled()
  })

  // 12: HTTP 401 throws auth-required
  it('maps HTTP 401 to auth-required error', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 401 }))
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('auth-required')
  })

  // 13: HTTP 403 / 404 throws forbidden
  it('maps HTTP 403 and 404 to forbidden error', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 403 }))
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('forbidden')

    mockFetch.mockResolvedValueOnce(new Response(null, { status: 404 }))
    const err2 = await expectAdminReportsError(client.getAdminDashboard())
    expect(err2.kind).toBe('forbidden')
  })

  // 14: HTTP 429 throws unavailable
  it('maps HTTP 429 to unavailable error', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 429 }))
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('unavailable')
  })

  // 15 & 16: HTTP 500 / 503 throws unavailable
  it('maps HTTP 500 and 503 to unavailable error', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 500 }))
    const client = createClient()

    const err1 = await expectAdminReportsError(client.getDeliveryStats())
    expect(err1.kind).toBe('unavailable')

    mockFetch.mockResolvedValueOnce(new Response(null, { status: 503 }))
    const err2 = await expectAdminReportsError(client.getAdminDashboard())
    expect(err2.kind).toBe('unavailable')
  })

  // 17: Network failure throws unavailable
  it('maps fetch network failure to unavailable error', async () => {
    mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('unavailable')
  })

  // 18: Request timeout throws timeout
  it('times out when response exceeds timeoutMs', async () => {
    mockFetch.mockImplementationOnce(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted', 'AbortError'))
          })
        }),
    )

    const client = createClient({ timeoutMs: 50 })
    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('timeout')
  })

  // 19: Abort handling
  it('aborts cleanly when external AbortSignal is triggered', async () => {
    const controller = new AbortController()
    mockFetch.mockImplementationOnce(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          if (init?.signal?.aborted) {
            reject(new DOMException('External abort', 'AbortError'))
            return
          }
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('External abort', 'AbortError'))
          })
        }),
    )

    const client = createClient()
    const promise = client.getDeliveryStats(undefined, controller.signal)
    controller.abort()

    await expect(promise).rejects.toThrow()
  })

  // 20: Invalid JSON
  it('maps unparseable JSON response to invalid-response', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response('<html><body>502 Bad Gateway</body></html>', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 21: GraphQL errors with HTTP 200
  it('maps GraphQL error codes truthfully', async () => {
    const client = createClient()

    // UNAUTHENTICATED
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          errors: [{ message: 'Denied', extensions: { code: 'UNAUTHENTICATED' } }],
        }),
        { status: 200 },
      ),
    )
    let err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('auth-required')

    // FORBIDDEN
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          errors: [{ message: 'Denied', extensions: { code: 'FORBIDDEN' } }],
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('forbidden')

    // UNAVAILABLE (exact behavior of missing deliveryStats resolver)
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: null,
          errors: [{ message: 'Service unavailable', extensions: { code: 'UNAVAILABLE' } }],
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('unavailable')

    // DEADLINE_EXCEEDED
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          errors: [{ message: 'Timeout', extensions: { code: 'DEADLINE_EXCEEDED' } }],
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('timeout')

    // BAD_USER_INPUT
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          errors: [{ message: 'Bad dates', extensions: { code: 'BAD_USER_INPUT' } }],
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-filter')
  })

  // 22: Missing data field
  it('rejects when GraphQL response lacks data property', async () => {
    mockFetch.mockResolvedValueOnce(new Response(JSON.stringify({}), { status: 200 }))
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 23: Negative counts
  it('rejects negative counts in deliveryStats', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            deliveryStats: {
              ...validDeliveryStatsResponse,
              deliveredCount: -1,
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 24: Non-integer counts
  it('rejects non-integer counts in deliveryStats', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            deliveryStats: {
              ...validDeliveryStatsResponse,
              totalCount: 15.5,
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 25: Integer overflow (> 2^31 - 1)
  it('rejects counts exceeding GraphQL 32-bit integer limit', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            deliveryStats: {
              ...validDeliveryStatsResponse,
              totalCount: 3_000_000_000,
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 26: Invalid success rate
  it('rejects terminalSuccessRate outside [0.0, 1.0] or non-finite', async () => {
    const client = createClient()

    // > 1.0
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              terminalSuccessRate: 1.5,
            },
          },
        }),
        { status: 200 },
      ),
    )
    let err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')

    // < 0.0
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              terminalSuccessRate: -0.1,
            },
          },
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')
  })

  // 27: Invalid duration
  it('rejects negative or non-finite averageDeliveryHours', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            deliveryStats: {
              ...validDeliveryStatsResponse,
              averageDeliveryHours: -2.5,
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.kind).toBe('invalid-response')
  })

  // 28: Malformed merchant entries
  it('rejects malformed merchant item in adminDashboard', async () => {
    const client = createClient()

    // Non-UUID merchantId
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              recentPendingMerchants: [
                {
                  merchantId: 'not-a-uuid',
                  businessName: 'Valid Name',
                  registrationNumber: 'REG-1',
                  submittedAt: '2026-10-28T04:30:00Z',
                },
              ],
            },
          },
        }),
        { status: 200 },
      ),
    )
    let err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')

    // Blank businessName
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              recentPendingMerchants: [
                {
                  merchantId: 'a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d',
                  businessName: '   ',
                  registrationNumber: 'REG-1',
                  submittedAt: '2026-10-28T04:30:00Z',
                },
              ],
            },
          },
        }),
        { status: 200 },
      ),
    )
    err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')
  })

  // 29: Duplicate merchants
  it('rejects duplicate merchant IDs in recentPendingMerchants', async () => {
    const duplicateId = 'a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d'
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              recentPendingMerchants: [
                {
                  merchantId: duplicateId,
                  businessName: 'Merchant One',
                  registrationNumber: 'REG-1',
                  submittedAt: '2026-10-28T04:30:00Z',
                },
                {
                  merchantId: duplicateId,
                  businessName: 'Merchant Two',
                  registrationNumber: 'REG-2',
                  submittedAt: '2026-10-28T05:30:00Z',
                },
              ],
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')
    expect(err.message).toContain('Duplicate merchant ID')
  })

  // 30: Bounded merchant list (> 50 items)
  it('rejects recentPendingMerchants list exceeding 50 items', async () => {
    const oversizedList = Array.from({ length: 51 }, (_, i) => ({
      merchantId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
      businessName: `Merchant ${i}`,
      registrationNumber: `REG-${i}`,
      submittedAt: '2026-10-28T04:30:00Z',
    }))

    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          data: {
            adminDashboard: {
              ...validAdminDashboardResponse,
              recentPendingMerchants: oversizedList,
            },
          },
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getAdminDashboard())
    expect(err.kind).toBe('invalid-response')
    expect(err.message).toContain('exceeds bounded limit of 50 items')
  })

  // 31: No raw backend error leakage in message
  it('does not expose internal gRPC or database details in errors', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          errors: [
            {
              message: 'io.grpc.StatusRuntimeException: UNAVAILABLE: connection to 10.0.1.4:9090 failed (delivery_db)',
              extensions: { code: 'UNAVAILABLE' },
            },
          ],
        }),
        { status: 200 },
      ),
    )
    const client = createClient()

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.message).not.toContain('10.0.1.4')
    expect(err.message).not.toContain('delivery_db')
    expect(err.message).toBe('Reporting information is temporarily unavailable. Please retry.')
  })

  // 32: No token logging
  it('never leaks Bearer token in error messages', async () => {
    const secretToken = 'super-secret-keycloak-token-xyz-123'
    const client = createClient({ token: secretToken })
    mockFetch.mockRejectedValueOnce(new Error('Connection failure'))

    const err = await expectAdminReportsError(client.getDeliveryStats())
    expect(err.message).not.toContain(secretToken)
  })
})

describe('Filter and Calendar Validation Semantics', () => {
  it('validates calendar dates strictly', () => {
    expect(isValidCalendarDate('2026-10-28')).toBe(true)
    expect(isValidCalendarDate('2024-02-29')).toBe(true) // Leap year
    expect(isValidCalendarDate('2025-02-29')).toBe(false) // Not leap year
    expect(isValidCalendarDate('2026-13-01')).toBe(false) // Invalid month
    expect(isValidCalendarDate('2026-04-31')).toBe(false) // April has 30 days
    expect(isValidCalendarDate('2026-10-32')).toBe(false) // Invalid day
    expect(isValidCalendarDate('invalid')).toBe(false)
  })

  it('calculates calendar day differences accurately', () => {
    expect(calendarDayDifference('2026-10-01', '2026-10-10')).toBe(9)
    expect(calendarDayDifference('2026-10-28', '2026-10-28')).toBe(0)
    expect(calendarDayDifference('2026-10-28', '2026-10-27')).toBe(-1)
    expect(calendarDayDifference('2026-01-01', '2026-04-01')).toBe(90)
    expect(calendarDayDifference('2026-01-01', '2026-04-02')).toBe(91)
  })

  it('enforces 90-day maximum reporting window', () => {
    const valid90 = validateFilterInput({
      startDate: '2026-01-01',
      endDate: '2026-04-01',
    })
    expect(valid90.valid).toBe(true)

    const invalid91 = validateFilterInput({
      startDate: '2026-01-01',
      endDate: '2026-04-02',
    })
    expect(invalid91.valid).toBe(false)
    expect(invalid91.error).toContain('exceeds maximum allowed range of 90 calendar days')
  })

  it('rejects start date after end date', () => {
    const inverted = validateFilterInput({
      startDate: '2026-10-28',
      endDate: '2026-10-20',
    })
    expect(inverted.valid).toBe(false)
    expect(inverted.error).toContain('Start date must be before or equal to end date')
  })

  it('validates parcel status enum allowlist', () => {
    expect(validateFilterInput({ status: 'DELIVERED' }).valid).toBe(true)
    expect(validateFilterInput({ status: 'FAILED' }).valid).toBe(true)
    expect(validateFilterInput({ status: 'IN_TRANSIT' }).valid).toBe(true)
    expect(validateFilterInput({ status: 'CREATED' }).valid).toBe(true)
    expect(validateFilterInput({ status: 'PICKED_UP' }).valid).toBe(true)
    expect(validateFilterInput({ status: 'OUT_FOR_DELIVERY' }).valid).toBe(true)

    // Invalid statuses
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(validateFilterInput({ status: 'CANCELLED' as any }).valid).toBe(false)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(validateFilterInput({ status: 'COMPLETED' as any }).valid).toBe(false)
  })
})

describe('Canonical Colombo Timezone and Day-Boundary Semantics (Phase 21)', () => {
  it('computes Colombo date string from UTC timestamps correctly', () => {
    // 2026-10-27 18:20 UTC -> 2026-10-27 23:50 Colombo (Oct 27)
    expect(toColomboDateString('2026-10-27T18:20:00Z')).toBe('2026-10-27')

    // 2026-10-27 18:35 UTC -> 2026-10-28 00:05 Colombo (Oct 28)
    expect(toColomboDateString('2026-10-27T18:35:00Z')).toBe('2026-10-28')

    // 2026-10-28 10:00 UTC -> 2026-10-28 15:30 Colombo (Oct 28)
    expect(toColomboDateString('2026-10-28T10:00:00Z')).toBe('2026-10-28')

    // 2026-10-28 18:40 UTC -> 2026-10-29 00:10 Colombo (Oct 29)
    expect(toColomboDateString('2026-10-28T18:40:00Z')).toBe('2026-10-29')
  })

  it('accurately verifies whether UTC events belong to target Colombo day (Phase 21 canonical fixtures)', () => {
    const targetDay = '2026-10-28'

    // Parcel A: Oct 27 18:20 UTC belongs to previous Colombo day
    expect(isTimestampInColomboDay('2026-10-27T18:20:00Z', targetDay)).toBe(false)

    // Parcel B: Oct 27 18:35 UTC belongs to Oct 28 in Colombo (+05:30 boundary crossing)
    expect(isTimestampInColomboDay('2026-10-27T18:35:00Z', targetDay)).toBe(true)

    // Parcel C: Oct 28 10:00 UTC belongs to Oct 28 in Colombo
    expect(isTimestampInColomboDay('2026-10-28T10:00:00Z', targetDay)).toBe(true)

    // Parcel D: Oct 28 18:40 UTC belongs to Oct 29 in Colombo
    expect(isTimestampInColomboDay('2026-10-28T18:40:00Z', targetDay)).toBe(false)
  })

  it('formats current Colombo date', () => {
    const colomboDate = getTodayColomboDate()
    expect(isValidCalendarDate(colomboDate)).toBe(true)
  })
})

describe('Terminal Success-Rate Denominator Semantics (Phase 9)', () => {
  it('computes success rate as deliveredCount / (deliveredCount + failedCount)', () => {
    // 8 delivered, 2 failed -> 8 / 10 = 80.0%
    expect(computeTerminalSuccessRate(8, 2)).toBe(80.0)
    expect(formatTerminalSuccessRate(8, 2)).toBe('80.0%')

    // 10 delivered, 0 failed -> 100.0%
    expect(computeTerminalSuccessRate(10, 0)).toBe(100.0)
    expect(formatTerminalSuccessRate(10, 0)).toBe('100.0%')

    // 0 delivered, 5 failed -> 0.0%
    expect(computeTerminalSuccessRate(0, 5)).toBe(0.0)
    expect(formatTerminalSuccessRate(0, 5)).toBe('0.0%')
  })

  it('returns null and "N/A" when delivered + failed is 0, never false 0%', () => {
    // 0 delivered, 0 failed, but 10 in-transit parcels
    expect(computeTerminalSuccessRate(0, 0)).toBeNull()
    expect(formatTerminalSuccessRate(0, 0)).toBe('N/A')
  })
})
