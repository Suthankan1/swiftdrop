import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type Keycloak from 'keycloak-js'
import {
  AuthSession,
  browserSession,
  getAuthConfig,
  resetBrowserSessionForTesting,
  type OidcConfig,
} from './session'

function mockClient(options?: {
  url?: string
  realm?: string
  clientId?: string
  authenticated?: boolean
}) {
  const authenticated = options?.authenticated ?? false
  return {
    authServerUrl: options?.url ?? 'http://localhost:8090',
    realm: options?.realm ?? 'swiftdrop',
    clientId: options?.clientId ?? 'swiftdrop-frontend',
    authenticated,
    token: authenticated ? 'synthetic-access-jwt' : undefined,
    tokenParsed: authenticated ? { sub: 'synthetic-sub-001', exp: 9999999999 } : undefined,
    init: vi.fn().mockResolvedValue(authenticated),
    updateToken: vi.fn().mockResolvedValue(false),
    login: vi.fn().mockResolvedValue(undefined),
    register: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    clearToken: vi.fn(),
    isTokenExpired: vi.fn().mockReturnValue(false),
    hasRealmRole: vi.fn((role: string) => role === 'CUSTOMER'),
  }
}

describe('OIDC Adapter Multi-Environment Configuration', () => {
  beforeEach(() => {
    resetBrowserSessionForTesting()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState(null, '', '/')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({})))
  })

  afterEach(() => {
    resetBrowserSessionForTesting()
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllEnvs()
  })

  it('1. Safe default resolves canonical SwiftDrop configuration without env overrides', () => {
    const config = getAuthConfig()
    expect(config.url).toBe('http://localhost:8090')
    expect(config.realm).toBe('swiftdrop')
    expect(config.clientId).toBe('swiftdrop-frontend')
    expect(config.redirectUri).toContain('/auth/callback')
    expect(config.postLogoutRedirectUri).toMatch(/\/$/)
  })

  it('2. Legacy configuration is supported when VITE_* variables are explicitly set', () => {
    vi.stubEnv('VITE_KEYCLOAK_URL', 'http://localhost:8090')
    vi.stubEnv('VITE_KEYCLOAK_REALM', 'devarom')
    vi.stubEnv('VITE_KEYCLOAK_CLIENT_ID', 'devarom-frontend')
    vi.stubEnv('VITE_AUTH_REDIRECT_URI', 'http://localhost:5173/auth/callback')
    vi.stubEnv('VITE_AUTH_POST_LOGOUT_REDIRECT_URI', 'http://localhost:5173/')

    const config = getAuthConfig()
    expect(config.url).toBe('http://localhost:8090')
    expect(config.realm).toBe('devarom')
    expect(config.clientId).toBe('devarom-frontend')
    expect(config.redirectUri).toBe('http://localhost:5173/auth/callback')
    expect(config.postLogoutRedirectUri).toBe('http://localhost:5173/')
  })

  it('3. Legacy deployment session enforces devarom redirect and logout parameters', async () => {
    const legacyConfig: OidcConfig = {
      url: 'http://localhost:8090',
      realm: 'devarom',
      clientId: 'devarom-frontend',
      redirectUri: 'http://localhost:5173/auth/callback',
      postLogoutRedirectUri: 'http://localhost:5173/',
    }
    const client = mockClient({ realm: 'devarom', clientId: 'devarom-frontend' })
    const session = new AuthSession(client as unknown as Keycloak, 'http://localhost:5173', {
      redirectUri: legacyConfig.redirectUri,
      postLogoutRedirectUri: legacyConfig.postLogoutRedirectUri,
    })

    await session.initialize()
    expect(client.init).toHaveBeenCalledWith(
      expect.objectContaining({
        flow: 'standard',
        pkceMethod: 'S256',
        useNonce: true,
        logoutMethod: 'POST',
        redirectUri: 'http://localhost:5173/auth/callback',
      }),
    )

    await session.login()
    expect(client.login).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/auth/callback',
    })

    await session.googleLogin()
    expect(client.login).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/auth/callback',
      idpHint: 'google',
    })

    await session.logout()
    expect(client.logout).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/',
      logoutMethod: 'POST',
    })
  })

  it('4. Target SwiftDrop deployment session enforces swiftdrop redirect and logout parameters', async () => {
    const swiftDropConfig: OidcConfig = {
      url: 'http://localhost:8090',
      realm: 'swiftdrop',
      clientId: 'swiftdrop-frontend',
      redirectUri: 'http://localhost:5173/auth/callback',
      postLogoutRedirectUri: 'http://localhost:5173/',
    }
    const client = mockClient({ realm: 'swiftdrop', clientId: 'swiftdrop-frontend' })
    const session = new AuthSession(client as unknown as Keycloak, 'http://localhost:5173', {
      redirectUri: swiftDropConfig.redirectUri,
      postLogoutRedirectUri: swiftDropConfig.postLogoutRedirectUri,
    })

    await session.initialize()
    expect(client.init).toHaveBeenCalledWith(
      expect.objectContaining({
        flow: 'standard',
        pkceMethod: 'S256',
        useNonce: true,
        logoutMethod: 'POST',
        redirectUri: 'http://localhost:5173/auth/callback',
      }),
    )

    await session.login()
    expect(client.login).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/auth/callback',
    })

    await session.googleLogin()
    expect(client.login).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/auth/callback',
      idpHint: 'google',
    })

    await session.register()
    expect(client.register).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/auth/callback',
    })

    await session.logout()
    expect(client.logout).toHaveBeenCalledWith({
      redirectUri: 'http://localhost:5173/',
      logoutMethod: 'POST',
    })
  })

  it('5. browserSession factory creates and reuses session with either configuration', () => {
    const s1 = browserSession()
    expect(s1).toBeDefined()
    const s2 = browserSession()
    expect(s1).toBe(s2)

    resetBrowserSessionForTesting()
    const custom = browserSession({
      url: 'http://localhost:8090',
      realm: 'swiftdrop',
      clientId: 'swiftdrop-frontend',
      redirectUri: 'http://localhost:5173/auth/callback',
      postLogoutRedirectUri: 'http://localhost:5173/',
    })
    expect(custom).toBeDefined()
  })

  it('6. Credentials and tokens are held in-memory and never persisted to Web Storage in either configuration', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const client = mockClient({ realm: 'swiftdrop', clientId: 'swiftdrop-frontend', authenticated: true })
    const session = new AuthSession(client as unknown as Keycloak, 'http://localhost:5173')

    await session.initialize()
    const token = await session.getAccessToken()
    expect(token).toBe('synthetic-access-jwt')
    await session.logout()

    expect(setItem).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})
