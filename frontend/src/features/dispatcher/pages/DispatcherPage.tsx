import { Link } from 'react-router'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { StaffProfile } from '../../staff/components/StaffProfile'
import { useStaffProfile } from '../../staff/hooks/useStaffProfile'
import { DispatcherOperations } from '../components/DispatcherOperations'

export function DispatcherPage() {
  const auth = useAuth()
  const actsAsAdmin = auth.snapshot.status === 'authenticated' &&
    !auth.snapshot.roles.includes('DISPATCHER') && auth.snapshot.roles.includes('ADMIN')
  const { state, query, refetch } = useStaffProfile(actsAsAdmin ? 'ADMIN' : 'DISPATCHER')

  return (
    <div className="page-container staff-page dispatcher-page">
      <header className="staff-heading">
        <p className="eyebrow">Dispatcher space</p>
        <h1 className="page-heading">Dispatcher workspace</h1>
        <p className="page-subheading" style={{ fontSize: '1.05rem', fontWeight: 600, color: 'var(--color-teal)', margin: '0.25rem 0 0.5rem' }}>
          Dispatch Operations Console
        </p>
        <p className="muted">
          Coordinate courier assignments, review unassigned parcel queues, and track delivery workloads.
        </p>
        <div className="dispatcher-quick-actions" style={{ marginTop: '0.75rem', display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
          {actsAsAdmin ? (
            <Link to="/admin/reports" className="button button-tonal button-sm">
              View daily outcomes and admin reports ↗
            </Link>
          ) : (
            <Link to="/dispatcher/reports" className="button button-tonal button-sm">
              View daily outcomes for your zones ↗
            </Link>
          )}
        </div>
      </header>

      {/* Primary operational panel visible first when profile is ready */}
      {state.status === 'ready' && (
        <section className="dispatcher-operations-container" aria-label="Dispatch Operations Console">
          <DispatcherOperations key={auth.snapshot.subject} />
        </section>
      )}

      {/* When profile is not ready (loading, unprovisioned, error), show profile feedback immediately */}
      {state.status !== 'ready' && (
        <section className="profile-grid" aria-label="Staff Profile Status">
          <StaffProfile state={state} onRetry={() => { void refetch() }} />
          <aside className="card staff-note" aria-labelledby="dispatcher-next-heading">
            <h2 className="section-heading" id="dispatcher-next-heading">Dispatch workflow notes</h2>
            <p className="muted">
              Review unassigned parcels and eligible courier workloads before confirming an assignment.
            </p>
            <p className="muted">Assigning a courier does not change the parcel lifecycle status.</p>
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
        </section>
      )}

      {/* Secondary profile section for operational dispatchers: profile details below operational tools */}
      {state.status === 'ready' && (
        <section className="profile-section secondary-section" aria-label="Dispatcher Profile & Staff Information" style={{ marginTop: '2.5rem' }}>
          <div className="section-heading-row" style={{ marginBottom: '1rem' }}>
            <div>
              <p className="eyebrow">Staff Account</p>
              <h2>Dispatcher Profile Details</h2>
            </div>
          </div>
          <div className="profile-grid">
            <StaffProfile state={state} onRetry={() => { void refetch() }} />
            <aside className="card staff-note" aria-labelledby="dispatcher-next-heading">
              <h2 className="section-heading" id="dispatcher-next-heading">Dispatch workflow notes</h2>
              <p className="muted">
                Review unassigned parcels and eligible courier workloads before confirming an assignment.
              </p>
              <p className="muted">Assigning a courier does not change the parcel lifecycle status.</p>
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
        </section>
      )}
    </div>
  )
}
