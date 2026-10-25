import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { courierStaffFixture } from '../../../test/fixtures'

function authenticated(
  roles: readonly SwiftDropRole[] = ['COURIER'],
  subject = 'courier-test-subject',
  profileResponse = courierStaffFixture,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'synthetic-courier-token',
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profileResponse), { status: 200 })),
  }
}

function renderCourier(auth: AuthContextValue) {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={['/courier']}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Courier role landing', () => {
  it('redirects an anonymous direct visit to the shared sign-in page without a provider request', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)

    renderCourier(anonymousAuth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /ready for your next route/i })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('shows shared session loading feedback without a provider request', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)
    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'loading', subject: null, roles: [] },
    }

    renderCourier(auth)

    expect(screen.getByRole('status')).toHaveTextContent(/checking session/i)
    expect(request).not.toHaveBeenCalled()
  })

  it('renders authorized active Courier profile without fake assignments or parcel data', async () => {
    const auth = authenticated()
    renderCourier(auth)

    expect(screen.getByRole('heading', { name: /ready for your next route/i })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: courierStaffFixture.displayName })).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.staffId)).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.email)).toBeInTheDocument()
    expect(screen.getByText(`${courierStaffFixture.role} · ${courierStaffFixture.businessStatus}`)).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.assignedZone!)).toBeInTheDocument()

    // Confirms no fake assignment or parcel data is loaded
    expect(screen.queryByText(/delivered/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
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

    renderCourier(auth)

    expect(await screen.findByText(/staff profile not provisioned/i)).toBeInTheDocument()
    expect(screen.getByText(/no staff business profile has been provisioned yet/i)).toBeInTheDocument()
    expect(screen.queryByText(courierStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('does not present an inactive or suspended profile as an active staff identity', async () => {
    const suspended = { ...courierStaffFixture, businessStatus: 'SUSPENDED' as const }
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(suspended), { status: 200 })),
    }

    renderCourier(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile inactive/i)
    expect(screen.getByText(/your staff profile is currently suspended \(non-operational\)/i)).toBeInTheDocument()
    expect(screen.queryByText(courierStaffFixture.staffId)).not.toBeInTheDocument()
  })

  it('fails closed and denies access if backend returns a different Keycloak subject', async () => {
    const mismatchedSubject = { ...courierStaffFixture, keycloakSub: 'someone-else-sub' }
    const auth: AuthContextValue = {
      ...authenticated(['COURIER'], 'courier-test-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(mismatchedSubject), { status: 200 })),
    }

    renderCourier(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile access denied/i)
    expect(screen.queryByText(courierStaffFixture.displayName)).not.toBeInTheDocument()
  })

  it('rejects a profile with wrong business role even if user has access to page', async () => {
    const wrongRoleProfile = { ...courierStaffFixture, role: 'DISPATCHER' as const }
    const auth: AuthContextValue = {
      ...authenticated(['COURIER'], 'courier-test-subject'),
      profileFetch: vi.fn(async () => new Response(JSON.stringify(wrongRoleProfile), { status: 200 })),
    }

    renderCourier(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff role mismatch/i)
    expect(screen.getByText(/access is restricted to authorized courier staff/i)).toBeInTheDocument()
    expect(screen.queryByText(wrongRoleProfile.staffId)).not.toBeInTheDocument()
  })

  it('displays provider failure truthfully and offers working retry action', async () => {
    const user = userEvent.setup()
    let calls = 0
    const profileFetch = vi.fn(async () => {
      calls++
      if (calls === 1) {
        return new Response(null, { status: 503 })
      }
      return new Response(JSON.stringify(courierStaffFixture), { status: 200 })
    })
    const auth: AuthContextValue = {
      ...authenticated(),
      profileFetch,
    }

    renderCourier(auth)

    expect(await screen.findByRole('alert')).toHaveTextContent(/staff profile temporarily unavailable/i)
    const retryButtons = screen.getAllByRole('button', { name: /retry profile/i })
    expect(retryButtons.length).toBeGreaterThanOrEqual(1)

    await user.click(retryButtons[0]!)

    expect(await screen.findByRole('heading', { level: 2, name: courierStaffFixture.displayName })).toBeInTheDocument()
    expect(profileFetch).toHaveBeenCalledTimes(2)
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'DISPATCHER', 'ADMIN'])(
    'denies the %s role without rendering Courier content or making a provider request',
    (role) => {
      const auth = authenticated([role])

      renderCourier(auth)

      expect(screen.getByRole('alert')).toHaveTextContent(/access unavailable/i)
      expect(screen.queryByRole('heading', { name: /ready for your next route/i })).not.toBeInTheDocument()
      expect(auth.profileFetch).not.toHaveBeenCalled()
    },
  )

  it('shows Courier navigation only to an authenticated Courier', () => {
    const { unmount } = renderCourier(authenticated())
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', {
        name: /courier space/i,
      }),
    ).toHaveAttribute('href', '/courier')
    unmount()

    renderCourier(authenticated(['CUSTOMER']))
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).queryByRole('link', {
        name: /courier space/i,
      }),
    ).not.toBeInTheDocument()
  })

  it('uses the shared logout action', async () => {
    const user = userEvent.setup()
    const logout = vi.fn(async () => {})

    renderCourier({ ...authenticated(), logout })
    await user.click(screen.getByRole('button', { name: /sign out/i }))

    expect(logout).toHaveBeenCalledOnce()
  })

  it('removes Courier content when the authenticated session is lost', () => {
    const { rerender } = renderCourier(authenticated())
    expect(screen.getByRole('heading', { name: /ready for your next route/i })).toBeInTheDocument()

    rerender(
      <TestProviders auth={anonymousAuth}>
        <MemoryRouter initialEntries={['/courier']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    expect(screen.queryByRole('heading', { name: /ready for your next route/i })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
  })
})
