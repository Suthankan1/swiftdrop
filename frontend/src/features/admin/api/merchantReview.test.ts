import { describe, expect, it, vi } from 'vitest'
import {
  createMerchantReviewApi, isAmbiguousCommandFailure, merchantReasonProblem, merchantReviewHttpError, MerchantReviewError,
} from './merchantReview'

// Synthetic payloads shaped like the Accounts AdminPageResponse, AdminMerchantDetailResponse,
// AdminMerchantAuditResponse and MerchantProfileResponse records. They do not prove backend integration.
const summary = {
  id: 'm-uuid-1', businessName: 'Synthetic Stores', registrationNumber: 'REG-100', status: 'PENDING',
  submittedAt: '2026-10-27T10:00:00Z', reviewToken: '1',
}
const pageBody = { content: [summary], page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true }
const detailBody = { ...summary, actions: [{ action: 'approve', reason: null }], audit: [] }
const auditBody = [{
  id: 'aud-1', actor: 'synthetic-admin-sub', action: 'APPROVE', previousStatus: 'PENDING', newStatus: 'APPROVED',
  occurredAt: '2026-10-27T11:00:00Z', reason: 'ADMIN_APPROVED', outcome: 'SUCCESS',
}]

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
function client(fetchMock: ReturnType<typeof vi.fn>, token: string | null = 'test-admin-token') {
  return createMerchantReviewApi({ getAccessToken: async () => token, gatewayUrl: 'http://localhost:8080/', fetch: fetchMock as typeof fetch })
}
function routed(routes: Record<string, () => Response>) {
  return vi.fn(async (url: string) => {
    const path = new URL(url).pathname
    const route = routes[path]
    if (!route) throw new Error(`unexpected ${path}`)
    return route()
  })
}

