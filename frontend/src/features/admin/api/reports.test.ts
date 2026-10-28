import { describe, expect, it, vi } from 'vitest'
import { adminDashboardFixture, deliveryStatsFixture, graphqlData, graphqlError, requestBodies } from '../../../test/reportsFixture'
import { ADMIN_DASHBOARD_QUERY, createReportsApi, DELIVERY_STATS_QUERY, formatRate, outcomeRates, parseAdminDashboard, parseDeliveryStats, ReportError } from './reports'

function api(response: Response | (() => Promise<Response>), token: string | null = 'synthetic-report-token') {
  const fetch = vi.fn<typeof globalThis.fetch>(async () => typeof response === 'function' ? response() : response)
  return { fetch, client: createReportsApi({ getAccessToken: async () => token, gatewayUrl: 'https://gateway.test/', fetch }) }
}
const filter = { startDate: '2026-10-26', endDate: '2026-10-28' }
const signal = () => new AbortController().signal
async function failure(promise: Promise<unknown>) {
  const error = await promise.then(() => null, (reason: unknown) => reason)
  expect(error).toBeInstanceOf(ReportError)
  return error as ReportError
}

describe('reporting GraphQL adapter', () => {
  it('sends the approved read-only deliveryStats query through the Gateway with the in-memory bearer token', async () => {
    const { fetch, client } = api(graphqlData('deliveryStats', deliveryStatsFixture()))
    await expect(client.deliveryStats(filter, signal())).resolves.toEqual(deliveryStatsFixture())
    expect(fetch).toHaveBeenCalledWith('https://gateway.test/graphql', expect.objectContaining({
      method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error',
      headers: expect.objectContaining({ Authorization: 'Bearer synthetic-report-token', 'Content-Type': 'application/json' }),
    }))
    const [body] = requestBodies(fetch)
    expect(body).toEqual({ query: DELIVERY_STATS_QUERY, variables: { filter: { startDate: '2026-10-26', endDate: '2026-10-28' } } })
    expect(body!.query).not.toMatch(/\b(mutation|subscription)\b/i)
  })

  it('sends a zone only when one is selected and never sends a status filter', async () => {
    const { fetch, client } = api(graphqlData('deliveryStats', deliveryStatsFixture()))
    await client.deliveryStats({ ...filter, zone: 'CMB-01' }, signal())
    expect(requestBodies(fetch)[0]!.variables!.filter).toEqual({ ...filter, zone: 'CMB-01' })
  })

  it('sends the zero-argument adminDashboard query and projects only contract fields', async () => {
    const leaky = adminDashboardFixture()
    const response = { ...leaky, internalNote: 'x', recentPendingMerchants: leaky.recentPendingMerchants.map(item => ({ ...item, email: 'owner@synthetic.invalid', apiKeyDigest: 'digest', phone: '+94000000000' })) }
    const { fetch, client } = api(graphqlData('adminDashboard', response))
    const result = await client.adminDashboard(signal())
    expect(result).toEqual(adminDashboardFixture())
    expect(JSON.stringify(result)).not.toMatch(/email|apiKeyDigest|phone|internalNote/)
    expect(requestBodies(fetch)[0]).toEqual({ query: ADMIN_DASHBOARD_QUERY })
  })

  it('requires a session token before any request', async () => {
    const { fetch, client } = api(graphqlData('deliveryStats', deliveryStatsFixture()), null)
    expect((await failure(client.deliveryStats(filter, signal()))).kind).toBe('auth-required')
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each([[400, 'invalid-filter'], [401, 'auth-required'], [403, 'forbidden'], [404, 'unavailable'], [500, 'unavailable'], [502, 'unavailable'], [504, 'timeout']] as const)(
    'maps HTTP %i to %s', async (status, kind) => {
      const { client } = api(new Response('provider detail with stack trace', { status }))
      const error = await failure(client.deliveryStats(filter, signal()))
      expect(error.kind).toBe(kind)
      expect(error.message).not.toContain('stack trace')
    })

  it.each([['UNAUTHENTICATED', 'auth-required'], ['FORBIDDEN', 'forbidden'], ['INVALID_ARGUMENT', 'invalid-filter'], ['DEADLINE_EXCEEDED', 'timeout'],
    ['UNAVAILABLE', 'unavailable'], ['INTERNAL', 'unavailable'], ['NOT_FOUND', 'unavailable']] as const)(
    'maps GraphQL error code %s to %s without rendering provider text', async (code, kind) => {
      const { client } = api(graphqlError(code))
      const error = await failure(client.deliveryStats(filter, signal()))
      expect(error.kind).toBe(kind)
      expect(error.message).not.toContain(code)
    })

  it('treats the most restrictive code as authoritative when several errors are returned', async () => {
    const { client } = api(Response.json({ errors: [{ extensions: { code: 'UNAVAILABLE' } }, { extensions: { code: 'FORBIDDEN' } }] }))
    expect((await failure(client.adminDashboard(signal()))).kind).toBe('forbidden')
  })

  it('reports network failure as unavailable and a deadline as timeout', async () => {
    const offline = createReportsApi({ getAccessToken: async () => 't', fetch: vi.fn(async () => { throw new TypeError('Failed to fetch') }) })
    expect((await failure(offline.deliveryStats(filter, signal()))).kind).toBe('unavailable')
    const hanging = createReportsApi({ getAccessToken: async () => 't', timeoutMs: 5, fetch: vi.fn((_input, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })) })
    expect((await failure(hanging.deliveryStats(filter, signal()))).kind).toBe('timeout')
  })

  it('propagates a caller abort rather than reporting a provider failure', async () => {
    const controller = new AbortController()
    const client = createReportsApi({ getAccessToken: async () => 't', fetch: vi.fn((_input, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })) })
    const pending = client.deliveryStats(filter, controller.signal)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it.each([
    ['unparseable JSON', new Response('not json', { status: 200 })],
    ['null data', Response.json({ data: null })],
    ['missing root field', Response.json({ data: {} })],
    ['negative count', graphqlData('deliveryStats', deliveryStatsFixture({ failedCount: -1 }))],
    ['fractional count', graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 40.5 }))],
    ['Int32 overflow', graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 2_147_483_648 }))],
    ['string count', graphqlData('deliveryStats', { ...deliveryStatsFixture(), deliveredCount: '18' })],
    ['status groups exceeding the total', graphqlData('deliveryStats', deliveryStatsFixture({ totalCount: 39 }))],
    ['average hours without deliveries', graphqlData('deliveryStats', deliveryStatsFixture({ deliveredCount: 0, averageDeliveryHours: 3 }))],
    ['negative average hours', graphqlData('deliveryStats', deliveryStatsFixture({ averageDeliveryHours: -1 }))],
  ])('rejects an invalid deliveryStats response: %s', async (_label, response) => {
    const { client } = api(response)
    expect((await failure(client.deliveryStats(filter, signal()))).kind).toBe('invalid-response')
  })
})

