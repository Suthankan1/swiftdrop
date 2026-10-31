import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import {
  type CourierDashboardSummary as CourierSummaryData,
  CourierApiError,
} from '../api/courierContracts'
import { createCourierDashboardApi } from '../api/courierDashboard'
import { Button } from '../../../components/ui/Button'

export interface CourierDashboardSummaryProps {
  apiOverride?: ReturnType<typeof createCourierDashboardApi>
}

export function CourierDashboardSummary({ apiOverride }: CourierDashboardSummaryProps) {
  const auth = useAuth()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''

  const api = useMemo(
    () =>
      apiOverride ||
      createCourierDashboardApi({
        getAccessToken: auth.getAccessToken,
      }),
    [apiOverride, auth.getAccessToken],
  )

  const [summary, setSummary] = useState<CourierSummaryData | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Subject isolation: clear stale stats immediately when courier identity changes
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setSummary(null)
    setError(null)
  }

  useEffect(() => {
    if (!subject) return

    let active = true
    const controller = new AbortController()

    void (async () => {
      try {
        setIsLoading(true)
        setError(null)
        const data = await api.getSummary(controller.signal)
        if (active) {
          setSummary(data)
        }
      } catch (err) {
        if (!active || controller.signal.aborted) return
        if (err instanceof CourierApiError) {
          setError(err.message)
        } else {
          setError('Unable to load operational summary.')
        }
      } finally {
        if (active) {
          setIsLoading(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, subject])

  const handleRetry = () => {
    if (!subject) return
    setIsLoading(true)
    setError(null)
    void api
      .getSummary()
      .then((data) => {
        setSummary(data)
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : 'Failed to reload summary.')
      })
      .finally(() => {
        setIsLoading(false)
      })
  }

  return (
    <section className="courier-summary-section" aria-labelledby="courier-summary-heading">
      <div className="section-heading-row">
        <div>
          <p className="eyebrow">Shift Overview · Asia/Colombo Day</p>
          <h2 id="courier-summary-heading">Today's Operational Summary</h2>
        </div>
      </div>

      {isLoading && (
        <div className="loading-state card" role="status" aria-label="Loading summary">
          <span className="loading-indicator" aria-hidden="true" />
          <p className="muted">Retrieving authoritative shift metrics...</p>
        </div>
      )}

      {error && (
        <div className="feedback-banner is-error card" role="alert">
          <span>{error}</span>
          <Button
            variant="secondary"
            size="sm"
            onClick={handleRetry}
          >
            Retry Summary
          </Button>
        </div>
      )}

      {!isLoading && !error && summary && (
        <div className="courier-summary-grid" role="region" aria-label="Operational metrics">
          <article className="card metric-card" aria-labelledby="metric-active-heading">
            <p className="eyebrow" id="metric-active-heading">Active Assigned</p>
            <div className="metric-value monospace">{summary.activeAssignedCount}</div>
            <p className="muted metric-caption">Parcels currently in your shift queue</p>
          </article>

          <article className="card metric-card" aria-labelledby="metric-delivered-heading">
            <p className="eyebrow" id="metric-delivered-heading">Delivered (Today)</p>
            <div className="metric-value monospace">{summary.todayDeliveredCount}</div>
            <p className="muted metric-caption">Authoritative completions today</p>
          </article>

          <article className="card metric-card" aria-labelledby="metric-failed-heading">
            <p className="eyebrow" id="metric-failed-heading">Exceptions (Today)</p>
            <div className="metric-value monospace">{summary.todayFailedCount}</div>
            <p className="muted metric-caption">Delivery attempts with logged exceptions</p>
          </article>
        </div>
      )}
    </section>
  )
}
