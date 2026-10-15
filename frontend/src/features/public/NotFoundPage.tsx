import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="page-container not-found-page">
      <span className="not-found-code" aria-hidden="true">404<span>↗</span></span>
      <p className="eyebrow">A LITTLE OFF THE ROUTE</p>
      <h1 className="page-heading">This page couldn’t be found.</h1>
      <p className="muted">The address may have changed. Let’s get you back to familiar ground.</p>
      <Link className="button button-primary" to="/">Back to home <span aria-hidden="true">→</span></Link>
    </div>
  )
}