describe('adminDashboard response validation', () => {
  const merchant = adminDashboardFixture().recentPendingMerchants[0]!
  it.each([
    ['rate above 1', adminDashboardFixture({ terminalSuccessRate: 1.2 })],
    ['negative rate', adminDashboardFixture({ terminalSuccessRate: -0.1 })],
    ['active above total', adminDashboardFixture({ activeParcelsCount: 28 })],
    ['preview larger than pending count', adminDashboardFixture({ pendingMerchantsCount: 1 })],
    ['malformed merchant ID', adminDashboardFixture({ recentPendingMerchants: [{ ...merchant, merchantId: 'merchant-1' }] })],
    ['duplicate merchant IDs', adminDashboardFixture({ recentPendingMerchants: [merchant, { ...merchant, merchantId: merchant.merchantId.toUpperCase() }] })],
    ['blank business name', adminDashboardFixture({ recentPendingMerchants: [{ ...merchant, businessName: '  ' }] })],
    ['control characters', adminDashboardFixture({ recentPendingMerchants: [{ ...merchant, businessName: 'Bad\u0007Name' }] })],
    ['invalid submission time', adminDashboardFixture({ recentPendingMerchants: [{ ...merchant, submittedAt: 'yesterday' }] })],
    ['oversized preview', adminDashboardFixture({ pendingMerchantsCount: 60, recentPendingMerchants: Array.from({ length: 51 }, (_, i) => ({ ...merchant, merchantId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}` })) })],
  ])('rejects %s', (_label, value) => {
    expect(() => parseAdminDashboard(value)).toThrow(ReportError)
  })
  it('accepts an empty review queue', () => {
    expect(parseAdminDashboard(adminDashboardFixture({ pendingMerchantsCount: 0, recentPendingMerchants: [] })).recentPendingMerchants).toEqual([])
  })
})

describe('outcome rate calculation', () => {
  it('uses delivered + failed as the shared denominator', () => {
    expect(outcomeRates(deliveryStatsFixture())).toEqual({ terminal: 20, successRate: 0.9, failureRate: 0.1 })
    expect(formatRate(0.9)).toBe('90.0%')
    expect(formatRate(1 / 3)).toBe('33.3%')
  })
  it('never yields NaN or Infinity for zero terminal outcomes', () => {
    const rates = outcomeRates({ deliveredCount: 0, failedCount: 0 })
    expect(rates).toEqual({ terminal: 0, successRate: null, failureRate: null })
    expect(formatRate(rates.successRate)).toBe('Not available')
    expect(formatRate(Number.NaN)).toBe('Not available')
    expect(formatRate(Number.POSITIVE_INFINITY)).toBe('Not available')
  })
  it('excludes in-progress and created parcels from the rate denominator', () => {
    const stats = parseDeliveryStats(deliveryStatsFixture({ totalCount: 100, deliveredCount: 3, failedCount: 1, inTransitCount: 50, createdCount: 46 }))
    expect(outcomeRates(stats).successRate).toBe(0.75)
  })
})
