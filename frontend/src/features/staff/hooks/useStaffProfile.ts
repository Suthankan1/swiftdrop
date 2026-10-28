import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { createStaffApi } from '../api/staffProfile'
import { StaffProfileError, type StaffRole } from '../api/contracts'
import type { StaffProfileState } from '../components/StaffProfile'

export function useStaffProfile(expectedRole: StaffRole) {
  const auth = useAuth()
  const subject = auth.snapshot.subject

  const query = useQuery({
    queryKey: ['private', 'staff-profile', subject],
    enabled:
      auth.snapshot.status === 'authenticated' &&
      auth.snapshot.roles.includes(expectedRole) &&
      Boolean(subject),
    queryFn: async ({ signal }) => {
      const api = createStaffApi({
        getAccessToken: auth.getAccessToken,
        fetch: auth.profileFetch,
      })
      const profile = await api.getStaffProfile(signal)
      if (signal.aborted || profile.keycloakSub !== subject) {
        throw new StaffProfileError('forbidden')
      }
      return profile
    },
    gcTime: 0,
    retry: false,
  })

  let state: StaffProfileState = { status: 'auth-required' }

  if (auth.snapshot.status === 'authenticated') {
    if (!auth.snapshot.roles.includes(expectedRole)) {
      state = { status: 'forbidden' }
    } else if (query.isPending) {
      state = { status: 'loading' }
    } else if (query.error) {
      if (query.error instanceof StaffProfileError) {
        if (query.error.kind === 'not-found') {
          state = { status: 'not-found' }
        } else if (query.error.kind === 'forbidden') {
          state = { status: 'forbidden' }
        } else if (query.error.kind === 'invalid-response') {
          state = { status: 'invalid-response' }
        } else if (query.error.kind === 'auth-required') {
          state = { status: 'auth-required' }
        } else {
          state = { status: 'unavailable' }
        }
      } else {
        state = { status: 'unavailable' }
      }
    } else if (query.data) {
      const data = query.data
      if (data.keycloakSub !== subject) {
        state = { status: 'forbidden' }
      } else if (data.role !== expectedRole) {
        state = { status: 'wrong-role', expectedRole, actualRole: data.role }
      } else if (data.businessStatus !== 'ACTIVE') {
        state = { status: 'inactive', profile: data }
      } else {
        state = { status: 'ready', profile: data }
      }
    }
  }

  return { state, query, refetch: query.refetch }
}
