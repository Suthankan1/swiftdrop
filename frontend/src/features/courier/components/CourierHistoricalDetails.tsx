import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { CourierApiError } from '../api/courierContracts'
import { createCourierAssignmentsApi } from '../api/courierAssignments'

interface CourierHistoricalDetailsProps {
  parcelId: string
  apiOverride?: ReturnType<typeof createCourierAssignmentsApi>
  onBack: () => void
}

export function CourierHistoricalDetails({ parcelId, apiOverride, onBack }: CourierHistoricalDetailsProps) {
  const auth = useAuth()
  const api = useMemo(
    () => apiOverride || createCourierAssignmentsApi({ getAccessToken: auth.getAccessToken }),
    [apiOverride, auth.getAccessToken],
  )
  const [parcel, setParcel] = useState<Awaited<ReturnType<typeof api.getHistoricalParcel>> | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    void api.getHistoricalParcel(parcelId, controller.signal).then(setParcel).catch((reason: unknown) => {
      if (!controller.signal.aborted) setError(reason instanceof CourierApiError ? reason.message : 'Historical details are unavailable.')
    })
    return () => controller.abort()
  }, [api, parcelId])

  return (
    <section className="courier-parcel-detail-view" aria-label="Historical delivery details">
      <div className="detail-navigation-bar">
        <Button type="button" variant="secondary" size="sm" onClick={onBack}>Back to History</Button>
      </div>
      {error && <div className="feedback-banner is-error card" role="alert"><span>{error}</span><Button variant="secondary" size="sm" onClick={onBack}>Return to History</Button></div>}
      {!error && !parcel && <div className="loading-state card" role="status"><span className="loading-indicator" aria-hidden="true" /><p className="muted">Loading authorized historical details…</p></div>}
      {parcel && (
        <article className="card parcel-detail-header-card" aria-labelledby="historical-detail-heading">
          <div className="section-heading-row">
            <div>
              <p className="eyebrow">Read-only delivery record</p>
              <h2 id="historical-detail-heading" className="monospace tracking-number-heading">{parcel.trackingNumber}</h2>
            </div>
            <Badge variant={parcel.currentStatus === 'DELIVERED' ? 'success' : 'danger'}>
              {parcel.currentStatus}
            </Badge>
          </div>
          <div className="route-addresses">
            <div className="address-block"><span className="address-label">Pickup Address</span><p className="address-text">{parcel.pickupAddress || 'Not available'}</p></div>
            <div className="route-arrow-down" aria-hidden="true">↓</div>
            <div className="address-block"><span className="address-label">Drop-off Address</span><p className="address-text">{parcel.dropoffAddress || 'Not available'}</p></div>
          </div>
          <p className="muted historical-readonly-note">This delivery is closed. Status changes, notes, and proof modifications are unavailable.</p>
        </article>
      )}
    </section>
  )
}
