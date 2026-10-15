import type { CurrentUserProfile, MerchantProfile } from './contracts'

export type ProfileFailure = 'auth-required' | 'forbidden' | 'unavailable' | 'invalid-response'

export class AccountsError extends Error {
  readonly kind: ProfileFailure

  constructor(kind: ProfileFailure) {
    super('The account profile could not be loaded.')
    this.name = 'AccountsError'
    this.kind = kind
  }
}

interface AccountsOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isMerchantProfile(value: unknown): value is MerchantProfile {
  return isRecord(value) &&
    ['merchantId', 'businessName', 'registrationNumber', 'status'].every(
      (key) => typeof value[key] === 'string',
    )
}

function isCurrentUserProfile(value: unknown): value is CurrentUserProfile {
  return isRecord(value) &&
    ['id', 'keycloakSub', 'email', 'fullName', 'phoneNumber', 'createdAt'].every(
      (key) => typeof value[key] === 'string',
    ) &&
    Array.isArray(value.roles) && value.roles.every((role) => typeof role === 'string') &&
    (value.merchantProfile === null || isMerchantProfile(value.merchantProfile))
}

/** Inactive until #36 supplies its token getter; never calls /me without a token. */
export function createAccountsApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: AccountsOptions) {
  return {
    async getCurrentUser(signal?: AbortSignal): Promise<CurrentUserProfile> {
      let token: string | null
      try {
        token = await getAccessToken()
      } catch {
        throw new AccountsError('auth-required')
      }
      if (!token?.trim()) throw new AccountsError('auth-required')

      let response: Response
      try {
        response = await fetchRequest(`${gatewayUrl.replace(/\/$/, '')}/api/v1/users/me`, {
          method: 'GET',
          headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new AccountsError('unavailable')
      }

      if (response.status === 401) throw new AccountsError('auth-required')
      if (response.status === 403 || response.status === 404) throw new AccountsError('forbidden')
      if (!response.ok) throw new AccountsError('unavailable')

      let profile: unknown
      try {
        profile = await response.json()
      } catch {
        throw new AccountsError('invalid-response')
      }
      if (!isCurrentUserProfile(profile)) throw new AccountsError('invalid-response')
      return profile
    },
  }
}
