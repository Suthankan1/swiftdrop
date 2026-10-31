import { useAuth } from '../../../auth/context'
import { Link } from 'react-router'
import { Button } from '../../../components/ui/Button'
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
          <p><Link to="/admin/merchants" className="button button-tonal button-sm" style={{ width: '100%', justifyContent: 'space-between', marginBottom: '8px' }}><span>Review merchant applications</span> <span aria-hidden="true">→</span></Link></p>
          {auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('ADMIN') && <>
            <h3>User and staff management</h3>
            <p><Link to="/admin/users" className="button button-tonal button-sm" style={{ width: '100%', justifyContent: 'space-between', marginBottom: '8px' }}><span>Browse account users</span> <span aria-hidden="true">→</span></Link></p>
            <p><Link to="/admin/staff" className="button button-tonal button-sm" style={{ width: '100%', justifyContent: 'space-between', marginBottom: '8px' }}><span>Manage staff profiles</span> <span aria-hidden="true">→</span></Link></p>
            <h3>Zones and pricing</h3>
            <p><Link to="/admin/pricing" className="button button-tonal button-sm" style={{ width: '100%', justifyContent: 'space-between', marginBottom: '8px' }}><span>Manage zones and pricing</span> <span aria-hidden="true">→</span></Link></p>
            <h3>Reports</h3>
            <p><Link to="/admin/reports" className="button button-tonal button-sm" style={{ width: '100%', justifyContent: 'space-between', marginBottom: '8px' }}><span>View daily outcomes and admin reports</span> <span aria-hidden="true">→</span></Link></p>
          </>}
          <Button variant="secondary" type="button" onClick={() => { void auth.logout() }}>
            Sign out
          </Button>
          {query.isError && (
            <Button
              variant="secondary"
              type="button"
              onClick={() => { void refetch() }}
            >
              Retry profile
            </Button>
          )}
        </aside>
      </div>
    </div>
  )
}
