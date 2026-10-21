import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'

function authenticated(roles: readonly SwiftDropRole[] = ['COURIER'], subject = 'courier-test-subject'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'synthetic-memory-only-test-value',
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

  it('renders an honest Courier entry without fake staff or parcel data', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)

    renderCourier(authenticated())

    expect(screen.getByRole('heading', { name: /ready for your next route/i })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/staff details are not yet available/i)
    expect(screen.getByText(/no account, assignment, parcel, or proof data is loaded/i)).toBeInTheDocument()
    expect(screen.queryByText(/delivered/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/courier-test-subject/i)).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'MERCHANT', 'DISPATCHER', 'ADMIN'])(
    'denies the %s role without rendering Courier content or making a provider request',
    (role) => {
      const request = vi.fn<typeof fetch>()
      vi.stubGlobal('fetch', request)

      renderCourier(authenticated([role]))

      expect(screen.getByRole('alert')).toHaveTextContent(/access unavailable/i)
      expect(screen.queryByRole('heading', { name: /ready for your next route/i })).not.toBeInTheDocument()
      expect(screen.queryByText(/staff details are not yet available/i)).not.toBeInTheDocument()
      expect(request).not.toHaveBeenCalled()
    },
  )

  it('shows Courier navigation only to an authenticated Courier', () => {
    const { unmount } = renderCourier(authenticated())
    expect(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /courier space/i })).toHaveAttribute('href', '/courier')
    unmount()

    renderCourier(authenticated(['CUSTOMER']))
    expect(within(screen.getByRole('navigation', { name: /main navigation/i })).queryByRole('link', { name: /courier space/i })).not.toBeInTheDocument()
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
