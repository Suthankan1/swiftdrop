import { useAuth } from '../../../auth/context'
import { StaffProfileUnavailable } from '../../staff/components/StaffProfileUnavailable'

export function DispatcherPage() {
  const auth = useAuth()

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
        <StaffProfileUnavailable />
        <aside className="card staff-note" aria-labelledby="dispatcher-next-heading">
          <h2 className="section-heading" id="dispatcher-next-heading">The work ahead</h2>
          <p className="muted">
            Live unassigned parcel queues, courier workloads, assignment commands and delivery analytics will arrive in future releases.
          </p>
          <p className="muted">This entry does not assign parcels or change deliveries.</p>
          <button className="button button-secondary" type="button" onClick={() => { void auth.logout() }}>
            Sign out
          </button>
        </aside>
      </div>
    </div>
  )
}
