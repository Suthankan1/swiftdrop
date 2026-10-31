import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { createDispatcherApi, DispatcherError, type UnassignedParcel } from '../api/dispatcher'

/** Mounted with a session key by the guarded page; no selection survives a session change. */
export function DispatcherOperations() {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const client = useQueryClient()
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<UnassignedParcel | null>(null)
  const [courierSub, setCourierSub] = useState('')
  const [zoneFilter, setZoneFilter] = useState('ALL')
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null)
  const command = useRef<AbortController | null>(null)
  const api = createDispatcherApi({ getAccessToken: auth.getAccessToken })
  const queryKey = ['private', 'dispatcher-dashboard', subject] as const
  const query = useQuery({
    queryKey: [...queryKey, page, 20],
    queryFn: async ({ signal }) => {
      try { return await api.dashboard(page, 20, signal) }
      catch (error) {
        if (!signal.aborted && error instanceof DispatcherError && error.kind === 'auth-required') await auth.handleSessionExpiry()
        throw error
      }
    },
    retry: false, gcTime: 0, staleTime: 0,
    enabled: auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('DISPATCHER'),
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

  const displayedParcels = data?.unassignedParcels.filter(p => {
    if (zoneFilter === 'ALL') return true
    return p.pickupZone === zoneFilter || p.dropoffZone === zoneFilter
  }) ?? []

  return <section className="dispatcher-operations" aria-label="Dispatch operations" aria-busy={query.isFetching || pending}>
    <div className="dispatcher-toolbar">
      <h2 className="section-heading">Unassigned parcels</h2>
      <Button variant="secondary" size="sm" disabled={pending || query.isFetching} onClick={() => { void refresh() }}>Refresh operations</Button>
    </div>
    {feedback && <p role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p>}
    {query.isFetching && <p role="status">Loading queue and courier workloads…</p>}
    {query.isError && <div role="alert"><p>{query.error instanceof DispatcherError ? query.error.message : 'Operations provider unavailable.'}</p><p>Refresh operations to try again.</p></div>}
    {data && !query.isError && <>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px', marginBottom: '12px' }}>
        <p style={{ margin: 0 }}>Page {data.pageInfo.currentPage + 1} · {data.pageInfo.pageSize} per page · {data.pageInfo.totalElements} parcels</p>
        <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }} aria-label="Filter unassigned parcels by zone">
          <span className="muted" style={{ fontSize: '0.85rem' }}>Zone:</span>
          {['ALL', 'ZONE_1', 'ZONE_2', 'ZONE_3'].map(z => (
            <Button
              key={z}
              size="sm"
              variant={zoneFilter === z ? 'primary' : 'tonal'}
              style={{ minHeight: '28px', padding: '2px 8px', fontSize: '0.78rem' }}
              onClick={() => setZoneFilter(z)}
            >
              {z === 'ALL' ? 'All Zones' : z}
            </Button>
          ))}
        </div>
      </div>
      {!data.unassignedParcels.length ? <p role="status">No unassigned parcels on this page.</p> :
        !displayedParcels.length ? <p role="status">No parcels match zone {zoneFilter}.</p> :
        <div className="dispatcher-table-scroll"><table className="dispatcher-table">
          <caption>Unassigned parcel queue</caption>
          <thead><tr>{['Tracking number', 'Pickup zone', 'Dropoff zone', 'Weight', 'Size', 'Created', 'Version', 'Action'].map(h => <th scope="col" key={h}>{h}</th>)}</tr></thead>
          <tbody>{displayedParcels.map(parcel => <tr key={parcel.parcelId}>
            <th scope="row">{parcel.trackingNumber}</th><td data-label="Pickup zone">{parcel.pickupZone}</td><td data-label="Dropoff zone">{parcel.dropoffZone}</td>
            <td data-label="Weight">{parcel.weightKg} kg</td><td data-label="Size">{parcel.sizeCategory}</td><td data-label="Created"><time dateTime={parcel.createdAt}>{new Date(parcel.createdAt).toLocaleString()}</time></td><td data-label="Version">{parcel.version}</td>
            <td data-label="Action"><Button variant="secondary" size="sm" disabled={pending || query.isFetching} onClick={() => { setSelected(parcel); setCourierSub(''); setFeedback(null) }} aria-label={`Select ${parcel.trackingNumber}`}>Select</Button></td>
          </tr>)}</tbody>
        </table></div>}
      <nav aria-label="Queue pagination" className="dispatcher-toolbar">
        <Button variant="secondary" size="sm" disabled={page === 0 || pending || query.isFetching} onClick={() => { clear(); setPage(p => p - 1) }}>Previous page</Button>
        <Button variant="secondary" size="sm" disabled={!data.pageInfo.hasNext || pending || query.isFetching} onClick={() => { clear(); setPage(p => p + 1) }}>Next page</Button>
      </nav>
      <section className="card" aria-labelledby="courier-workloads-heading">
        <h2 id="courier-workloads-heading" className="section-heading">Courier workloads</h2>
        <p className="muted">Current active assignments. Eligibility and capacity are supplied by the provider.</p>
        {!available.length && <p role="status">No eligible couriers available.</p>}
        <ul style={{ listStyle: 'none', padding: 0, margin: '12px 0 0 0', display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {data.availableCouriers.map(c => {
            const pct = Math.min(100, Math.round((c.activeAssignmentsCount / (c.maxCapacity || 1)) * 100))
            const isFull = c.activeAssignmentsCount >= c.maxCapacity
            return (
              <li key={c.courierSub} style={{ padding: '8px 12px', background: 'var(--sd-surface-card, #fff)', border: '1px solid var(--sd-border, #e0e5d8)', borderRadius: '8px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '6px' }}>
                  <span>{c.fullName} · {c.activeAssignmentsCount} active assignments · Capacity {c.maxCapacity} · {c.eligible ? 'Eligible' : 'Not eligible'}</span>
                  <Badge variant={c.eligible ? (isFull ? 'danger' : 'success') : 'neutral'} size="sm">
                    {c.eligible ? (isFull ? 'Capacity Reached' : 'Available') : 'Not Eligible'}
                  </Badge>
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
              </li>
            )
          })}
        </ul>
      </section>
      {selected && <section className="card dispatcher-assignment" aria-labelledby="assignment-heading">
        <h2 id="assignment-heading" className="section-heading">Confirm courier assignment</h2>
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
