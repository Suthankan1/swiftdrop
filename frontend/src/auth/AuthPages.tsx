import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from './context'
import { FeedbackState } from '../components/ui/FeedbackState'
import { Button } from '../components/ui/Button'
import { ArrowRightIcon, TruckIcon } from '../components/ui/icons'
import type { SwiftDropRole } from './contracts'
import { readReturnTarget, saveReturnTarget, sanitizeRedirectUrl } from './redirect'
import sessionStreet from './assets/session-street.webp'
import sessionVan from './assets/session-delivery-van.webp'
import sessionCourier from './assets/session-courier.webp'
import './AuthPages.css'

const messages = {
  cancelled: 'Sign-in was cancelled. You can try again.',
  'invalid-callback': 'Sign-in could not be verified. Please start again.',
  'provider-unavailable': 'The identity provider is unavailable. Please try again later.',
  'session-expired': 'Your session expired. Please sign in again.',
}

const sessionMotionQuery = '(prefers-reduced-motion: no-preference)'
// The trip takes 5.2 seconds. Bound the wait if the browser misses the animation event.
const sessionTripFallbackMs = 6_000

export function LoginPage() {
  const auth = useAuth()
  const location = useLocation()
  const returnTarget = sanitizeRedirectUrl((location.state as { from?: string } | null)?.from)
  const action = new URLSearchParams(location.search).get('start') === 'register' ? 'register' : 'login'
  const attemptedLocation = useRef<string | null>(null)

  useEffect(() => {
    if (auth.snapshot.status !== 'unauthenticated' || auth.problem || attemptedLocation.current === location.key) return
    attemptedLocation.current = location.key
    void auth[action](returnTarget ?? undefined)
  }, [auth, action, location.key, returnTarget])

  if (auth.snapshot.status === 'loading') {
    return <FeedbackState title="Checking sign-in" tone="loading">Please wait.</FeedbackState>
  }

  if (auth.snapshot.status === 'unauthenticated') {
    const title = action === 'register' ? 'Opening registration' : 'Opening sign-in'
    if (!auth.problem) return <FeedbackState title={title} tone="loading">Please wait.</FeedbackState>

    return (
      <FeedbackState
        title="Could not open SwiftDrop authentication"
        tone="error"
        action={<Button onClick={() => { void auth[action](returnTarget ?? undefined) }}>Try again</Button>}
      >
        {messages[auth.problem]}
      </FeedbackState>
    )
  }

  return (
    <div className="auth-page">
      <div className="auth-shell">
        <div className="auth-art" aria-hidden="true">
          <span className="auth-art-orbit" />
          <span className="auth-art-truck"><TruckIcon size={56} /></span>
          <span className="auth-art-arrow"><ArrowRightIcon size={22} /></span>
        </div>
        <div className="auth-card">
          <h1>Your SwiftDrop workspaces</h1>
          {returnTarget && (
            <p style={{ marginBottom: '18px' }}>
              <Link to={returnTarget} className="intended-destination-link" style={{ fontWeight: 600, color: 'var(--color-teal)' }}>
                Continue to requested page ({returnTarget}) →
              </Link>
            </p>
          )}
          {auth.snapshot.roles.length === 0 ? (
            <div
              role="status"
              className="notice notice-warning"
              style={{
                margin: '16px 0',
                padding: '14px',
                borderRadius: '6px',
                background: 'var(--color-bg-warning, #fff8e1)',
                border: '1px solid var(--color-border-warning, #ffe082)',
              }}
            >
              <p style={{ fontWeight: 600, marginBottom: '6px', color: '#b78103' }}>Role Assignment Pending</p>
              <p className="muted" style={{ fontSize: '14px', margin: 0 }}>
                Your account is authenticated, but no workspace roles (Customer, Merchant, Courier, Dispatcher, or Admin) have been assigned to your profile yet. Please contact your system administrator or sign out below.
              </p>
            </div>
          ) : (
            <div className="role-links" style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem', margin: '1.2rem 0' }}>
              {auth.snapshot.roles.includes('CUSTOMER') && <Link to="/customer" className="button button-tonal button-sm">Customer space <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('MERCHANT') && <Link to="/merchant" className="button button-tonal button-sm">Merchant portal <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('COURIER') && <Link to="/courier" className="button button-tonal button-sm">Courier workspace <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('DISPATCHER') && <Link to="/dispatcher" className="button button-tonal button-sm">Dispatcher console <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('ADMIN') && <Link to="/admin" className="button button-tonal button-sm">Admin console <span aria-hidden="true">↗</span></Link>}
            </div>
          )}
          <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--color-border)' }}>
            <Button variant="secondary" size="sm" onClick={() => { void auth.logout() }}>Sign out</Button>
          </div>
        </div>
      </div>
    </div>
  )
}

