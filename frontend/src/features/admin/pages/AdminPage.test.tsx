import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { adminStaffFixture } from '../../../test/fixtures'

function authenticated(
  roles: readonly SwiftDropRole[] = ['ADMIN'],
  subject = 'synthetic-admin-subject',
  profileResponse = adminStaffFixture,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-admin-token'),
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profileResponse), { status: 200 })),
  }
}

function entry(auth: AuthContextValue, path = '/admin') {
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
    expect(screen.queryByText('Administration workspace')).not.toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('renders a distinct Admin entry with authorized staff profile and accountability messaging', async () => {
    const auth = authenticated()
    render(entry(auth))

    expect(screen.getByRole('heading', { name: 'Administration workspace' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Access with accountability' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: adminStaffFixture.displayName })).toBeInTheDocument()
    expect(screen.getByText(adminStaffFixture.staffId)).toBeInTheDocument()
    expect(screen.getByText(adminStaffFixture.email)).toBeInTheDocument()
    expect(screen.getByText(`${adminStaffFixture.role} · ${adminStaffFixture.businessStatus}`)).toBeInTheDocument()
    expect(screen.getAllByText(adminStaffFixture.department)).toHaveLength(2)

    expect(screen.queryByRole('heading', { name: 'Dispatcher workspace' })).not.toBeInTheDocument()
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
    expect(screen.queryByText(adminStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('does not present an inactive or suspended profile as an active staff identity', async () => {
    const suspended = { ...adminStaffFixture, businessStatus: 'SUSPENDED' as const }
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(suspended), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile inactive/i)
    expect(screen.getByText(/your staff profile is currently suspended \(non-operational\)/i)).toBeInTheDocument()
    expect(screen.queryByText(adminStaffFixture.staffId)).not.toBeInTheDocument()
  })

  it('fails closed and denies access if backend returns a different Keycloak subject', async () => {
    const mismatched = { ...adminStaffFixture, keycloakSub: 'other-sub' }
    const auth: AuthContextValue = {
      ...authenticated(['ADMIN'], 'synthetic-admin-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(mismatched), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile access denied/i)
    expect(screen.queryByText(adminStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('rejects a profile with wrong business role even if user has page access', async () => {
    const wrongRole = { ...adminStaffFixture, role: 'COURIER' as const }
    const auth: AuthContextValue = {
      ...authenticated(['ADMIN'], 'synthetic-admin-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(wrongRole), { status: 200 })),
    }

    render(entry(auth))

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff role mismatch/i)
    expect(screen.getByText(/access is restricted to authorized admin staff/i)).toBeInTheDocument()
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
      return new Response(JSON.stringify(adminStaffFixture), { status: 200 })
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

    expect(await screen.findByRole('heading', { level: 2, name: adminStaffFixture.displayName })).toBeInTheDocument()
    expect(profileFetch).toHaveBeenCalledTimes(2)
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'])(
    'denies direct URL probing by %s without rendering staff data or calling providers',
    (role) => {
      const auth = authenticated([role])
      render(entry(auth))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
      expect(auth.profileFetch).not.toHaveBeenCalled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('denies an authenticated identity without an approved Admin role', () => {
    render(entry(authenticated([])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('links merchant review, ADMIN management and the Pricing workspace', () => {
    render(entry(authenticated()))
    const main = within(screen.getByRole('main'))
    expect(main.getByText(/staff access is provisioned by an authorized administrator/i)).toBeInTheDocument()
    expect(main.getByRole('link', { name: 'Review merchant applications' })).toHaveAttribute('href', '/admin/merchants')
    expect(main.getByRole('link', { name: 'Browse account users' })).toHaveAttribute('href', '/admin/users')
    expect(main.getByRole('link', { name: 'Manage staff profiles' })).toHaveAttribute('href', '/admin/staff')
    expect(main.getByRole('link', { name: 'Manage zones and pricing' })).toHaveAttribute('href', '/admin/pricing')
    expect(main.getByRole('link', { name: /view daily outcomes and admin reports/i })).toHaveAttribute('href', '/admin/reports')
    expect(main.queryByText(/operational reporting is a future feature/i)).not.toBeInTheDocument()
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
    expect(screen.getByRole('alert')).toHaveTextContent('Your session expired')
    expect(screen.queryByRole('link', { name: /admin space/i })).not.toBeInTheDocument()
  })

  it('removes Admin content immediately when the role is revoked for the same subject', () => {
    const view = render(entry(authenticated()))
    view.rerender(entry(authenticated(['DISPATCHER'])))
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
  })

  it('does not trust browser role flags or URL parameters', () => {
    localStorage.setItem('role', 'ADMIN')
    sessionStorage.setItem('authenticated', 'true')
    try {
      render(entry(authenticated(['CUSTOMER']), '/admin?role=ADMIN&staffId=other'))
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('heading', { name: 'Administration workspace' })).not.toBeInTheDocument()
    } finally {
      localStorage.removeItem('role')
      sessionStorage.removeItem('authenticated')
    }
  })

  it('is discoverable through the existing header after Admin sign-in', async () => {
    const user = userEvent.setup()
    render(entry(authenticated(), '/login'))
    await user.click(
      within(screen.getByRole('navigation', { name: 'Main navigation' })).getByRole('link', {
        name: /admin space/i,
      }),
    )
    expect(screen.getByRole('heading', { name: 'Administration workspace' })).toBeInTheDocument()
  })
})
