import { beforeEach, describe, expect, it, vi } from 'vitest'
import type Keycloak from 'keycloak-js'
import { AuthSession } from './session'
function fixture(authenticated = false) {
  const client = {
    authServerUrl: 'http://localhost:8090', realm: 'swiftdrop', authenticated, token: authenticated ? 'test-access' : undefined,
    tokenParsed: authenticated ? { sub: 'subject-a', exp: 9999999999 } : undefined,
    init: vi.fn().mockResolvedValue(authenticated), updateToken: vi.fn().mockResolvedValue(false),
    login: vi.fn().mockResolvedValue(undefined), register: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined), clearToken: vi.fn(),
    isTokenExpired: vi.fn().mockReturnValue(false), hasRealmRole: vi.fn((role: string) => role === 'CUSTOMER'),
  }
  return { client, session: new AuthSession(client as unknown as Keycloak, 'http://localhost:5173') }
}
beforeEach(() => { window.history.replaceState(null, '', '/'); vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({}))) })
describe('in-memory Keycloak session', () => {
  it('initializes once with standard flow, S256, nonce, no iframe and POST logout', async () => {
    const { session, client } = fixture()
    await Promise.all([session.initialize(), session.initialize()])
    expect(client.init).toHaveBeenCalledTimes(1)
    expect(client.init).toHaveBeenCalledWith(expect.objectContaining({ flow: 'standard', pkceMethod: 'S256', useNonce: true, logoutMethod: 'POST' }))
    expect(session.getState().snapshot.status).toBe('unauthenticated')
  })
  it('delegates login, Google broker and registration without any role request', async () => {
    const { session, client } = fixture()
    await session.login(); await session.googleLogin(); await session.register()
    expect(client.login).toHaveBeenNthCalledWith(1, { redirectUri: 'http://localhost:5173/auth/callback' })
    expect(client.login).toHaveBeenNthCalledWith(2, { redirectUri: 'http://localhost:5173/auth/callback', idpHint: 'google' })
    expect(client.register).toHaveBeenCalledWith({ redirectUri: 'http://localhost:5173/auth/callback' })
  })
  it('accepts only a live authenticated client and known realm roles', async () => {
    const { session, client } = fixture(true)
    await session.initialize()
    expect(session.getState().snapshot).toEqual({ status: 'authenticated', subject: 'subject-a', roles: ['CUSTOMER'] })
    expect(await session.getAccessToken()).toBe('test-access')
    expect(client.updateToken).toHaveBeenCalledWith(30)
  })
  it.each(['access_denied', 'invalid_state'])('sanitizes failed callback %s and clears stale state', async (error) => {
    window.history.replaceState(null, '', `/auth/callback?error=${error}&state=untrusted&code=sensitive`)
    const { session, client } = fixture(true)
    client.init.mockRejectedValue(new Error('sensitive provider error'))
    await session.initialize()
    expect(session.getState()).toEqual({ snapshot: { status: 'unauthenticated', subject: null, roles: [] }, problem: error === 'access_denied' ? 'cancelled' : 'invalid-callback' })
    expect(window.location.search).toBe('')
    expect(client.clearToken).toHaveBeenCalled()
  })
  it('rejects an unrecognized callback even when the library returns false', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=orphan')
    const { session } = fixture()
    await session.initialize()
    expect(session.getState().problem).toBe('invalid-callback')
  })
  it('does not claim authentication from incomplete or expired state', async () => {
    const { session, client } = fixture(true)
    client.isTokenExpired.mockReturnValue(true)
    await session.initialize()
    expect(session.getState().snapshot.status).toBe('unauthenticated')
  })
  it('handles provider outage and redirect failure without exposing errors', async () => {
    const { session, client } = fixture()
    client.init.mockRejectedValue(new Error('secret'))
    await session.initialize()
    expect(session.getState().problem).toBe('provider-unavailable')
    client.login.mockRejectedValue(new Error('secret'))
    await session.login()
    expect(JSON.stringify(session.getState())).not.toContain('secret')
  })
  it('keeps the user in a safe state when discovery is unavailable', async () => {
    const { session, client } = fixture()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    await session.login()
    expect(client.login).not.toHaveBeenCalled()
    expect(session.getState().problem).toBe('provider-unavailable')
    expect(session.getState().snapshot.status).toBe('unauthenticated')
  })
  it('expires locally on refresh failure and never returns the stale token', async () => {
    const { session, client } = fixture(true)
    await session.initialize()
    client.updateToken.mockRejectedValue(new Error('expired'))
    expect(await session.getAccessToken()).toBeNull()
    expect(session.getState().problem).toBe('session-expired')
  })
  it('deduplicates refresh and rejects refresh completion after logout', async () => {
    const { session, client } = fixture(true)
    await session.initialize()
    let finish!: (value: boolean) => void
    client.updateToken.mockReturnValue(new Promise<boolean>((resolve) => { finish = resolve }))
    const a = session.getAccessToken(); const b = session.getAccessToken()
    await session.logout(); finish(true)
    expect(await a).toBeNull(); expect(await b).toBeNull()
    expect(client.updateToken).toHaveBeenCalledTimes(1)
  })
  it('clears local state and deduplicates server logout even if it fails', async () => {
    const { session, client } = fixture(true)
    await session.initialize()
    client.logout.mockRejectedValue(new Error('offline'))
    await Promise.all([session.logout(), session.logout()])
    expect(client.logout).toHaveBeenCalledTimes(1)
    expect(client.logout).toHaveBeenCalledWith({ redirectUri: 'http://localhost:5173/', logoutMethod: 'POST' })
    expect(session.getState().snapshot.subject).toBeNull()
    expect(await session.getAccessToken()).toBeNull()
  })
  it('retries a 401 once with a refreshed Bearer and expires on repeated rejection', async () => {
    const { session, client } = fixture(true)
    await session.initialize()
    client.updateToken.mockImplementation(async () => { client.token = 'refreshed-test-token'; return true })
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', request)
    await session.profileFetch('http://localhost:8080/api/v1/users/me', { headers: { Authorization: 'Bearer test-access' } })
    expect(request).toHaveBeenCalledTimes(2)
    expect(client.updateToken).toHaveBeenCalledWith(-1)
    expect(new Headers(request.mock.calls[1]![1].headers).get('Authorization')).toBe('Bearer refreshed-test-token')
    expect(session.getState().problem).toBe('session-expired')
  })
  it('never persists access or refresh tokens during initialization/refresh/logout', async () => {
    const persist = vi.spyOn(Storage.prototype, 'setItem')
    const { session } = fixture(true)
    await session.initialize(); await session.getAccessToken(); await session.logout()
    expect(persist).not.toHaveBeenCalled()
  })
})
