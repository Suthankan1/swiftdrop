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

export interface CourierPageProps {
  summaryApiOverride?: ReturnType<typeof createCourierDashboardApi>
  assignmentsApiOverride?: ReturnType<typeof createCourierAssignmentsApi>
  proofApiOverride?: ReturnType<typeof createCourierProofApi>
}

export function CourierPage({
  summaryApiOverride,
  assignmentsApiOverride,
  proofApiOverride,
}: CourierPageProps = {}) {
  const auth = useAuth()
  const { state, query, refetch } = useStaffProfile('COURIER')

  const isOperationalCourier =
    state.status === 'ready' &&
    state.profile.businessStatus === 'ACTIVE' &&
    state.profile.role === 'COURIER'

  const [selectedParcelId, setSelectedParcelId] = useState<string | null>(null)

  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setSelectedParcelId(null)
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
          <h2 id="courier-next-heading">Built for the work ahead.</h2>
          <p className="muted">
            Secure handover proof capture is active for assigned parcels. Status updates and queue management will arrive in separately reviewed features.
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
            />
          ) : (
            <CourierAssignmentList
              key={`assignments-${subject}`}
              selectedParcelId={selectedParcelId}
              onSelectParcel={(id) => setSelectedParcelId(id)}
              apiOverride={assignmentsApiOverride}
            />
          )}
        </div>
      )}
    </div>
  )
}
