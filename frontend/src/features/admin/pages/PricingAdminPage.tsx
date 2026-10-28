import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { createPricingApi, formatLkr, PricingError, pricingFailure, type Card, type Page, type PricingApi, type PricingCommand, type Zone } from '../api/pricingAdmin'
import { PricingForm, type Editor } from '../components/PricingForm'
import { StaffConfirmation } from '../components/StaffConfirmation'

function usePricingRead<T>(prefix: readonly string[], key: readonly unknown[], read: (signal: AbortSignal) => Promise<T>, fail: (error: unknown, signal: AbortSignal) => Promise<void>, enabled: boolean) {
  return useQuery({ queryKey: [...prefix, ...key], queryFn: async ({ signal }) => {
    try { const result = await read(signal); signal.throwIfAborted(); return result }
    catch (error) { await fail(error, signal); throw error }
  }, enabled, retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false })
}
function checkEffectivePeriod(card: Card) {
  const now = Date.now()
  if (Date.parse(card.effectiveFrom) > now || (card.effectiveTo && Date.parse(card.effectiveTo) <= now)) throw new PricingError('validation', 'RATE_NOT_EFFECTIVE')
}
export function PricingAdminPage({ api }: { api?: PricingApi }) {
  const auth = useAuth()
  const provider = useMemo(() => createPricingApi({ getAccessToken: auth.getAccessToken }), [auth.getAccessToken])
  if (auth.snapshot.status !== 'authenticated' || !auth.snapshot.roles.includes('ADMIN')) return <p role="alert">Pricing administration requires an authenticated ADMIN session.</p>
  return <Workspace key={`${auth.snapshot.subject}:${auth.snapshot.roles.join(',')}`} api={api ?? provider} />
}
function Workspace({ api }: { api: PricingApi }) {
  const auth = useAuth(), cache = useQueryClient(), session = useId()
  const prefix = useMemo(() => ['private', 'admin-pricing', auth.snapshot.subject ?? '', session], [auth.snapshot.subject, session])
  const [denied, setDenied] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  const editorTrigger = useRef<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false), [feedback, setFeedback] = useState<string | null>(null)
  const [unknown, setUnknown] = useState(false), [reconciled, setReconciled] = useState(false)
  const [conflict, setConflict] = useState(false), [latestZone, setLatestZone] = useState<Zone | null>(null)
  const [zonePage, setZonePage] = useState(0), [cardPage, setCardPage] = useState(0), [rulePage, setRulePage] = useState(0)
  const [selected, setSelected] = useState<Card | null>(null)
  const [editor, setEditor] = useState<Editor | null>(null), [editorId, setEditorId] = useState(0)
  const [confirmation, setConfirmation] = useState<PricingCommand | null>(null)
  useEffect(() => () => { controller.current?.abort(); void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix }) }, [cache, prefix])
  useEffect(() => { if (denied) controller.current?.abort() }, [denied])
  async function fail(error: unknown, signal: AbortSignal) {
    if (signal.aborted) return
    const failure = pricingFailure(error)
    if (failure.kind === 'session' || failure.kind === 'denied') {
      setDenied(failure.message)
      void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix })
      if (failure.kind === 'session') await auth.handleSessionExpiry()
    }
  }
  const zones = usePricingRead(prefix, ['zones', zonePage], signal => api.zones(zonePage, signal), fail, !denied)
  const cards = usePricingRead(prefix, ['cards', cardPage], signal => api.cards(cardPage, signal), fail, !denied)
  const catalogue = usePricingRead(prefix, ['catalogue'], signal => api.review(signal), fail, !denied)
  const rules = usePricingRead(prefix, ['rules', selected?.id, rulePage], signal => api.rules(selected!.id, rulePage, signal), fail, !denied && !!selected)
  const canonicalCard = selected && catalogue.data?.find(c => c.id === selected.id)
  const current = catalogue.data?.find(c => c.status === 'ACTIVE')
  const locked = busy || unknown
  const canonicalReady = !catalogue.isError && !catalogue.isFetching && !!catalogue.data
  function openEditor(value: Editor) { editorTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setEditor(value); setEditorId(n => n + 1); setConflict(false); setFeedback(null); setLatestZone(null) }
  function closeEditor() { setEditor(null); setConflict(false); if (editorTrigger.current?.isConnected) editorTrigger.current.focus() }
  async function refresh() {
    if (controller.current) return
    const operation = new AbortController(); controller.current = operation; setBusy(true)
    setConfirmation(null); setReconciled(false)
    try {
      await cache.invalidateQueries({ queryKey: prefix, refetchType: 'none' })
      const results = await Promise.allSettled([
        zones.refetch({ throwOnError: true }), cards.refetch({ throwOnError: true }), catalogue.refetch({ throwOnError: true }),
        ...(selected ? [rules.refetch({ throwOnError: true })] : []),
        ...(editor?.kind === 'zone' && editor.zone ? [api.zone(editor.zone.id, operation.signal).then(value => { operation.signal.throwIfAborted(); setLatestZone(value) })] : []),
      ])
      if (operation.signal.aborted) return
      const rejected = results.find(r => r.status === 'rejected')
      if (rejected?.status === 'rejected') { setFeedback(pricingFailure(rejected.reason).message); await fail(rejected.reason, operation.signal) }
      else setReconciled(true)
    } finally { if (!operation.signal.aborted) { controller.current = null; setBusy(false) } }
  }
  async function publication(card: Card) {
    if (controller.current || locked) return
    const operation = new AbortController(); controller.current = operation; setBusy(true); setFeedback(null)
    try {
      const latest = await api.review(operation.signal)
      operation.signal.throwIfAborted()
      cache.setQueryData([...prefix, 'catalogue'], latest)
      const draft = latest.find(c => c.id === card.id)
      if (!draft || draft.status !== 'DRAFT' || draft.revision !== card.revision) throw new PricingError('conflict')
      checkEffectivePeriod(draft)
      const page = await api.rules(draft.id, 0, operation.signal)
      operation.signal.throwIfAborted()
      if (!page.totalElements) throw new PricingError('validation', 'INCOMPLETE_RATE_CARD')
      setConfirmation({ action: 'activate', card: draft, active: latest.find(c => c.status === 'ACTIVE') ?? null })
    } catch (error) { if (!operation.signal.aborted) { setFeedback(pricingFailure(error).message); await fail(error, operation.signal) } }
    finally { if (!operation.signal.aborted) { controller.current = null; setBusy(false) } }
  }
  async function submit() {
    if (!confirmation || controller.current || unknown) return
    const command = confirmation, operation = new AbortController()
    controller.current = operation; setBusy(true); setFeedback(null); setReconciled(false)
    try {
      await api.command(command, operation.signal)
      operation.signal.throwIfAborted()
      setConfirmation(null); closeEditor()
      setFeedback('Pricing confirmed persistence. Canonical server configuration is being refreshed.')
      await cache.invalidateQueries({ queryKey: prefix })
    } catch (error) {
      if (operation.signal.aborted) return
      const failure = pricingFailure(error)
      setConfirmation(null); setFeedback(failure.message)
      setUnknown(failure.kind === 'unknown'); setConflict(failure.kind === 'conflict')
      await fail(error, operation.signal)
      if (!operation.signal.aborted) {
        await cache.invalidateQueries({ queryKey: prefix })
        if ('zone' in command) {
          try { const value = await api.zone(command.zone.id, operation.signal); operation.signal.throwIfAborted(); setLatestZone(value) }
          catch (readError) { await fail(readError, operation.signal) }
        }
      }
    } finally { if (!operation.signal.aborted) { controller.current = null; setBusy(false) } }
  }
  const editorCard = editor?.kind === 'rule' ? catalogue.data?.find(c => c.id === editor.card.id) : undefined
  const changed = editor?.kind === 'rule' && (!editorCard || editorCard.revision !== editor.card.revision || editorCard.status !== 'DRAFT')
  const zoneChanged = editor?.kind === 'zone' && editor.zone && latestZone && latestZone.id === editor.zone.id && latestZone.revision !== editor.zone.revision
  const needsReview = conflict || !!changed || !!zoneChanged
  // Background reads never silently change a reviewed confirmation.
  const staleConfirmation = confirmation && 'card' in confirmation && catalogue.data && (() => {
    const latest = catalogue.data.find(c => c.id === confirmation.card.id)
    return !latest || latest.revision !== confirmation.card.revision || latest.status !== confirmation.card.status || (confirmation.action === 'activate' && (current?.id ?? null) !== (confirmation.active?.id ?? null))
  })()
  return <div className="page-container admin-management pricing-admin">
    <Link to="/admin">Back to administration</Link>
    <header className="staff-heading"><p className="eyebrow">Admin space</p><h1 className="page-heading">Zones and pricing</h1>
      <p>Prepare unpublished drafts, review revisions and publish for eligible new quotes.</p></header>
    <aside className="card"><h2>Historical prices are preserved</h2><p>Previously issued quotes retain their original amount, currency and version until their existing expiry. Accepted booking and receipt snapshots keep their original prices. This editor never recalculates historical amounts.</p></aside>
    {denied ? <p role="alert">{denied}</p> : <>
      <div className="admin-management-toolbar"><button className="button button-secondary" disabled={busy || zones.isFetching || cards.isFetching || catalogue.isFetching} onClick={() => { void refresh() }}>Refresh configuration</button></div>
      {feedback && <p role="status">{feedback}</p>}
      {unknown && <aside className="card" role="alert"><h2>Review the unknown outcome</h2><p>Writes are paused. Refresh and inspect the affected zone, version or rule, including other pages. A create may have succeeded even if its response was lost. Do not recreate an existing code or rule.</p><button className="button button-secondary" disabled={!reconciled || busy} onClick={() => { setUnknown(false); setConflict(true); setFeedback('Server configuration reviewed. Reapply only changes that are still needed and confirm again.') }}>I reviewed the server state</button></aside>}
      <section aria-label="Current active pricing"><h2>Current ACTIVE pricing</h2><ReadState query={catalogue} retry={() => { setConfirmation(null); void catalogue.refetch() }} />
        {canonicalReady && (current ? <p><strong>{current.versionCode}</strong> · ACTIVE · {current.currency} · revision {current.revision} · {current.effectiveFrom} to {current.effectiveTo ?? 'open end'}</p> : <p>No ACTIVE card is configured.</p>)}
      </section>
      <section aria-label="Zone management"><div className="admin-management-toolbar"><h2>Zones</h2><button className="button button-primary" disabled={locked} onClick={() => openEditor({ kind: 'zone' })}>Create zone</button></div>
        <ReadState query={zones} retry={() => { void zones.refetch() }} />
        {zones.data && !zones.isError && <><div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Zone table"><table><caption>Server zone configuration</caption><thead><tr>{['Zone', 'Status', 'Transit', 'Revision', 'Actions'].map(s => <th scope="col" key={s}>{s}</th>)}</tr></thead><tbody>{zones.data.items.map(zone => <tr key={zone.id}><td><strong>{zone.zoneCode}</strong><br />{zone.name}<br />{zone.id}<p>{zone.description}</p><small>Updated {zone.updatedAt}</small></td><td>{zone.active ? 'Enabled' : 'Disabled'}</td><td>{zone.standardTransitHours} hours</td><td>{zone.revision}</td><td><button className="button button-secondary" disabled={locked || zones.isFetching} onClick={() => openEditor({ kind: 'zone', zone })}>Edit {zone.zoneCode}</button>{zone.active && <button className="button button-secondary" disabled={locked || zones.isFetching} onClick={() => { setFeedback(null); setConfirmation({ action: 'disable', zone }) }}>Disable {zone.zoneCode}</button>}</td></tr>)}</tbody></table></div>{!zones.data.items.length && <p>No zones on this page.</p>}<Pagination data={zones.data} busy={locked || zones.isFetching} change={p => { setConfirmation(null); setZonePage(p) }} /></>}
      </section>
      <section aria-label="Rate cards"><div className="admin-management-toolbar"><h2>Rate cards</h2><button className="button button-primary" disabled={locked} onClick={() => openEditor({ kind: 'draft' })}>Create draft</button></div>
        <p>DRAFT is unpublished and does not affect quotes. Only DRAFT rules can be edited. Only SUPERSEDED cards can be archived. Publication is immediate within the configured effective period.</p>
        <ReadState query={cards} retry={() => { setConfirmation(null); void cards.refetch() }} />
        {cards.data && !cards.isError && <><div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Rate card table"><table><caption>Versioned rate cards</caption><thead><tr>{['Version', 'Status', 'Currency', 'Effective period (UTC)', 'Revision', 'Actions'].map(s => <th key={s} scope="col">{s}</th>)}</tr></thead><tbody>{cards.data.items.map(card => <tr key={card.id}><td>{card.versionCode}<br /><small>{card.id}</small><p>{card.description}</p></td><td><strong>{card.status}</strong>{card.status === 'DRAFT' && <p>Unpublished</p>}</td><td>{card.currency}</td><td>{card.effectiveFrom}<br />to {card.effectiveTo ?? 'open end'}</td><td>{card.revision}</td><td><button className="button button-secondary" disabled={locked || cards.isFetching} onClick={() => { setSelected(card); setRulePage(0); setConfirmation(null) }}>Inspect {card.versionCode}</button>{card.status === 'DRAFT' && <button className="button button-primary" disabled={locked || cards.isFetching || !canonicalReady} onClick={() => { void publication(card) }}>Review publication {card.versionCode}</button>}{card.status === 'SUPERSEDED' && <button className="button button-secondary" disabled={locked || cards.isFetching} onClick={() => setConfirmation({ action: 'archive', card })}>Archive {card.versionCode}</button>}</td></tr>)}</tbody></table></div>{!cards.data.items.length && <p>No rate cards on this page.</p>}<Pagination data={cards.data} busy={locked || cards.isFetching} change={p => { setConfirmation(null); setCardPage(p) }} /></>}
      </section>
      {selected && <section aria-label="Rate rules"><h2>Rules for {selected.versionCode}</h2><p>{canonicalCard?.status ?? selected.status} · {selected.currency} · parent revision {canonicalCard?.revision ?? selected.revision} · {rules.data?.totalElements ?? 'Unknown'} rules</p>
        {canonicalCard?.status === 'DRAFT' && <button className="button button-primary" disabled={locked || !canonicalReady || rules.isFetching || rules.isError} onClick={() => openEditor({ kind: 'rule', card: canonicalCard })}>Add draft rule</button>}
        <ReadState query={rules} retry={() => { void rules.refetch() }} />
        {rules.data && !rules.isError && <><div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Rate rules table"><table><caption>Inclusive weight bands and integer minor units</caption><thead><tr>{['Route / category', 'Weight (kg)', 'Base fee', 'Surcharge / kg', 'Threshold (kg)', 'Actions'].map(s => <th key={s} scope="col">{s}</th>)}</tr></thead><tbody>{rules.data.items.map(rule => <tr key={rule.id}><td>{rule.pickupZoneCode} → {rule.dropoffZoneCode}<br />{rule.sizeCategory}<br /><small>{rule.id}</small></td><td>{rule.minWeightKg}–{rule.maxWeightKg}</td><td>{formatLkr(rule.baseFeeMinorUnits)}<br />{rule.baseFeeMinorUnits} minor units</td><td>{formatLkr(rule.perKgSurchargeMinorUnits)} / kg</td><td>{rule.weightThresholdKg}</td><td>{canonicalCard?.status === 'DRAFT' ? <button className="button button-secondary" disabled={locked || !canonicalReady || rules.isFetching || rule.revision !== canonicalCard.revision} onClick={() => openEditor({ kind: 'rule', card: canonicalCard, rule })}>Edit rule {rule.id}</button> : 'Historical rules are immutable'}</td></tr>)}</tbody></table></div>{!rules.data.items.length && <p>No rules on this page.</p>}<Pagination data={rules.data} busy={locked || rules.isFetching} change={setRulePage} /></>}
      </section>}
      {editor && <>
        {needsReview && <aside className="card" role="alert"><h2>Review concurrent changes</h2><p>Your inputs below are preserved. Compare them with these latest server values before choosing a new revision. No write has been retried.</p>
          {editor.kind === 'zone' && latestZone && <dl><dt>Latest zone name</dt><dd>{latestZone.name}</dd><dt>Description</dt><dd>{latestZone.description}</dd><dt>Transit hours / revision</dt><dd>{latestZone.standardTransitHours} / {latestZone.revision}</dd></dl>}
          {editor.kind === 'rule' && editorCard && <p>Latest {editorCard.versionCode}: {editorCard.status}, revision {editorCard.revision}. Inspect the refreshed rule rows above to compare values.</p>}
          <button className="button button-secondary" disabled={locked || (editor.kind === 'zone' && !!editor.zone && !latestZone) || (editor.kind === 'rule' && (!editorCard || editorCard.status !== 'DRAFT' || rules.isError || rules.isFetching))} onClick={() => {
            if (editor.kind === 'zone' && editor.zone && latestZone) setEditor({ ...editor, zone: latestZone })
            if (editor.kind === 'rule' && editorCard?.status === 'DRAFT') setEditor({ ...editor, card: editorCard })
            setConflict(false)
          }}>Reapply to reviewed revision</button>
        </aside>}
        <PricingForm key={editorId} editor={editor} busy={busy} blocked={unknown || needsReview || (editor.kind === 'rule' && !canonicalReady)} onCancel={closeEditor} onReview={setConfirmation} />
      </>}
      {confirmation && <StaffConfirmation title="Confirm Pricing change" pending={busy} confirmDisabled={!!staleConfirmation} providerNote="Pricing independently validates ADMIN access, revisions, references and publication coverage. Commands are never automatically replayed." onCancel={() => setConfirmation(null)} onConfirm={() => { if (!staleConfirmation) void submit() }}>
        <Confirmation command={confirmation} />
        {staleConfirmation && <p role="alert">Reviewed configuration changed. Cancel and review the latest configuration again.</p>}
      </StaffConfirmation>}
    </>}
  </div>
}
function Confirmation({ command }: { command: PricingCommand }) {
  if (command.action === 'activate') return <><p>Publish <strong>{command.card.versionCode}</strong> ({command.card.id}) · {command.card.currency} · revision {command.card.revision}.</p><p>Effective {command.card.effectiveFrom} to {command.card.effectiveTo ?? 'open end'}. This is immediate publication, not scheduling.</p><p>{command.active ? `ACTIVE ${command.active.versionCode} (${command.active.id}) will be superseded.` : 'No ACTIVE card was found in the reviewed configuration.'}</p><p>Eligible new quotes can use this version. Previously issued quotes and accepted booking and receipt snapshots retain their original prices.</p></>
  if (command.action === 'archive') return <p>Archive SUPERSEDED {command.card.versionCode} ({command.card.id}), revision {command.card.revision}. Historical rules and quote eligibility are preserved.</p>
  if (command.action === 'disable') return <p>Disable {command.zone.zoneCode} — {command.zone.name} ({command.zone.id}), revision {command.zone.revision}. This removes the zone from new eligible routes. Active-rule and unexpired-quote references block disabling. No history is deleted.</p>
  const labels: Record<string, string> = { zoneCode: 'Zone code', name: 'Zone name', description: 'Description', standardTransitHours: 'Transit hours', versionCode: 'Version code', currency: 'Currency', effectiveFrom: 'Effective from (UTC)', effectiveTo: 'Effective to (UTC)', pickupZoneCode: 'Pickup zone', dropoffZoneCode: 'Dropoff zone', sizeCategory: 'Parcel category', minWeightKg: 'Minimum weight (kg)', maxWeightKg: 'Maximum weight (kg)', baseFeeMinorUnits: 'Base fee', perKgSurchargeMinorUnits: 'Surcharge per kg', weightThresholdKg: 'Surcharge threshold (kg)' }
  return <><p>{command.action === 'draft' ? 'Create an unpublished draft. It does not affect quotes.' : command.action === 'rule' ? `Save ${command.ruleId ? `rule ${command.ruleId}` : 'a new rule'} in DRAFT ${command.card.versionCode}, LKR, parent revision ${command.card.revision}.` : command.action === 'create-zone' ? 'Create an enabled zone.' : `Update ${command.zone.zoneCode} (${command.zone.id}), revision ${command.zone.revision}.`}</p><dl className="admin-management-fields">{Object.entries(command.input).map(([key, value]) => <div key={key}><dt>{labels[key] ?? key}</dt><dd>{value === null ? 'Open end' : key.endsWith('MinorUnits') ? `${formatLkr(String(value))} (${value} minor units)` : String(value)}</dd></div>)}</dl></>
}
function ReadState({ query, retry }: { query: Pick<UseQueryResult, 'isFetching' | 'isError' | 'error'>; retry: () => void }) {
  return <>{query.isFetching && <p role="status">Loading server configuration…</p>}{query.isError && <div role="alert"><p>{pricingFailure(query.error).message}</p><button className="button button-secondary" disabled={query.isFetching} onClick={retry}>Retry read</button></div>}</>
}
function Pagination({ data, busy, change }: { data: Page<unknown>; busy: boolean; change: (p: number) => void }) {
  return <nav className="admin-management-toolbar" aria-label="Configuration pages"><button className="button button-secondary" disabled={busy || data.page === 0} onClick={() => change(data.page - 1)}>Previous page</button><span>Page {data.page + 1} · {data.totalElements} records · at most {data.size} per page</span><button className="button button-secondary" disabled={busy || (data.page + 1) * data.size >= data.totalElements} onClick={() => change(data.page + 1)}>Next page</button>{data.page > 0 && !data.items.length && <button disabled={busy} onClick={() => change(0)}>Return to first page</button>}</nav>
}
