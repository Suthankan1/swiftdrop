import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../app/providers/AppProviders'
import { AppRoutes } from '../app/router/routes'
import { AppErrorBoundary } from '../components/ui/AppErrorBoundary'
import { customerFixture } from './fixtures'

function renderRoute(path = '/') {
  return render(<AppProviders><MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter></AppProviders>)
}

describe('shared application routes', () => {
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
    expect(screen.getByRole('heading', { name: /your profile, kept private/i })).toBeInTheDocument()

    await user.click(within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /^home$/i }))
    expect(screen.queryByRole('heading', { name: /your profile, kept private/i })).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('renders the customer structure without fetching or displaying a private profile', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)
    renderRoute('/customer')

    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /your profile, kept private/i })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/sign-in is not available yet/i)
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
    expect(screen.getByRole('heading', { name: /your profile, kept private/i })).toBeInTheDocument()
  })

  it('provides a not-found experience and a way home', async () => {
    const user = userEvent.setup()
    renderRoute('/not-a-real-devarom-page')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/couldn.t be found/i)
    await user.click(screen.getByRole('link', { name: /back to home/i }))
    expect(screen.queryByRole('heading', { name: /couldn.t be found/i })).not.toBeInTheDocument()
  })

  it.each(['/login', '/auth/callback', '/logout'])('reserves %s without simulating an identity session', (path) => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)
    renderRoute(path)

    expect(screen.getByRole('status')).toHaveTextContent(/authentication is not implemented yet/i)
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('offers a safe fallback after a rendering failure', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    function BrokenPage(): never { throw new Error('Synthetic test-only render failure') }

    render(<AppErrorBoundary><BrokenPage /></AppErrorBoundary>)

    expect(screen.getByRole('alert')).toHaveTextContent(/couldn.t display this page/i)
    expect(screen.getByRole('link', { name: /return home/i })).toHaveAttribute('href', '/')
    expect(screen.queryByText(/synthetic test-only render failure/i)).not.toBeInTheDocument()
  })
})
