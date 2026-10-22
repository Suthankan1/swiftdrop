import { useAuth } from '../../../auth/context'
import { StaffProfileUnavailable } from '../../staff/components/StaffProfileUnavailable'

export function AdminPage() {
  const auth = useAuth()

  return (
    <div className="page-container staff-page admin-page">
      <header className="staff-heading">
        <p className="eyebrow">Admin space</p>
        <h1 className="page-heading">Administration workspace</h1>
        <p className="muted">
          A separate entry for authorized administration, with access limited to approved responsibilities.
        </p>
      </header>
      <div className="profile-grid">
        <StaffProfileUnavailable />
        <aside className="card staff-note admin-policy" aria-labelledby="admin-policy-heading">
          <h2 className="section-heading" id="admin-policy-heading">Access with accountability</h2>
          <p className="muted">
            Staff access is provisioned by an authorized administrator. Signing in does not grant unrestricted access to business records.
          </p>
          <h3>Administration tools are coming later</h3>
          <p className="muted">
            Merchant approval management, staff administration, zone and pricing management, and operational reporting are future features.
          </p>
          <button className="button button-secondary" type="button" onClick={() => { void auth.logout() }}>
            Sign out
          </button>
        </aside>
      </div>
    </div>
  )
}
