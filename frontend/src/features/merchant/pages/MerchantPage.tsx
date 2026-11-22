import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Link } from 'react-router'
import { ArrowRightIcon } from '../../../components/ui/icons'
import { AccountsError, createAccountsApi, normalizeApprovalStatus } from '../api/merchant'
import { MerchantProfile, type MerchantProfileState } from '../components/MerchantProfile'
import { MerchantScene } from '../components/MerchantScene'
import './MerchantOverview.css'

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

  const approved = state.status === 'ready' && normalizeApprovalStatus(state.merchant.status) === 'APPROVED'

  return (
    <div className="page-container merchant-page merchant-overview">
      <div className="merchant-overview-hero">
        <div className="merchant-overview-hero-main">
          <header className="merchant-overview-heading">
            <h1>Manage your deliveries</h1>
          </header>

          {approved && (
            <div className="merchant-overview-shortcuts" role="group" aria-label="Order shortcuts">
              <Link to="/merchant/orders/new" className="merchant-overview-shortcut merchant-overview-shortcut-primary">
                <span><strong>New order</strong><span>Book a parcel for delivery</span></span>
                <ArrowRightIcon />
              </Link>
              <Link to="/merchant/orders" className="merchant-overview-shortcut">
                <span><strong>View orders</strong><span>Track deliveries and manage shipments</span></span>
                <ArrowRightIcon />
              </Link>
            </div>
          )}
        </div>
        <MerchantScene />
      </div>

      <section className="merchant-overview-account" aria-label="Business account">
        <MerchantProfile state={state} />
        {query.isError && (
          <Button variant="secondary" type="button" isLoading={query.isFetching} onClick={() => { void query.refetch() }}>
            Retry profile
          </Button>
        )}
      </section>
    </div>
  )
}