export function CallbackPage() {
  const { snapshot, problem } = useAuth()
  const [target] = useState(readReturnTarget)

  useEffect(() => {
    if (snapshot.status !== 'loading') saveReturnTarget()
  }, [snapshot.status])

  if (snapshot.status === 'loading') {
    return <FeedbackState title="Completing sign-in" tone="loading">Please wait.</FeedbackState>
  }

  if (problem) {
    return <Navigate to={problem === 'cancelled' ? '/login?error=cancelled' : '/login'} replace />
  }

  if (snapshot.status === 'authenticated') {
    if (target) {
      return <Navigate to={target} replace />
    }

    // Role-specific entrypoint prioritized by privilege
    if (snapshot.roles.includes('ADMIN')) return <Navigate to="/admin" replace />
    if (snapshot.roles.includes('DISPATCHER')) return <Navigate to="/dispatcher" replace />
    if (snapshot.roles.includes('COURIER')) return <Navigate to="/courier" replace />
    if (snapshot.roles.includes('MERCHANT')) return <Navigate to="/merchant" replace />
    if (snapshot.roles.includes('CUSTOMER')) return <Navigate to="/customer" replace />
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
        You are signed out of SwiftDrop on this device.
      </FeedbackState>
      <Link to="/login">Return to sign-in</Link>
    </div>
  )
}

/** UX boundary only. Every backend must independently authorize the actor. */
export function ProtectedRoute({ role, anyOf }: { role?: SwiftDropRole; anyOf?: readonly SwiftDropRole[] }) {
  const { snapshot } = useAuth()
  const location = useLocation()
  const trip = useRef<HTMLDivElement>(null)
  const [waitingForTrip, setWaitingForTrip] = useState(() =>
    snapshot.status === 'loading' && globalThis.matchMedia?.(sessionMotionQuery).matches === true,
  )

  useEffect(() => {
    if (!waitingForTrip) return

    const motion = window.matchMedia(sessionMotionQuery)
    const vanDrive = trip.current
    const stopWaiting = () => setWaitingForTrip(false)
    const onTripIteration = (event: AnimationEvent) => {
      if (event.target === vanDrive && event.animationName === 'session-loading-delivery') stopWaiting()
    }
    const onMotionChange = (event: MediaQueryListEvent) => {
      if (!event.matches) stopWaiting()
    }
    vanDrive?.addEventListener('animationiteration', onTripIteration)
    motion.addEventListener('change', onMotionChange)
    const fallback = window.setTimeout(stopWaiting, sessionTripFallbackMs)

    return () => {
      vanDrive?.removeEventListener('animationiteration', onTripIteration)
      motion.removeEventListener('change', onMotionChange)
      window.clearTimeout(fallback)
    }
  }, [waitingForTrip])

  if (snapshot.status === 'loading' || waitingForTrip) {
    return (
      <div className="session-loading">
        <div className="session-loading-indicator" role="status">
          <div className="session-loading-scene" aria-hidden="true">
            <img className="session-loading-street" src={sessionStreet} alt="" width={696} height={356} />
            <span className="session-loading-porch" />
            <span className="session-loading-parcel session-loading-pickup" />
            <span className="session-loading-parcel session-loading-dropoff" />
            <div className="session-loading-drive" ref={trip}>
              <div className="session-loading-van">
                <img src={sessionVan} alt="" width={192} height={96} />
              </div>
            </div>
            <img className="session-loading-courier" src={sessionCourier} alt="" width={88} height={132} />
          </div>
          <span>Checking session</span>
        </div>
      </div>
    )
  }
  if (snapshot.status !== 'authenticated') {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  }
  if ((role && !snapshot.roles.includes(role)) || (anyOf && !anyOf.some(item => snapshot.roles.includes(item)))) {
    return <FeedbackState title="Access unavailable" tone="error">
      This page is not available for your role. <Link to="/login">Go to your workspaces</Link>.
    </FeedbackState>
  }
  return <Outlet />
}