describe('createMerchantReviewApi reads', () => {
  it('requests the pending review page through the Gateway with a bearer token and no cookies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(pageBody))
    const result = await client(fetchMock).list(0, 20)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/admin/merchants?page=0&size=20&status=PENDING')
    expect(init).toMatchObject({ method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(init.headers).toEqual({ Accept: 'application/json', Authorization: 'Bearer test-admin-token' })
    expect(init.body).toBeUndefined()
    // reviewToken and other extra fields are not projected into list rows.
    expect(result).toEqual({ items: [{ id: 'm-uuid-1', businessName: 'Synthetic Stores', registrationNumber: 'REG-100',
      status: 'PENDING', submittedAt: '2026-10-27T10:00:00Z' }], page: 0, hasNext: false })
  })

  it.each([['APPROVED', '&status=APPROVED'], ['SUSPENDED', '&status=SUSPENDED'], ['ALL', '']] as const)('applies the %s filter', async (filter, query) => {
    const fetchMock = vi.fn().mockResolvedValue(json({ ...pageBody, page: 2, last: false }))
    const result = await client(fetchMock).list(2, 20, undefined, filter)
    expect(fetchMock.mock.calls[0]?.[0]).toBe(`http://localhost:8080/api/v1/admin/merchants?page=2&size=20${query}`)
    expect(result.hasNext).toBe(true)
  })

  it.each([
    ['a row without an id', { ...pageBody, content: [{ ...summary, id: '' }] }],
    ['a row without a business name', { ...pageBody, content: [{ ...summary, businessName: undefined }] }],
    ['a non-object row', { ...pageBody, content: ['m-uuid-1'] }],
    ['a different page', { ...pageBody, page: 3 }],
    ['missing paging metadata', { content: [summary], page: 0 }],
    ['a non-page body', [summary]],
  ])('rejects %s as an invalid response', async (_name, body) => {
    await expect(client(vi.fn().mockResolvedValue(json(body))).list(0, 20)).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('maps an unknown server status to UNKNOWN rather than trusting it', async () => {
    const result = await client(vi.fn().mockResolvedValue(json({ ...pageBody, content: [{ ...summary, status: 'SUPERUSER' }] }))).list(0, 20)
    expect(result.items[0]?.status).toBe('UNKNOWN')
  })

  it('reads details and the dedicated audit projection with status transitions', async () => {
    const fetchMock = routed({
      '/api/v1/admin/merchants/m-uuid-1': () => json(detailBody),
      '/api/v1/admin/merchants/m-uuid-1/audit': () => json(auditBody),
    })
    const detail = await client(fetchMock).detail('m-uuid-1')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(detail).toEqual({
      id: 'm-uuid-1', businessName: 'Synthetic Stores', registrationNumber: 'REG-100', status: 'PENDING',
      submittedAt: '2026-10-27T10:00:00Z', reviewToken: '1', actions: [{ action: 'approve' }],
      audit: [{ id: 'aud-1', actor: 'synthetic-admin-sub', action: 'APPROVE', previousStatus: 'PENDING', newStatus: 'APPROVED',
        occurredAt: '2026-10-27T11:00:00Z', reason: 'ADMIN_APPROVED', outcome: 'SUCCESS' }],
    })
  })

  it('drops unknown actions and malformed reason policies instead of offering them', async () => {
    const fetchMock = routed({
      '/api/v1/admin/merchants/m-uuid-1': () => json({ ...detailBody, status: 'APPROVED', actions: [
        { action: 'delete' }, { action: 'approve', reason: { required: 'yes', maxLength: 10 } },
        { action: 'suspend', reason: { required: true, maxLength: 500 } },
      ] }),
      '/api/v1/admin/merchants/m-uuid-1/audit': () => json([]),
    })
    const detail = await client(fetchMock).detail('m-uuid-1')
    expect(detail.actions).toEqual([{ action: 'suspend', reason: { required: true, maxLength: 500 } }])
    expect(detail.audit).toEqual([])
  })

  it.each([
    ['an audit read failure', () => json({ title: 'Internal' }, 500)],
    ['a malformed audit entry', () => json([{ id: 'aud-1', action: 'APPROVE' }])],
    ['a non-array audit body', () => json({ entries: auditBody })],
  ])('keeps details usable and reports audit unavailable after %s', async (_name, audit) => {
    const fetchMock = routed({ '/api/v1/admin/merchants/m-uuid-1': () => json(detailBody), '/api/v1/admin/merchants/m-uuid-1/audit': audit })
    const detail = await client(fetchMock).detail('m-uuid-1')
    expect(detail.id).toBe('m-uuid-1')
    expect(detail.audit).toBeUndefined()
  })

  it('rejects details for a different merchant', async () => {
    const fetchMock = routed({
      '/api/v1/admin/merchants/m-uuid-1': () => json({ ...detailBody, id: 'm-uuid-2' }),
      '/api/v1/admin/merchants/m-uuid-1/audit': () => json([]),
    })
    await expect(client(fetchMock).detail('m-uuid-1')).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('propagates cancellation rather than reporting a service failure', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      controller.abort()
      throw init?.signal?.reason
    })
    const error = await client(fetchMock).detail('m-uuid-1', controller.signal).catch((caught: unknown) => caught)
    expect(error).not.toBeInstanceOf(MerchantReviewError)
  })

  it('rejects a non-JSON read body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('<html>', { status: 200 }))
    await expect(client(fetchMock).list(0, 20)).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})

describe('createMerchantReviewApi commands', () => {
  it('approves with the server review token and returns the confirmed status', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ id: 'm-uuid-1', keycloakSub: 'merchant-sub', businessName: 'Synthetic Stores',
      registrationNumber: 'REG-100', status: 'APPROVED', createdAt: '2026-10-27T10:00:00Z', updatedAt: '2026-10-27T11:00:00Z' }))
    const outcome = await client(fetchMock).change!({ merchantId: 'm-uuid-1', action: 'approve', reviewToken: '1' }, new AbortController().signal)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/merchants/m-uuid-1/approve')
    expect(init).toMatchObject({ method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(init.headers).toEqual({ 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer test-admin-token' })
    expect(JSON.parse(init.body as string)).toEqual({ reviewToken: '1' })
    // Only the confirmed status is returned; the merchant's identity subject is not projected.
    expect(outcome).toEqual({ status: 'APPROVED' })
  })

  it('suspends with the reason and review token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ id: 'm-uuid-1', status: 'SUSPENDED' }))
    const outcome = await client(fetchMock).change!({ merchantId: 'm-uuid-1', action: 'suspend', reviewToken: '2', reason: 'Repeated billing failure' })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/v1/merchants/m-uuid-1/suspend')
    expect(JSON.parse((fetchMock.mock.calls[0]?.[1] as RequestInit).body as string)).toEqual({ reviewToken: '2', reason: 'Repeated billing failure' })
    expect(outcome).toEqual({ status: 'SUSPENDED' })
  })

  it.each([
    ['an unexpected resulting status', () => json({ id: 'm-uuid-1', status: 'PENDING' })],
    ['a different merchant', () => json({ id: 'm-uuid-2', status: 'APPROVED' })],
    ['an empty body', () => new Response(null, { status: 200 })],
    ['a non-JSON body', () => new Response('ok', { status: 200 })],
  ])('treats a 2xx with %s as unconfirmed', async (_name, response) => {
    const fetchMock = vi.fn(async () => response())
    await expect(client(fetchMock).change!({ merchantId: 'm-uuid-1', action: 'approve', reviewToken: '1' }))
      .rejects.toMatchObject({ kind: 'unconfirmed' })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('reports a network failure as ambiguous and never retries the command', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const error = await client(fetchMock).change!({ merchantId: 'm-uuid-1', action: 'approve', reviewToken: '1' }).catch((caught: unknown) => caught)
    expect(error).toMatchObject({ kind: 'unavailable' })
    expect(isAmbiguousCommandFailure((error as MerchantReviewError).kind)).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it.each([[409, 'conflict'], [403, 'forbidden'], [401, 'auth-required'], [400, 'validation']] as const)('maps command HTTP %s without retrying', async (status, kind) => {
    const fetchMock = vi.fn().mockResolvedValue(json({ type: 'about:blank', status, detail: 'Caller roles: [SECRET]' }, status))
    await expect(client(fetchMock).change!({ merchantId: 'm-uuid-1', action: 'approve', reviewToken: '1' })).rejects.toMatchObject({ kind })
    expect(fetchMock).toHaveBeenCalledOnce()
  })
})

describe('merchant review error and session handling', () => {
  it.each([
    [400, 'validation'], [401, 'auth-required'], [403, 'forbidden'], [404, 'not-found'], [409, 'conflict'], [412, 'conflict'],
    [422, 'validation'], [429, 'rate-limited'], [500, 'unavailable'], [502, 'unavailable'], [503, 'unavailable'], [504, 'unavailable'],
  ] as const)('maps HTTP %s to %s with a fixed message', (status, kind) => {
    const error = merchantReviewHttpError(status)
    expect(error.kind).toBe(kind)
    expect(error.message).not.toMatch(/SECRET|Caller roles/)
  })

  it('classifies only unknown-outcome failures as ambiguous', () => {
    expect(['unavailable', 'unconfirmed', 'invalid-response'].every(kind => isAmbiguousCommandFailure(kind as never))).toBe(true)
    expect(['conflict', 'forbidden', 'rate-limited', 'validation', 'auth-required', 'not-found'].some(kind => isAmbiguousCommandFailure(kind as never))).toBe(false)
  })

  it.each([null, '  '])('fails as auth-required without a request when the token is %j', async token => {
    const fetchMock = vi.fn()
    await expect(client(fetchMock, token).list(0, 20)).rejects.toMatchObject({ kind: 'auth-required' })
    await expect(client(fetchMock, token).change!({ merchantId: 'm-uuid-1', action: 'approve', reviewToken: '1' })).rejects.toMatchObject({ kind: 'auth-required' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('fails as auth-required when the token getter throws', async () => {
    const fetchMock = vi.fn()
    const api = createMerchantReviewApi({ getAccessToken: async () => { throw new Error('refresh failed') }, fetch: fetchMock })
    await expect(api.detail('m-uuid-1')).rejects.toMatchObject({ kind: 'auth-required' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('merchantReasonProblem', () => {
  const policy = { required: true, maxLength: 10 }
  it.each([
    ['', 'Enter a reason for this action.'],
    ['   ', 'Enter a reason for this action.'],
    ['x'.repeat(11), 'Reason must be 10 characters or fewer.'],
    ['bad\u0001text', 'Reason contains unsupported control characters.'],
    ['bad\u007f', 'Reason contains unsupported control characters.'],
  ])('rejects %j', (reason, problem) => {
    expect(merchantReasonProblem(reason, policy)).toBe(problem)
  })
  it.each(['  Fraud  ', 'line\nbreak', 'x'.repeat(10)])('accepts %j', reason => {
    expect(merchantReasonProblem(reason, policy)).toBeNull()
  })
  it('accepts an empty optional reason and anything without a policy', () => {
    expect(merchantReasonProblem('', { required: false, maxLength: 10 })).toBeNull()
    expect(merchantReasonProblem('\u0001')).toBeNull()
  })
})
