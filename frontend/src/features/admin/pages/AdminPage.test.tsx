import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'

function authenticated(roles: readonly SwiftDropRole[] = ['ADMIN']): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject: 'synthetic-admin-subject', roles },
    getAccessToken: vi.fn(async () => null),
    profileFetch: vi.fn<typeof fetch>(),
  }
}

function entry(auth: AuthContextValue, path = '/admin') {
  return <TestProviders auth={auth}><MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter></TestProviders>
}

beforeEach(() => { vi.stubGlobal('fetch', vi.fn<typeof fetch>()) })

describe('Admin role landing', () => {
  it('redirects an anonymous direct URL to shared login without requesting staff data', () => {
    render(entry(anonymousAuth))
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('waits for the shared session before displaying Admin content', () => {
    render(entry({ ...anonymousAuth, snapshot: { status: 'loading', subject: null, roles: [] } }))
    expect(screen.getByRole('status')).toHaveTextContent('Checking session')
    expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('renders a distinct Admin entry with an unavailable profile and no invented staff identity', () => {
    const auth = authenticated()
    render(entry(auth))
    expect(screen.getByRole('heading', { name: 'Administration workspace' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Access with accountability' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Staff profile unavailable')
    expect(screen.queryByText(auth.snapshot.subject!)).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
    expect(auth.getAccessToken).not.toHaveBeenCalled()
    expect(auth.profileFetch).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'])(
    'denies direct URL probing by %s without rendering staff data or calling providers', (role) => {
      const auth = authenticated([role])
      render(entry(auth))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
      expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
      expect(auth.getAccessToken).not.toHaveBeenCalled()
      expect(auth.profileFetch).not.toHaveBeenCalled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('denies an authenticated identity without an approved Admin role', () => {
    render(entry(authenticated([])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('offers no self-provisioning or working merchant/pricing controls', () => {
    render(entry(authenticated()))
    const main = within(screen.getByRole('main'))
    expect(main.getByText(/staff access is provisioned by an authorized administrator/i)).toBeInTheDocument()
    expect(main.getByText(/merchant approval management.*future features/i)).toBeInTheDocument()
    expect(main.queryByRole('combobox')).not.toBeInTheDocument()
    expect(main.queryByRole('textbox')).not.toBeInTheDocument()
    expect(main.getAllByRole('button')).toHaveLength(1)
    expect(main.getByRole('button', { name: 'Sign out' })).toHaveAttribute('type', 'button')
    expect(screen.getByRole('main').textContent).not.toMatch(/\b\d+\b/)
  })

  it('uses the shared logout action', async () => {
    const user = userEvent.setup()
    const logout = vi.fn(async () => {})
    render(entry({ ...authenticated(), logout }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(logout).toHaveBeenCalledOnce()
  })

  it('removes protected Admin content and navigation when the session expires', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry({ ...anonymousAuth, problem: 'session-expired' }))
    expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
    expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Your session expired')
    expect(screen.queryByRole('link', { name: /admin space/i })).not.toBeInTheDocument()
  })

  it('removes Admin content immediately when the role is revoked for the same subject', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry(authenticated(['DISPATCHER'])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not trust browser role flags or URL parameters', () => {
    localStorage.setItem('role', 'ADMIN')
    sessionStorage.setItem('authenticated', 'true')
    try {
      render(entry(authenticated(['CUSTOMER']), '/admin?role=ADMIN&staffId=other'))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
      expect(fetch).not.toHaveBeenCalled()
    } finally {
      localStorage.removeItem('role')
      sessionStorage.removeItem('authenticated')
    }
  })

  it('is discoverable through the existing header after Admin sign-in', async () => {
    const user = userEvent.setup()
    render(entry(authenticated(), '/login'))
    await user.click(within(screen.getByRole('navigation', { name: 'Main navigation' })).getByRole('link', { name: /admin space/i }))
    expect(screen.getByRole('heading', { name: 'Administration workspace' })).toBeInTheDocument()
  })
})
