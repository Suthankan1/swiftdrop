import { useLayoutEffect, useRef } from 'react'

/**
 * The page's one reveal-on-scroll utility. Elements marked `data-reveal` inside the returned ref start as
 * "pending" and become "shown" the first time 15% of them is visible. The hidden state only exists in CSS under
 * `prefers-reduced-motion: no-preference`, and without IntersectionObserver everything is shown immediately, so
 * content is never left invisible. An optional `--reveal-delay` custom property staggers siblings.
 */
export function useRevealOnScroll<T extends HTMLElement>() {
  const ref = useRef<T>(null)

  useLayoutEffect(() => {
    const root = ref.current
    if (!root) return
    const targets = Array.from(root.querySelectorAll<HTMLElement>('[data-reveal]'))
    const reduceMotion = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

    if (typeof IntersectionObserver === 'undefined' || reduceMotion) {
      targets.forEach((target) => { target.dataset.reveal = 'shown' })
      return
    }

    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        ;(entry.target as HTMLElement).dataset.reveal = 'shown'
        observer.unobserve(entry.target)
      }
    }, { threshold: 0.15 })

    targets.forEach((target) => {
      if (target.dataset.reveal === 'shown') return
      target.dataset.reveal = 'pending'
      observer.observe(target)
    })
    return () => observer.disconnect()
  }, [])

  return ref
}
