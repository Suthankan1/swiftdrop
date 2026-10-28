import { useEffect, useId, useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  createAdminReportsApi,
  type AdminReportsApi,
} from '../api/adminReports'
import {
  AdminReportsError,
  COLOMBO_TIMEZONE,
  formatTerminalSuccessRate,
  getTodayColomboDate,
  parcelStatuses,
  validateFilterInput,
  type DeliveryStatsFilterInput,
  type ParcelStatus,
} from '../api/adminReportsContracts'

export interface AdminReportsPageProps {
  api?: AdminReportsApi
}

function formatSubmittedAt(isoString: string): string {
  try {
    const d = new Date(isoString)
    if (isNaN(d.getTime())) return isoString
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: COLOMBO_TIMEZONE,
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(d)
  } catch {
    return isoString
  }
}

export function AdminReportsPage({ api: injectedApi }: AdminReportsPageProps) {
  const auth = useAuth()
  const defaultApi = useMemo(
    () => createAdminReportsApi({ getAccessToken: auth.getAccessToken }),
    [auth.getAccessToken],
  )
  const api = injectedApi ?? defaultApi

  if (auth.snapshot.status !== 'authenticated' || !auth.snapshot.roles.includes('ADMIN')) {
    return (
      <div className="page-container admin-reports-page">
        <p role="alert">Operational reporting requires an authenticated ADMIN session.</p>
      </div>
    )
  }

  return (
    <AdminReportsWorkspace
      key={`${auth.snapshot.subject}:${auth.snapshot.roles.join(',')}`}
      api={api}
    />
  )
}

