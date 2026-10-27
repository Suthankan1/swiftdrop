import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  merchantReviewApi, merchantReviewFailure, MerchantReviewError,
  type MerchantReviewApi, type MerchantReviewAction, type MerchantReviewDetail,
} from '../api/merchantReview'
import { MerchantReviewConfirmation } from '../components/MerchantReviewConfirmation'

function Status({ status }: { status: string }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>
}

export function MerchantReviewPage({ api = merchantReviewApi }: { api?: MerchantReviewApi }) {
  const auth = useAuth()
  const allowed = auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('ADMIN')
  return <div className="page-container merchant-review-page">
    <Link to="/admin">Back to administration</Link>
    <header className="staff-heading"><p className="eyebrow">Admin space</p>
      <h1 className="page-heading">Merchant review</h1>
      <p className="muted">Review business enrollment and the actions available for each merchant.</p>
    </header>
    {allowed ? api ? <MerchantReviewWorkspace key={auth.snapshot.subject} api={api} /> :
      <section className="card" aria-labelledby="review-unavailable-title">
        <h2 id="review-unavailable-title">Merchant review unavailable</h2>
        <p role="status">Merchant review is awaiting the Accounts review service. Applications and audit history cannot be loaded yet.</p>
        <div className="merchant-review-toolbar">
          <button className="button button-primary" disabled aria-describedby="review-action-unavailable">Approve merchant</button>
          <button className="button button-secondary" disabled aria-describedby="review-action-unavailable">Suspend merchant</button>
        </div>
        <p id="review-action-unavailable">Approval and suspension are unavailable until the review service is connected.</p>
      </section> : <p role="alert">Access to merchant review requires an authenticated ADMIN session.</p>}
  </div>
}

function MerchantReviewWorkspace({ api }: { api: MerchantReviewApi }) {
  const auth = useAuth()
  const cache = useQueryClient()
  const sessionId = useId()
  const prefix = ['private', 'admin-merchant-review', auth.snapshot.subject, sessionId] as const
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const detailTrigger = useRef<HTMLButtonElement | null>(null)
  // The shared AuthProvider also cancels/clears all private queries at logout.
  useEffect(() => {
    const queryKey = ['private', 'admin-merchant-review', auth.snapshot.subject, sessionId]
    return () => { void cache.cancelQueries({ queryKey }); cache.removeQueries({ queryKey }) }
  }, [cache, auth.snapshot.subject, sessionId])
  const query = useQuery({
    queryKey: [...prefix, 'list', page, 20],
    queryFn: async ({ signal }) => {
      try {
        const result = await api.list(page, 20, signal)
        signal.throwIfAborted()
        if (result.page !== page || result.items.length > 20) throw new MerchantReviewError('invalid-response')
        return result
      } catch (error) {
        if (!signal.aborted && merchantReviewFailure(error).kind === 'auth-required') await auth.handleSessionExpiry()
        throw error
      }
    },
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false,
  })
  return <section aria-label="Merchant applications" aria-busy={query.isFetching}>
    <div className="merchant-review-toolbar"><h2 className="section-heading">Merchant applications</h2>
      <button className="button button-secondary" disabled={query.isFetching} onClick={() => { setSelectedId(null); void query.refetch() }}>Refresh list</button>
    </div>
    {query.isFetching && <p role="status">Loading merchant applications…</p>}
    {query.isError && <div role="alert"><p>{merchantReviewFailure(query.error).message}</p>
      <button className="button button-secondary" disabled={query.isFetching} onClick={() => { void query.refetch() }}>Retry list</button>
    </div>}
    {query.data && !query.isError && <>
      {query.data.items.length === 0 ? <p role="status">No merchant applications on this page.</p> :
        <div className="merchant-review-table-scroll" tabIndex={0} role="region" aria-label="Scrollable merchant applications table"><table className="merchant-review-table">
          <caption>Merchant applications · Page {page + 1}</caption>
          <thead><tr><th scope="col">Business</th><th scope="col">Registration</th><th scope="col">Status</th><th scope="col">Submitted</th><th scope="col">Review</th></tr></thead>
          <tbody>{query.data.items.map(item => <tr key={item.id}>
            <th scope="row">{item.businessName}<small className="merchant-review-id">{item.id}</small></th>
            <td>{item.registrationNumber ?? 'Not provided'}</td><td><Status status={item.status} /></td>
            <td>{item.submittedAt ?? 'Not provided'}</td>
            <td><button className="button button-secondary" disabled={query.isFetching}
              aria-expanded={selectedId === item.id} aria-controls={selectedId === item.id ? 'merchant-review-details' : undefined}
              onClick={event => { detailTrigger.current = event.currentTarget; setSelectedId(item.id) }}>Review {item.businessName}</button></td>
          </tr>)}</tbody>
        </table></div>}
      <nav className="merchant-review-toolbar" aria-label="Merchant review pages">
        <button className="button button-secondary" disabled={page === 0 || query.isFetching} onClick={() => { setSelectedId(null); setPage(value => value - 1) }}>Previous page</button>
        <span>Page {page + 1}</span>
        <button className="button button-secondary" disabled={!query.data.hasNext || query.isFetching} onClick={() => { setSelectedId(null); setPage(value => value + 1) }}>Next page</button>
      </nav>
    </>}
    {selectedId && <MerchantDetails key={selectedId} id={selectedId} api={api} prefix={prefix}
      onClose={() => { setSelectedId(null); detailTrigger.current?.focus() }} />}
  </section>
}

