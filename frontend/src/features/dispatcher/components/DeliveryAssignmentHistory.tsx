import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { createDispatcherApi, DispatcherError, type HistoricalParcelItem } from '../api/dispatcher'
import { ParcelHistoryTimeline } from './ParcelHistoryTimeline'
import { AssignmentHistoryTimeline } from './AssignmentHistoryTimeline'

function getStatusBadgeVariant(status: string): 'primary' | 'success' | 'danger' | 'neutral' {
  switch (status) {
    case 'DELIVERED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PICKED_UP':
    case 'IN_TRANSIT':
    case 'OUT_FOR_DELIVERY':
      return 'primary'
    default:
      return 'neutral'
  }
}

export function DeliveryAssignmentHistory({ api: customApi }: { api?: ReturnType<typeof createDispatcherApi> } = {}) {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const api = useMemo(
    () => customApi ?? createDispatcherApi({ getAccessToken: auth.getAccessToken }),
    [customApi, auth.getAccessToken]
  )

  // State
  const [page, setPage] = useState(0)
  const pageSize = 10
  const [trackingSearch, setTrackingSearch] = useState('')
  const [parcelIdSearch, setParcelIdSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [courierFilter, setCourierFilter] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')

  const handleTrackingSearchChange = (value: string) => {
    setTrackingSearch(value)
    setPage(0)
  }

  const handleParcelIdSearchChange = (value: string) => {
    setParcelIdSearch(value)
    setPage(0)
  }

  const handleStatusFilterChange = (value: string) => {
    setStatusFilter(value)
    setPage(0)
  }

  const handleCourierFilterChange = (value: string) => {
    setCourierFilter(value)
    setPage(0)
  }

  const handleStartDateChange = (value: string) => {
    setStartDate(value)
    setPage(0)
  }

  const handleEndDateChange = (value: string) => {
    setEndDate(value)
    setPage(0)
  }

  // Expanded history tracking: parcelId -> 'status' | 'assignment' | null
  const [expandedParcelId, setExpandedParcelId] = useState<string | null>(null)
  const [expandedType, setExpandedType] = useState<'status' | 'assignment' | null>(null)

  // Query for historical parcel list
  const listQueryKey = ['private', 'dispatcher-parcel-history-list', subject, page, pageSize] as const
  const listQuery = useQuery({
    queryKey: listQueryKey,
    queryFn: async ({ signal }) => {
      try {
        return await api.getParcelList({ page, size: pageSize }, signal)
      } catch (error) {
        if (!signal.aborted && error instanceof DispatcherError && error.kind === 'auth-required') {
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    enabled:
      auth.snapshot.status === 'authenticated' &&
      (auth.snapshot.roles.includes('DISPATCHER') || auth.snapshot.roles.includes('ADMIN')),
    staleTime: 15_000,
  })

  // Lazy query for parcel status history
  const statusHistoryQueryKey = ['private', 'parcel-status-history', subject, expandedParcelId] as const
  const statusHistoryQuery = useQuery({
    queryKey: statusHistoryQueryKey,
    queryFn: async ({ signal }) => {
      if (!expandedParcelId) return null
      try {
        return await api.getParcelStatusHistory(expandedParcelId, { page: 0, size: 50 }, signal)
      } catch (error) {
        if (!signal.aborted && error instanceof DispatcherError && error.kind === 'auth-required') {
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    enabled: Boolean(expandedParcelId && expandedType === 'status'),
    staleTime: 30_000,
  })

  // Lazy query for assignment history
  const assignmentHistoryQueryKey = ['private', 'parcel-assignment-history', subject, expandedParcelId] as const
  const assignmentHistoryQuery = useQuery({
    queryKey: assignmentHistoryQueryKey,
    queryFn: async ({ signal }) => {
      if (!expandedParcelId) return null
      try {
        return await api.getAssignmentHistory(expandedParcelId, signal)
      } catch (error) {
        if (!signal.aborted && error instanceof DispatcherError && error.kind === 'auth-required') {
          await auth.handleSessionExpiry()
        }
        throw error
      }
    },
    enabled: Boolean(expandedParcelId && expandedType === 'assignment'),
    staleTime: 30_000,
  })

  const handleToggleHistory = (parcelId: string, type: 'status' | 'assignment') => {
    if (expandedParcelId === parcelId && expandedType === type) {
      setExpandedParcelId(null)
      setExpandedType(null)
    } else {
      setExpandedParcelId(parcelId)
      setExpandedType(type)
    }
  }

  // Filter items in memory if client filters are applied
  const filteredParcels = useMemo(() => {
    const list = listQuery.data?.items ?? []
    return list.filter((p: HistoricalParcelItem) => {
      if (trackingSearch.trim()) {
        const q = trackingSearch.trim().toLowerCase()
        if (!p.trackingNumber.toLowerCase().includes(q)) return false
      }
      if (parcelIdSearch.trim()) {
        const q = parcelIdSearch.trim().toLowerCase()
        if (!p.parcelId.toLowerCase().includes(q)) return false
      }
      if (statusFilter !== 'ALL') {
        if (p.currentStatus !== statusFilter) return false
      }
      if (courierFilter.trim()) {
        const q = courierFilter.trim().toLowerCase()
        if (!p.activeCourierSub || !p.activeCourierSub.toLowerCase().includes(q)) return false
      }
      if (startDate) {
        const start = new Date(startDate).getTime()
        if (Date.parse(p.createdAt) < start) return false
      }
      if (endDate) {
        // End of the day
        const end = new Date(endDate).getTime() + 24 * 60 * 60 * 1000 - 1
        if (Date.parse(p.createdAt) > end) return false
      }
      return true
    })
  }, [listQuery.data?.items, trackingSearch, parcelIdSearch, statusFilter, courierFilter, startDate, endDate])

  const totalPages = listQuery.data?.totalPages ?? 1
  const currentPage = listQuery.data?.currentPage ?? 0

  return (
    <section
      className="card delivery-assignment-history-section"
      aria-labelledby="delivery-assignment-history-heading"
      style={{ marginTop: '1.5rem', marginBottom: '1.5rem' }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '12px', marginBottom: '1rem' }}>
        <div>
          <h2 id="delivery-assignment-history-heading" className="section-heading" style={{ margin: '0 0 4px 0' }}>
            Delivery and Assignment History
          </h2>
          <p className="muted" style={{ margin: 0, fontSize: '0.9rem' }}>
            Review parcel delivery progress and courier assignment records.
          </p>
        </div>
        <Button
          variant="secondary"
          size="sm"
          disabled={listQuery.isFetching}
          onClick={() => { void listQuery.refetch() }}
        >
          {listQuery.isFetching ? 'Refreshing…' : 'Refresh'}
        </Button>
      </div>

      {/* Search and filtering toolbar */}
      <div
        className="history-filter-toolbar"
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
          gap: '10px',
          padding: '12px',
          background: 'var(--sd-surface-card, #fff)',
          border: '1px solid var(--sd-border, #e0e5d8)',
          borderRadius: '8px',
          marginBottom: '1rem',
        }}
      >
        <div>
          <label htmlFor="filter-tracking" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            Tracking Number
          </label>
          <input
            id="filter-tracking"
            type="search"
            placeholder="Search tracking..."
            value={trackingSearch}
            onChange={e => handleTrackingSearchChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '5px 8px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          />
        </div>

        <div>
          <label htmlFor="filter-parcel-id" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            Parcel ID
          </label>
          <input
            id="filter-parcel-id"
            type="search"
            placeholder="Search parcel ID..."
            value={parcelIdSearch}
            onChange={e => handleParcelIdSearchChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '5px 8px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          />
        </div>

        <div>
          <label htmlFor="filter-status" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            Parcel Status
          </label>
          <select
            id="filter-status"
            value={statusFilter}
            onChange={e => handleStatusFilterChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '5px 8px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          >
            <option value="ALL">All Statuses</option>
            <option value="CREATED">CREATED</option>
            <option value="PICKED_UP">PICKED_UP</option>
            <option value="IN_TRANSIT">IN_TRANSIT</option>
            <option value="OUT_FOR_DELIVERY">OUT_FOR_DELIVERY</option>
            <option value="DELIVERED">DELIVERED</option>
            <option value="FAILED">FAILED</option>
          </select>
        </div>

        <div>
          <label htmlFor="filter-courier" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            Courier Sub
          </label>
          <input
            id="filter-courier"
            type="search"
            placeholder="Filter by courier..."
            value={courierFilter}
            onChange={e => handleCourierFilterChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '5px 8px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          />
        </div>

        <div>
          <label htmlFor="filter-date-from" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            From Date
          </label>
          <input
            id="filter-date-from"
            type="date"
            value={startDate}
            onChange={e => handleStartDateChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '4px 6px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          />
        </div>

        <div>
          <label htmlFor="filter-date-to" style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, marginBottom: '2px', color: 'var(--color-heading)' }}>
            To Date
          </label>
          <input
            id="filter-date-to"
            type="date"
            value={endDate}
            onChange={e => handleEndDateChange(e.target.value)}
            style={{ width: '100%', fontSize: '0.82rem', padding: '4px 6px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)' }}
          />
        </div>
      </div>

      <div
        style={{
          fontSize: '0.78rem',
          color: 'var(--color-muted)',
          backgroundColor: 'rgba(0, 0, 0, 0.02)',
          border: '1px solid var(--sd-border, #e0e5d8)',
          borderRadius: '6px',
          padding: '6px 10px',
          marginBottom: '1rem',
        }}
      >
        ℹ️ Note: Server-side parcel filtering is not currently supported by the delivery query service. Filters apply to parcels on the current page ({pageSize} per page).
      </div>

      {/* Loading & Error States */}
      {listQuery.isLoading && (
        <div style={{ padding: '24px 0', textAlign: 'center', fontSize: '0.95rem' }} className="muted" role="status">
          Loading parcel history...
        </div>
      )}

      {listQuery.isError && (
        <div style={{ padding: '16px', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: '8px', color: '#991b1b', margin: '12px 0' }} role="alert">
          <p style={{ margin: '0 0 8px 0', fontWeight: 500 }}>
            {listQuery.error instanceof DispatcherError ? listQuery.error.message : 'Unable to load history. Please try again.'}
          </p>
          <Button variant="secondary" size="sm" onClick={() => { void listQuery.refetch() }}>
            Retry
          </Button>
        </div>
      )}

      {/* Empty State */}
      {!listQuery.isLoading && !listQuery.isError && filteredParcels.length === 0 && (
        <div style={{ padding: '28px 16px', textAlign: 'center', border: '1px dashed var(--sd-border, #e0e5d8)', borderRadius: '8px' }} role="status">
          <p className="muted" style={{ margin: 0 }}>No parcel records found.</p>
        </div>
      )}

      {/* Parcel List */}
      {!listQuery.isLoading && !listQuery.isError && filteredParcels.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {filteredParcels.map((parcel: HistoricalParcelItem) => {
            const isStatusOpen = expandedParcelId === parcel.parcelId && expandedType === 'status'
            const isAssignmentOpen = expandedParcelId === parcel.parcelId && expandedType === 'assignment'
            const route = parcel.pickupZone && parcel.dropoffZone
              ? `${parcel.pickupZone} → ${parcel.dropoffZone}`
              : (parcel.pickupAddress || parcel.dropoffAddress || 'Route not recorded')

            return (
              <div
                key={parcel.parcelId}
                style={{
                  border: '1px solid var(--sd-border, #e0e5d8)',
                  borderRadius: '8px',
                  background: 'var(--sd-surface-card, #fff)',
                  overflow: 'hidden',
                }}
              >
                {/* Parcel row */}
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    flexWrap: 'wrap',
                    padding: '12px 16px',
                    gap: '12px',
                    background: isStatusOpen || isAssignmentOpen ? 'rgba(0, 0, 0, 0.015)' : 'transparent',
                  }}
                >
                  <div style={{ minWidth: '220px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <strong style={{ fontSize: '1rem', color: 'var(--color-heading, #1e3a29)' }}>
                        {parcel.trackingNumber}
                      </strong>
                      <Badge variant={getStatusBadgeVariant(parcel.currentStatus)} size="sm">
                        {parcel.currentStatus}
                      </Badge>
                    </div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--color-muted)', marginTop: '3px', fontFamily: 'monospace' }}>
                      ID: {parcel.parcelId}
                    </div>
                    <div style={{ fontSize: '0.82rem', marginTop: '4px' }}>
                      <span className="muted">Route:</span> {route}
                      {parcel.weightKg !== undefined && (
                        <span style={{ marginLeft: '10px' }}>
                          <span className="muted">Weight:</span> {parcel.weightKg} kg
                        </span>
                      )}
                    </div>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '4px', fontSize: '0.82rem' }}>
                    <div>
                      <span className="muted">Assigned courier:</span>{' '}
                      <strong>{parcel.activeCourierSub || 'Unassigned'}</strong>
                    </div>
                    <div className="muted" style={{ fontSize: '0.78rem' }}>
                      Created: {new Date(parcel.createdAt).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })}
                    </div>

                    {/* Actions */}
                    <div style={{ display: 'flex', gap: '8px', marginTop: '6px' }}>
                      <Button
                        type="button"
                        variant={isStatusOpen ? 'primary' : 'tonal'}
                        size="sm"
                        style={{ fontSize: '0.78rem', minHeight: '28px', padding: '3px 10px' }}
                        onClick={() => handleToggleHistory(parcel.parcelId, 'status')}
                        aria-expanded={isStatusOpen}
                        aria-label={`Toggle parcel status history for ${parcel.trackingNumber}`}
                      >
                        ⏱ Parcel Status History
                      </Button>
                      <Button
                        type="button"
                        variant={isAssignmentOpen ? 'primary' : 'tonal'}
                        size="sm"
                        style={{ fontSize: '0.78rem', minHeight: '28px', padding: '3px 10px' }}
                        onClick={() => handleToggleHistory(parcel.parcelId, 'assignment')}
                        aria-expanded={isAssignmentOpen}
                        aria-label={`Toggle assignment history for ${parcel.trackingNumber}`}
                      >
                        👥 Assignment History
                      </Button>
                    </div>
                  </div>
                </div>

                {/* Expandable History Area */}
                {isStatusOpen && (
                  <div style={{ borderTop: '1px solid var(--sd-border, #e0e5d8)' }}>
                    <ParcelHistoryTimeline
                      history={statusHistoryQuery.data?.items ?? []}
                      isLoading={statusHistoryQuery.isLoading}
                      error={statusHistoryQuery.isError ? (statusHistoryQuery.error instanceof Error ? statusHistoryQuery.error.message : 'Unable to load history. Please try again.') : null}
                      onRetry={() => { void statusHistoryQuery.refetch() }}
                    />
                  </div>
                )}

                {isAssignmentOpen && (
                  <div style={{ borderTop: '1px solid var(--sd-border, #e0e5d8)' }}>
                    <AssignmentHistoryTimeline
                      history={assignmentHistoryQuery.data ?? []}
                      isLoading={assignmentHistoryQuery.isLoading}
                      error={assignmentHistoryQuery.isError ? (assignmentHistoryQuery.error instanceof Error ? assignmentHistoryQuery.error.message : 'Unable to load history. Please try again.') : null}
                      onRetry={() => { void assignmentHistoryQuery.refetch() }}
                    />
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}

      {/* Pagination - rendered independently of filtered result count using authoritative server metadata */}
      {!listQuery.isLoading && !listQuery.isError && totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '12px' }}>
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            Page {currentPage + 1} of {totalPages}
            {listQuery.data?.totalElements != null && ` · ${listQuery.data.totalElements} total`}
          </span>
          <div style={{ display: 'flex', gap: '8px' }}>
            <Button
              variant="secondary"
              size="sm"
              disabled={currentPage <= 0 || listQuery.isFetching}
              onClick={() => setPage(prev => Math.max(0, prev - 1))}
            >
              Previous
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={currentPage >= totalPages - 1 || listQuery.isFetching}
              onClick={() => setPage(prev => prev + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
