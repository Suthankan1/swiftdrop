import { useEffect } from 'react'
import { Link, Navigate, Outlet } from 'react-router'
import { useAuth } from './context'
import { FeedbackState } from '../components/ui/FeedbackState'
import type { SwiftDropRole } from './contracts'
const messages = {
  cancelled: 'Sign-in was cancelled. You can try again.',
  'invalid-callback': 'Sign-in could not be verified. Please start again.',
  'provider-unavailable': 'The identity provider is unavailable. Please try again later.',
  'session-expired': 'Your session expired. Please sign in again.',
}
export function LoginPage() {
  const auth = useAuth()
  if (auth.snapshot.status === 'loading') return <FeedbackState title="Checking sign-in" tone="loading">Please wait.</FeedbackState>
  return <div className="page-container"><h1>Sign in to SwiftDrop</h1>
    {auth.problem && <p role="alert">{messages[auth.problem]}</p>}
    {auth.snapshot.status === 'authenticated' ? <><p>You are signed in.</p>
      {auth.snapshot.roles.includes('CUSTOMER') && <Link to="/customer">Customer space</Link>}
      <p>Other role landing experiences are being built by their teams.</p><button onClick={() => { void auth.logout() }}>Sign out</button></> :
      <><p>Continue securely with Keycloak. Reloading requires signing in again; an existing Keycloak session can be reused.</p>
        <button onClick={() => { void auth.login() }}>Sign in</button>{' '}
        <button onClick={() => { void auth.googleLogin() }}>Continue with Google</button>{' '}
        <button onClick={() => { void auth.register() }}>Create account</button></>}
  </div>
}
export function CallbackPage() {
  const { snapshot, problem } = useAuth()
  if (snapshot.status === 'loading') return <FeedbackState title="Completing sign-in" tone="loading">Please wait.</FeedbackState>
  return <Navigate to={problem === 'cancelled' ? '/login?error=cancelled' : '/login'} replace />
}
export function LogoutPage() {
  const { logout } = useAuth()
  useEffect(() => { void logout() }, [logout])
  return <div className="page-container"><FeedbackState title="Signed out">Local session cleared. If the identity provider is unavailable, close its session separately.</FeedbackState><Link to="/login">Return to sign-in</Link></div>
}
/** UX boundary only. Every backend must independently authorize the actor. */
export function ProtectedRoute({ role }: { role?: SwiftDropRole }) {
  const { snapshot } = useAuth()
  if (snapshot.status === 'loading') return <FeedbackState title="Checking session" tone="loading">Please wait.</FeedbackState>
  if (snapshot.status !== 'authenticated') return <Navigate to="/login" replace />
  if (role && !snapshot.roles.includes(role)) return <FeedbackState title="Access unavailable" tone="error">This entry is unavailable for your current role.</FeedbackState>
  return <Outlet />
}
