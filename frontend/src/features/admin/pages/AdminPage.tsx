import { useAuth } from '../../../auth/context'
import { Link } from 'react-router'
import { StaffProfile } from '../../staff/components/StaffProfile'
import { useStaffProfile } from '../../staff/hooks/useStaffProfile'

export function AdminPage() {
  const auth = useAuth()
  const { state, query, refetch } = useStaffProfile('ADMIN')

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
        <StaffProfile state={state} onRetry={() => { void refetch() }} />
        <aside className="card staff-note admin-policy" aria-labelledby="admin-policy-heading">
          <h2 className="section-heading" id="admin-policy-heading">Access with accountability</h2>
          <p className="muted">
            Staff access is provisioned by an authorized administrator. Signing in does not grant unrestricted access to business records.
          </p>
          <h3>Merchant review</h3>
          <p><Link to="/admin/merchants">Review merchant applications</Link></p>
          <h3>More administration tools are coming later</h3>
          <p className="muted">
            Staff administration, zone and pricing management, and operational reporting are future features.
          </p>
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
    </div>
  )
}
