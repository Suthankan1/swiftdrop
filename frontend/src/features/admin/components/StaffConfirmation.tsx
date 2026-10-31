import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../../components/ui/Button'

export function StaffConfirmation({ title, children, pending, onCancel, onConfirm, providerNote, confirmDisabled = false }: {
  title: string; children: ReactNode; pending: boolean; onCancel: () => void; onConfirm: () => void; providerNote?: string; confirmDisabled?: boolean
}) {
  const titleId = useId(), descriptionId = useId()
  const panel = useRef<HTMLDivElement>(null), overlay = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement
    const siblings = Array.from(document.body.children).filter((item): item is HTMLElement => item instanceof HTMLElement && item !== overlay.current)
    const inert = siblings.map(item => item.inert)
    siblings.forEach(item => { item.inert = true })
    panel.current?.querySelector('button')?.focus()
    return () => {
      siblings.forEach((item, index) => { item.inert = inert[index] ?? false })
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => { if (pending) panel.current?.focus() }, [pending])
  return createPortal(<div className="modal-backdrop" ref={overlay}>
    <div className="modal-panel staff-confirmation" ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId} aria-busy={pending}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); if (!pending) onCancel() }
        if (event.key === 'Tab') {
          const buttons = panel.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
          const first = buttons?.[0], last = buttons?.[buttons.length - 1]
          if (!first) event.preventDefault()
          else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
        }
      }}>
      <h2 id={titleId}>{title}</h2>
      <div id={descriptionId}>{children}</div>
      <p>{providerNote ?? 'Accounts will verify the request. Identity changes may require recovery before they are complete.'}</p>
      <div className="modal-actions">
        <Button variant="secondary" disabled={pending} onClick={onCancel}>Cancel</Button>
        <Button variant="primary" disabled={pending || confirmDisabled} isLoading={pending} loadingText="Submitting…" onClick={onConfirm}>Confirm action</Button>
      </div>
      {pending && <p role="status">Waiting for the server. This command will not be replayed automatically.</p>}
    </div>
  </div>, document.body)
}
