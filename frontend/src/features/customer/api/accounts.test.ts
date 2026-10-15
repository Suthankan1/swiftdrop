import { describe, expect, it, vi } from 'vitest'
import { customerFixture, merchantFixture } from '../../../test/fixtures'
import { AccountsError, createAccountsApi } from './accounts'

const testOnlyAccessToken = 'synthetic-test-only-access-value'

function createTestApi(response: Response) {
  const request = vi.fn<typeof fetch>().mockResolvedValue(response)
  const api = createAccountsApi({
    getAccessToken: async () => testOnlyAccessToken,
    gatewayUrl: 'http://localhost:8080/',
    fetch: request,
  })
  return { api, request }
}

describe('Accounts API adapter', () => {
  it.each([null, '', '   '])('rejects missing authentication (%j) before making any HTTP request', async (token) => {
    const request = vi.fn<typeof fetch>()
    const api = createAccountsApi({ getAccessToken: async () => token, fetch: request })

    await expect(api.getCurrentUser()).rejects.toMatchObject({ kind: 'auth-required' })
    expect(request).not.toHaveBeenCalled()
  })

  it('sanitizes token-provider failure and avoids an unauthenticated request', async () => {
    const request = vi.fn<typeof fetch>()
    const api = createAccountsApi({
      getAccessToken: async () => { throw new Error('Private session-provider details') },
      fetch: request,
    })

    await expect(api.getCurrentUser()).rejects.toMatchObject({
      kind: 'auth-required',
      message: 'The account profile could not be loaded.',
    })
    expect(request).not.toHaveBeenCalled()
  })

  it('requests the approved Gateway endpoint with an injected token and privacy controls', async () => {
    const { api, request } = createTestApi(Response.json(customerFixture))
    const controller = new AbortController()

    await expect(api.getCurrentUser(controller.signal)).resolves.toEqual(customerFixture)
    expect(request).toHaveBeenCalledExactlyOnceWith('http://localhost:8080/api/v1/users/me', {
      method: 'GET',
      headers: { Accept: 'application/json', Authorization: `Bearer ${testOnlyAccessToken}` },
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal,
    })
  })

  it('accepts the merchant DTO and opaque identifiers without imposing an invented status vocabulary', async () => {
    const { api } = createTestApi(Response.json(merchantFixture))

    await expect(api.getCurrentUser()).resolves.toEqual(merchantFixture)
  })

  it.each([
    [401, 'auth-required'],
    [403, 'forbidden'],
    [404, 'forbidden'],
    [503, 'unavailable'],
    [500, 'unavailable'],
  ])('maps HTTP %i to a safe %s failure without exposing server details', async (status, kind) => {
    const { api } = createTestApi(Response.json({ detail: 'Sensitive server details must not reach the UI.' }, { status }))

    const failure: unknown = await api.getCurrentUser().catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(AccountsError)
    expect(failure).toMatchObject({ kind })
    expect((failure as Error).message).not.toContain('Sensitive')
  })

  it('maps a network failure to service unavailability without leaking its raw message', async () => {
    const request = vi.fn<typeof fetch>().mockRejectedValue(new Error('Private network information'))
    const api = createAccountsApi({ getAccessToken: async () => testOnlyAccessToken, fetch: request })

    await expect(api.getCurrentUser()).rejects.toMatchObject({ kind: 'unavailable', message: 'The account profile could not be loaded.' })
  })

  it('preserves cancellation rather than reporting it as a service outage', async () => {
    const controller = new AbortController()
    const cancellation = new DOMException('Test request cancelled', 'AbortError')
    const request = vi.fn<typeof fetch>().mockRejectedValue(cancellation)
    const api = createAccountsApi({ getAccessToken: async () => testOnlyAccessToken, fetch: request })
    controller.abort()

    await expect(api.getCurrentUser(controller.signal)).rejects.toBe(cancellation)
  })

  it('rejects malformed JSON', async () => {
    const { api } = createTestApi(new Response('{invalid-json', { status: 200 }))

    await expect(api.getCurrentUser()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it.each([
    null,
    [],
    { ...customerFixture, keycloakSub: undefined },
    { ...customerFixture, roles: [42] },
    { ...customerFixture, merchantProfile: { merchantId: 'incomplete' } },
  ])('rejects a response that does not satisfy the agreed DTO (%j)', async (payload) => {
    const { api } = createTestApi(Response.json(payload))

    await expect(api.getCurrentUser()).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})
