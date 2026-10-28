import { Link, useInRouterContext } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { AccountsError, createAccountsApi } from '../api/accounts'
import { CustomerProfile, type ProfileState } from '../components/CustomerProfile'
import { CustomerNav } from '../components/CustomerNav'
import { CustomerDashboardCards } from '../components/CustomerDashboardCards'
import type { createCustomerDashboardApi } from '../api/customerDashboard'

export interface CustomerPageProps {
  dashboardApiOverride?: ReturnType<typeof createCustomerDashboardApi>
}

export function CustomerPage({ dashboardApiOverride }: CustomerPageProps = {}) {
  const auth = useAuth()
  const inRouter = useInRouterContext()
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
      <p className="muted">Your SwiftDrop account starts here, with your personal details kept private.</p></header>
    <CustomerNav />
    {state.status === 'ready' && (
      <CustomerDashboardCards key={auth.snapshot.subject} apiOverride={dashboardApiOverride} />
    )}
    <div className="profile-grid" style={{ marginTop: '24px' }}><CustomerProfile state={state} />
      <aside className="card customer-note" aria-labelledby="customer-note-heading"><h2 id="customer-note-heading">Built around you.</h2>
        <p>Calculate dynamic rates, book delivery dispatches, monitor active milestones, and download official receipts.</p>
        {inRouter ? (
          <Link to="/customer/quote" className="button button-primary">Request a quote &amp; book</Link>
        ) : (
          <a href="/customer/quote" className="button button-primary">Request a quote &amp; book</a>
        )}
        {inRouter ? (
          <Link to="/customer/history" className="button button-secondary">View delivery history</Link>
        ) : (
          <a href="/customer/history" className="button button-secondary">View delivery history</a>
        )}
        {inRouter ? (
          <Link to="/merchant/enroll" className="button button-secondary">Merchant onboarding ↗</Link>
        ) : (
          <a href="/merchant/enroll" className="button button-secondary">Merchant onboarding ↗</a>
        )}
        <button onClick={() => { void auth.logout() }}>Sign out</button>
        {query.isError && <button onClick={() => { void query.refetch() }}>Retry profile</button>}
      </aside></div>
  </div>
}
