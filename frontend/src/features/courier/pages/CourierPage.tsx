import { useAuth } from '../../../auth/context'
import { StaffProfile } from '../../staff/components/StaffProfile'
import { useStaffProfile } from '../../staff/hooks/useStaffProfile'
import { CourierProofWorkspace } from '../components/CourierProofWorkspace'

export function CourierPage() {
  const auth = useAuth()
  const { state, query, refetch } = useStaffProfile('COURIER')

  const isOperationalCourier =
    state.status === 'ready' &&
    state.profile.businessStatus === 'ACTIVE' &&
    state.profile.role === 'COURIER'

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
        <section className="courier-proof-section" aria-label="Proof of Delivery Section">
          <CourierProofWorkspace
            key={auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : 'anon'}
          />
        </section>
      )}

    </div>
  )
}
