import { afterEach, describe, expect, it, vi } from 'vitest'
import { createDispatcherApi, dashboardQuery } from './dispatcher'
import { dashboardFixture, assignmentFixture } from '../testFixtures'
const command = { parcelId: 'parcel-1', courierSub: 'courier-1', expectedVersion: 4 }
const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status })
function setup(response: () => Promise<Response>) {
  const request = vi.fn(response)
  const api = createDispatcherApi({ getAccessToken: async () => 'test-token', fetch: request, gatewayUrl: 'http://gateway.test/' })
  return { api, request }
}
afterEach(() => vi.useRealTimers())
describe('Dispatcher boundary adapters', () => {
  it('uses the SDL and bounded pagination with the shared token getter', async () => {
    const { api, request } = setup(async () => json({ data: { dispatcherDashboard: dashboardFixture } }))
    expect(await api.dashboard()).toEqual(dashboardFixture)
    expect(request).toHaveBeenCalledWith('http://gateway.test/graphql', expect.objectContaining({ method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error', headers: expect.objectContaining({ Authorization: 'Bearer test-token' }), body: JSON.stringify({ query: dashboardQuery, variables: { pagination: { page: 0, size: 20 } } }) }))
  })
  it.each([[-1, 20], [0, 0], [0, 51], [1.5, 20], [NaN, 20]])('rejects invalid page %s / size %s before requesting', async (page, size) => {
    const { api, request } = setup(async () => json({}))
    await expect(api.dashboard(page, size)).rejects.toMatchObject({ kind: 'invalid-pagination' })
    expect(request).not.toHaveBeenCalled()
  })
  it('fails closed without a token', async () => {
    const request = vi.fn()
    await expect(createDispatcherApi({ getAccessToken: async () => null, fetch: request }).dashboard()).rejects.toMatchObject({ kind: 'auth-required' })
    expect(request).not.toHaveBeenCalled()
  })
  it.each([[401, 'auth-required'], [403, 'forbidden'], [404, 'forbidden'], [409, 'conflict'], [400, 'rejected'], [422, 'rejected'], [503, 'unavailable'], [504, 'timeout']])('maps command HTTP %s safely without replay', async (status, kind) => {
    const { api, request } = setup(async () => json({ detail: 'sensitive internals' }, status as number))
    await expect(api.assign(command)).rejects.toMatchObject({ kind })
    expect(request).toHaveBeenCalledOnce()
  })
  it.each([['UNAVAILABLE', 'unavailable'], ['UNAUTHENTICATED', 'auth-required'], ['FORBIDDEN', 'forbidden'], ['NOT_FOUND', 'forbidden'], ['DEADLINE_EXCEEDED', 'timeout'], ['INVALID_ARGUMENT', 'invalid-pagination']])('rejects GraphQL %s even with partial data', async (code, kind) => {
    const { api } = setup(async () => json({ data: { dispatcherDashboard: dashboardFixture }, errors: [{ message: 'private', extensions: { code } }] }))
    await expect(api.dashboard()).rejects.toMatchObject({ kind })
  })
  it('sends exactly the approved assignment fields and validates acknowledgment', async () => {
    const { api, request } = setup(async () => json(assignmentFixture))
    expect(await api.assign(command)).toEqual(assignmentFixture)
    expect(request).toHaveBeenCalledWith('http://gateway.test/api/v1/assignments', expect.objectContaining({ body: JSON.stringify(command) }))
  })
  it.each([{ ...assignmentFixture, parcelId: 'other' }, { ...assignmentFixture, active: false }, { ...assignmentFixture, newParcelVersion: 4 }])('rejects mismatched or invalid acknowledgment', async payload => {
    const { api } = setup(async () => json(payload))
    await expect(api.assign(command)).rejects.toMatchObject({ kind: 'invalid-response' })
  })
  it.each([{ ...dashboardFixture, pageInfo: { ...dashboardFixture.pageInfo, currentPage: 1 } }, { ...dashboardFixture, unassignedParcels: [{ ...dashboardFixture.unassignedParcels[0], version: -1 }] }, { ...dashboardFixture, availableCouriers: [{ ...dashboardFixture.availableCouriers[0], eligible: 'yes' }] }])('rejects invalid projections', async payload => {
    const { api } = setup(async () => json({ data: { dispatcherDashboard: payload } }))
    await expect(api.dashboard()).rejects.toMatchObject({ kind: 'invalid-response' })
  })
  it('aborts a timed out command and does not replay', async () => {
    vi.useFakeTimers()
    const request = vi.fn<typeof fetch>((_url, init) => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))))
    const api = createDispatcherApi({ getAccessToken: async () => 'test-token', fetch: request, timeoutMs: 100 })
    const result = expect(api.assign(command)).rejects.toMatchObject({ kind: 'timeout' })
    await vi.advanceTimersByTimeAsync(101)
    await result
    expect(request).toHaveBeenCalledOnce()
  })
  it('times out even if shared token retrieval never settles', async () => {
    vi.useFakeTimers()
    const request = vi.fn()
    const api = createDispatcherApi({ getAccessToken: () => new Promise(() => {}), fetch: request, timeoutMs: 100 })
    const result = expect(api.dashboard()).rejects.toMatchObject({ kind: 'timeout' })
    await vi.advanceTimersByTimeAsync(101)
    await result
    expect(request).not.toHaveBeenCalled()
  })
  it('retrieves active assignments for a courier from /api/v1/assignments', async () => {
    const assignmentsPayload = {
      items: [
        { assignmentId: 'asg-1', parcelId: 'parcel-1', courierSub: 'courier-1', active: true, assignedAt: '2026-11-14T10:00:00Z' },
        { assignmentId: 'asg-2', parcelId: 'parcel-2', courierSub: 'courier-2', active: true, assignedAt: '2026-11-14T11:00:00Z' },
        { assignmentId: 'asg-3', parcelId: 'parcel-3', courierSub: 'courier-1', active: false, assignedAt: '2026-11-14T09:00:00Z' },
      ],
      currentPage: 0,
      pageSize: 100,
    }
    const { api, request } = setup(async () => json(assignmentsPayload))
    const list = await api.getCourierAssignments('courier-1')
    expect(request).toHaveBeenCalledWith('http://gateway.test/api/v1/assignments?activeOnly=true&size=100', expect.any(Object))
    expect(list).toHaveLength(1)
    expect(list[0]?.parcelId).toBe('parcel-1')
  })
  it('retrieves parcel details by ID from /api/v1/parcels/{id}', async () => {
    const parcelPayload = { parcelId: 'parcel-1', trackingNumber: 'SWD-TEST-99', currentStatus: 'ASSIGNED', weightKg: 2.5 }
    const { api, request } = setup(async () => json(parcelPayload))
    const parcel = await api.getParcel('parcel-1')
    expect(request).toHaveBeenCalledWith('http://gateway.test/api/v1/parcels/parcel-1', expect.any(Object))
    expect(parcel?.trackingNumber).toBe('SWD-TEST-99')
  })

  describe('getParcelList', () => {
    it('successfully retrieves and validates parcel list with server pagination', async () => {
      const validPayload = {
        items: [
          {
            parcelId: 'p-1',
            trackingNumber: 'SWD-001',
            currentStatus: 'CREATED',
            createdAt: '2026-11-20T10:00:00Z',
            weightKg: 2.5,
            activeCourierSub: 'courier-1',
          },
        ],
        currentPage: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
      }
      const { api, request } = setup(async () => json(validPayload))
      const res = await api.getParcelList({ page: 0, size: 20 })
      expect(request).toHaveBeenCalledWith('http://gateway.test/api/v1/parcels?page=0&size=20', expect.any(Object))
      expect(res.items).toHaveLength(1)
      expect(res.items[0]?.trackingNumber).toBe('SWD-001')
      expect(res.totalElements).toBe(1)
    })

    it.each([
      ['missing trackingNumber', { parcelId: 'p-1', currentStatus: 'CREATED', createdAt: '2026-11-20T10:00:00Z' }],
      ['missing parcelId', { trackingNumber: 'SWD-001', currentStatus: 'CREATED', createdAt: '2026-11-20T10:00:00Z' }],
      ['missing currentStatus', { parcelId: 'p-1', trackingNumber: 'SWD-001', createdAt: '2026-11-20T10:00:00Z' }],
      ['invalid createdAt', { parcelId: 'p-1', trackingNumber: 'SWD-001', currentStatus: 'CREATED', createdAt: 'not-a-date' }],
    ])('rejects invalid parcel items without fallback (%s)', async (_, invalidItem) => {
      const payload = {
        items: [invalidItem],
        currentPage: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
      }
      const { api } = setup(async () => json(payload))
      await expect(api.getParcelList({ page: 0, size: 20 })).rejects.toMatchObject({ kind: 'invalid-response' })
    })

    it('rejects response missing pagination metadata', async () => {
      const payload = {
        items: [],
      }
      const { api } = setup(async () => json(payload))
      await expect(api.getParcelList({ page: 0, size: 20 })).rejects.toMatchObject({ kind: 'invalid-response' })
    })
  })

  describe('getParcelStatusHistory', () => {
    it('successfully retrieves and validates status history ordered chronologically', async () => {
      const validPayload = {
        items: [
          {
            historyId: 'h-2',
            parcelId: 'p-1',
            previousStatus: 'CREATED',
            newStatus: 'PICKED_UP',
            resultingVersion: 2,
            occurredAt: '2026-11-20T11:00:00Z',
          },
          {
            historyId: 'h-1',
            parcelId: 'p-1',
            previousStatus: null,
            newStatus: 'CREATED',
            resultingVersion: 1,
            occurredAt: '2026-11-20T10:00:00Z',
          },
        ],
        currentPage: 0,
        pageSize: 50,
        totalElements: 2,
        totalPages: 1,
        hasNext: false,
      }
      const { api } = setup(async () => json(validPayload))
      const res = await api.getParcelStatusHistory('p-1')
      expect(res.items).toHaveLength(2)
      // Oldest first
      expect(res.items[0]?.historyId).toBe('h-1')
      expect(res.items[1]?.historyId).toBe('h-2')
    })

    it.each([
      ['missing historyId', { newStatus: 'CREATED', resultingVersion: 1, occurredAt: '2026-11-20T10:00:00Z' }],
      ['missing newStatus', { historyId: 'h-1', resultingVersion: 1, occurredAt: '2026-11-20T10:00:00Z' }],
      ['missing resultingVersion', { historyId: 'h-1', newStatus: 'CREATED', occurredAt: '2026-11-20T10:00:00Z' }],
      ['invalid occurredAt', { historyId: 'h-1', newStatus: 'CREATED', resultingVersion: 1, occurredAt: 'bad-date' }],
    ])('rejects invalid history entries (%s)', async (_, invalidEntry) => {
      const payload = {
        items: [invalidEntry],
        currentPage: 0,
        pageSize: 50,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
      }
      const { api } = setup(async () => json(payload))
      await expect(api.getParcelStatusHistory('p-1')).rejects.toMatchObject({ kind: 'invalid-response' })
    })
  })

  describe('getAssignmentHistory', () => {
    it('successfully retrieves and validates assignment history', async () => {
      const validPayload = [
        {
          auditId: 'a-1',
          parcelId: 'p-1',
          assignmentId: 'asg-1',
          courierSub: 'c-1',
          action: 'ASSIGNED',
          actorSub: 'd-1',
          actorRole: 'DISPATCHER',
          occurredAt: '2026-11-20T10:00:00Z',
        },
      ]
      const { api } = setup(async () => json(validPayload))
      const res = await api.getAssignmentHistory('p-1')
      expect(res).toHaveLength(1)
      expect(res[0]?.auditId).toBe('a-1')
      expect(res[0]?.action).toBe('ASSIGNED')
    })

    it.each([
      ['missing auditId', { assignmentId: 'asg-1', courierSub: 'c-1', action: 'ASSIGNED', actorSub: 'd-1', occurredAt: '2026-11-20T10:00:00Z' }],
      ['missing courierSub', { auditId: 'a-1', assignmentId: 'asg-1', action: 'ASSIGNED', actorSub: 'd-1', occurredAt: '2026-11-20T10:00:00Z' }],
      ['missing action', { auditId: 'a-1', assignmentId: 'asg-1', courierSub: 'c-1', actorSub: 'd-1', occurredAt: '2026-11-20T10:00:00Z' }],
      ['missing actorSub', { auditId: 'a-1', assignmentId: 'asg-1', courierSub: 'c-1', action: 'ASSIGNED', occurredAt: '2026-11-20T10:00:00Z' }],
      ['invalid occurredAt', { auditId: 'a-1', assignmentId: 'asg-1', courierSub: 'c-1', action: 'ASSIGNED', actorSub: 'd-1', occurredAt: 'invalid' }],
    ])('rejects invalid assignment history entries (%s)', async (_, invalidEntry) => {
      const payload = [invalidEntry]
      const { api } = setup(async () => json(payload))
      await expect(api.getAssignmentHistory('p-1')).rejects.toMatchObject({ kind: 'invalid-response' })
    })
  })
})
