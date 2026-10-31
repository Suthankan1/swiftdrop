/** Integration contract for #36. Canonical role type for SwiftDrop platform. */
export type SwiftDropRole = 'CUSTOMER' | 'MERCHANT' | 'COURIER' | 'DISPATCHER' | 'ADMIN'
/** Preserved alias for backward compatibility. */
export type DevaromRole = SwiftDropRole

export type AuthSnapshot =
  | { status: 'loading'; subject: null; roles: readonly [] }
  | { status: 'unauthenticated'; subject: null; roles: readonly [] }
  | { status: 'authenticated'; subject: string; roles: readonly SwiftDropRole[] }

export interface AuthIntegration {
  snapshot: AuthSnapshot
  /** Resolve a currently valid token from memory; never persist or log it. */
  getAccessToken: () => Promise<string | null>
  login: (returnUrl?: string) => Promise<void>
  /** Cancel requests and clear all private cache on logout or subject changes. */
  logout: () => Promise<void>
  /** #36 owns the single refresh attempt, cache clearing and login fallback. */
  handleSessionExpiry: () => Promise<void>
}
