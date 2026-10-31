import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useQueries, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { ReportCharts } from '../components/ReportCharts'
import { useAdminQuery, type AdminScope } from '../hooks/useAdminManagement'
import { createReportsApi, formatCount, formatRate, outcomeRates, reportFailure, ZONE_PATTERN,
  type AdminDashboardSummary, type DeliveryStatsSummary, type ReportFilter, type ReportsApi } from '../api/reports'
import { addReportingDays, formatReportingDate,formatReportingDateTime, MAX_BREAKDOWN_DAYS, MAX_REPORT_DAYS, rangeProblemMessages, reportingDayCount,
  reportingDays, reportingToday, REPORTING_TIME_ZONE_LABEL, validateReportRange } from '../api/reportingPeriod'

/** deliveryStats permits DISPATCHER (server-scoped to assigned zones) and ADMIN; adminDashboard is ADMIN only. */
export type ReportAudience = 'ADMIN' | 'DISPATCHER'

export function ReportsPage({ audience, api, now }: { audience: ReportAudience; api?: ReportsApi; now?: () => Date }) {
  const auth = useAuth()
  const provider = useMemo(() => createReportsApi({ getAccessToken: auth.getAccessToken }), [auth.getAccessToken])
  if (auth.snapshot.status !== 'authenticated' || !auth.snapshot.roles.includes(audience))
    return <p role="alert">Delivery reports require an authenticated {audience} session.</p>
  return <ReportsWorkspace key={`${auth.snapshot.subject}:${auth.snapshot.roles.join(',')}`} audience={audience} api={api ?? provider} now={now} />
}

function ReportsWorkspace({ audience, api, now }: { audience: ReportAudience; api: ReportsApi; now?: () => Date }) {
  const auth = useAuth(), cache = useQueryClient(), sessionId = useId()
  const subject = auth.snapshot.subject ?? ''
  const prefix = useMemo(() => ['private', 'reports', audience, subject, sessionId], [audience, subject, sessionId])
  const [today] = useState(() => reportingToday(now?.() ?? new Date()))
  const [filter, setFilter] = useState<ReportFilter>({ startDate: today, endDate: today })
  const [denied, setDenied] = useState<string | null>(null)
  useEffect(() => () => { void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix }) }, [cache, prefix])
  const scope: AdminScope = { prefix, fail: async (error, signal) => {
    if (signal.aborted) return
    const failure = reportFailure(error)
    if (failure.kind === 'auth-required' || failure.kind === 'forbidden') {
      setDenied(failure.message)
      void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix })
      if (failure.kind === 'auth-required') await auth.handleSessionExpiry()
    }
  } }
  const admin = audience === 'ADMIN'
  return <div className="page-container admin-management reports-page">
    <Link to={admin ? '/admin' : '/dispatcher'}>{admin ? 'Back to administration' : 'Back to dispatch'}</Link>
    <header className="staff-heading">
      <p className="eyebrow">{admin ? 'Admin space' : 'Dispatcher space'}</p>
      <h1 className="page-heading">{admin ? 'Daily outcomes and admin reports' : 'Daily outcomes'}</h1>
      <p className="muted">Read-only delivery reporting. Reporting days are calendar days in <strong>{REPORTING_TIME_ZONE_LABEL}</strong>, from 00:00 to the end of the day.</p>
    </header>
    {denied ? <FeedbackState title="Report access denied" tone="error">{denied}</FeedbackState>
      : <ReportSections admin={admin} api={api} scope={scope} today={today} filter={filter} setFilter={setFilter} />}
  </div>
}

function ReportSections({ admin, api, scope, today, filter, setFilter }: {
  admin: boolean; api: ReportsApi; scope: AdminScope; today: string; filter: ReportFilter; setFilter: (filter: ReportFilter) => void
}) {
  const stats = useAdminQuery(scope, ['delivery-stats', filter.startDate, filter.endDate, filter.zone ?? ''], signal => api.deliveryStats(filter, signal))
  return <>
    {admin && <AdminOverview api={api} scope={scope} today={today} stats={stats} />}
    <ReportFilters admin={admin} today={today} applied={filter} onApply={setFilter} />
    <DailyOutcomes api={api} scope={scope} filter={filter} today={today} stats={stats} />
  </>
}

