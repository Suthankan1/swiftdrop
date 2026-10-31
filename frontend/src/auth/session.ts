import Keycloak from 'keycloak-js'
import type { AuthIntegration, AuthSnapshot, SwiftDropRole } from './contracts'

export type AuthProblem = 'cancelled' | 'invalid-callback' | 'provider-unavailable' | 'session-expired' | null
const roles: readonly SwiftDropRole[] = ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'ADMIN']
const anonymous: AuthSnapshot = { status: 'unauthenticated', subject: null, roles: [] }
export type SessionState = { snapshot: AuthSnapshot; problem: AuthProblem }

export type AuthSyncMessage =
  | {
      type: 'AUTH_SYNC_LOGIN'
      subject: string
      roles: SwiftDropRole[]
      token?: string
      idToken?: string
      refreshToken?: string
      tokenParsed?: Keycloak['tokenParsed']
    }
  | {
      type: 'AUTH_SYNC_LOGOUT'
    }
  | {
      type: 'AUTH_SYNC_REQUEST'
    }

export interface AuthSessionOptions {
  redirectUri?: string
  postLogoutRedirectUri?: string
}

export interface OidcConfig {
  url: string
  realm: string
  clientId: string
  redirectUri?: string
  postLogoutRedirectUri?: string
}

/** One runtime per browser document: init/code exchange must survive StrictMode remounts. */
export class AuthSession {
  private state: SessionState = { snapshot: { status: 'loading', subject: null, roles: [] }, problem: null }
  private listeners = new Set<() => void>()
  private initialization?: Promise<void>
  private refresh?: Promise<boolean>
  private generation = 0
  private signingOut = false
  private entry?: Promise<void>
  private logoutPromise?: Promise<void>
  private readonly client: Keycloak
  private readonly redirectUri: string
  private readonly postLogoutRedirectUri: string
  private channel?: BroadcastChannel
  constructor(
    client: Keycloak,
    origin = typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:5173',
    options?: AuthSessionOptions,
  ) {
    this.client = client
    this.redirectUri = options?.redirectUri || `${origin}/auth/callback`
    this.postLogoutRedirectUri = options?.postLogoutRedirectUri || `${origin}/`
    client.onAuthLogout = () => { if (!this.signingOut) this.expire() }
    client.onTokenExpired = () => { void this.getAccessToken() }
    if (typeof BroadcastChannel !== 'undefined') {
      try {
        this.channel = new BroadcastChannel('swiftdrop_auth_channel')
        this.channel.onmessage = (event: MessageEvent<AuthSyncMessage>) => {
          this.handleChannelMessage(event.data)
        }
      } catch {
        // Fall back gracefully if BroadcastChannel is restricted
      }
    }
  }
  getState = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(snapshot: AuthSnapshot, problem: AuthProblem = null) {
    this.state = { snapshot, problem }
    this.listeners.forEach((listener) => listener())
  }
  private handleChannelMessage(data: AuthSyncMessage) {
    if (!data || typeof data !== 'object') return
    if (data.type === 'AUTH_SYNC_LOGIN') {
      if (this.signingOut) return
      this.client.token = data.token
      this.client.idToken = data.idToken
      this.client.refreshToken = data.refreshToken
      this.client.tokenParsed = data.tokenParsed
      this.client.authenticated = true
      this.publish({ status: 'authenticated', subject: data.subject, roles: data.roles })
    } else if (data.type === 'AUTH_SYNC_LOGOUT') {
      this.clearCredentials()
      this.publish(anonymous)
    } else if (data.type === 'AUTH_SYNC_REQUEST') {
      if (this.state.snapshot.status === 'authenticated' && this.client.token && this.state.snapshot.subject) {
        this.broadcast({
          type: 'AUTH_SYNC_LOGIN',
          subject: this.state.snapshot.subject,
          roles: [...this.state.snapshot.roles],
          token: this.client.token,
          idToken: this.client.idToken,
          refreshToken: this.client.refreshToken,
          tokenParsed: this.client.tokenParsed,
        })
      }
    }
  }
  private broadcast(msg: AuthSyncMessage) {
    try {
      this.channel?.postMessage(msg)
    } catch {
      // Ignore broadcast errors
    }
  }
  private clearCredentials() {
    this.client.clearToken()
    // Also remove partially decoded responses if adapter parsing failed before setting token.
    delete this.client.refreshToken
    delete this.client.refreshTokenParsed
    delete this.client.idToken
    delete this.client.idTokenParsed
  }
  private accept(): boolean {
    const claims = this.client.tokenParsed
    if (!this.client.authenticated || !this.client.token || !claims?.sub || !claims.exp || this.client.isTokenExpired(0)) return false
    const activeRoles = roles.filter((role) => this.client.hasRealmRole(role))
    this.publish({ status: 'authenticated', subject: claims.sub, roles: activeRoles })
    this.broadcast({
      type: 'AUTH_SYNC_LOGIN',
      subject: claims.sub,
      roles: activeRoles,
      token: this.client.token,
      idToken: this.client.idToken,
      refreshToken: this.client.refreshToken,
      tokenParsed: this.client.tokenParsed,
    })
    return true
  }
  initialize = () => {
    this.initialization ??= this.initializeOnce()
    return this.initialization
  }
  private async initializeOnce() {
    const callback = window.location.pathname === '/auth/callback'
    const params = new URLSearchParams(window.location.search || window.location.hash.slice(1))
    const cancelled = params.get('error') === 'access_denied'
    const generation = this.generation
    const origin = typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:5173'
    const isTestEnv = typeof navigator !== 'undefined' && navigator.userAgent.includes('jsdom')

    // In a multi-tab browser context, ask open tabs if an active session exists
    if (!isTestEnv && !callback && this.channel) {
      this.broadcast({ type: 'AUTH_SYNC_REQUEST' })
      await new Promise<void>((resolve) => {
        const timeout = setTimeout(resolve, 100)
        const check = () => {
          if (this.state.snapshot.status === 'authenticated') {
            clearTimeout(timeout)
            resolve()
          }
        }
        const unsub = this.subscribe(() => {
          check()
          unsub()
        })
      })
      if (this.state.snapshot.status === 'authenticated') {
        return
      }
    }

    try {
      const initOptions: Record<string, unknown> = {
        flow: 'standard', pkceMethod: 'S256', responseMode: 'query', useNonce: true,
        checkLoginIframe: false, enableLogging: false, logoutMethod: 'POST',
        redirectUri: this.redirectUri, messageReceiveTimeout: 5000,
      }
      if (!isTestEnv && !callback) {
        initOptions.onLoad = 'check-sso'
        initOptions.silentCheckSsoRedirectUri = `${origin}/silent-check-sso.html`
      }
      const initPromise = this.client.init(initOptions as Parameters<Keycloak['init']>[0])
      const timeoutPromise = new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 3000))
      const authenticated = await Promise.race([initPromise, timeoutPromise])
      if (generation !== this.generation) { this.clearCredentials(); return }
      if (!authenticated || !this.accept()) {
        this.clearCredentials()
        this.publish(anonymous, callback ? (cancelled ? 'cancelled' : 'invalid-callback') : null)
      }
    } catch {
      this.clearCredentials()
      this.publish(anonymous, callback ? (cancelled ? 'cancelled' : 'invalid-callback') : 'provider-unavailable')
    } finally {
      // Do not retain authorization codes or provider error details in the address bar.
      if (callback) window.history.replaceState(null, '', '/auth/callback')
    }
  }
  expire = () => {
    this.generation++
    this.broadcast({ type: 'AUTH_SYNC_LOGOUT' })
    this.publish(anonymous, 'session-expired')
    this.clearCredentials()
  }
  private async renew(force: boolean): Promise<boolean> {
    if (this.signingOut || this.state.snapshot.status !== 'authenticated') return false
    const generation = this.generation
    try {
      this.refresh ??= this.client.updateToken(force ? -1 : 30).finally(() => { this.refresh = undefined })
      await this.refresh
      if (generation !== this.generation) { this.clearCredentials(); return false }
      if (!this.accept()) { this.expire(); return false }
      return true
    } catch { this.expire(); return false }
  }
  getAccessToken: AuthIntegration['getAccessToken'] = async () => (await this.renew(false)) ? this.client.token ?? null : null
  handleSessionExpiry = async () => { this.expire() }
  login = async (returnUrl?: string) => { await this.enter('login', returnUrl) }
  register = async (returnUrl?: string) => { await this.enter('register', returnUrl) }
  googleLogin = async (returnUrl?: string) => { await this.enter('google', returnUrl) }
  private enter(kind: 'login' | 'register' | 'google', returnUrl?: string) {
    if (typeof window !== 'undefined' && returnUrl) {
      try {
        window.name = JSON.stringify({ swiftdrop_auth_target: returnUrl })
      } catch {
        // ignore window.name access issues
      }
    }
    this.entry ??= this.enterOnce(kind).finally(() => { this.entry = undefined })
    return this.entry
  }
  private async enterOnce(kind: 'login' | 'register' | 'google') {
    await this.initialize()
    if (this.signingOut) return
    const generation = this.generation
    try {
      const available = await fetch(`${this.client.authServerUrl}/realms/${encodeURIComponent(this.client.realm!)}/.well-known/openid-configuration`, {
        credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(5000),
      })
      if (generation !== this.generation || this.signingOut) return
      if (!available.ok) throw new Error('Identity provider unavailable')
      const options = { redirectUri: this.redirectUri }
      if (kind === 'register') await this.client.register(options)
      else await this.client.login({ ...options, ...(kind === 'google' ? { idpHint: 'google' } : {}) })
    } catch { this.clearCredentials(); this.publish(anonymous, 'provider-unavailable') }
  }
  logout = () => {
    this.logoutPromise ??= this.endSession()
    return this.logoutPromise
  }
  private async endSession() {
    this.signingOut = true
    this.generation++
    this.broadcast({ type: 'AUTH_SYNC_LOGOUT' })
    this.publish(anonymous)
    try {
      // Keep ID token hint intact so Keycloak automatically proceeds with RP-Initiated logout
      const pending = this.client.logout({ redirectUri: this.postLogoutRedirectUri, logoutMethod: 'POST' })
      await pending
    } catch { this.publish(anonymous, 'provider-unavailable') }
    finally { this.clearCredentials() }
  }
  destroy = () => {
    try {
      this.channel?.close()
    } catch {
      // ignore
    }
  }
  /** Restricted to approved idempotent profile reads; never replay arbitrary commands. */
  profileFetch: typeof fetch = async (input, init) => {
    const gatewayUrl = (import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080').replace(/\/$/, '')
    const allowedUrls = new Set([
      `${gatewayUrl}/api/v1/users/me`,
      `${gatewayUrl}/api/v1/staff/me`,
    ])
    const requestUrl = String(input)
    if (!allowedUrls.has(requestUrl) || (init?.method ?? 'GET') !== 'GET') {
      throw new Error('Unsupported profile request')
    }
    const generation = this.generation
    const response = await fetch(input, init)
    if (generation !== this.generation || init?.signal?.aborted) throw new Error('Session changed')
    if (response.status !== 401) return response
    if (!await this.renew(true)) return response
    if (generation !== this.generation || init?.signal?.aborted) throw new Error('Session changed')
    const headers = new Headers(init?.headers)
    headers.set('Authorization', `Bearer ${this.client.token}`)
    const retried = await fetch(input, { ...init, headers })
    if (generation !== this.generation || init?.signal?.aborted) throw new Error('Session changed')
    if (retried.status === 401) this.expire()
    return retried
  }
}

