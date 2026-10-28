import type { AdminDashboardSummary, DeliveryStatsSummary } from '../features/admin/api/reports'

/**
 * Synthetic canonical parcels for reporting tests. Asia/Colombo midnight is 18:30Z on the
 * previous UTC date, so several parcels sit on either side of that boundary.
 */
export interface CanonicalParcel { id: string; status: 'CREATED' | 'IN_TRANSIT' | 'DELIVERED' | 'FAILED'; activityAt: string }
export const canonicalParcels: readonly CanonicalParcel[] = [
  { id: 'p-01', status: 'DELIVERED', activityAt: '2026-10-26T18:29:59.999Z' }, // 23:59:59.999 on 26 Oct Colombo
  { id: 'p-02', status: 'DELIVERED', activityAt: '2026-10-26T18:30:00.000Z' }, // 00:00 on 27 Oct Colombo
  { id: 'p-03', status: 'FAILED', activityAt: '2026-10-27T02:15:00.000Z' },
  { id: 'p-04', status: 'IN_TRANSIT', activityAt: '2026-10-27T12:00:00.000Z' },
  { id: 'p-05', status: 'DELIVERED', activityAt: '2026-10-27T18:29:00.000Z' }, // 23:59 on 27 Oct Colombo, still 27 Oct UTC
  { id: 'p-06', status: 'FAILED', activityAt: '2026-10-27T18:31:00.000Z' }, // 00:01 on 28 Oct Colombo, still 27 Oct UTC
  { id: 'p-07', status: 'CREATED', activityAt: '2026-10-28T04:00:00.000Z' },
  { id: 'p-08', status: 'DELIVERED', activityAt: '2026-10-28T09:45:00.000Z' },
]

export function deliveryStatsFixture(overrides: Partial<DeliveryStatsSummary> = {}): DeliveryStatsSummary {
  return { totalCount: 40, deliveredCount: 18, failedCount: 2, inTransitCount: 12, createdCount: 8, averageDeliveryHours: 5.25, ...overrides }
}

export function adminDashboardFixture(overrides: Partial<AdminDashboardSummary> = {}): AdminDashboardSummary {
  return {
    pendingMerchantsCount: 3, totalParcelsCount: 27, activeParcelsCount: 9, terminalSuccessRate: 0.9333,
    recentPendingMerchants: [
      { merchantId: '6f1c2b8e-0d4a-4c1e-9a7b-3e5d2f1a0b9c', businessName: 'Synthetic Spice Traders', registrationNumber: 'PV-000101', submittedAt: '2026-10-27T18:45:00Z' },
      { merchantId: '0b7e4d2a-9c3f-4e8b-8a1d-5f6c7b8a9d0e', businessName: 'Synthetic Tea Exporters', registrationNumber: 'PV-000102', submittedAt: '2026-10-25T04:00:00Z' },
    ],
    ...overrides,
  }
}

type GraphqlHandler = (variables: Record<string, unknown> | undefined) => Response | Promise<Response>
export function graphqlData(field: string, value: unknown): Response { return Response.json({ data: { [field]: value } }) }
export function graphqlError(code: string): Response {
  return Response.json({ data: null, errors: [{ message: code, extensions: { code, classification: 'INTERNAL_ERROR' } }] })
}
/** Routes a stubbed Gateway fetch to per-root handlers; any other request fails the test. */
export function graphqlFetch(handlers: { deliveryStats?: GraphqlHandler; adminDashboard?: GraphqlHandler }) {
  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input)
    if (!url.endsWith('/graphql')) throw new Error(`Unexpected request ${url}`)
    const body = JSON.parse(String(init?.body)) as { query: string; variables?: Record<string, unknown> }
    const root = body.query.includes('deliveryStats(') ? 'deliveryStats' : body.query.includes('adminDashboard {') ? 'adminDashboard' : null
    const handler = root ? handlers[root] : undefined
    if (!handler) throw new Error(`Unhandled GraphQL root in ${body.query.slice(0, 40)}`)
    return handler(body.variables)
  }
}
export function requestBodies(mock: { mock: { calls: unknown[][] } }) {
  return mock.mock.calls.map(call => JSON.parse(String((call[1] as RequestInit).body)) as { query: string; variables?: { filter?: Record<string, unknown> } })
}