function MerchantDetails({ id, api, prefix, onClose }: {
  id: string; api: MerchantReviewApi; prefix: readonly unknown[]; onClose: () => void
}) {
  const auth = useAuth()
  const cache = useQueryClient()
  const [confirm, setConfirm] = useState<MerchantReviewAction | null>(null)
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ error: boolean; text: string } | null>(null)
  const command = useRef<AbortController | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus(); return () => command.current?.abort() }, [])
  const query = useQuery({
    queryKey: [...prefix, 'detail', id],
    queryFn: async ({ signal }) => {
      try {
        const detail = await api.detail(id, signal)
        signal.throwIfAborted()
        if (detail.id !== id) throw new MerchantReviewError('invalid-response')
        return detail
      } catch (error) {
        if (!signal.aborted && merchantReviewFailure(error).kind === 'auth-required') await auth.handleSessionExpiry()
        throw error
      }
    },
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false,
  })
  const detail = query.data
  const canChange = (action: MerchantReviewAction, value: MerchantReviewDetail | undefined = detail) =>
    Boolean(value?.reviewToken && api.change && api.supportedActions.includes(action) &&
      value.actions.some(item => item.action === action) && (action !== 'approve' || value.status === 'PENDING'))

  const submit = async (reason?: string) => {
    if (!confirm || !detail || !canChange(confirm) || query.isFetching || query.isError || command.current) return
    const policy = detail.actions.find(item => item.action === confirm)?.reason
    if (policy && ((policy.required && !reason?.trim()) || (reason?.length ?? 0) > policy.maxLength)) return
    const controller = new AbortController()
    command.current = controller
    setPending(true)
    setFeedback(null)
    try {
      await api.change!({ merchantId: id, action: confirm, reviewToken: detail.reviewToken!, ...(reason ? { reason } : {}) }, controller.signal)
      if (!controller.signal.aborted) setFeedback({ error: false, text: 'The server accepted the change. Check the refreshed merchant details below.' })
    } catch (error) {
      if (controller.signal.aborted) return
      const failure = merchantReviewFailure(error)
      setFeedback({ error: true, text: failure.kind === 'unavailable'
        ? 'The command outcome could not be confirmed. Refresh and review the current state before trying again. It was not retried.' : failure.message })
      if (failure.kind === 'auth-required') await auth.handleSessionExpiry()
    } finally {
      if (!controller.signal.aborted) {
        setConfirm(null)
        // Reconcile success, conflicts and unknown outcomes. Never replay a command.
        await cache.invalidateQueries({ queryKey: prefix })
        if (!controller.signal.aborted) { command.current = null; setPending(false) }
      }
    }
  }
  return <section id="merchant-review-details" className="card merchant-review-detail" aria-labelledby="merchant-detail-heading" aria-busy={query.isFetching || pending}>
    <div className="merchant-review-toolbar"><h2 ref={heading} tabIndex={-1} id="merchant-detail-heading">Merchant details</h2>
      <button className="button button-secondary" disabled={pending} onClick={onClose}>Close details</button>
      <button className="button button-secondary" disabled={pending || query.isFetching} onClick={() => { setConfirm(null); void query.refetch() }}>Refresh details</button>
    </div>
    {feedback && <p role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p>}
    {query.isFetching && <p role="status">Loading merchant details…</p>}
    {query.isError && <div role="alert"><p>{merchantReviewFailure(query.error).message}</p>
      <button className="button button-secondary" disabled={pending || query.isFetching} onClick={() => { void query.refetch() }}>Retry details</button>
    </div>}
    {detail && !query.isError && <>
      <h3>{detail.businessName}</h3>
      <dl><dt>Merchant ID</dt><dd>{detail.id}</dd>
        {detail.registrationNumber && <><dt>Registration</dt><dd>{detail.registrationNumber}</dd></>}
        <dt>Status</dt><dd><Status status={detail.status} /></dd>
        {detail.submittedAt && <><dt>Submitted</dt><dd>{detail.submittedAt}</dd></>}
      </dl>
      <div className="merchant-review-toolbar">{(['approve', 'suspend'] as const).map(action => <button key={action}
        className="button button-secondary" disabled={pending || query.isFetching || !canChange(action)}
        aria-describedby={!canChange(action) ? `merchant-${action}-unavailable` : undefined}
        onClick={() => setConfirm(action)}>{action === 'approve' ? 'Approve merchant' : 'Suspend merchant'}</button>)}</div>
      {!canChange('approve') && <p id="merchant-approve-unavailable">Approval is unavailable for this merchant or is not supported by the review service.</p>}
      {!canChange('suspend') && <p id="merchant-suspend-unavailable">Suspension is unavailable for this merchant or is not supported by the review service.</p>}
      <h3>Audit history</h3>
      {detail.audit === undefined ? <p>Audit history is unavailable from the review service.</p> : detail.audit.length === 0 ?
        <p>No audit records were returned.</p> : <ol className="merchant-review-audit">{detail.audit.map(item => <li key={item.id}>
          <p>{item.action} · {item.actor} · <time dateTime={item.occurredAt}>{item.occurredAt}</time></p>
          {item.reason && <p>Reason: {item.reason}</p>}
        </li>)}</ol>}
      {confirm && <MerchantReviewConfirmation merchant={detail} action={confirm} pending={pending}
        onCancel={() => setConfirm(null)} onConfirm={reason => { void submit(reason) }} />}
    </>}
  </section>
}
