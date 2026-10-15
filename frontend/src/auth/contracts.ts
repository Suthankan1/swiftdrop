/** Integration contract for #36. No identity provider is implemented by #6. */
export type DevaromRole = 'CUSTOMER' | 'MERCHANT' | 'COURIER' | 'DISPATCHER' | 'ADMIN'

export type AuthSnapshot =
  | { status: 'loading'; subject: null; roles: readonly [] }
  | { status: 'unauthenticated'; subject: null; roles: readonly [] }
  | { status: 'authenticated'; subject: string; roles: readonly DevaromRole[] }

export interface AuthIntegration {
  snapshot: AuthSnapshot
  /** Resolve a currently valid token from memory; never persist or log it. */
  getAccessToken: () => Promise<string | null>
  login: () => Promise<void>
  /** Cancel requests and clear all private cache on logout or subject changes. */
  logout: () => Promise<void>
  /** #36 owns the single refresh attempt, cache clearing and login fallback. */
  handleSessionExpiry: () => Promise<void>
}
