import type { ReactNode } from 'react'

interface FeedbackStateProps {
  title: string
  children: ReactNode
  tone?: 'neutral' | 'loading' | 'error'
  action?: ReactNode
}

export function FeedbackState({ title, children, tone = 'neutral', action }: FeedbackStateProps) {
  return (
    <section className={`state-panel state-panel-${tone}`} aria-label={title}>
      <div role={tone === 'error' ? 'alert' : 'status'} aria-live={tone === 'error' ? 'assertive' : 'polite'}>
        {tone === 'loading' && <span className="loading-indicator" aria-hidden="true" />}
        <h2>{title}</h2>
        <p className="muted">{children}</p>
      </div>
      {action}
    </section>
  )
}
