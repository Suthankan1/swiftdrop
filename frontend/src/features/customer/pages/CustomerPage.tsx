import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { AccountsError, createAccountsApi } from '../api/accounts'
import { CustomerProfile, type ProfileState } from '../components/CustomerProfile'

export function CustomerPage() {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const query = useQuery({
    queryKey: ['private', 'profile', subject],
    enabled: auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('CUSTOMER'),
    queryFn: async ({ signal }) => {
      const api = createAccountsApi({ getAccessToken: auth.getAccessToken, fetch: auth.profileFetch })
      const profile = await api.getCurrentUser(signal)
      if (signal.aborted || profile.keycloakSub !== subject) throw new AccountsError('forbidden')
      return profile
    },
    gcTime: 0,
  })
  let state: ProfileState = { status: 'auth-required' }
  if (auth.snapshot.status === 'authenticated') {
    if (!auth.snapshot.roles.includes('CUSTOMER')) state = { status: 'forbidden' }
    else if (query.isPending) state = { status: 'loading' }
    else if (query.error) state = { status: query.error instanceof AccountsError && query.error.kind === 'forbidden' ? 'forbidden' : 'unavailable' }
    else if (query.data?.keycloakSub === subject) state = { status: 'ready', profile: query.data }
  }
  return <div className="page-container customer-page">
    <header className="page-heading"><p className="eyebrow">Customer space</p><h1>A little space for<br />everything you send.</h1>
      <p className="muted">Your Devarom account starts here, with your personal details kept private.</p></header>
    <div className="profile-grid"><CustomerProfile state={state} />
      <aside className="card customer-note" aria-labelledby="customer-note-heading"><h2 id="customer-note-heading">Built around you.</h2>
        <p>Booking and parcel tracking will arrive in a later release.</p><button onClick={() => { void auth.logout() }}>Sign out</button>
        {query.isError && <button onClick={() => { void query.refetch() }}>Retry profile</button>}
      </aside></div>
  </div>
}
