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
})
