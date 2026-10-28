import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { AccountsError, createAccountsApi } from '../api/merchant'
import { MerchantProfile, type MerchantProfileState } from '../components/MerchantProfile'
import { MerchantNav } from '../components/MerchantNav'

export function MerchantPage() {
  const auth = useAuth()
  const subject = auth.snapshot.subject

  const query = useQuery({
    queryKey: ['private', 'profile', subject],
    enabled: auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT'),
    queryFn: async ({ signal }) => {
      const api = createAccountsApi({ getAccessToken: auth.getAccessToken, fetch: auth.profileFetch })
      const profile = await api.getCurrentUser(signal)
      if (signal.aborted || profile.keycloakSub !== subject) throw new AccountsError('forbidden')
      return profile
    },
    gcTime: 0,
  })

  let state: MerchantProfileState = { status: 'auth-required' }

  if (auth.snapshot.status === 'authenticated') {
    if (!auth.snapshot.roles.includes('MERCHANT')) {
      state = { status: 'forbidden' }
    } else if (query.isPending) {
      state = { status: 'loading' }
    } else if (query.error) {
      state = {
        status:
          query.error instanceof AccountsError && query.error.kind === 'forbidden'
            ? 'forbidden'
            : 'unavailable',
      }
    } else if (query.data?.keycloakSub === subject) {
      if (!query.data.merchantProfile) {
        state = { status: 'empty' }
      } else {
        state = { status: 'ready', profile: query.data, merchant: query.data.merchantProfile }
      }
    }
  }

  return (
    <div className="page-container merchant-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>Commercial logistics.<br />Delivered seamlessly.</h1>
        <p className="muted">
          Manage your business shipments, track approval status, and coordinate deliveries.
        </p>
      </header>

      <MerchantNav />

      <div className="profile-grid">
        <MerchantProfile state={state} />

        <aside className="card merchant-note" aria-labelledby="merchant-note-heading">
          <h2 id="merchant-note-heading">Merchant operations</h2>
          <p className="muted">
            Batch order dispatching, shipping labels, and real-time webhook tracking will arrive in the upcoming delivery milestone.
          </p>
          <Link
            to="/merchant/orders"
            className="button button-primary"
          >
            Orders Dashboard ↗
          </Link>
          <Link
            to="/merchant/credentials"
            className="button button-secondary"
          >
            API Credentials ↗
          </Link>
          <Link
            to="/merchant/webhooks"
            className="button button-secondary"
          >
            Webhook Subscriptions ↗
          </Link>
          <button
            className="button button-secondary"
            type="button"
            onClick={() => { void auth.logout() }}
          >
            Sign out
          </button>
          {query.isError && (
            <button
              className="button button-secondary"
              type="button"
              onClick={() => { void query.refetch() }}
            >
              Retry profile
            </button>
          )}
        </aside>
      </div>

    </div>
  )
}
