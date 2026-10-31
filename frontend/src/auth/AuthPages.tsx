import { useEffect, useState } from 'react'
import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from './context'
import { FeedbackState } from '../components/ui/FeedbackState'
import { Button } from '../components/ui/Button'
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
    <div className="page-container" style={{ paddingBlock: '60px 80px', maxWidth: '580px' }}>
      <div className="card" style={{ padding: '36px 38px', boxShadow: 'var(--sd-shadow-sm)' }}>
        <p className="eyebrow" style={{ marginBottom: '12px' }}>Authentication</p>
        <h1 style={{ fontSize: '32px', letterSpacing: '-1px', marginBottom: '14px' }}>Sign in to SwiftDrop</h1>
        {auth.problem && <p role="alert" className="form-error-banner" style={{ marginBottom: '20px' }}>{messages[auth.problem]}</p>}
        {auth.snapshot.status === 'authenticated' ? (
          <>
            <p className="muted" style={{ marginBottom: '20px' }}>You are currently authenticated in an active session.</p>
            {returnTarget && (
              <p style={{ marginBottom: '18px' }}>
                <Link to={returnTarget} className="intended-destination-link" style={{ fontWeight: 600, color: 'var(--color-teal)' }}>
                  Continue to requested page ({returnTarget}) →
                </Link>
              </p>
            )}
            <div className="role-links" style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem', margin: '1.2rem 0' }}>
              {auth.snapshot.roles.includes('CUSTOMER') && <Link to="/customer" className="button button-tonal button-sm">Customer space <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('MERCHANT') && <Link to="/merchant" className="button button-tonal button-sm">Merchant portal <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('COURIER') && <Link to="/courier" className="button button-tonal button-sm">Courier workspace <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('DISPATCHER') && <Link to="/dispatcher" className="button button-tonal button-sm">Dispatcher console <span aria-hidden="true">↗</span></Link>}
              {auth.snapshot.roles.includes('ADMIN') && <Link to="/admin" className="button button-tonal button-sm">Admin console <span aria-hidden="true">↗</span></Link>}
            </div>
            <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--color-border)' }}>
              <Button variant="secondary" size="sm" onClick={() => { void auth.logout() }}>Sign out</Button>
            </div>
          </>
        ) : (
          <>
            <p className="muted" style={{ marginBottom: '24px', fontSize: '14px', lineHeight: 1.6 }}>
              Continue securely with Keycloak identity provider. Tokens are preserved in-memory and synchronized across tabs.
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <Button
                variant="primary"
                size="md"
                fullWidth
                onClick={() => { void auth.login(returnTarget ?? undefined) }}
              >
                Sign in
              </Button>
              <Button
                variant="secondary"
                size="md"
                fullWidth
                onClick={() => { void auth.googleLogin(returnTarget ?? undefined) }}
                leftIcon={
                  <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" style={{ width: '18px', height: '18px' }}>
                    <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
                    <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
                    <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" />
                    <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" />
                  </svg>
                }
              >
                Continue with Google
              </Button>
              <div style={{ textAlign: 'center', marginTop: '4px' }}>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => { void auth.register(returnTarget ?? undefined) }}
                >
                  Create account
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export function CallbackPage() {
  const { snapshot, problem } = useAuth()
  const [target] = useState<string | null>(() => {
    try {
      if (typeof window !== 'undefined' && window.name) {
        const parsed = JSON.parse(window.name) as { swiftdrop_auth_target?: string }
        if (parsed.swiftdrop_auth_target) {
          return sanitizeRedirectUrl(parsed.swiftdrop_auth_target)
        }
      }
    } catch {
      // Ignore JSON parse errors or window access
    }
    return null
  })

  useEffect(() => {
    if (snapshot.status === 'authenticated' && typeof window !== 'undefined' && window.name) {
      try {
        const parsed = JSON.parse(window.name) as { swiftdrop_auth_target?: string }
        if (parsed.swiftdrop_auth_target) {
          window.name = ''
        }
      } catch {
        // Ignore
      }
    }
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