function AdminReportsWorkspace({ api }: { api: AdminReportsApi }) {
  const auth = useAuth()
  const cache = useQueryClient()
  const sessionId = useId()
  const subject = auth.snapshot.subject ?? ''

  const prefix = useMemo(
    () => ['private', 'admin-reports', subject, sessionId],
    [subject, sessionId],
  )

  // Default to today in Asia/Colombo
  const todayColombo = useMemo(() => getTodayColomboDate(), [])

  // Filter form draft state
  const [startDateDraft, setStartDateDraft] = useState(todayColombo)
  const [endDateDraft, setEndDateDraft] = useState(todayColombo)
  const [zoneDraft, setZoneDraft] = useState('')
  const [statusDraft, setStatusDraft] = useState<string>('')

  // Active committed filters for query
  const [activeFilter, setActiveFilter] = useState<DeliveryStatsFilterInput>({
    startDate: todayColombo,
    endDate: todayColombo,
  })

  const [validationError, setValidationError] = useState<string | null>(null)
  const [lastRefreshedAt, setLastRefreshedAt] = useState<Date>(new Date())

  // Session teardown: cancel queries and remove private cache on unmount
  useEffect(() => {
    return () => {
      void cache.cancelQueries({ queryKey: prefix })
      cache.removeQueries({ queryKey: prefix })
    }
  }, [cache, prefix])

  // 1. Delivery statistics query
  const deliveryStatsKey = useMemo(
    () => [
      ...prefix,
      'delivery-stats',
      activeFilter.startDate ?? '',
      activeFilter.endDate ?? '',
      activeFilter.zone ?? '',
      activeFilter.status ?? '',
    ],
    [prefix, activeFilter],
  )

  const deliveryStatsQuery = useQuery({
    queryKey: deliveryStatsKey,
    queryFn: async ({ signal }) => {
      try {
        const result = await api.getDeliveryStats(activeFilter, signal)
        signal.throwIfAborted()
        setLastRefreshedAt(new Date())
        return result
      } catch (error) {
        if (error instanceof AdminReportsError && error.kind === 'auth-required') {
          void cache.cancelQueries({ queryKey: prefix })
          cache.removeQueries({ queryKey: prefix })
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  // 2. Admin dashboard summary query (merchant review & platform overview)
  const adminDashboardKey = useMemo(
    () => [...prefix, 'admin-dashboard'],
    [prefix],
  )

  const adminDashboardQuery = useQuery({
    queryKey: adminDashboardKey,
    queryFn: async ({ signal }) => {
      try {
        const result = await api.getAdminDashboard(signal)
        signal.throwIfAborted()
        setLastRefreshedAt(new Date())
        return result
      } catch (error) {
        if (error instanceof AdminReportsError && error.kind === 'auth-required') {
          void cache.cancelQueries({ queryKey: prefix })
          cache.removeQueries({ queryKey: prefix })
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  function handleFilterSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)

    const proposedFilter: DeliveryStatsFilterInput = {
      startDate: startDateDraft.trim() || undefined,
      endDate: endDateDraft.trim() || undefined,
      zone: zoneDraft.trim() || undefined,
      status: (statusDraft.trim() as ParcelStatus) || undefined,
    }

    const check = validateFilterInput(proposedFilter)
    if (!check.valid) {
      setValidationError(check.error || 'Invalid filter parameters.')
      return
    }

    setActiveFilter(proposedFilter)
  }

  function handleResetFilters() {
    setStartDateDraft(todayColombo)
    setEndDateDraft(todayColombo)
    setZoneDraft('')
    setStatusDraft('')
    setValidationError(null)
    setActiveFilter({
      startDate: todayColombo,
      endDate: todayColombo,
    })
  }

  async function handleRefreshAll() {
    await Promise.all([
      deliveryStatsQuery.refetch(),
      adminDashboardQuery.refetch(),
    ])
  }

  const isRefreshing = deliveryStatsQuery.isFetching || adminDashboardQuery.isFetching

  return (
    <div className="page-container admin-reports-page">
      <Link to="/admin" className="admin-back-link">
        Back to administration
      </Link>

      <header className="staff-heading admin-reports-header">
        <p className="eyebrow">Admin space · Operational reporting</p>
        <h1 className="page-heading">Daily delivery outcomes</h1>
        <p className="muted">
          Review delivery activity and merchant onboarding using authorized operational data.
        </p>
        <p className="reporting-timezone-badge">
          Reporting timezone: <strong>{COLOMBO_TIMEZONE} (UTC+05:30)</strong>
        </p>
      </header>

      {/* Reporting Filter Form */}
      <section className="card report-filter-card" aria-labelledby="report-filter-heading">
        <div className="section-heading-row">
          <h2 className="section-heading" id="report-filter-heading">
            Report parameters
          </h2>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => void handleRefreshAll()}
            disabled={isRefreshing}
          >
            {isRefreshing ? 'Refreshing...' : 'Refresh report'}
          </button>
        </div>

        <form onSubmit={handleFilterSubmit} className="report-filter-form">
          <div className="admin-management-filters">
            <label htmlFor="report-start-date">
              <span>Start date (Colombo)</span>
              <input
                id="report-start-date"
                type="date"
                value={startDateDraft}
                onChange={(e) => setStartDateDraft(e.target.value)}
              />
            </label>

            <label htmlFor="report-end-date">
              <span>End date (Colombo)</span>
              <input
                id="report-end-date"
                type="date"
                value={endDateDraft}
                onChange={(e) => setEndDateDraft(e.target.value)}
              />
            </label>

            <label htmlFor="report-zone">
              <span>Operational zone</span>
              <input
                id="report-zone"
                type="text"
                placeholder="e.g. CMB-01"
                value={zoneDraft}
                onChange={(e) => setZoneDraft(e.target.value)}
              />
            </label>

            <label htmlFor="report-status">
              <span>Parcel status</span>
              <select
                id="report-status"
                value={statusDraft}
                onChange={(e) => setStatusDraft(e.target.value)}
              >
                <option value="">All lifecycle statuses</option>
                {parcelStatuses.map((st) => (
                  <option key={st} value={st}>
                    {st}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <p className="small-note muted">
            Zone reporting is currently in development by the delivery service.
            Filtering by a specific zone may report zero delivery outcomes.
          </p>

          {validationError && (
            <p role="alert" className="report-error-text">
              {validationError}
            </p>
          )}

          <div className="admin-management-toolbar">
            <button type="submit" className="button button-primary">
              Apply filters
            </button>
            <button
              type="button"
              className="button button-secondary"
              onClick={handleResetFilters}
            >
              Reset to today
            </button>
          </div>
        </form>
      </section>

      {/* Delivery Statistics Section */}
      <section
        className="report-section delivery-outcomes-section"
        aria-labelledby="delivery-outcomes-heading"
      >
        <div className="section-heading-row">
          <div>
            <h2 className="section-heading" id="delivery-outcomes-heading">
              Delivery outcomes
            </h2>
            <p className="muted">
              Aggregated delivery performance metrics for the selected interval in{' '}
              {COLOMBO_TIMEZONE}.
            </p>
          </div>
          {deliveryStatsQuery.isFetching && (
            <span role="status" className="badge">
              Updating outcomes...
            </span>
          )}
        </div>

        {deliveryStatsQuery.isLoading && (
          <div role="status" className="state-panel" aria-live="polite">
            <h3>Loading delivery outcomes...</h3>
            <p>Fetching authorized operational statistics from the GraphQL query service.</p>
          </div>
        )}

        {deliveryStatsQuery.isError && (
          <div role="alert" className="state-panel report-error-panel">
            <h3>Delivery statistics temporarily unavailable</h3>
            <p>
              {deliveryStatsQuery.error instanceof AdminReportsError
                ? deliveryStatsQuery.error.message
                : 'Unable to load delivery statistics. Please check service connectivity and retry.'}
            </p>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => void deliveryStatsQuery.refetch()}
            >
              Retry delivery statistics
            </button>
          </div>
        )}

        {deliveryStatsQuery.isSuccess && (
          <>
            {deliveryStatsQuery.data.totalCount === 0 ? (
              <div className="state-panel report-empty-panel">
                <h3>No delivery activity recorded</h3>
                <p>No delivery activity was recorded for this reporting period.</p>
              </div>
            ) : null}

            <div className="courier-summary-grid report-metrics-grid" role="region" aria-label="Delivery outcome metrics">
              <div className="card metric-card">
                <span className="eyebrow">Total parcels</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.totalCount}
                </span>
                <span className="muted metric-caption">
                  Total distinct parcel records in the selected period.
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Successful deliveries</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.deliveredCount}
                </span>
                <span className="muted metric-caption">
                  Parcels successfully completed (DELIVERED status).
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Failed deliveries</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.failedCount}
                </span>
                <span className="muted metric-caption">
                  Parcels with terminal FAILED outcome status.
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Terminal success rate</span>
                <span className="metric-value monospace">
                  {formatTerminalSuccessRate(
                    deliveryStatsQuery.data.deliveredCount,
                    deliveryStatsQuery.data.failedCount,
                  )}
                </span>
                <span className="muted metric-caption">
                  Delivered / (delivered + failed). N/A when no terminal outcomes exist.
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Active in-transit</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.inTransitCount}
                </span>
                <span className="muted metric-caption">
                  Parcels currently collected, in transit, or out for delivery.
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Created / awaiting</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.createdCount}
                </span>
                <span className="muted metric-caption">
                  Parcels booked and awaiting courier pickup.
                </span>
              </div>

              <div className="card metric-card">
                <span className="eyebrow">Average delivery duration</span>
                <span className="metric-value monospace">
                  {deliveryStatsQuery.data.averageDeliveryHours !== null
                    ? `${deliveryStatsQuery.data.averageDeliveryHours.toFixed(2)} hrs`
                    : 'N/A'}
                </span>
                <span className="muted metric-caption">
                  Average duration from parcel booking to fulfilled delivery.
                </span>
              </div>
            </div>
          </>
        )}
      </section>

      {/* Merchant Onboarding Review Section */}
      <section
        className="report-section merchant-review-summary-section"
        aria-labelledby="merchant-summary-heading"
      >
        <div className="section-heading-row">
          <div>
            <h2 className="section-heading" id="merchant-summary-heading">
              Merchant onboarding review
            </h2>
            <p className="muted">
              Recent merchant applications awaiting administrative approval or verification.
            </p>
          </div>
          {adminDashboardQuery.isFetching && (
            <span role="status" className="badge">
              Updating merchants...
            </span>
          )}
        </div>

        {adminDashboardQuery.isLoading && (
          <div role="status" className="state-panel" aria-live="polite">
            <h3>Loading merchant review summary...</h3>
            <p>Fetching pending merchant overview from the GraphQL admin aggregate.</p>
          </div>
        )}

        {adminDashboardQuery.isError && (
          <div role="alert" className="state-panel report-error-panel">
            <h3>Merchant review summary temporarily unavailable</h3>
            <p>
              {adminDashboardQuery.error instanceof AdminReportsError
                ? adminDashboardQuery.error.message
                : 'Unable to load merchant review summary. Please retry.'}
            </p>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => void adminDashboardQuery.refetch()}
            >
              Retry merchant review
            </button>
          </div>
        )}

        {adminDashboardQuery.isSuccess && (
          <div className="card report-merchant-card">
            <div className="merchant-pending-badge-row">
              <span className="badge">
                Pending review:{' '}
                <strong>{adminDashboardQuery.data.pendingMerchantsCount}</strong>
              </span>
              <Link to="/admin/merchants" className="button button-secondary">
                Open merchant review workspace
              </Link>
            </div>

            {adminDashboardQuery.data.recentPendingMerchants.length === 0 ? (
              <p className="report-empty-message">
                No merchant applications are currently awaiting review.
              </p>
            ) : (
              <div className="admin-management-scroll">
                <table className="admin-management-table" aria-label="Recent pending merchant applications">
                  <caption>Recent pending merchant applications</caption>
                  <thead>
                    <tr>
                      <th scope="col">Business name</th>
                      <th scope="col">Registration number</th>
                      <th scope="col">Submitted at (Colombo)</th>
                      <th scope="col">Review</th>
                    </tr>
                  </thead>
                  <tbody>
                    {adminDashboardQuery.data.recentPendingMerchants.map((item) => (
                      <tr key={item.merchantId}>
                        <td>{item.businessName}</td>
                        <td>
                          <span className="monospace">{item.registrationNumber}</span>
                        </td>
                        <td>{formatSubmittedAt(item.submittedAt)}</td>
                        <td>
                          <Link to="/admin/merchants" className="button button-secondary button-small">
                            Review application
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </section>

      {/* Platform Operational Overview (Today snapshot from adminDashboard) */}
      {adminDashboardQuery.isSuccess && (
        <section
          className="report-section platform-overview-section"
          aria-labelledby="platform-overview-heading"
        >
          <h2 className="section-heading" id="platform-overview-heading">
            Platform operational overview (Today, Asia/Colombo)
          </h2>
          <p className="muted">
            Current-day platform snapshot derived from the admin dashboard aggregate.
          </p>

          <div className="courier-summary-grid report-metrics-grid" role="region" aria-label="Platform current-day overview">
            <div className="card metric-card">
              <span className="eyebrow">Total parcels today</span>
              <span className="metric-value monospace">
                {adminDashboardQuery.data.totalParcelsCount}
              </span>
              <span className="muted metric-caption">
                All parcels booked or active in platform today.
              </span>
            </div>

            <div className="card metric-card">
              <span className="eyebrow">Active parcels today</span>
              <span className="metric-value monospace">
                {adminDashboardQuery.data.activeParcelsCount}
              </span>
              <span className="muted metric-caption">
                Parcels currently active or in transit across all zones.
              </span>
            </div>

            <div className="card metric-card">
              <span className="eyebrow">Terminal success rate today</span>
              <span className="metric-value monospace">
                {(adminDashboardQuery.data.terminalSuccessRate * 100).toFixed(1)}%
              </span>
              <span className="muted metric-caption">
                Calculated by admin dashboard resolver for today&apos;s Colombo window.
              </span>
            </div>
          </div>
        </section>
      )}

      {/* Report footer status */}
      <footer className="report-footer-meta">
        <p className="muted small-note">
          Last refreshed: {lastRefreshedAt.toLocaleTimeString()} · Data fetched via Spring Cloud Gateway
          POST /graphql with authorized Keycloak JWT.
        </p>
      </footer>
    </div>
  )
}
