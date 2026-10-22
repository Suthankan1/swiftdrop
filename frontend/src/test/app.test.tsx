import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { anonymousAuth, TestProviders as AppProviders } from './authFixture'
import type { AuthContextValue } from '../auth/context'
import type { SwiftDropRole } from '../auth/contracts'
import { AppRoutes } from '../app/router/routes'
import { AppErrorBoundary } from '../components/ui/AppErrorBoundary'
import { customerFixture } from './fixtures'
import entryHtml from '../../index.html?raw'

function renderRoute(path = '/', auth?: AuthContextValue) {
  return render(<AppProviders auth={auth}><MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter></AppProviders>)
}

describe('shared application routes', () => {
  it('publishes SwiftDrop in the browser title and description', () => {
    const entry = new DOMParser().parseFromString(entryHtml, 'text/html')

    expect(entry.title).toBe('SwiftDrop — Every parcel has a purpose')
    expect(entry.querySelector('meta[name="description"]')?.getAttribute('content')).toContain('SwiftDrop')
    expect(entry.head.textContent).not.toMatch(/devarom/i)
  })

  it.each(['/', '/login', '/customer', '/merchant', '/courier', '/dispatcher', '/admin', '/auth/callback', '/missing-page'])('uses current branding at %s', (path) => {
    const { container } = renderRoute(path)

    expect(screen.getByRole('link', { name: 'SwiftDrop home' })).toHaveAttribute('href', '/')
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'SwiftDrop.' })).toHaveAttribute('href', '/')
    expect(container.textContent).not.toMatch(/devarom/i)
    expect(container.querySelector('[aria-label*="Devarom" i]')).not.toBeInTheDocument()
  })

  it('retains sign-in, Google and registration actions under SwiftDrop branding', async () => {
    const user = userEvent.setup()
    const auth = { ...anonymousAuth, login: vi.fn(), googleLogin: vi.fn(), register: vi.fn() }
    renderRoute('/login', auth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^sign in$/i }))
    await user.click(screen.getByRole('button', { name: 'Continue with Google' }))
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(auth.login).toHaveBeenCalledOnce()
    expect(auth.googleLogin).toHaveBeenCalledOnce()
    expect(auth.register).toHaveBeenCalledOnce()
  })

  it('uses SwiftDrop on the authenticated Customer page', async () => {
    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: customerFixture.keycloakSub, roles: ['CUSTOMER'] },
      getAccessToken: async () => 'test-only-access',
      profileFetch: async () => new Response(JSON.stringify(customerFixture)),
    }
    const { container } = renderRoute('/customer', auth)

    expect(await screen.findByRole('heading', { name: customerFixture.fullName })).toBeInTheDocument()
    expect(screen.getByText(/your SwiftDrop account starts here/i)).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/devarom/i)
  })

  it('renders the public shell and landing page with semantic landmarks', () => {
    renderRoute()

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /main navigation/i })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /skip to/i })).toHaveAttribute('href', '#main-content')
  })

  it('navigates to the customer entry and back home', async () => {
    const user = userEvent.setup()
    renderRoute()

    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /customer/i }))
    expect(screen.getByRole('heading', { name: /sign in to swiftdrop/i })).toBeInTheDocument()

    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /^home$/i }))
    expect(screen.queryByRole('heading', { name: /your profile, kept private/i })).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('renders the customer structure without fetching or displaying a private profile', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)
    renderRoute('/customer')

    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /sign in to swiftdrop/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
    expect(screen.queryByText(customerFixture.keycloakSub)).not.toBeInTheDocument()
    expect(screen.queryByText(/logged in successfully/i)).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('supports opening, dismissing and following the mobile navigation', async () => {
    const user = userEvent.setup()
    renderRoute()
    const toggle = screen.getByRole('button', { name: /open navigation/i })

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard('{Escape}')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveFocus()

    await user.click(toggle)
    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /customer/i }))
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('heading', { name: /sign in to swiftdrop/i })).toBeInTheDocument()
  })

  it('provides a not-found experience and a way home', async () => {
    const user = userEvent.setup()
    renderRoute('/not-a-real-swiftdrop-page')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/couldn.t be found/i)
    await user.click(screen.getByRole('link', { name: /back to home/i }))
    expect(screen.queryByRole('heading', { name: /couldn.t be found/i })).not.toBeInTheDocument()
  })

  it('offers a safe fallback after a rendering failure', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    function BrokenPage(): never { throw new Error('Synthetic test-only render failure') }

    render(<AppErrorBoundary><BrokenPage /></AppErrorBoundary>)

    expect(screen.getByRole('alert')).toHaveTextContent(/couldn.t display this page/i)
    expect(screen.getByRole('link', { name: /return home/i })).toHaveAttribute('href', '/')
    expect(screen.queryByText(/synthetic test-only render failure/i)).not.toBeInTheDocument()
  })

  it('shows Merchant navigation only to an authenticated Merchant', () => {
    const merchantAuth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: 'merchant-test-sub', roles: ['MERCHANT'] },
    }
    const { unmount } = renderRoute('/', merchantAuth)
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /merchant space/i }),
    ).toHaveAttribute('href', '/merchant')
    unmount()

    renderRoute('/', anonymousAuth)
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).queryByRole('link', { name: /merchant space/i }),
    ).not.toBeInTheDocument()
  })

  it.each<{ roles: readonly SwiftDropRole[] }>([
    { roles: [] }, { roles: ['CUSTOMER'] }, { roles: ['MERCHANT'] }, { roles: ['COURIER'] },
    { roles: ['DISPATCHER'] }, { roles: ['ADMIN'] }, { roles: ['DISPATCHER', 'ADMIN'] },
    { roles: ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'ADMIN'] },
  ])('scopes both staff navigation entries to authenticated roles $roles', ({ roles }) => {
    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: 'synthetic-navigation-subject', roles },
    }
    renderRoute('/login', auth)
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))
    for (const [role, label, path] of [
      ['DISPATCHER', 'Dispatcher space', '/dispatcher'], ['ADMIN', 'Admin space', '/admin'],
    ] as const) {
      if (roles.includes(role)) expect(nav.getByRole('link', { name: new RegExp(label) })).toHaveAttribute('href', path)
      else expect(nav.queryByRole('link', { name: new RegExp(label) })).not.toBeInTheDocument()
    }
  })

  it.each(['unauthenticated', 'loading'] as const)('hides both staff navigation entries while %s', (status) => {
    renderRoute('/', { ...anonymousAuth, snapshot: { status, subject: null, roles: [] } })
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))
    expect(nav.queryByRole('link', { name: /dispatcher space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('link', { name: /admin space/i })).not.toBeInTheDocument()
  })

  it('keeps both genuine staff roles navigable through the existing mobile menu', async () => {
    const user = userEvent.setup()
    renderRoute('/login', {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: 'synthetic-multi-role', roles: ['DISPATCHER', 'ADMIN'] },
    })
    const toggle = screen.getByRole('button', { name: 'Open navigation' })
    await user.click(toggle)
    await user.keyboard('{Escape}')
    expect(toggle).toHaveFocus()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    for (const [label, heading] of [['Dispatcher space', 'Dispatcher workspace'], ['Admin space', 'Administration workspace']] as const) {
      await user.click(toggle)
      await user.click(within(screen.getByRole('navigation', { name: 'Main navigation' })).getByRole('link', { name: new RegExp(label) }))
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
    }
  })
})
