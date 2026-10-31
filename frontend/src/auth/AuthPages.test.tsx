import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from './context'
import type { AuthSnapshot } from './contracts'
import {
  CallbackPage,
  LoginPage,
  LogoutPage,
  ProtectedRoute,
} from './AuthPages'
import { sanitizeRedirectUrl } from './redirect'

function createMockAuthContext(overrides?: Partial<AuthContextValue>): AuthContextValue {
  const defaultSnapshot: AuthSnapshot = {
    status: 'unauthenticated',
    subject: null,
    roles: [],
  }
  return {
    snapshot: defaultSnapshot,
    problem: null,
    getAccessToken: vi.fn().mockResolvedValue(null),
    login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    register: vi.fn().mockResolvedValue(undefined),
    googleLogin: vi.fn().mockResolvedValue(undefined),
    handleSessionExpiry: vi.fn().mockResolvedValue(undefined),
    profileFetch: vi.fn().mockResolvedValue(new Response()),
    ...overrides,
  }
}

describe('AuthPages - Browser Recovery & In-Memory Routing', () => {
  // =========================================================================
  // 1. Open-Redirect Sanitation
  // =========================================================================

  describe('sanitizeRedirectUrl', () => {
    it('accepts valid internal relative routes with paths and query parameters', () => {
      expect(sanitizeRedirectUrl('/customer')).toBe('/customer')
      expect(sanitizeRedirectUrl('/customer/history')).toBe('/customer/history')
      expect(sanitizeRedirectUrl('/merchant/credentials')).toBe('/merchant/credentials')
      expect(sanitizeRedirectUrl('/courier')).toBe('/courier')
      expect(sanitizeRedirectUrl('/dispatcher')).toBe('/dispatcher')
      expect(sanitizeRedirectUrl('/admin/merchants?status=PENDING')).toBe('/admin/merchants?status=PENDING')
    })

    it('rejects protocol-relative URLs (//attacker.com) and backslash bypasses (/\\)', () => {
      expect(sanitizeRedirectUrl('//attacker.com')).toBeNull()
      expect(sanitizeRedirectUrl('//swiftdrop.local/evil')).toBeNull()
      expect(sanitizeRedirectUrl('/\\attacker.com')).toBeNull()
      expect(sanitizeRedirectUrl('///evil.com')).toBeNull()
    })

    it('rejects external URLs, URI schemes, and invalid values', () => {
      expect(sanitizeRedirectUrl('https://evil.com/fake-login')).toBeNull()
      expect(sanitizeRedirectUrl('http://swiftdrop.local:18443')).toBeNull()
      expect(sanitizeRedirectUrl('javascript:alert(1)')).toBeNull()
      expect(sanitizeRedirectUrl('data:text/html,evil')).toBeNull()
      expect(sanitizeRedirectUrl('')).toBeNull()
      expect(sanitizeRedirectUrl(null)).toBeNull()
      expect(sanitizeRedirectUrl(undefined)).toBeNull()
    })

    it('rejects auth loops (/login, /auth/callback, /logout)', () => {
      expect(sanitizeRedirectUrl('/login')).toBeNull()
      expect(sanitizeRedirectUrl('/login?error=cancelled')).toBeNull()
      expect(sanitizeRedirectUrl('/auth/callback')).toBeNull()
      expect(sanitizeRedirectUrl('/auth/callback?code=abc')).toBeNull()
      expect(sanitizeRedirectUrl('/logout')).toBeNull()
    })
  })

  // =========================================================================
  // 2. ProtectedRoute & Unauthenticated Interception
  // =========================================================================

  describe('ProtectedRoute', () => {
    it('displays loading state while session verification is in progress', () => {
      const auth = createMockAuthContext({
        snapshot: { status: 'loading', subject: null, roles: [] },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/customer']}>
            <Routes>
              <Route element={<ProtectedRoute role="CUSTOMER" />}>
                <Route path="/customer" element={<div>Protected Customer Space</div>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Checking session')).toBeInTheDocument()
      expect(screen.queryByText('Protected Customer Space')).not.toBeInTheDocument()
    })

    it('intercepts unauthenticated user and redirects to /login with state.from preserved', () => {
      const auth = createMockAuthContext({
        snapshot: { status: 'unauthenticated', subject: null, roles: [] },
      })

      function LoginProbe() {
        const location = useLocation()
        return (
          <div>
            <h1>Login Screen</h1>
            <span data-testid="from-state">{JSON.stringify(location.state)}</span>
          </div>
        )
      }

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/customer/history?filter=active']}>
            <Routes>
              <Route element={<ProtectedRoute role="CUSTOMER" />}>
                <Route path="/customer/history" element={<div>Customer History</div>} />
              </Route>
              <Route path="/login" element={<LoginProbe />} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Login Screen')).toBeInTheDocument()
      expect(screen.queryByText('Customer History')).not.toBeInTheDocument()
      expect(screen.getByTestId('from-state')).toHaveTextContent('/customer/history?filter=active')
    })

    it('allows access to protected route when authenticated and role matches', () => {
      const auth = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-user-1', roles: ['ADMIN'] },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/admin/merchants']}>
            <Routes>
              <Route element={<ProtectedRoute role="ADMIN" />}>
                <Route path="/admin/merchants" element={<div>Admin Merchants Console</div>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Admin Merchants Console')).toBeInTheDocument()
    })

    it('blocks access with role error when authenticated but role is insufficient', () => {
      const auth = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-user-2', roles: ['CUSTOMER'] },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/admin']}>
            <Routes>
              <Route element={<ProtectedRoute role="ADMIN" />}>
                <Route path="/admin" element={<div>Admin Secret Area</div>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Access unavailable')).toBeInTheDocument()
      expect(screen.getByText('This entry is unavailable for your current role.')).toBeInTheDocument()
      expect(screen.queryByText('Admin Secret Area')).not.toBeInTheDocument()
    })
  })

  // =========================================================================
  // 3. LoginPage & Multi-Role Dashboard Access
  // =========================================================================

  describe('LoginPage', () => {
    it('displays error messages for all documented auth problem codes', () => {
      const problems = [
        { code: 'cancelled' as const, text: 'Sign-in was cancelled. You can try again.' },
        { code: 'invalid-callback' as const, text: 'Sign-in could not be verified. Please start again.' },
        { code: 'provider-unavailable' as const, text: 'The identity provider is unavailable. Please try again later.' },
        { code: 'session-expired' as const, text: 'Your session expired. Please sign in again.' },
      ]

      for (const { code, text } of problems) {
        const auth = createMockAuthContext({
          problem: code,
          snapshot: { status: 'unauthenticated', subject: null, roles: [] },
        })

        const { unmount } = render(
          <AuthContext.Provider value={auth}>
            <MemoryRouter initialEntries={['/login']}>
              <LoginPage />
            </MemoryRouter>
          </AuthContext.Provider>
        )

        expect(screen.getByRole('alert')).toHaveTextContent(text)
        unmount()
      }
    })

    it('displays link to intended destination when returning authenticated with from state', () => {
      const auth = createMockAuthContext({
        snapshot: {
          status: 'authenticated',
          subject: 'cust-user',
          roles: ['CUSTOMER'],
        },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '/customer/history' } }]}>
            <LoginPage />
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText(/Continue to requested page \(\/customer\/history\)/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /Continue to requested page/ })).toHaveAttribute(
        'href',
        '/customer/history'
      )
    })

    it('displays dashboard links for all 5 authorized roles when authenticated', () => {
      const auth = createMockAuthContext({
        snapshot: {
          status: 'authenticated',
          subject: 'all-roles-user',
          roles: ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'ADMIN'],
        },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/login']}>
            <LoginPage />
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByRole('link', { name: 'Customer space' })).toHaveAttribute('href', '/customer')
      expect(screen.getByRole('link', { name: 'Merchant portal' })).toHaveAttribute('href', '/merchant')
      expect(screen.getByRole('link', { name: 'Courier workspace' })).toHaveAttribute('href', '/courier')
      expect(screen.getByRole('link', { name: 'Dispatcher console' })).toHaveAttribute('href', '/dispatcher')
      expect(screen.getByRole('link', { name: 'Admin console' })).toHaveAttribute('href', '/admin')
      expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    })
  })

  // =========================================================================
  // 4. CallbackPage & Destination Recovery
  // =========================================================================

  describe('CallbackPage', () => {
    it('redirects to role default space upon successful authentication', () => {
      const authCustomer = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-cust', roles: ['CUSTOMER'] },
      })

      function CustomerProbe() {
        return <div>Customer Space Home</div>
      }

      render(
        <AuthContext.Provider value={authCustomer}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/customer" element={<CustomerProbe />} />
              <Route path="/login" element={<div>Login Page</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Customer Space Home')).toBeInTheDocument()
    })

    it('redirects to courier space when authenticated as courier', () => {
      const authCourier = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-courier', roles: ['COURIER'] },
      })

      render(
        <AuthContext.Provider value={authCourier}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/courier" element={<div>Courier Workspace Home</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Courier Workspace Home')).toBeInTheDocument()
    })

    it('navigates to /login?error=cancelled if callback was cancelled', () => {
      const auth = createMockAuthContext({
        problem: 'cancelled',
        snapshot: { status: 'unauthenticated', subject: null, roles: [] },
      })

      function LoginProbe() {
        const location = useLocation()
        return <div>Login Cancelled Probe: {location.search}</div>
      }

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/login" element={<LoginProbe />} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Login Cancelled Probe: ?error=cancelled')).toBeInTheDocument()
    })

    it('navigates to /login if callback experienced an invalid-callback problem', () => {
      const auth = createMockAuthContext({
        problem: 'invalid-callback',
        snapshot: { status: 'unauthenticated', subject: null, roles: [] },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/login" element={<div>Login Home Page</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Login Home Page')).toBeInTheDocument()
    })

    it('prioritizes ADMIN role over default CUSTOMER composite role', () => {
      const authAdmin = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-admin', roles: ['CUSTOMER', 'ADMIN'] },
      })

      render(
        <AuthContext.Provider value={authAdmin}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/admin" element={<div>Admin Console Home</div>} />
              <Route path="/customer" element={<div>Customer Space Home</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Admin Console Home')).toBeInTheDocument()
      expect(screen.queryByText('Customer Space Home')).not.toBeInTheDocument()
    })

    it('prioritizes DISPATCHER role over default CUSTOMER composite role', () => {
      const authDispatcher = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-disp', roles: ['CUSTOMER', 'DISPATCHER'] },
      })

      render(
        <AuthContext.Provider value={authDispatcher}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/dispatcher" element={<div>Dispatcher Console Home</div>} />
              <Route path="/customer" element={<div>Customer Space Home</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Dispatcher Console Home')).toBeInTheDocument()
      expect(screen.queryByText('Customer Space Home')).not.toBeInTheDocument()
    })

    it('recovers intended destination from window.name state and purges it', () => {
      window.name = JSON.stringify({ swiftdrop_auth_target: '/admin/pricing' })
      const auth = createMockAuthContext({
        snapshot: { status: 'authenticated', subject: 'sub-admin', roles: ['ADMIN'] },
      })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/auth/callback']}>
            <Routes>
              <Route path="/auth/callback" element={<CallbackPage />} />
              <Route path="/admin/pricing" element={<div>Admin Pricing Page</div>} />
              <Route path="/admin" element={<div>Admin Home</div>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Admin Pricing Page')).toBeInTheDocument()
      expect(window.name).toBe('')
    })
  })

  // =========================================================================
  // 5. LogoutPage
  // =========================================================================

  describe('LogoutPage', () => {
    it('executes logout and provides link to return to sign-in', async () => {
      const logoutMock = vi.fn().mockResolvedValue(undefined)
      const auth = createMockAuthContext({ logout: logoutMock })

      render(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/logout']}>
            <LogoutPage />
          </MemoryRouter>
        </AuthContext.Provider>
      )

      expect(screen.getByText('Signed out')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Return to sign-in' })).toHaveAttribute('href', '/login')
      await waitFor(() => expect(logoutMock).toHaveBeenCalledOnce())
    })
  })
})
