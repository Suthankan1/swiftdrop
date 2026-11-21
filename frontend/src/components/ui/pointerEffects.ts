import { useCallback, useRef, type PointerEvent } from 'react'

/** Pointer effects are a mouse-only enhancement: no touch, no keyboard dependence, and off for reduced motion. */
const FINE_POINTER_WITH_MOTION = '(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)'

function pointerEffectsAllowed(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(FINE_POINTER_WITH_MOTION).matches
}

/**
 * Writes the pointer position (px, relative to the element) into `--px` / `--py` and sets
 * `data-pointer="on"` while the mouse is over it. With `tilt`, also writes `--rx` / `--ry` (degrees) for a
 * gentle 3D tilt of at most ±tilt. CSS decides what to do with the values; updates are batched per frame.
 */
export function usePointerEffects<T extends HTMLElement>(tilt = 0) {
  const frame = useRef(0)

  const onPointerMove = useCallback((event: PointerEvent<T>) => {
    if (event.pointerType !== 'mouse' || !pointerEffectsAllowed()) return
    const element = event.currentTarget
    const bounds = element.getBoundingClientRect()
    const x = event.clientX - bounds.left
    const y = event.clientY - bounds.top
    cancelAnimationFrame(frame.current)
    frame.current = requestAnimationFrame(() => {
      element.style.setProperty('--px', `${x.toFixed(1)}px`)
      element.style.setProperty('--py', `${y.toFixed(1)}px`)
      if (tilt > 0) {
        element.style.setProperty('--rx', `${((0.5 - y / bounds.height) * 2 * tilt).toFixed(2)}deg`)
        element.style.setProperty('--ry', `${((x / bounds.width - 0.5) * 2 * tilt).toFixed(2)}deg`)
      }
      element.dataset.pointer = 'on'
    })
  }, [tilt])

  const onPointerLeave = useCallback((event: PointerEvent<T>) => {
    cancelAnimationFrame(frame.current)
    const element = event.currentTarget
    element.dataset.pointer = 'off'
    element.style.setProperty('--rx', '0deg')
    element.style.setProperty('--ry', '0deg')
  }, [])

  return { onPointerMove, onPointerLeave }
}
