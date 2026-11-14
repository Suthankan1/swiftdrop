import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { AuthContextValue } from '../../auth/context'
import type { AuthSnapshot, SwiftDropRole } from '../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../test/authFixture'
import { AppLayout } from './AppLayout'
import {
  determineActiveWorkspace,
  getAuthorizedWorkspaces,
  ROLE_PRIORITY,
} from './navigation'

function createAuth(roles: readonly SwiftDropRole[], status: 'authenticated' | 'loading' | 'unauthenticated' = 'authenticated', logout = vi.fn(async () => {})): AuthContextValue {
  let snapshot: AuthSnapshot
  if (status === 'loading') {
    snapshot = { status: 'loading', subject: null, roles: [] }
  } else if (status === 'unauthenticated') {
    snapshot = { status: 'unauthenticated', subject: null, roles: [] }
  } else {
    snapshot = { status: 'authenticated', subject: 'test-user-sub', roles }
  }
  return {
    ...anonymousAuth,
    snapshot,
    logout,
  }
}

function renderShell(auth: AuthContextValue, initialPath = '/') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route index element={<div>Home Page</div>} />
            <Route path="tracking" element={<div>Tracking Page</div>} />
            <Route path="customer" element={<div>Customer Workspace Page</div>} />
            <Route path="merchant" element={<div>Merchant Workspace Page</div>} />
            <Route path="courier" element={<div>Courier Workspace Page</div>} />
            <Route path="dispatcher" element={<div>Dispatcher Workspace Page</div>} />
            <Route path="admin" element={<div>Admin Workspace Page</div>} />
            <Route path="login" element={<div>Login Page</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </TestProviders>,
  )
}