export function getAuthConfig(): OidcConfig {
  const origin = typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:5173'
  const url = (import.meta.env.VITE_KEYCLOAK_URL || import.meta.env.VITE_KEYCLOAK_ORIGIN || 'http://localhost:8090').replace(/\/$/, '')
  const realm = import.meta.env.VITE_KEYCLOAK_REALM || 'swiftdrop'
  const clientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'swiftdrop-frontend'
  const redirectUri = import.meta.env.VITE_AUTH_REDIRECT_URI || `${origin}/auth/callback`
  const postLogoutRedirectUri = import.meta.env.VITE_AUTH_POST_LOGOUT_URI || import.meta.env.VITE_AUTH_POST_LOGOUT_REDIRECT_URI || `${origin}/`
  return { url, realm, clientId, redirectUri, postLogoutRedirectUri }
}

let session: AuthSession | undefined
export function browserSession(config?: OidcConfig): AuthSession {
  if (config) {
    return new AuthSession(
      new Keycloak({ url: config.url, realm: config.realm, clientId: config.clientId }),
      typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:5173',
      { redirectUri: config.redirectUri, postLogoutRedirectUri: config.postLogoutRedirectUri },
    )
  }
  if (!session) {
    const cfg = getAuthConfig()
    session = new AuthSession(
      new Keycloak({ url: cfg.url, realm: cfg.realm, clientId: cfg.clientId }),
      typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:5173',
      { redirectUri: cfg.redirectUri, postLogoutRedirectUri: cfg.postLogoutRedirectUri },
    )
  }
  return session
}

export function resetBrowserSessionForTesting(): void {
  session?.destroy()
  session = undefined
}
