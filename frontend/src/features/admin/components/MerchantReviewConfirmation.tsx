import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { merchantReasonProblem, type MerchantReviewAction, type MerchantReviewDetail } from '../api/merchantReview'

export function MerchantReviewConfirmation({ merchant, action, pending, onCancel, onConfirm }: {
  merchant: MerchantReviewDetail
  action: MerchantReviewAction
  pending: boolean
  onCancel: () => void
  onConfirm: (reason?: string) => void
}) {
  const panel = useRef<HTMLDivElement>(null)
  const overlay = useRef<HTMLDivElement>(null)
  const [reason, setReason] = useState('')
  const policy = merchant.actions.find(item => item.action === action)?.reason
  const problem = merchantReasonProblem(reason, policy)
  const label = action === 'approve' ? 'Approve' : 'Suspend'
  useEffect(() => {
    const previous = document.activeElement
    const siblings = Array.from(document.body.children).filter((item): item is HTMLElement =>
      item instanceof HTMLElement && item !== overlay.current)
    const inertStates = siblings.map(item => item.inert)
    siblings.forEach(item => { item.inert = true })
    panel.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => {
      siblings.forEach((item, index) => { item.inert = inertStates[index] ?? false })
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => { if (pending) panel.current?.focus() }, [pending])

  return createPortal(<div className="modal-backdrop" ref={overlay}>
    <div className="modal-panel merchant-review-confirmation" ref={panel} tabIndex={-1} role="dialog" aria-modal="true"
      aria-labelledby="merchant-confirm-title" aria-describedby="merchant-confirm-description" aria-busy={pending}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); if (!pending) onCancel() }
        if (event.key === 'Tab') {
          const elements = panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled), textarea:not(:disabled)')
          const first = elements?.[0]
          const last = elements?.[elements.length - 1]
          if (!first) { event.preventDefault(); return }
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
          if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
        }
      }}>
      <h2 id="merchant-confirm-title">{label} merchant?</h2>
      <p id="merchant-confirm-description">{label} <strong>{merchant.businessName}</strong> (merchant {merchant.id})?
        {merchant.registrationNumber && <> Registration: {merchant.registrationNumber}.</>}</p>
      <p>Current status: {merchant.status}. Confirm only after reviewing this merchant.</p>
      <form onSubmit={event => { event.preventDefault(); if (!pending && !problem) onConfirm(policy ? reason.trim() || undefined : undefined) }}>
        {policy && <div className="form-group">
          <label htmlFor="merchant-review-reason">Reason {policy.required ? '(required)' : '(optional)'}</label>
          <textarea id="merchant-review-reason" value={reason} required={policy.required} maxLength={policy.maxLength}
            aria-invalid={Boolean(problem && reason)} aria-describedby="merchant-review-reason-hint"
            disabled={pending} onChange={event => setReason(event.target.value)} />
          <p id="merchant-review-reason-hint">{problem && reason ? <span role="alert">{problem}</span> : null}
            {' '}{reason.trim().length}/{policy.maxLength} characters. Recorded in the merchant audit history.</p>
        </div>}
        <div className="modal-actions">
          <button className="button button-secondary" type="button" disabled={pending} onClick={onCancel}>Cancel</button>
          <button className="button button-primary" type="submit"
            disabled={pending || Boolean(problem)}>
            {pending ? 'Submitting…' : `Confirm ${action}`}
          </button>
        </div>
      </form>
      {pending && <p role="status">Waiting for the server. This command will not be retried automatically.</p>}
    </div>
  </div>, document.body)
}
