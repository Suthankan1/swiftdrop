import { useState } from 'react'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { StaffProfile } from '../../staff/components/StaffProfile'
import { useStaffProfile } from '../../staff/hooks/useStaffProfile'
import { CourierDashboardSummary } from '../components/CourierDashboardSummary'
import { CourierAssignmentList } from '../components/CourierAssignmentList'
import { CourierParcelDetails } from '../components/CourierParcelDetails'
import type { createCourierDashboardApi } from '../api/courierDashboard'
import type { createCourierAssignmentsApi } from '../api/courierAssignments'
import type { createCourierProofApi } from '../api/proof'
import type { createCourierStatusApi } from '../api/status'

export interface CourierPageProps {
  summaryApiOverride?: ReturnType<typeof createCourierDashboardApi>
  assignmentsApiOverride?: ReturnType<typeof createCourierAssignmentsApi>
  proofApiOverride?: ReturnType<typeof createCourierProofApi>
  statusApiOverride?: ReturnType<typeof createCourierStatusApi>
}

export function CourierPage({
  summaryApiOverride,
  assignmentsApiOverride,
  proofApiOverride,
  statusApiOverride,
}: CourierPageProps = {}) {
  const auth = useAuth()
  const { state, query, refetch } = useStaffProfile('COURIER')

  const isOperationalCourier =
    state.status === 'ready' &&
    state.profile.businessStatus === 'ACTIVE' &&
    state.profile.role === 'COURIER'

  const [selectedParcelId, setSelectedParcelId] = useState<string | null>(null)
  const [assignmentNotice, setAssignmentNotice] = useState<string | null>(null)
  const [summaryRefresh, setSummaryRefresh] = useState(0)

  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setSelectedParcelId(null)
    setAssignmentNotice(null)
  }

  return (
    <div className="page-container courier-page">
      <header className="page-heading">
        <p className="eyebrow">Courier space</p>
        <h1>
          Ready for your next route.
          <br />
          <span className="page-heading-subtitle" style={{ fontSize: '0.85em', fontWeight: 600, color: 'var(--color-teal)' }}>
            Courier Operations Dashboard
          </span>
        </h1>
        <p className="muted">
          Your assigned parcels, stage updates and proof of delivery in one place.
        </p>
      </header>

      {/* Primary operational panel visible first for operational couriers */}
      {isOperationalCourier && (
        <section className="courier-dashboard-container" aria-label="Courier Operations Dashboard">
          {assignmentNotice && (
            <div className="feedback-banner is-error" role="alert">
              <span>{assignmentNotice}</span>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => setAssignmentNotice(null)}
              >
                Dismiss
              </Button>
            </div>
          )}
          <CourierDashboardSummary
            key={`summary-${subject}`}
            apiOverride={summaryApiOverride}
            refreshToken={summaryRefresh}
          />

          {selectedParcelId ? (
            <CourierParcelDetails
              key={`parcel-${subject}-${selectedParcelId}`}
              parcelId={selectedParcelId}
              onClose={() => setSelectedParcelId(null)}
              onParcelChanged={() => setSummaryRefresh((value) => value + 1)}
              apiOverride={proofApiOverride}
              statusApiOverride={statusApiOverride}
              onAssignmentLost={(message) => {
                setAssignmentNotice(message)
                setSelectedParcelId(null)
              }}
            />
          ) : (
            <CourierAssignmentList
              key={`assignments-${subject}`}
              selectedParcelId={selectedParcelId}
              onSelectParcel={(id) => {
                setAssignmentNotice(null)
                setSelectedParcelId(id)
              }}
              apiOverride={assignmentsApiOverride}
            />
          )}
        </section>
      )}

      {/* When profile is not ready (loading, unprovisioned, suspended), show profile state immediately */}
      {!isOperationalCourier && (
        <section className="profile-grid" aria-label="Staff Profile Status">
          <StaffProfile
            state={state}
            onRetry={() => {
              void refetch()
            }}
          />

          <aside className="card courier-note" aria-labelledby="courier-next-heading">
            <h2 id="courier-next-heading">Your active delivery workflow.</h2>
            <p className="muted">
              Review authorized assignments, record operational notes, update parcel stages, and verify proof before completing delivery.
            </p>
            {query.isError && (
              <Button
                variant="secondary"
                type="button"
                onClick={() => {
                  void refetch()
                }}
              >
                Retry profile
              </Button>
            )}
          </aside>
        </section>
      )}

      {/* Secondary section for operational couriers: profile information moved below operations */}
      {isOperationalCourier && (
        <section className="profile-section secondary-section" aria-label="Courier Profile & Staff Information" style={{ marginTop: '2.5rem' }}>
          <div className="section-heading-row" style={{ marginBottom: '1rem' }}>
            <div>
              <p className="eyebrow">Staff Account</p>
              <h2>Courier Profile Details</h2>
            </div>
          </div>
          <div className="profile-grid">
            <StaffProfile
              state={state}
              onRetry={() => {
                void refetch()
              }}
            />

            <aside className="card courier-note" aria-labelledby="courier-next-heading">
              <h2 id="courier-next-heading">Your active delivery workflow.</h2>
              <p className="muted">
                Review authorized assignments, record operational notes, update parcel stages, and verify proof before completing delivery.
              </p>
              {query.isError && (
                <Button
                  variant="secondary"
                  type="button"
                  onClick={() => {
                    void refetch()
                  }}
                >
                  Retry profile
                </Button>
              )}
            </aside>
          </div>
        </section>
      )}
    </div>
  )
}
