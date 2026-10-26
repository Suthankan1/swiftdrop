import { useAuth } from '../../../auth/context'
import { StaffProfile } from '../../staff/components/StaffProfile'
import { useStaffProfile } from '../../staff/hooks/useStaffProfile'
import { DispatcherOperations } from '../components/DispatcherOperations'

export function DispatcherPage() {
  const auth = useAuth()
  const { state, query, refetch } = useStaffProfile('DISPATCHER')

  return (
    <div className="page-container staff-page dispatcher-page">
      <header className="staff-heading">
        <p className="eyebrow">Dispatcher space</p>
        <h1 className="page-heading">Dispatcher workspace</h1>
        <p className="muted">
          Coordinate courier assignments and delivery operations from a workspace built for dispatch.
        </p>
      </header>
      <div className="profile-grid">
        <StaffProfile state={state} onRetry={() => { void refetch() }} />
        <aside className="card staff-note" aria-labelledby="dispatcher-next-heading">
          <h2 className="section-heading" id="dispatcher-next-heading">Dispatch operations</h2>
          <p className="muted">
            Review unassigned parcels and eligible courier workloads before confirming an assignment.
          </p>
          <p className="muted">Assigning a courier does not change the parcel lifecycle status.</p>
          <button className="button button-secondary" type="button" onClick={() => { void auth.logout() }}>
            Sign out
          </button>
          {query.isError && (
            <button
              className="button button-secondary"
              type="button"
              onClick={() => { void refetch() }}
            >
              Retry profile
            </button>
          )}
        </aside>
      </div>
      {state.status === 'ready' && <DispatcherOperations key={auth.snapshot.subject} />}
    </div>
  )
}
