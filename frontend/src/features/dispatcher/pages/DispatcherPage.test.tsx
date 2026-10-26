import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { dispatcherStaffFixture } from '../../../test/fixtures'

function authenticated(
  roles: readonly SwiftDropRole[] = ['DISPATCHER'],
  subject = 'synthetic-dispatcher-subject',
  profileResponse = dispatcherStaffFixture,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-dispatcher-token'),
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profileResponse), { status: 200 })),
  }
}

function entry(auth: AuthContextValue, path = '/dispatcher') {
  return (
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>())
})

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
    expect(screen.queryByText('Dispatcher workspace')).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('renders authorized active Dispatcher profile without fake queue or assignment commands', async () => {
    const auth = authenticated()
    render(entry(auth))

    expect(screen.getByRole('heading', { name: 'Dispatcher workspace' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: dispatcherStaffFixture.displayName })).toBeInTheDocument()
    expect(screen.getByText(dispatcherStaffFixture.staffId)).toBeInTheDocument()
    expect(screen.getByText(dispatcherStaffFixture.email)).toBeInTheDocument()
    expect(screen.getByText(`${dispatcherStaffFixture.role} · ${dispatcherStaffFixture.businessStatus}`)).toBeInTheDocument()
    expect(screen.getAllByText(dispatcherStaffFixture.department)).toHaveLength(2)

    expect(auth.profileFetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/staff/me'),
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('handles missing staff profile (HTTP 404) truthfully with unprovisioned notice', async () => {
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch: vi.fn(async () => new Response(null, { status: 404 })),
    }

    render(entry(auth))

    expect(await screen.findByText(/staff profile not provisioned/i)).toBeInTheDocument()
    expect(screen.getByText(/no staff business profile has been provisioned yet/i)).toBeInTheDocument()
    expect(screen.queryByText(dispatcherStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('does not present an inactive or suspended profile as an active staff identity', async () => {
    const suspended = { ...dispatcherStaffFixture, businessStatus: 'SUSPENDED' as const }
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(suspended), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile inactive/i)
    expect(screen.getByText(/your staff profile is currently suspended \(non-operational\)/i)).toBeInTheDocument()
    expect(screen.queryByText(dispatcherStaffFixture.staffId)).not.toBeInTheDocument()
  })

  it('fails closed and denies access if backend returns a different Keycloak subject', async () => {
    const mismatched = { ...dispatcherStaffFixture, keycloakSub: 'other-sub' }
    const auth: AuthContextValue = {
      ...authenticated(['DISPATCHER'], 'synthetic-dispatcher-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(mismatched), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile access denied/i)
    expect(screen.queryByText(dispatcherStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('rejects a profile with wrong business role even if user has page access', async () => {
    const wrongRole = { ...dispatcherStaffFixture, role: 'COURIER' as const }
    const auth: AuthContextValue = {
      ...authenticated(['DISPATCHER'], 'synthetic-dispatcher-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(wrongRole), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff role mismatch/i)
    expect(screen.getByText(/access is restricted to authorized dispatcher staff/i)).toBeInTheDocument()
    expect(screen.queryByText(wrongRole.staffId)).not.toBeInTheDocument()
  })

  it('displays provider failure truthfully and offers working retry action', async () => {
    const user = userEvent.setup()
    let calls = 0
    const profileFetch = vi.fn(async () => {
      calls++
      if (calls === 1) {
        return new Response(null, { status: 503 })
      }
      return new Response(JSON.stringify(dispatcherStaffFixture), { status: 200 })
    })
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch,
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile temporarily unavailable/i)
    const retryButtons = screen.getAllByRole('button', { name: /retry profile/i })
    expect(retryButtons.length).toBeGreaterThanOrEqual(1)

    await user.click(retryButtons[0]!)

    expect(await screen.findByRole('heading', { level: 2, name: dispatcherStaffFixture.displayName })).toBeInTheDocument()
    expect(profileFetch).toHaveBeenCalledTimes(2)
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'COURIER', 'ADMIN'])(
    'denies a direct visit by %s without exposing staff data or calling providers',
    (role) => {
      const auth = authenticated([role])
      render(entry(auth))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
      expect(auth.profileFetch).not.toHaveBeenCalled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('denies an authenticated identity without the Dispatcher role', () => {
    render(entry(authenticated([])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('offers no operational commands, assignment queues, or fabricated metrics', () => {
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
    expect(screen.getByRole('alert')).toHaveTextContent('Your session expired')
    expect(screen.queryByRole('link', { name: /dispatcher space/i })).not.toBeInTheDocument()
  })

  it('rechecks permission when the role is revoked without changing subject', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry(authenticated(['ADMIN'])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
  })

  it('ignores a role supplied in the URL', () => {
    render(entry(authenticated(['CUSTOMER']), '/dispatcher?role=DISPATCHER&staffId=other'))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
  })

  it('is reachable through shared navigation after a Dispatcher signs in', async () => {
    const user = userEvent.setup()
    render(entry(authenticated(), '/login'))
    await user.click(
      within(screen.getByRole('navigation', { name: 'Main navigation' })).getByRole('link', {
        name: /dispatcher space/i,
      }),
    )
    expect(screen.getByRole('heading', { name: 'Dispatcher workspace' })).toBeInTheDocument()
  })
})
