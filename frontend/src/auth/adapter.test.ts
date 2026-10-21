/// <reference types="node" />
import Keycloak from 'keycloak-js'
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthSession } from './session'
const config = { url: 'http://localhost:8090', realm: 'swiftdrop', clientId: 'swiftdrop-frontend' }
const origin = 'http://localhost:3000'
function jwt(claims: object) {
  return `${btoa('{}')}.${btoa(JSON.stringify(claims))}.test-only-signature`
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('crypto', webcrypto)
})
afterEach(() => { localStorage.clear(); sessionStorage.clear() })
async function authorization() {
  const client = new Keycloak(config)
  const session = new AuthSession(client, origin)
  await session.initialize()
  const url = new URL(await client.createLoginUrl({ redirectUri: `${origin}/auth/callback` }))
  return { client, session, url }
}
describe('real Keycloak adapter with isolated token endpoint', () => {
  it('generates S256/state/nonce and exchanges code using the stored verifier without persisting tokens', async () => {
    const { url } = await authorization()
    expect(url.searchParams.get('response_type')).toBe('code')
    expect(url.searchParams.get('code_challenge_method')).toBe('S256')
    expect(url.searchParams.get('state')).toBeTruthy()
    expect(url.searchParams.get('nonce')).toBeTruthy()
    const now = Math.floor(Date.now() / 1000)
    const access = jwt({ sub: 'adapter-subject', exp: now + 300, iat: now, realm_access: { roles: ['CUSTOMER'] } })
    const id = jwt({ sub: 'adapter-subject', exp: now + 300, nonce: url.searchParams.get('nonce') })
    const request = vi.fn().mockResolvedValue(Response.json({ access_token: access, refresh_token: jwt({ sub: 'adapter-subject', exp: now + 1800 }), id_token: id }))
    vi.stubGlobal('fetch', request)
    window.history.replaceState(null, '', `/auth/callback?code=test-code&state=${url.searchParams.get('state')}`)
    const next = new AuthSession(new Keycloak(config), origin)
    await next.initialize()
    expect(next.getState().snapshot.status).toBe('authenticated')
    expect(request).toHaveBeenCalledTimes(1)
    const body = new URLSearchParams(request.mock.calls[0]![1].body)
    expect(body.get('grant_type')).toBe('authorization_code')
    expect(body.get('code_verifier')).toBeTruthy()
    const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(body.get('code_verifier')!))
    const challenge = btoa(String.fromCharCode(...new Uint8Array(digest))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
    expect(challenge).toBe(url.searchParams.get('code_challenge'))
    expect(window.location.search).toBe('')
    const persisted = JSON.stringify({ ...localStorage, ...sessionStorage })
    for (const value of [access, id, 'test-refresh']) expect(persisted).not.toContain(value)
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
    await next.logout()
    const form = document.querySelector('form')!
    expect(submit).toHaveBeenCalledTimes(1)
    expect(form.method).toBe('post')
    expect(form.action).not.toContain('id_token_hint')
    expect(form.querySelector<HTMLInputElement>('[name="id_token_hint"]')?.value).toBe(id)
    expect(next.getState().snapshot.status).toBe('unauthenticated')
    form.remove()
  })
  it('rejects unrecognized state without exchanging a code', async () => {
    const { url } = await authorization()
    const request = vi.fn()
    vi.stubGlobal('fetch', request)
    window.history.replaceState(null, '', `/auth/callback?code=test-code&state=wrong-${url.searchParams.get('state')}`)
    const next = new AuthSession(new Keycloak(config), origin)
    await next.initialize()
    expect(next.getState().snapshot.status).toBe('unauthenticated')
    expect(request).not.toHaveBeenCalled()
  })
  it('rejects a mismatched nonce and clears tokens', async () => {
    const { url } = await authorization()
    const now = Math.floor(Date.now() / 1000)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({
      access_token: jwt({ sub: 'test', exp: now + 300, iat: now }), refresh_token: jwt({ sub: 'adapter-subject', exp: now + 1800 }),
      id_token: jwt({ sub: 'test', exp: now + 300, nonce: 'incorrect' }),
    })))
    window.history.replaceState(null, '', `/auth/callback?code=test&state=${url.searchParams.get('state')}`)
    const client = new Keycloak(config)
    const next = new AuthSession(client, origin)
    await next.initialize()
    expect(next.getState().snapshot.status).toBe('unauthenticated')
    expect(client.token).toBeUndefined()
    expect(client.refreshToken).toBeUndefined()
  })
})
