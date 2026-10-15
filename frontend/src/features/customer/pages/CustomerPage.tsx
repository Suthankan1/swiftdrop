import { CustomerProfile } from '../components/CustomerProfile'

/** #36 will connect verified session state here; #6 never requests private data. */
export function CustomerPage() {
  return (
    <div className="page-container customer-page">
      <header className="page-heading">
        <p className="eyebrow">Customer space</p>
        <h1>A little space for<br />everything you send.</h1>
        <p className="muted">Your Devarom account starts here. A simple home for your profile, with your personal details kept private.</p>
      </header>
      <div className="profile-grid">
        <CustomerProfile state={{ status: 'auth-required' }} />
        <aside className="card customer-note" aria-labelledby="customer-note-heading">
          <span className="badge">The first step</span>
          <h2 id="customer-note-heading">Built around you.</h2>
          <p className="muted">This is the beginning of your customer space. Secure sign-in and account access are coming next.</p>
          <p className="muted">Booking and parcel tracking will arrive in a later release.</p>
        </aside>
      </div>
    </div>
  )
}
