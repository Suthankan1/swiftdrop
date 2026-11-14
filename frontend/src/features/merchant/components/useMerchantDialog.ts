import { useEffect, useRef } from 'react'

/** Editing/loading rerenders never steal focus; closing restores the opener. */
export function useMerchantDialog(open: boolean, onClose: () => void, busy = false) {
  const panel = useRef<HTMLDivElement>(null)
  const state = useRef({ onClose, busy })
  useEffect(() => { state.current = { onClose, busy } }, [onClose, busy])
  useEffect(() => {
    if (!open) return
    const node = panel.current
    const previous = document.activeElement
    node?.focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopPropagation()
        if (!state.current.busy) state.current.onClose()
      }
      if (event.key === 'Tab') {
        const controls = node?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled):not([type="hidden"]), select:not(:disabled), a[href]')
        const first = controls?.[0], last = controls?.[controls.length - 1]
        if (!first) { event.preventDefault(); node?.focus(); return }
        if (event.shiftKey && (document.activeElement === first || document.activeElement === node)) {
          event.preventDefault(); last?.focus()
        } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === node)) {
          event.preventDefault(); first.focus()
        }
      }
    }
    node?.addEventListener('keydown', keydown)
    return () => {
      node?.removeEventListener('keydown', keydown)
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus()
    }
  }, [open])
  return panel
}
