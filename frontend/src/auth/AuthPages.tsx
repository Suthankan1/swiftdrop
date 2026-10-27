import { useEffect } from 'react'
import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from './context'
import { FeedbackState } from '../components/ui/FeedbackState'
import type { SwiftDropRole } from './contracts'
import { sanitizeRedirectUrl } from './redirect'

const messages = {
  cancelled: 'Sign-in was cancelled. You can try again.',
  'invalid-callback': 'Sign-in could not be verified. Please start again.',
  'provider-unavailable': 'The identity provider is unavailable. Please try again later.',
  'session-expired': 'Your session expired. Please sign in again.',
}

export function LoginPage() {
  const auth = useAuth()
  const location = useLocation()
  const returnTarget = sanitizeRedirectUrl((location.state as { from?: string } | null)?.from)

  if (auth.snapshot.status === 'loading') {
    return <FeedbackState title="Checking sign-in" tone="loading">Please wait.</FeedbackState>
  }

  return (
    <div className="page-container">
      <h1>Sign in to SwiftDrop</h1>
      {auth.problem && <p role="alert">{messages[auth.problem]}</p>}
      {auth.snapshot.status === 'authenticated' ? (
        <>
          <p>You are signed in.</p>
          {returnTarget && (
            <p>
              <Link to={returnTarget} className="intended-destination-link">
                Continue to requested page ({returnTarget})
              </Link>
            </p>
          )}
          <div className="role-links" style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', margin: '1rem 0' }}>
            {auth.snapshot.roles.includes('CUSTOMER') && <Link to="/customer">Customer space</Link>}
            {auth.snapshot.roles.includes('MERCHANT') && <Link to="/merchant">Merchant portal</Link>}
            {auth.snapshot.roles.includes('COURIER') && <Link to="/courier">Courier workspace</Link>}
            {auth.snapshot.roles.includes('DISPATCHER') && <Link to="/dispatcher">Dispatcher console</Link>}
            {auth.snapshot.roles.includes('ADMIN') && <Link to="/admin">Admin console</Link>}
          </div>
          <button onClick={() => { void auth.logout() }}>Sign out</button>
        </>
      ) : (
        <>
          <p>Continue securely with Keycloak. Reloading requires signing in again; an existing Keycloak session can be reused.</p>
          <button onClick={() => { void auth.login() }}>Sign in</button>{' '}
          <button onClick={() => { void auth.googleLogin() }}>Continue with Google</button>{' '}
          <button onClick={() => { void auth.register() }}>Create account</button>
        </>
      )}
    </div>
  )
}

export function CallbackPage() {
  const { snapshot, problem } = useAuth()

  if (snapshot.status === 'loading') {
    return <FeedbackState title="Completing sign-in" tone="loading">Please wait.</FeedbackState>
  }

  if (problem) {
    return <Navigate to={problem === 'cancelled' ? '/login?error=cancelled' : '/login'} replace />
  }

  if (snapshot.status === 'authenticated') {
    // Default safe role entrypoint
    if (snapshot.roles.includes('CUSTOMER')) return <Navigate to="/customer" replace />
    if (snapshot.roles.includes('MERCHANT')) return <Navigate to="/merchant" replace />
    if (snapshot.roles.includes('COURIER')) return <Navigate to="/courier" replace />
    if (snapshot.roles.includes('DISPATCHER')) return <Navigate to="/dispatcher" replace />
    if (snapshot.roles.includes('ADMIN')) return <Navigate to="/admin" replace />
    return <Navigate to="/login" replace />
  }

  return <Navigate to="/login" replace />
}

export function LogoutPage() {
  const { logout } = useAuth()
  useEffect(() => {
    void logout()
  }, [logout])

  return (
    <div className="page-container">
      <FeedbackState title="Signed out">
        Local session cleared. If the identity provider is unavailable, close its session separately.
      </FeedbackState>
      <Link to="/login">Return to sign-in</Link>
    </div>
  )
}

/** UX boundary only. Every backend must independently authorize the actor. */
export function ProtectedRoute({ role }: { role?: SwiftDropRole }) {
  const { snapshot } = useAuth()
  const location = useLocation()

  if (snapshot.status === 'loading') {
    return <FeedbackState title="Checking session" tone="loading">Please wait.</FeedbackState>
  }
  if (snapshot.status !== 'authenticated') {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  }
  if (role && !snapshot.roles.includes(role)) {
    return <FeedbackState title="Access unavailable" tone="error">This entry is unavailable for your current role.</FeedbackState>
  }
  return <Outlet />
}