describe('Phase 1 — Role-Aware Global Navigation & Workspace Switcher', () => {
  it('1. Enforces strict central role priority order', () => {
    expect(ROLE_PRIORITY).toEqual([
      'ADMIN',
      'DISPATCHER',
      'COURIER',
      'MERCHANT',
      'CUSTOMER',
    ])
  })

  it('2. Guest users see public navigation (Home, Track parcel, Sign in) and no workspace links', () => {
    renderShell(createAuth([], 'unauthenticated'))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/')
    expect(nav.getByRole('link', { name: 'Track parcel' })).toHaveAttribute('href', '/tracking')
    expect(nav.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')

    // No workspace links or switcher visible to guests
    expect(nav.queryByRole('link', { name: /space|workspace|hub/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /sign out/i })).not.toBeInTheDocument()
  })

  it('3. Loading / initializing auth state does not temporarily leak role links', () => {
    renderShell(createAuth(['ADMIN', 'CUSTOMER'], 'loading'))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(nav.getByRole('link', { name: 'Track parcel' })).toBeInTheDocument()
    expect(nav.getByRole('link', { name: 'Sign in' })).toBeInTheDocument()

    expect(nav.queryByRole('link', { name: /admin space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
  })

  it('4. CUSTOMER only: displays Customer space and no Switch Workspace menu', () => {
    renderShell(createAuth(['CUSTOMER']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /customer space/i })).toHaveAttribute('href', '/customer')
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
    expect(nav.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    expect(screen.getByText('CUSTOMER')).toBeInTheDocument()
  })

  it('5. MERCHANT only: displays Merchant space as primary workspace', () => {
    renderShell(createAuth(['MERCHANT']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /merchant space/i })).toHaveAttribute('href', '/merchant')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
  })

  it('6. COURIER only: displays Courier space as primary workspace', () => {
    renderShell(createAuth(['COURIER']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /courier space/i })).toHaveAttribute('href', '/courier')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
  })

  it('7. DISPATCHER only: displays Dispatcher space as primary workspace', () => {
    renderShell(createAuth(['DISPATCHER']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /dispatcher space/i })).toHaveAttribute('href', '/dispatcher')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()
    expect(nav.queryByRole('button', { name: /switch workspace/i })).not.toBeInTheDocument()
  })

  it('8. ADMIN only: displays Admin space as primary, with Dispatcher in Switch Workspace', async () => {
    const user = userEvent.setup()
    renderShell(createAuth(['ADMIN']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /admin space/i })).toHaveAttribute('href', '/admin')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()

    // Switcher gives access to Dispatcher space
    const switcher = nav.getByRole('button', { name: /switch workspace/i })
    expect(switcher).toHaveAttribute('aria-expanded', 'false')
    await user.click(switcher)
    expect(switcher).toHaveAttribute('aria-expanded', 'true')

    const menu = screen.getByRole('menu', { name: 'Authorized workspaces' })
    expect(within(menu).getByRole('menuitem', { name: /dispatcher space/i })).toHaveAttribute('href', '/dispatcher')
  })

  it('9. Multi-role CUSTOMER + MERCHANT: prioritizes Merchant, avoids competing top-level Customer link, and offers Switcher', async () => {
    const user = userEvent.setup()
    renderShell(createAuth(['CUSTOMER', 'MERCHANT']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    // Merchant space is primary
    expect(nav.getByRole('link', { name: /merchant space/i })).toHaveAttribute('href', '/merchant')
    // Crucial bug fix: Customer space is NOT a parallel competing top-level link
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()

    // Switcher provides Customer space
    const switcher = nav.getByRole('button', { name: /switch workspace/i })
    await user.click(switcher)
    const menu = screen.getByRole('menu', { name: 'Authorized workspaces' })
    expect(within(menu).getByRole('menuitem', { name: /customer space/i })).toHaveAttribute('href', '/customer')
  })

  it('10. Multi-role CUSTOMER + COURIER: prioritizes Courier and offers Switcher to Customer', async () => {
    const user = userEvent.setup()
    renderShell(createAuth(['CUSTOMER', 'COURIER']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    expect(nav.getByRole('link', { name: /courier space/i })).toHaveAttribute('href', '/courier')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()

    const switcher = nav.getByRole('button', { name: /switch workspace/i })
    await user.click(switcher)
    const menu = screen.getByRole('menu', { name: 'Authorized workspaces' })
    expect(within(menu).getByRole('menuitem', { name: /customer space/i })).toHaveAttribute('href', '/customer')
  })

  it('11. Multi-role CUSTOMER + ADMIN: prioritizes Admin, routes to active workspace, and switches seamlessly', async () => {
    const user = userEvent.setup()
    const auth = createAuth(['CUSTOMER', 'ADMIN'])
    renderShell(auth, '/customer')
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    // When on /customer, active workspace is Customer space
    expect(nav.getByRole('link', { name: /customer space/i })).toHaveAttribute('href', '/customer')
    expect(nav.queryByRole('link', { name: /admin space/i })).not.toBeInTheDocument()

    // Switcher lists Admin space and Dispatcher space
    const switcher = nav.getByRole('button', { name: /switch workspace/i })
    await user.click(switcher)
    const menu = screen.getByRole('menu', { name: 'Authorized workspaces' })
    expect(within(menu).getByRole('menuitem', { name: /admin space/i })).toHaveAttribute('href', '/admin')
    expect(within(menu).getByRole('menuitem', { name: /dispatcher space/i })).toHaveAttribute('href', '/dispatcher')

    // Navigate to Admin workspace
    await user.click(within(menu).getByRole('menuitem', { name: /admin space/i }))
    expect(screen.getByText('Admin Workspace Page')).toBeInTheDocument()

    // Now on /admin, active workspace is Admin space
    expect(nav.getByRole('link', { name: /admin space/i })).toHaveAttribute('href', '/admin')
    expect(nav.queryByRole('link', { name: /customer space/i })).not.toBeInTheDocument()
  })

  it('12. Keyboard accessibility: Escape dismisses workspace switcher and restores focus', async () => {
    const user = userEvent.setup()
    renderShell(createAuth(['CUSTOMER', 'ADMIN']))
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))

    const switcher = nav.getByRole('button', { name: /switch workspace/i })
    await user.click(switcher)
    expect(switcher).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('menu', { name: 'Authorized workspaces' })).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(switcher).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('menu', { name: 'Authorized workspaces' })).not.toBeInTheDocument()
    expect(switcher).toHaveFocus()
  })

  it('13. Global sign-out control immediately delegates to auth.logout', async () => {
    const user = userEvent.setup()
    const logoutMock = vi.fn(async () => {})
    renderShell(createAuth(['CUSTOMER'], 'authenticated', logoutMock))

    const signOutBtn = screen.getByRole('button', { name: 'Sign out' })
    await user.click(signOutBtn)

    expect(logoutMock).toHaveBeenCalledOnce()
  })

  it('14. Helper determineActiveWorkspace accurately selects route-based or fallback primary workspace', () => {
    const workspaces = getAuthorizedWorkspaces(['ADMIN', 'CUSTOMER'])
    // Should contain Admin, Dispatcher, Customer
    expect(workspaces.map((w) => w.id)).toEqual(['admin', 'dispatcher', 'customer'])

    // On /customer route
    expect(determineActiveWorkspace(workspaces, '/customer')?.id).toBe('customer')
    expect(determineActiveWorkspace(workspaces, '/customer/history')?.id).toBe('customer')

    // On /dispatcher route
    expect(determineActiveWorkspace(workspaces, '/dispatcher')?.id).toBe('dispatcher')

    // On /admin route
    expect(determineActiveWorkspace(workspaces, '/admin/pricing')?.id).toBe('admin')

    // On home or unrelated route -> falls back to primary role (admin)
    expect(determineActiveWorkspace(workspaces, '/')?.id).toBe('admin')
    expect(determineActiveWorkspace(workspaces, '/tracking')?.id).toBe('admin')
  })
})
