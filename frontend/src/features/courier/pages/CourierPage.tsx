import { useState } from 'react'
import { useAuth } from '../../../auth/context'
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
          Ready for your
          <br /> next route.
        </h1>
        <p className="muted">
          This protected entry confirms the Courier navigation experience and secure proof of delivery capture.
        </p>
      </header>

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
          <button
            className="button button-secondary"
            type="button"
            onClick={() => {
              void auth.logout()
            }}
          >
            Sign out
          </button>
          {query.isError && (
            <button
              className="button button-secondary"
              type="button"
              onClick={() => {
                void refetch()
              }}
            >
              Retry profile
            </button>
          )}
        </aside>
      </div>

      {isOperationalCourier && (
        <div className="courier-dashboard-container" aria-label="Courier Operations Dashboard">
          {assignmentNotice && (
            <div className="feedback-banner is-error" role="alert">
              <span>{assignmentNotice}</span>
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={() => setAssignmentNotice(null)}
              >
                Dismiss
              </button>
            </div>
          )}
          <CourierDashboardSummary
            key={`summary-${subject}`}
            apiOverride={summaryApiOverride}
          />

          {selectedParcelId ? (
            <CourierParcelDetails
              key={`parcel-${subject}-${selectedParcelId}`}
              parcelId={selectedParcelId}
              onClose={() => setSelectedParcelId(null)}
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
        </div>
      )}
    </div>
  )
}
