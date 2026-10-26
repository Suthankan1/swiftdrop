import {
  isStaffProfile,
  StaffProfileError,
  type StaffProfile,
} from './contracts'

export interface StaffApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

export function createStaffApi({
  getAccessToken,
  gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080',
  fetch: fetchRequest = globalThis.fetch,
}: StaffApiOptions) {
  return {
    async getStaffProfile(signal?: AbortSignal): Promise<StaffProfile> {
      let token: string | null
      try {
        token = await getAccessToken()
      } catch {
        throw new StaffProfileError('auth-required')
      }
      if (!token?.trim()) {
        throw new StaffProfileError('auth-required')
      }

      const targetUrl = `${gatewayUrl.replace(/\/$/, '')}/api/v1/staff/me`

      let response: Response
      try {
        response = await fetchRequest(targetUrl, {
          method: 'GET',
          headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
          },
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw error
        throw new StaffProfileError('unavailable')
      }

      if (response.status === 401) {
        throw new StaffProfileError('auth-required')
      }
      if (response.status === 403) {
        throw new StaffProfileError('forbidden')
      }
      if (response.status === 404) {
        throw new StaffProfileError('not-found')
      }
      if (!response.ok) {
        throw new StaffProfileError('unavailable')
      }

      let data: unknown
      try {
        data = await response.json()
      } catch {
        throw new StaffProfileError('invalid-response')
      }

      if (!isStaffProfile(data)) {
        throw new StaffProfileError('invalid-response')
      }

      const profileId = (data.profileId ?? data.id) as string
      const status = (data.businessStatus ?? data.status) as StaffProfile['businessStatus']

      return {
        ...data,
        id: (data.id ?? data.profileId) as string,
        profileId,
        status,
        businessStatus: status,
        phoneNumber: data.phoneNumber ?? null,
        assignedZone: data.assignedZone ?? null,
      }
    },
  }
}
