import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'

function authenticated(roles: readonly SwiftDropRole[] = ['DISPATCHER']): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject: 'synthetic-dispatcher-subject', roles },
    getAccessToken: vi.fn(async () => null),
    profileFetch: vi.fn<typeof fetch>(),
  }
}

function entry(auth: AuthContextValue, path = '/dispatcher') {
  return <TestProviders auth={auth}><MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter></TestProviders>
}

beforeEach(() => { vi.stubGlobal('fetch', vi.fn<typeof fetch>()) })

describe('Dispatcher role landing', () => {
  it('redirects an anonymous direct URL to shared login without requesting a profile', () => {
    render(entry(anonymousAuth))
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('waits for the shared session before displaying the workspace', () => {
    render(entry({ ...anonymousAuth, snapshot: { status: 'loading', subject: null, roles: [] } }))
    expect(screen.getByRole('status')).toHaveTextContent('Checking session')
    expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows the Dispatcher entry and honest provider-unavailable feedback with no identity read', () => {
    const auth = authenticated()
    render(entry(auth))
    expect(screen.getByRole('heading', { name: 'Dispatcher workspace' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Staff profile unavailable')
    expect(screen.getByText(/no personal staff details are loaded or displayed/i)).toBeInTheDocument()
    expect(screen.queryByText(auth.snapshot.subject!)).not.toBeInTheDocument()
    expect(auth.getAccessToken).not.toHaveBeenCalled()
    expect(auth.profileFetch).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'COURIER', 'ADMIN'])(
    'denies a direct visit by %s without exposing staff data or calling providers', (role) => {
      const auth = authenticated([role])
      render(entry(auth))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
      expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
      expect(auth.getAccessToken).not.toHaveBeenCalled()
      expect(auth.profileFetch).not.toHaveBeenCalled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('denies an authenticated identity without the Dispatcher role', () => {
    render(entry(authenticated([])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('offers no operational commands, retry for a missing provider, or fabricated metrics', () => {
    render(entry(authenticated()))
    const main = within(screen.getByRole('main'))
    expect(main.getByText(/live unassigned parcel queues.*future releases/i)).toBeInTheDocument()
    expect(main.getAllByRole('button')).toHaveLength(1)
    expect(main.getByRole('button', { name: 'Sign out' })).toHaveAttribute('type', 'button')
    expect(main.queryByRole('table')).not.toBeInTheDocument()
    expect(main.queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(screen.getByRole('main').textContent).not.toMatch(/\b\d+\b/)
  })

  it('delegates logout to the shared auth provider', async () => {
    const user = userEvent.setup()
    const logout = vi.fn(async () => {})
    render(entry({ ...authenticated(), logout }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(logout).toHaveBeenCalledOnce()
  })

  it('removes protected content and navigation on session expiration', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry({ ...anonymousAuth, problem: 'session-expired' }))
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
    expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Your session expired')
    expect(screen.queryByRole('link', { name: /dispatcher space/i })).not.toBeInTheDocument()
  })

  it('rechecks permission when the role is revoked without changing subject', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry(authenticated(['ADMIN'])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('ignores a role supplied in the URL', () => {
    render(entry(authenticated(['CUSTOMER']), '/dispatcher?role=DISPATCHER&staffId=other'))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByText('Staff profile unavailable')).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('is reachable through shared navigation after a Dispatcher signs in', async () => {
    const user = userEvent.setup()
    render(entry(authenticated(), '/login'))
    await user.click(within(screen.getByRole('navigation', { name: 'Main navigation' })).getByRole('link', { name: /dispatcher space/i }))
    expect(screen.getByRole('heading', { name: 'Dispatcher workspace' })).toBeInTheDocument()
  })
})
