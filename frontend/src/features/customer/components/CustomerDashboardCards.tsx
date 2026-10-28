import { useMemo } from 'react'
import { Link, useInRouterContext } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  createCustomerDashboardApi,
  type CustomerDashboardApiOptions,
} from '../api/customerDashboard'
import {
  type CustomerDashboardData,
  CustomerDashboardError,
} from '../api/customerDashboardContracts'
import { formatCurrency, formatDateTime, getStatusPresentation } from './customerUiUtils'

export interface CustomerDashboardCardsProps {
  apiOverride?: ReturnType<typeof createCustomerDashboardApi>
  apiOptions?: Partial<CustomerDashboardApiOptions>
}

export function CustomerDashboardCards({ apiOverride, apiOptions }: CustomerDashboardCardsProps) {
  const auth = useAuth()
  const inRouter = useInRouterContext()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''
  const isCustomer = auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('CUSTOMER')

  const api = useMemo(
    () =>
      apiOverride ||
      createCustomerDashboardApi({
        getAccessToken: auth.getAccessToken,
        ...apiOptions,
      }),
    [apiOverride, auth.getAccessToken, apiOptions],
  )

  const query = useQuery({
    queryKey: ['private', 'customer-dashboard', subject],
    queryFn: async ({ signal }) => {
      try {
        return await api.getDashboard(signal)
      } catch (error) {
        if (!signal.aborted && error instanceof CustomerDashboardError && error.kind === 'auth-required') {
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    enabled: isCustomer && Boolean(subject),
    retry: false,
    gcTime: 0,
    staleTime: 15_000,
    refetchOnWindowFocus: false,
  })

  if (!isCustomer) {
    return (
      <section className="card customer-dashboard-section" aria-label="Customer dashboard">
        <p className="muted">Customer dashboard is only accessible by customers.</p>
      </section>
    )
  }

  const data: CustomerDashboardData | undefined = query.data
  const totalBookings = data ? data.activeDeliveriesCount + data.completedDeliveriesCount : 0

  return (
    <section className="customer-dashboard-section" aria-labelledby="customer-dashboard-heading" aria-busy={query.isFetching}>
      <div className="section-heading-row">
        <div>
          <p className="eyebrow">Real-time overview</p>
          <h2 id="customer-dashboard-heading" className="section-heading">Your Deliveries Summary</h2>
        </div>
        <button
          type="button"
          className="button button-secondary"
          disabled={query.isFetching}
          onClick={() => { void query.refetch() }}
          aria-label="Refresh deliveries summary"
        >
          {query.isFetching ? 'Refreshing…' : 'Refresh summary'}
        </button>
      </div>

      {query.isFetching && !data && (
        <div className="card loading-state" role="status" aria-label="Loading deliveries summary">
          <p>Loading your deliveries summary…</p>
        </div>
      )}

      {query.isError && (
        <div className="card error-state" role="status" aria-live="polite">
          <p className="field-error">
            {query.error instanceof CustomerDashboardError
              ? query.error.message
              : 'Unable to load deliveries summary.'}
          </p>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => { void query.refetch() }}
            style={{ marginTop: '8px' }}
          >
            Retry summary
          </button>
        </div>
      )}

      {data && (
        <>
          <div className="customer-summary-grid">
            <div className="card metric-card">
              <span className="spec-label">Active Deliveries</span>
              <span className="metric-value">{data.activeDeliveriesCount}</span>
              <p className="metric-caption muted">Parcels in transit or out for delivery</p>
              <div style={{ marginTop: '8px' }}>
                {inRouter ? (
                  <Link to="/customer/history" className="button button-secondary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    View active
                  </Link>
                ) : (
                  <a href="/customer/history" className="button button-secondary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    View active
                  </a>
                )}
              </div>
            </div>

            <div className="card metric-card">
              <span className="spec-label">Completed Deliveries</span>
              <span className="metric-value">{data.completedDeliveriesCount}</span>
              <p className="metric-caption muted">Delivered parcels with official receipts</p>
              <div style={{ marginTop: '8px' }}>
                {inRouter ? (
                  <Link to="/customer/history" className="button button-secondary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    View history
                  </Link>
                ) : (
                  <a href="/customer/history" className="button button-secondary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    View history
                  </a>
                )}
              </div>
            </div>

            <div className="card metric-card">
              <span className="spec-label">Total Shipments</span>
              <span className="metric-value">{totalBookings}</span>
              <p className="metric-caption muted">Total dispatches booked with SwiftDrop</p>
              <div style={{ marginTop: '8px' }}>
                {inRouter ? (
                  <Link to="/customer/quote" className="button button-primary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    Book new
                  </Link>
                ) : (
                  <a href="/customer/quote" className="button button-primary" style={{ fontSize: '13px', padding: '6px 12px' }}>
                    Book new
                  </a>
                )}
              </div>
            </div>
          </div>

          <div className="card customer-recent-parcels-card" style={{ marginTop: '20px' }}>
            <div className="section-heading-row" style={{ marginBottom: '12px' }}>
              <h3 className="section-heading" style={{ margin: 0, fontSize: '18px' }}>Recent Deliveries</h3>
              {inRouter ? (
                <Link to="/customer/history" className="button button-secondary" style={{ fontSize: '12px' }}>
                  All deliveries →
                </Link>
              ) : (
                <a href="/customer/history" className="button button-secondary" style={{ fontSize: '12px' }}>
                  All deliveries →
                </a>
              )}
            </div>

            {data.recentParcels.length === 0 ? (
              <div className="customer-empty-parcels" role="status" style={{ padding: '16px 0', textAlign: 'center' }}>
                <p className="muted">You don't have any recent delivery bookings.</p>
                <div style={{ marginTop: '12px' }}>
                  {inRouter ? (
                    <Link to="/customer/quote" className="button button-primary">
                      Request a quote &amp; book
                    </Link>
                  ) : (
                    <a href="/customer/quote" className="button button-primary">
                      Request a quote &amp; book
                    </a>
                  )}
                </div>
              </div>
            ) : (
              <div className="dispatcher-table-scroll">
                <table className="dispatcher-table">
                  <caption>Recent customer deliveries preview</caption>
                  <thead>
                    <tr>
                      <th scope="col">Tracking Number</th>
                      <th scope="col">Status</th>
                      <th scope="col">Destination</th>
                      <th scope="col">Amount</th>
                      <th scope="col">Booked Date</th>
                      <th scope="col">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.recentParcels.map((parcel) => {
                      const presentation = getStatusPresentation(parcel.currentStatus)
                      return (
                        <tr key={parcel.parcelId}>
                          <th scope="row" style={{ fontFamily: 'monospace' }}>
                            {parcel.trackingNumber}
                          </th>
                          <td data-label="Status">
                            <span className={presentation.pillClass}>{presentation.label}</span>
                          </td>
                          <td data-label="Destination">{parcel.recipientCity}</td>
                          <td data-label="Amount">{formatCurrency(parcel.amountMinorUnits, parcel.currency)}</td>
                          <td data-label="Booked Date">
                            <time dateTime={parcel.createdAt}>{formatDateTime(parcel.createdAt)}</time>
                          </td>
                          <td data-label="Action">
                            {inRouter ? (
                              <Link
                                to="/customer/history"
                                className="button button-secondary"
                                style={{ fontSize: '12px', padding: '4px 8px' }}
                                aria-label={`View details for ${parcel.trackingNumber}`}
                              >
                                View
                              </Link>
                            ) : (
                              <a
                                href="/customer/history"
                                className="button button-secondary"
                                style={{ fontSize: '12px', padding: '4px 8px' }}
                                aria-label={`View details for ${parcel.trackingNumber}`}
                              >
                                View
                              </a>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  )
}
