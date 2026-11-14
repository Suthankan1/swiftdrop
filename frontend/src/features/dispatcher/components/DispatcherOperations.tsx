import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { createDispatcherApi, DispatcherError, type UnassignedParcel } from '../api/dispatcher'

function CourierParcelsExpanded({ courierSub, api }: { courierSub: string; api: ReturnType<typeof createDispatcherApi> }) {
  const query = useQuery({
    queryKey: ['private', 'courier-active-assignments', courierSub],
    queryFn: async ({ signal }) => {
      const assignments = await api.getCourierAssignments(courierSub, signal)
      return await Promise.all(
        assignments.map(async (a) => {
          const parcel = await api.getParcel(a.parcelId, signal)
          return { ...a, parcel }
        })
      )
    },
    staleTime: 10_000,
  })

  if (query.isLoading) {
    return <div style={{ padding: '8px 12px', fontSize: '0.85rem' }} className="muted">Loading assigned parcels…</div>
  }

  if (query.isError || !query.data?.length) {
    return (
      <div style={{ padding: '8px 12px', fontSize: '0.85rem' }} className="muted">
        No active parcels currently in delivery for this courier.
      </div>
    )
  }

  return (
    <div style={{ marginTop: '10px', paddingTop: '10px', borderTop: '1px dashed var(--sd-border, #e0e5d8)' }}>
      <p style={{ margin: '0 0 8px 0', fontSize: '0.8rem', fontWeight: 600, color: 'var(--color-muted)' }}>
        Active Assigned Parcels ({query.data.length}):
      </p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        {query.data.map(item => {
          const p = item.parcel as Record<string, unknown> | null
          const trackingNumber = (typeof p?.trackingNumber === 'string' && p.trackingNumber) || `Parcel ${item.parcelId.slice(0, 8)}…`
          const status = (typeof p?.currentStatus === 'string' && p.currentStatus) || (item.active ? 'ASSIGNED' : 'COMPLETED')
          const pickup = typeof p?.pickupZone === 'string' ? p.pickupZone : (typeof p?.pickupAddress === 'string' ? p.pickupAddress : null)
          const dropoff = typeof p?.dropoffZone === 'string' ? p.dropoffZone : (typeof p?.dropoffAddress === 'string' ? p.dropoffAddress : null)
          const route = pickup && dropoff ? `${pickup} → ${dropoff}` : (pickup || dropoff || null)
          const weight = typeof p?.weightKg === 'number' ? `${p.weightKg} kg` : null

          return (
            <div
              key={item.assignmentId}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                flexWrap: 'wrap',
                gap: '8px',
                padding: '8px 12px',
                background: 'rgba(0,0,0,0.02)',
                borderRadius: '6px',
                fontSize: '0.82rem',
                border: '1px solid rgba(0,0,0,0.04)',
              }}
            >
              <div>
                <strong>{trackingNumber}</strong>
                {weight && <span className="muted" style={{ marginLeft: '6px', fontSize: '0.78rem' }}>({weight})</span>}
                {route && <span className="muted" style={{ marginLeft: '8px', fontSize: '0.78rem' }}>{route}</span>}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span className="muted" style={{ fontSize: '0.75rem' }}>
                  Assigned {new Date(item.assignedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
                <Badge variant={status === 'DELIVERED' ? 'success' : status === 'FAILED' ? 'danger' : 'primary'} size="sm">
                  {status}
                </Badge>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/** Mounted with a session key by the guarded page; no selection survives a session change. */
export function DispatcherOperations() {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const client = useQueryClient()
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<UnassignedParcel | null>(null)
  const [courierSub, setCourierSub] = useState('')
  const [zoneFilter, setZoneFilter] = useState('ALL')
  const [seenZones, setSeenZones] = useState<string[]>([])
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null)
  const [queueSearch, setQueueSearch] = useState('')
  const [queueSort, setQueueSort] = useState<'default' | 'weight-desc' | 'weight-asc' | 'newest'>('default')
  const [courierSearch, setCourierSearch] = useState('')
  const [expandedCouriers, setExpandedCouriers] = useState<Set<string>>(new Set())
  const command = useRef<AbortController | null>(null)
  const assignmentPanel = useRef<HTMLElement>(null)
  // Bring the confirm panel into view and move focus to it when a parcel is selected (audit 92).
  useEffect(() => {
    if (!selected || !assignmentPanel.current) return
    assignmentPanel.current.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
    assignmentPanel.current.querySelector<HTMLElement>('h2')?.focus()
  }, [selected])
  const api = createDispatcherApi({ getAccessToken: auth.getAccessToken })
  const queryKey = ['private', 'dispatcher-dashboard', subject] as const
  const query = useQuery({
    queryKey: [...queryKey, page, 20, zoneFilter],
    queryFn: async ({ signal }) => {
      try { return await (zoneFilter === 'ALL' ? api.dashboard(page, 20, signal) : api.dashboard(page, 20, signal, zoneFilter)) }
      catch (error) {
        if (!signal.aborted && error instanceof DispatcherError && error.kind === 'auth-required') await auth.handleSessionExpiry()
        throw error
      }
    },
    retry: false, gcTime: 0, staleTime: 0,
    enabled: auth.snapshot.status === 'authenticated' && (auth.snapshot.roles.includes('DISPATCHER') || auth.snapshot.roles.includes('ADMIN')),
    refetchOnWindowFocus: false,
  })
  useEffect(() => () => { command.current?.abort() }, [])
  const clear = () => { setSelected(null); setCourierSub('') }
  const refresh = async () => {
    clear()
    await client.invalidateQueries({ queryKey })
  }
  const submit = async () => {
    const courier = query.data?.availableCouriers.find(c => c.courierSub === courierSub && c.eligible)
    if (!selected || !courier || command.current || query.isFetching || query.isError) return
    const controller = new AbortController()
    command.current = controller
    setPending(true)
    setFeedback(null)
    try {
      await api.assign({ parcelId: selected.parcelId, courierSub: courier.courierSub, expectedVersion: selected.version }, controller.signal)
      if (controller.signal.aborted) return
      setFeedback({ text: `Assigned ${selected.trackingNumber} to ${courier.fullName}.`, error: false })
    } catch (error) {
      if (controller.signal.aborted) return
      const failure = error instanceof DispatcherError ? error : new DispatcherError('unavailable')
      setFeedback({ text: failure.message, error: true })
      if (failure.kind === 'auth-required') await auth.handleSessionExpiry()
    } finally {
      if (!controller.signal.aborted) {
        // Also reconcile unknown outcomes: commands are never retried automatically.
        await refresh()
        if (!controller.signal.aborted) { command.current = null; setPending(false) }
      }
    }
  }
  const data = query.data
  const available = data?.availableCouriers.filter(c => c.eligible) ?? []
  const chosenCourier = available.find(c => c.courierSub === courierSub)

  // Zone choices accumulate from every page seen, so a zone stays selectable after filtering to it.
  const pageZones = (data?.unassignedParcels ?? []).flatMap(p => [p.pickupZone, p.dropoffZone])
    .filter(z => Boolean(z) && z !== 'UNKNOWN')
  if (pageZones.some(z => !seenZones.includes(z))) setSeenZones(Array.from(new Set([...seenZones, ...pageZones])).sort())
  const zoneCodes = seenZones

  const toggleCourier = (sub: string) => {
    setExpandedCouriers(prev => {
      const next = new Set(prev)
      if (next.has(sub)) next.delete(sub)
      else next.add(sub)
      return next
    })
  }

  // The server already filtered by zone, so the page is shown as returned.
  const displayedParcels = useMemo(() => data?.unassignedParcels ?? [], [data?.unassignedParcels])

  const filteredParcels = useMemo(() => {
    let list = displayedParcels
    if (queueSearch.trim()) {
      const q = queueSearch.trim().toLowerCase()
      list = list.filter(p => p.trackingNumber.toLowerCase().includes(q) || p.pickupZone.toLowerCase().includes(q) || p.dropoffZone.toLowerCase().includes(q))
    }
    if (queueSort === 'weight-desc') return [...list].sort((a, b) => b.weightKg - a.weightKg)
    if (queueSort === 'weight-asc') return [...list].sort((a, b) => a.weightKg - b.weightKg)
    if (queueSort === 'newest') return [...list].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    return list
  }, [displayedParcels, queueSearch, queueSort])

  const filteredCouriers = useMemo(() => {
    const list = data?.availableCouriers ?? []
    if (!courierSearch.trim()) return list
    const q = courierSearch.trim().toLowerCase()
    return list.filter(c => c.fullName.toLowerCase().includes(q) || c.courierSub.toLowerCase().includes(q))
  }, [data?.availableCouriers, courierSearch])

  return <section className="dispatcher-operations" aria-label="Dispatch operations" aria-busy={query.isFetching || pending}>
    <div className="dispatcher-toolbar">
      <h2 className="section-heading">Unassigned parcels</h2>
      <Button variant="secondary" size="sm" disabled={pending || query.isFetching} onClick={() => { void refresh() }}>Refresh operations</Button>
    </div>
    {feedback && <p role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p>}
    {query.isFetching && <p role="status">Loading queue and courier workloads…</p>}
    {query.isError && <div role="alert"><p>{query.error instanceof DispatcherError ? query.error.message : 'Operations provider unavailable.'}</p><p>Refresh operations to try again.</p></div>}
    {data && !query.isError && <>
      <div className="dispatcher-queue-controls card" style={{ marginBottom: '16px', padding: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px', marginBottom: '12px' }}>
          <p style={{ margin: 0, fontWeight: 500, fontSize: '0.88rem' }}>
            Page {data.pageInfo.currentPage + 1} · {data.pageInfo.pageSize} per page · {data.pageInfo.totalElements} {data.pageInfo.totalElements === 1 ? 'parcel' : 'parcels'}{zoneFilter !== 'ALL' && ` in ${zoneFilter}`}
          </p>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
            <input
              type="search"
              placeholder="Search tracking #..."
              aria-label="Search unassigned parcels"
              value={queueSearch}
              onChange={e => setQueueSearch(e.target.value)}
              className="queue-search-input"
            />
            <select
              aria-label="Sort unassigned parcels"
              value={queueSort}
              onChange={e => setQueueSort(e.target.value as 'default' | 'weight-desc' | 'weight-asc' | 'newest')}
              className="queue-sort-select"
            >
              <option value="default">Default order</option>
              <option value="weight-desc">Heaviest first</option>
              <option value="weight-asc">Lightest first</option>
              <option value="newest">Newest first</option>
            </select>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap', paddingTop: '10px', borderTop: '1px solid var(--sd-border, #e0e5d8)' }} aria-label="Filter unassigned parcels by zone">
          <span className="muted" style={{ fontSize: '0.85rem', fontWeight: 600 }}>Zone:</span>
          {['ALL', ...zoneCodes].map(z => (
            <Button
              key={z}
              size="sm"
              variant={zoneFilter === z ? 'primary' : 'tonal'}
              aria-pressed={zoneFilter === z}
              style={{ minHeight: '28px', padding: '2px 10px', fontSize: '0.78rem', borderRadius: '16px' }}
              onClick={() => { setZoneFilter(z); setPage(0); clear() }}
            >
              {/* Selected state is shown by a check mark and aria-pressed, not colour alone (audit 97). */}
              {zoneFilter === z && <span aria-hidden="true">✓ </span>}
              {z === 'ALL' ? 'All Zones' : z}
            </Button>
          ))}
        </div>
      </div>
      {!data.unassignedParcels.length ? (
        <div className="queue-empty-card card" role="status">
          <div className="empty-icon-circle">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
              <polyline points="22 4 12 14.01 9 11.01"></polyline>
            </svg>
          </div>
          <h3 style={{ margin: '0 0 4px 0', fontSize: '1rem', fontWeight: 600 }}>Queue is clear</h3>
          <p style={{ margin: '0 0 4px 0' }}>No unassigned parcels on this page.</p>
          <span className="muted" style={{ fontSize: '0.82rem' }}>All booked deliveries have been dispatched to active couriers. New bookings will appear here automatically.</span>
        </div>
      ) : !displayedParcels.length ? (
        <div className="queue-empty-card card" role="status">
          <div className="empty-icon-circle">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon>
            </svg>
          </div>
          <h3 style={{ margin: '0 0 4px 0', fontSize: '1rem', fontWeight: 600 }}>No zone matches</h3>
          <p style={{ margin: '0 0 4px 0' }}>No parcels match zone {zoneFilter}.</p>
          <span className="muted" style={{ fontSize: '0.82rem' }}>Select &ldquo;All Zones&rdquo; to view unassigned deliveries in other zones.</span>
        </div>
      ) : !filteredParcels.length ? (
        <div className="queue-empty-card card" role="status">
          <div className="empty-icon-circle">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
          </div>
          <h3 style={{ margin: '0 0 4px 0', fontSize: '1rem', fontWeight: 600 }}>No search results</h3>
          <p style={{ margin: '0 0 4px 0' }}>No parcels match search criteria.</p>
          <span className="muted" style={{ fontSize: '0.82rem' }}>Check tracking number spelling or clear the search filter.</span>
        </div>
      ) : (
        <div className="dispatcher-table-scroll"><table className="dispatcher-table">
          <caption>Unassigned parcel queue</caption>
          <thead><tr>{['Tracking number', 'Pickup zone', 'Dropoff zone', 'Weight', 'Size', 'Created', 'Version', 'Action'].map(h => <th scope="col" key={h}>{h}</th>)}</tr></thead>
          <tbody>{filteredParcels.map(parcel => <tr key={parcel.parcelId}>
            <th scope="row">{parcel.trackingNumber}</th><td data-label="Pickup zone">{parcel.pickupZone}</td><td data-label="Dropoff zone">{parcel.dropoffZone}</td>
            <td data-label="Weight">{parcel.weightKg} kg</td><td data-label="Size">{parcel.sizeCategory}</td><td data-label="Created"><time dateTime={parcel.createdAt}>{new Date(parcel.createdAt).toLocaleString()}</time></td><td data-label="Version">{parcel.version}</td>
            <td data-label="Action"><Button variant="secondary" size="sm" disabled={pending || query.isFetching} onClick={() => { setSelected(parcel); setCourierSub(''); setFeedback(null) }} aria-label={`Select ${parcel.trackingNumber}`}>Select</Button></td>
          </tr>)}</tbody>
        </table></div>
      )}
      <nav aria-label="Queue pagination" className="dispatcher-toolbar">
        <Button variant="secondary" size="sm" disabled={page === 0 || pending || query.isFetching} onClick={() => { clear(); setPage(p => p - 1) }}>Previous page</Button>
        <Button variant="secondary" size="sm" disabled={!data.pageInfo.hasNext || pending || query.isFetching} onClick={() => { clear(); setPage(p => p + 1) }}>Next page</Button>
      </nav>
      <section className="card" aria-labelledby="courier-workloads-heading">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '8px' }}>
          <div>
            <h2 id="courier-workloads-heading" className="section-heading">Courier workloads</h2>
            <p className="muted" style={{ margin: 0 }}>Each courier's current assignments and how many they can take.</p>
          </div>
          <input
            type="search"
            placeholder="Filter couriers..."
            aria-label="Filter couriers by name"
            value={courierSearch}
            onChange={e => setCourierSearch(e.target.value)}
            style={{ fontSize: '0.8rem', padding: '3px 8px', borderRadius: '4px', border: '1px solid var(--sd-border, #ccc)', maxWidth: '180px' }}
          />
        </div>
        {!available.length && <p role="status">No eligible couriers available.</p>}
        <ul style={{ listStyle: 'none', padding: 0, margin: '12px 0 0 0', display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {filteredCouriers.map(c => {
            const pct = Math.min(100, Math.round((c.activeAssignmentsCount / (c.maxCapacity || 1)) * 100))
            const isFull = c.activeAssignmentsCount >= c.maxCapacity
            const isExpanded = expandedCouriers.has(c.courierSub)
            return (
              <li key={c.courierSub} style={{ padding: '10px 14px', background: 'var(--sd-surface-card, #fff)', border: '1px solid var(--sd-border, #e0e5d8)', borderRadius: '8px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '6px' }}>
                  <span>{c.fullName} · {c.activeAssignmentsCount} active {c.activeAssignmentsCount === 1 ? 'assignment' : 'assignments'} · Capacity {c.maxCapacity} · {c.eligible ? 'Eligible' : 'Not eligible'}</span>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    {c.activeAssignmentsCount > 0 && (
                      <Button
                        variant="tonal"
                        size="sm"
                        style={{ fontSize: '0.75rem', padding: '2px 8px', minHeight: '26px' }}
                        onClick={() => toggleCourier(c.courierSub)}
                        aria-expanded={isExpanded}
                      >
                        {isExpanded ? 'Hide parcels' : `View ${c.activeAssignmentsCount} parcels`}
                      </Button>
                    )}
                    <Badge variant={c.eligible ? (isFull ? 'danger' : 'success') : 'neutral'} size="sm">
                      {c.eligible ? (isFull ? 'Capacity Reached' : 'Available') : 'Not Eligible'}
                    </Badge>
                  </div>
                </div>
                {c.maxCapacity > 0 && (
                  <div style={{ marginTop: '6px', height: '6px', background: '#e5e7eb', borderRadius: '3px', overflow: 'hidden' }}>
                    <div
                      style={{
                        width: `${pct}%`,
                        height: '100%',
                        background: !c.eligible ? '#9ca3af' : isFull ? '#dc2626' : pct > 60 ? '#f59e0b' : '#059669',
                        transition: 'width 0.3s ease',
                      }}
                    />
                  </div>
                )}
                {isExpanded && (
                  <CourierParcelsExpanded courierSub={c.courierSub} api={api} />
                )}
              </li>
            )
          })}
        </ul>
      </section>
      {selected && <section ref={assignmentPanel} className="card dispatcher-assignment" aria-labelledby="assignment-heading">
        <h2 id="assignment-heading" className="section-heading" tabIndex={-1}>Confirm courier assignment</h2>
        <p>{selected.trackingNumber} · Parcel {selected.parcelId} · Version {selected.version}</p>
        <p>{selected.pickupZone} → {selected.dropoffZone} · {selected.weightKg} kg · {selected.sizeCategory}</p>
        <label htmlFor="assignment-courier">Eligible courier</label>
        <select id="assignment-courier" value={courierSub} disabled={pending || query.isFetching} onChange={e => setCourierSub(e.target.value)}>
          <option value="">Choose a courier</option>
          {available.map(c => <option key={c.courierSub} value={c.courierSub}>{c.fullName} ({c.activeAssignmentsCount} active)</option>)}
        </select>
        {chosenCourier && <p>Assign {selected.trackingNumber} to {chosenCourier.fullName}?</p>}
        <div className="dispatcher-toolbar">
          <Button variant="primary" disabled={!chosenCourier || pending || query.isFetching} onClick={() => { void submit() }}>{pending ? 'Assigning…' : 'Confirm assignment'}</Button>
          <Button variant="secondary" disabled={pending} onClick={clear}>Cancel assignment</Button>
        </div>
      </section>}
    </>}
  </section>
}
