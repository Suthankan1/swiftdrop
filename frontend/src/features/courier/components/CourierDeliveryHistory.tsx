import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { CourierApiError, type CourierHistoryStatus, type PagedAssignmentsResponse, type ParcelDetailResponse } from '../api/courierContracts'
import { createCourierAssignmentsApi } from '../api/courierAssignments'
import { CourierAssignmentCard } from './CourierAssignmentCard'

interface CourierDeliveryHistoryProps {
  apiOverride?: ReturnType<typeof createCourierAssignmentsApi>
  initialStatus?: CourierHistoryStatus
  onBack: () => void
  onSelectParcel: (parcelId: string) => void
}

function todayBounds(): { since: Date; until: Date } {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const end = new Date(start)
  end.setDate(end.getDate() + 1)
  return { since: start, until: end }
}

export function CourierDeliveryHistory({
  apiOverride,
  initialStatus,
  onBack,
  onSelectParcel,
}: CourierDeliveryHistoryProps) {
  const auth = useAuth()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''
  const api = useMemo(
    () => apiOverride || createCourierAssignmentsApi({ getAccessToken: auth.getAccessToken }),
    [apiOverride, auth.getAccessToken],
  )
  const [status, setStatus] = useState<CourierHistoryStatus | ''>(initialStatus ?? '')
  const [todayOnly, setTodayOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PagedAssignmentsResponse | null>(null)
  const [parcels, setParcels] = useState<Record<string, ParcelDetailResponse>>({})
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    if (!subject) return
    const controller = new AbortController()
    let active = true
    void (async () => {
      try {
        setLoading(true)
        setError(null)
        const bounds = todayOnly ? todayBounds() : undefined
        const response = await api.getHistoryPaged({
          page,
          size: 20,
          status: status || undefined,
          completedSince: bounds?.since,
          completedUntil: bounds?.until,
          signal: controller.signal,
        })
        if (!active) return
        setData(response)
        const details = await Promise.allSettled(
          response.items.map((item) => api.getHistoricalParcel(item.parcelId, controller.signal)),
        )
        if (!active) return
        const next: Record<string, ParcelDetailResponse> = {}
        details.forEach((result) => {
          if (result.status === 'fulfilled') next[result.value.parcelId] = result.value
        })
        setParcels(next)
      } catch (reason) {
        if (!active || controller.signal.aborted) return
        setError(reason instanceof CourierApiError ? reason.message : 'Unable to load delivery history.')
      } finally {
        if (active) setLoading(false)
      }
    })()
    return () => {
      active = false
      controller.abort()
    }
  }, [api, page, retry, status, subject, todayOnly])

  const items = data?.items ?? []
  const totalPages = Math.max(1, data?.totalPages ?? 1)

  return (
    <section className="card assignment-list-section courier-history-section" aria-labelledby="courier-history-heading">
      <div className="section-heading-row">
        <div>
          <p className="eyebrow">Completed route records</p>
          <h2 id="courier-history-heading">Delivery History</h2>
        </div>
        <Button type="button" variant="secondary" size="sm" onClick={onBack}>Back to Assignments</Button>
      </div>
      <div className="assignment-filters" aria-label="Delivery history filters">
        <label>
          <span>Status</span>
          <select value={status} onChange={(event) => { setStatus(event.target.value as CourierHistoryStatus | ''); setPage(0) }}>
            <option value="">All history</option>
            <option value="DELIVERED">Delivered</option>
            <option value="FAILED">Failed</option>
          </select>
        </label>
        <label className="assignment-today-filter">
          <input type="checkbox" checked={todayOnly} onChange={(event) => { setTodayOnly(event.target.checked); setPage(0) }} />
          Completed today
        </label>
        <Button type="button" variant="secondary" size="sm" onClick={() => setRetry((value) => value + 1)} disabled={loading}>
          {loading ? 'Refreshing…' : 'Refresh history'}
        </Button>
      </div>
      {loading && <div className="loading-state" role="status"><span className="loading-indicator" aria-hidden="true" /><p className="muted">Loading your completed deliveries…</p></div>}
      {error && <div className="feedback-banner is-error" role="alert"><span>{error}</span><Button variant="secondary" size="sm" onClick={() => setRetry((value) => value + 1)}>Retry History</Button></div>}
      {!loading && !error && items.length === 0 && <p className="muted" role="status">No completed or failed deliveries match these filters.</p>}
      {!loading && !error && items.length > 0 && (
        <>
          <div className="assignment-cards-grid" role="list" aria-label="Courier delivery history">
            {items.map((assignment) => (
              <CourierAssignmentCard
                key={assignment.assignmentId}
                assignment={assignment}
                parcel={parcels[assignment.parcelId]}
                isSelected={false}
                onSelect={onSelectParcel}
              />
            ))}
          </div>
          <nav className="pagination-controls-row" aria-label="Delivery history pagination">
            <Button variant="secondary" size="sm" disabled={page === 0 || loading} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</Button>
            <span className="pagination-info muted">Page {page + 1} of {totalPages}</span>
            <Button variant="secondary" size="sm" disabled={!data?.hasNext || loading} onClick={() => setPage((value) => value + 1)}>Next</Button>
          </nav>
        </>
      )}
    </section>
  )
}