function AdminOverview({ api, scope, today, stats }: { api: ReportsApi; scope: AdminScope; today: string; stats: UseQueryResult<DeliveryStatsSummary> }) {
  const dashboard = useAdminQuery(scope, ['admin-dashboard'], signal => api.adminDashboard(signal))
  const partial = (dashboard.isError && stats.isSuccess) || (dashboard.isSuccess && stats.isError)
  return <section className="report-section" aria-labelledby="overview-heading" aria-busy={dashboard.isFetching}>
    {partial && <p className="report-partial" role="status">Partial report: some sections could not be retrieved. Only figures returned by a responding provider are shown; nothing is estimated.</p>}
    <div className="admin-management-toolbar">
      <h2 id="overview-heading">Today's operations overview</h2>
      <Button variant="secondary" size="sm" type="button" disabled={dashboard.isFetching} onClick={() => { void dashboard.refetch() }}>Refresh overview</Button>
    </div>
    <p className="muted">Reporting day {formatReportingDate(today)} ({REPORTING_TIME_ZONE_LABEL}), day to date. This overview always covers today and is not changed by the filters below.</p>
    {dashboard.isPending && <FeedbackState title="Loading operations overview" tone="loading">Requesting today's administrative summary.</FeedbackState>}
    {dashboard.isError && <ReportReadError title="Operations overview unavailable" error={dashboard.error} busy={dashboard.isFetching} retry={() => { void dashboard.refetch() }} />}
    {dashboard.isSuccess && <OverviewContent data={dashboard.data} updatedAt={dashboard.dataUpdatedAt} refreshing={dashboard.isFetching} />}
  </section>
}

function OverviewContent({ data, updatedAt, refreshing }: { data: AdminDashboardSummary; updatedAt: number; refreshing: boolean }) {
  return <>
    <Freshness updatedAt={updatedAt} refreshing={refreshing} />
    <div className="courier-summary-grid" role="group" aria-label="Today's operations metrics">
      <Metric label="Parcels today" value={formatCount(data.totalParcelsCount)} caption="Parcels created or active in today's reporting day" />
      <Metric label="Active in the field" value={formatCount(data.activeParcelsCount)} caption="Picked up, in transit or out for delivery" />
      <Metric label="Terminal success rate" value={formatRate(data.terminalSuccessRate)}
        caption="Delivered ÷ (delivered + failed) today. The provider also reports 0.0% when no parcel has reached a terminal outcome yet." />
      <Metric label="Merchants awaiting review" value={formatCount(data.pendingMerchantsCount)} caption="Applications with PENDING status" />
    </div>
    <section className="report-section" aria-labelledby="merchant-summary-heading">
      <div className="admin-management-toolbar">
        <h3 id="merchant-summary-heading">Merchant review summary</h3>
        <Link to="/admin/merchants">Open merchant review</Link>
      </div>
      {data.recentPendingMerchants.length === 0
        ? <p role="status">No merchant applications are awaiting review.</p>
        : <>
          <p className="muted">Showing {data.recentPendingMerchants.length} of {formatCount(data.pendingMerchantsCount)} pending applications. Contact details and credentials are not part of this summary.</p>
          <div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Scrollable pending merchants table">
            <table>
              <caption>Pending merchant applications</caption>
              <thead><tr><th scope="col">Business</th><th scope="col">Registration number</th><th scope="col">Submitted ({REPORTING_TIME_ZONE_LABEL})</th></tr></thead>
              <tbody>{data.recentPendingMerchants.map(merchant => <tr key={merchant.merchantId}>
                <td>{merchant.businessName}</td><td>{merchant.registrationNumber}</td>
                <td><time dateTime={merchant.submittedAt}>{formatReportingDateTime(merchant.submittedAt)}</time></td>
              </tr>)}</tbody>
            </table>
          </div>
        </>}
    </section>
  </>
}

function ReportFilters({ admin, today, applied, onApply }: { admin: boolean; today: string; applied: ReportFilter; onApply: (filter: ReportFilter) => void }) {
  const [startDate, setStartDate] = useState(applied.startDate)
  const [endDate, setEndDate] = useState(applied.endDate)
  const [zone, setZone] = useState(applied.zone ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const errorId = useId()
  function apply(next: ReportFilter) {
    const range = validateReportRange(next.startDate, next.endDate, today)
    if (range) { setProblem(rangeProblemMessages[range]); return }
    const normalizedZone = next.zone?.trim().toUpperCase() ?? ''
    if (normalizedZone && !ZONE_PATTERN.test(normalizedZone)) { setProblem('Zone codes use 1–32 capital letters, digits, hyphens or underscores, such as CMB-01.'); return }
    setProblem(null); setZone(normalizedZone)
    onApply({ startDate: next.startDate, endDate: next.endDate, ...(admin && normalizedZone ? { zone: normalizedZone } : {}) })
  }
  function preset(days: number) {
    const start = addReportingDays(today, 1 - days)
    setStartDate(start); setEndDate(today)
    apply({ startDate: start, endDate: today, zone })
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); apply({ startDate, endDate, zone }) }
  return <section className="report-section" aria-labelledby="filters-heading">
    <h2 id="filters-heading">Report filters</h2>
    <form className="admin-management-filters" onSubmit={submit} noValidate aria-describedby={problem ? errorId : undefined}>
      <label>Start date
        <input type="date" value={startDate} max={today} required aria-invalid={!!problem} onChange={event => setStartDate(event.target.value)} />
      </label>
      <label>End date
        <input type="date" value={endDate} max={today} required aria-invalid={!!problem} onChange={event => setEndDate(event.target.value)} />
      </label>
      {admin && <label>Zone code (optional)
        <input type="text" value={zone} maxLength={32} placeholder="All zones" autoComplete="off" spellCheck={false} onChange={event => setZone(event.target.value)} />
      </label>}
      <Button variant="primary" size="sm" type="submit">Apply filters</Button>
    </form>
    <div className="admin-management-toolbar" role="group" aria-label="Quick reporting periods">
      <Button variant="secondary" size="sm" type="button" onClick={() => preset(1)}>Today</Button>
      <Button variant="secondary" size="sm" type="button" onClick={() => preset(7)}>Last 7 days</Button>
      <Button variant="secondary" size="sm" type="button" onClick={() => preset(30)}>Last 30 days</Button>
    </div>
    {problem && <p id={errorId} role="alert">{problem}</p>}
    <p className="muted">Up to {MAX_REPORT_DAYS} days ending today. {admin ? 'Leave the zone blank for platform-wide figures.' : 'Figures are limited by the server to your assigned operational zones.'}</p>
  </section>
}

function DailyOutcomes({ api, scope, filter, today, stats }: {
  api: ReportsApi; scope: AdminScope; filter: ReportFilter; today: string; stats: UseQueryResult<DeliveryStatsSummary>
}) {
  const dayCount = reportingDayCount(filter.startDate, filter.endDate)
  return <section className="report-section" aria-labelledby="outcomes-heading" aria-busy={stats.isFetching}>
    <div className="admin-management-toolbar">
      <h2 id="outcomes-heading">Delivery outcomes</h2>
      <Button variant="secondary" size="sm" type="button" disabled={stats.isFetching} onClick={() => { void stats.refetch() }}>Refresh outcomes</Button>
    </div>
    <p className="report-period">
      <strong>Reporting period:</strong> {formatReportingDate(filter.startDate)}{dayCount > 1 && <> to {formatReportingDate(filter.endDate)}</>} ({dayCount} {dayCount === 1 ? 'day' : 'days'}) · {REPORTING_TIME_ZONE_LABEL} · {filter.zone ? `Zone ${filter.zone}` : 'All authorized zones'}
    </p>
    {filter.endDate === today && <p className="muted">The period includes today, so today's figures are day to date and can still change.</p>}
    {stats.isPending && <FeedbackState title="Loading delivery outcomes" tone="loading">Requesting authoritative outcome totals.</FeedbackState>}
    {stats.isError && <ReportReadError title="Delivery outcomes unavailable" error={stats.error} busy={stats.isFetching} retry={() => { void stats.refetch() }} />}
    {stats.isSuccess && <>
      <Freshness updatedAt={stats.dataUpdatedAt} refreshing={stats.isFetching} />
      {stats.data.totalCount === 0
        ? <FeedbackState title="No delivery activity in this period">The reporting service recorded 0 parcels for the selected period and scope.</FeedbackState>
        : <OutcomeContent data={stats.data} />}
      {dayCount > MAX_BREAKDOWN_DAYS
        ? <p className="muted">A day-by-day breakdown is available for periods of up to {MAX_BREAKDOWN_DAYS} days, to keep reporting queries bounded.</p>
        : dayCount > 1 && <DailyBreakdown api={api} scope={scope} filter={filter} />}
    </>}
  </section>
}

function OutcomeContent({ data }: { data: DeliveryStatsSummary }) {
  const { terminal, successRate, failureRate } = outcomeRates(data)
  const noTerminal = 'No delivered or failed parcels in this period'
  const classified = data.createdCount + data.inTransitCount + data.deliveredCount + data.failedCount
  const share = (value: number) => formatRate(value / data.totalCount)
  return <>
    <div className="courier-summary-grid" role="group" aria-label="Delivery outcome metrics">
      <Metric label="Total parcels" value={formatCount(data.totalCount)} caption="Distinct parcels with lifecycle activity in the period" />
      <Metric label="Delivered" value={formatCount(data.deliveredCount)} caption="Delivery completed within the period" />
      <Metric label="Failed" value={formatCount(data.failedCount)} caption="Failure recorded within the period" />
      <Metric label="Success rate" value={formatRate(successRate)} caption={terminal ? `Delivered ÷ ${formatCount(terminal)} terminal outcomes` : noTerminal} />
      <Metric label="Failure rate" value={formatRate(failureRate)} caption={terminal ? `Failed ÷ ${formatCount(terminal)} terminal outcomes` : noTerminal} />
      <Metric label="Average delivery time" value={data.averageDeliveryHours === null ? 'Not available' : `${data.averageDeliveryHours.toFixed(2)} h`}
        caption="Booking to delivery, delivered parcels only" />
    </div>
    <ReportCharts data={data} />
    <div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Scrollable status distribution table">
      <table>
        <caption>Status distribution for the period</caption>
        <thead><tr><th scope="col">Status group</th><th scope="col">Parcels</th><th scope="col">Share of total</th></tr></thead>
        <tbody>
          <tr><th scope="row">Awaiting pickup (created)</th><td>{formatCount(data.createdCount)}</td><td>{share(data.createdCount)}</td></tr>
          <tr><th scope="row">In progress (picked up, in transit, out for delivery)</th><td>{formatCount(data.inTransitCount)}</td><td>{share(data.inTransitCount)}</td></tr>
          <tr><th scope="row">Delivered</th><td>{formatCount(data.deliveredCount)}</td><td>{share(data.deliveredCount)}</td></tr>
          <tr><th scope="row">Failed</th><td>{formatCount(data.failedCount)}</td><td>{share(data.failedCount)}</td></tr>
          {classified < data.totalCount && <tr><th scope="row">Not classified by the provider</th><td>{formatCount(data.totalCount - classified)}</td><td>{share(data.totalCount - classified)}</td></tr>}
        </tbody>
      </table>
    </div>
  </>
}

/** Each row is its own server aggregate for one reporting day; rows are never summed client-side. */
function DailyBreakdown({ api, scope, filter }: { api: ReportsApi; scope: AdminScope; filter: ReportFilter }) {
  const days = reportingDays(filter.startDate, filter.endDate)
  const results = useQueries({ queries: days.map(day => ({
    queryKey: [...scope.prefix, 'delivery-stats-day', day, filter.zone ?? ''],
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      try { const result = await api.deliveryStats({ startDate: day, endDate: day, zone: filter.zone }, signal); signal.throwIfAborted(); return result }
      catch (error) { await scope.fail(error, signal); throw error }
    },
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false,
  })) })
  const failed = results.filter(result => result.isError)
  const busy = results.some(result => result.isFetching)
  return <section className="report-section" aria-labelledby="breakdown-heading" aria-busy={busy}>
    <h3 id="breakdown-heading">Daily outcomes by reporting day</h3>
    {failed.length > 0 && failed.length < results.length && <p className="report-partial" role="status">
      Partial breakdown: {failed.length} of {results.length} days could not be retrieved. Their figures are not shown or estimated.
    </p>}
    {failed.length > 0 && <Button variant="secondary" size="sm" type="button" disabled={busy} onClick={() => { failed.forEach(result => { void result.refetch() }) }}>Retry unavailable days</Button>}
    <div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Scrollable daily outcomes table">
      <table>
        <caption>Daily outcomes ({REPORTING_TIME_ZONE_LABEL})</caption>
        <thead><tr><th scope="col">Reporting day</th><th scope="col">Total</th><th scope="col">Delivered</th><th scope="col">Failed</th><th scope="col">Success rate</th><th scope="col">Failure rate</th></tr></thead>
        <tbody>{days.map((day, index) => {
          const result = results[index]
          const label = <th scope="row"><time dateTime={day}>{formatReportingDate(day)}</time></th>
          if (result?.isSuccess) {
            const { data } = result, rates = outcomeRates(data)
            return <tr key={day}>{label}<td>{formatCount(data.totalCount)}</td><td>{formatCount(data.deliveredCount)}</td><td>{formatCount(data.failedCount)}</td>
              <td>{formatRate(rates.successRate)}</td><td>{formatRate(rates.failureRate)}</td></tr>
          }
          return <tr key={day}>{label}<td colSpan={5}>{result?.isError ? reportFailure(result.error).message : 'Loading…'}</td></tr>
        })}</tbody>
      </table>
    </div>
  </section>
}

function Metric({ label, value, caption }: { label: string; value: string; caption: string }) {
  return <div className="card metric-card">
    <span className="eyebrow">{label}</span>
    <span className="metric-value monospace">{value}</span>
    <span className="muted metric-caption">{caption}</span>
  </div>
}
function Freshness({ updatedAt, refreshing }: { updatedAt: number; refreshing: boolean }) {
  return <p className="muted report-freshness" role="status">
    {refreshing ? 'Refreshing… figures below are from the previous read.' : `Retrieved ${formatReportingDateTime(updatedAt)} (${REPORTING_TIME_ZONE_LABEL}).`}
  </p>
}
function ReportReadError({ title, error, busy, retry }: { title: string; error: unknown; busy: boolean; retry: () => void }) {
  return <FeedbackState title={title} tone="error"
    action={<Button variant="secondary" disabled={busy} onClick={retry}>Retry</Button>}>
    {reportFailure(error).message}
  </FeedbackState>
}
