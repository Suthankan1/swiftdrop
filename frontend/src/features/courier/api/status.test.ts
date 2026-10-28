import { afterEach, describe, expect, it, vi } from 'vitest'
import { createCourierStatusApi } from './status'
import { CourierStatusError } from './statusContracts'

const responsePayload = {
  parcelId: 'parcel-153',
  trackingNumber: 'SWD-20261027-0153',
  previousStatus: 'PICKED_UP',
  currentStatus: 'IN_TRANSIT',
  stateVersion: 4,
  updatedParcelVersion: 4,
  occurredAt: '2026-10-27T08:30:00Z',
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.useRealTimers()
})

describe('courier status API', () => {
  it('PATCHes the canonical URL with Bearer auth, actual version, trimmed note, and no idempotency header', async () => {
    const request = vi.fn<typeof fetch>(async () => jsonResponse(responsePayload))
    const api = createCourierStatusApi({
      getAccessToken: async () => 'courier-token',
      gatewayUrl: 'http://gateway.test/',
      fetch: request,
    })

    await expect(api.updateStatus('parcel-153', {
      newStatus: 'IN_TRANSIT',
      expectedVersion: 3,
      note: '  Departed origin hub.  ',
    })).resolves.toEqual(responsePayload)

    expect(request).toHaveBeenCalledOnce()
    const [url, init] = request.mock.calls[0]!
    expect(url).toBe('http://gateway.test/api/v1/parcels/parcel-153/status')
    expect(init).toMatchObject({
      method: 'PATCH',
      credentials: 'omit',
      cache: 'no-store',
      headers: {
        Authorization: 'Bearer courier-token',
        'Content-Type': 'application/json',
      },
    })
    expect((init?.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined()
    expect(JSON.parse(init?.body as string)).toEqual({
      newStatus: 'IN_TRANSIT',
      expectedVersion: 3,
      note: 'Departed origin hub.',
    })
  })

  it('omits blank optional fields and sends approved failure metadata', async () => {
    const request = vi.fn<typeof fetch>(async () => jsonResponse({
      ...responsePayload,
      currentStatus: 'FAILED',
    }))
    const api = createCourierStatusApi({ getAccessToken: async () => 'token', fetch: request })

    await api.updateStatus('parcel-153', {
      newStatus: 'FAILED',
      expectedVersion: 3,
      note: '   ',
      reason: 'PARCEL_DAMAGED',
    })

    expect(JSON.parse(request.mock.calls[0]![1]?.body as string)).toEqual({
      newStatus: 'FAILED',
      expectedVersion: 3,
      reason: 'PARCEL_DAMAGED',
    })
  })

  it('sends the exact proofId for delivery completion', async () => {
    const request = vi.fn<typeof fetch>(async () => jsonResponse({
      ...responsePayload,
      previousStatus: 'OUT_FOR_DELIVERY',
      currentStatus: 'DELIVERED',
    }))
    const api = createCourierStatusApi({ getAccessToken: async () => 'token', fetch: request })

    await api.updateStatus('parcel-153', {
      newStatus: 'DELIVERED',
      expectedVersion: 7,
      proofId: 'proof-server-confirmed',
    })

    expect(JSON.parse(request.mock.calls[0]![1]?.body as string)).toEqual({
      newStatus: 'DELIVERED',
      expectedVersion: 7,
      proofId: 'proof-server-confirmed',
    })
  })

  it.each([
    [400, 'validation-failed'],
    [401, 'auth-required'],
    [403, 'forbidden'],
    [404, 'not-found'],
    [409, 'conflict'],
    [422, 'illegal-transition'],
    [503, 'service-unavailable'],
  ] as const)('classifies HTTP %s as %s and retains safe ProblemDetail', async (status, kind) => {
    const request = vi.fn(async () => jsonResponse({
      title: 'Command rejected',
      status,
      detail: 'Safe provider detail',
      code: 'TEST_CODE',
    }, status))
    const api = createCourierStatusApi({ getAccessToken: async () => 'token', fetch: request })

    const error = await api.updateStatus('parcel-153', {
      newStatus: 'PICKED_UP',
      expectedVersion: 2,
    }).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(CourierStatusError)
    expect(error).toMatchObject({ kind, statusCode: status })
    expect((error as CourierStatusError).problemDetail?.detail).toBe('Safe provider detail')
  })

  it('treats network failure and request timeout as ambiguous outcomes', async () => {
    const networkApi = createCourierStatusApi({
      getAccessToken: async () => 'token',
      fetch: vi.fn(async () => { throw new TypeError('offline') }),
    })
    await expect(networkApi.updateStatus('parcel-153', {
      newStatus: 'PICKED_UP',
      expectedVersion: 2,
    })).rejects.toMatchObject({ kind: 'ambiguous-outcome' })

    vi.useFakeTimers()
    const timeoutApi = createCourierStatusApi({
      getAccessToken: async () => 'token',
      timeoutMs: 50,
      fetch: vi.fn((_url, init) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      })),
    })
    const pending = timeoutApi.updateStatus('parcel-153', {
      newStatus: 'PICKED_UP',
      expectedVersion: 2,
    })
    const assertion = expect(pending).rejects.toMatchObject({ kind: 'ambiguous-outcome' })
    await vi.advanceTimersByTimeAsync(51)
    await assertion
  })

  it('treats caller abort and invalid success payloads as ambiguous without retrying', async () => {
    const controller = new AbortController()
    const abortedRequest = vi.fn((_url, init) => new Promise<Response>((_resolve, reject) => {
      if (init?.signal?.aborted) {
        reject(new DOMException('aborted', 'AbortError'))
        return
      }
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    const abortedApi = createCourierStatusApi({ getAccessToken: async () => 'token', fetch: abortedRequest })
    const pending = abortedApi.updateStatus('parcel-153', {
      newStatus: 'PICKED_UP',
      expectedVersion: 2,
    }, controller.signal)
    const assertion = expect(pending).rejects.toMatchObject({ kind: 'ambiguous-outcome' })
    await Promise.resolve()
    controller.abort()
    await assertion
    expect(abortedRequest).toHaveBeenCalledOnce()

    const invalidRequest = vi.fn(async () => jsonResponse({ ok: true }))
    const invalidApi = createCourierStatusApi({ getAccessToken: async () => 'token', fetch: invalidRequest })
    await expect(invalidApi.updateStatus('parcel-153', {
      newStatus: 'PICKED_UP',
      expectedVersion: 2,
    })).rejects.toMatchObject({ kind: 'ambiguous-outcome' })
    expect(invalidRequest).toHaveBeenCalledOnce()
  })
})
