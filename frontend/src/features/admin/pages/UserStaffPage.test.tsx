import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import { AuthContext, type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth } from '../../../test/authFixture'
import { adminPage, adminStaff, adminUser, deferred, staffAudit } from '../../../test/adminManagementFixture'
import { AdminManagementError, type AdminManagementApi, type AdminPage, type AdminUser } from '../api/userStaff'
import { UserStaffPage } from './UserStaffPage'

function auth(roles: readonly SwiftDropRole[] = ['ADMIN'], subject = 'synthetic-admin-a'): AuthContextValue {
  return { ...anonymousAuth, snapshot: { status: 'authenticated', subject, roles }, getAccessToken: vi.fn(async () => 'synthetic-memory-token'), handleSessionExpiry: vi.fn(async () => {}) }
}
function provider() {
  return {
    users: vi.fn<AdminManagementApi['users']>().mockImplementation(async page => adminPage([adminUser], page)),
    staff: vi.fn<AdminManagementApi['staff']>().mockImplementation(async page => adminPage([adminStaff], page)),
    detail: vi.fn<AdminManagementApi['detail']>().mockResolvedValue(adminStaff),
    audit: vi.fn<AdminManagementApi['audit']>().mockResolvedValue([staffAudit]),
    provision: vi.fn<AdminManagementApi['provision']>().mockResolvedValue(adminStaff.profileId),
    command: vi.fn<AdminManagementApi['command']>().mockResolvedValue(undefined),
  } satisfies AdminManagementApi
}
function setup({ api = provider(), current = auth(), path = '/admin/users', productionRoutes = false }: {
  api?: AdminManagementApi; current?: AuthContextValue; path?: string; productionRoutes?: boolean
} = {}) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
  function tree(value: AuthContextValue, nextApi = api) {
    return <QueryClientProvider client={cache}><AuthContext value={value}><MemoryRouter initialEntries={[path]}>
      {productionRoutes ? <AppRoutes /> : <Routes>
        <Route path="/admin/users" element={<UserStaffPage section="users" api={nextApi} />} />
        <Route path="/admin/staff" element={<UserStaffPage section="staff" api={nextApi} />} />
        <Route path="/admin/staff/:profileId" element={<UserStaffPage section="detail" api={nextApi} />} />
      </Routes>}
    </MemoryRouter></AuthContext></QueryClientProvider>
  }
  const view = render(tree(current))
  return { ...view, cache, api, rerenderAuth: (next: AuthContextValue, nextApi = api) => view.rerender(tree(next, nextApi)) }
}
const detailPath = `/admin/staff/${adminStaff.profileId}`
async function confirmAction(label = 'Suspend staff') {
  const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: label })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: label }))
  return { user, dialog: screen.getByRole('dialog') }
}
async function fillProvision() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Existing identity subject'), adminStaff.keycloakSub)
  await user.type(screen.getByLabelText('Staff ID', { exact: true }), adminStaff.staffId)
  await user.type(screen.getByLabelText('Department', { exact: true }), adminStaff.department)
  await user.click(screen.getByRole('button', { name: 'Review provisioning' }))
  return { user, dialog: screen.getByRole('dialog') }
}

describe('ADMIN routes and navigation', () => {
  it.each(['/admin/users', '/admin/staff', detailPath])('redirects anonymous direct access to %s through shared login', path => {
    setup({ current: anonymousAuth, path, productionRoutes: true })
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
  })
  for (const role of ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'] as const) {
    it.each(['/admin/users', '/admin/staff', detailPath])(`denies ${role} direct URL %s and hides admin navigation`, path => {
      setup({ current: auth([role]), path, productionRoutes: true })
      expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
      expect(screen.queryByRole('link', { name: /Admin space/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Review provisioning' })).not.toBeInTheDocument()
    })
  }
  it('waits for shared authentication before exposing management', () => {
    setup({ current: { ...anonymousAuth, snapshot: { status: 'loading', subject: null, roles: [] } }, productionRoutes: true })
    expect(screen.getByRole('status')).toHaveTextContent('Checking session')
  })
  it('guards the feature even outside the route and ignores browser privilege flags', () => {
    const api = provider()
    localStorage.setItem('role', 'ADMIN')
    try {
      setup({ api, current: auth(['CUSTOMER']), path: '/admin/staff?role=ADMIN' })
      expect(screen.getByRole('alert')).toHaveTextContent('requires an authenticated ADMIN')
      expect(api.staff).not.toHaveBeenCalled()
      expect(api.provision).not.toHaveBeenCalled()
    } finally { localStorage.removeItem('role') }
  })
  it('wires real ADMIN routes to the Gateway adapter with the existing token getter', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify(adminPage([adminUser])), { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    setup({ productionRoutes: true, current: auth(['CUSTOMER', 'ADMIN']) })
    await screen.findByText(adminUser.fullName!)
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/v1/admin/users?page=0&size=20'), expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer synthetic-memory-token' }) }))
  })
  it('rejects invalid profile identifiers before reading detail', () => {
    const api = provider()
    setup({ api, path: '/admin/staff/not-a-uuid' })
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid staff profile ID')
    expect(api.detail).not.toHaveBeenCalled()
  })
})

describe('User and staff reads', () => {
  it('shows user loading, safe nullable fields, empty and refresh states', async () => {
    const api = provider(), pending = deferred<AdminPage<AdminUser>>()
    api.users.mockReturnValueOnce(pending.promise).mockResolvedValue(adminPage([]))
    setup({ api })
    expect(screen.getByRole('status')).toHaveTextContent('Loading users')
    await act(async () => pending.resolve(adminPage([{ ...adminUser, fullName: null, email: null }])))
    expect(await screen.findAllByText('Not provided')).toHaveLength(2)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Refresh users' }))
    await screen.findByText('No users on this page.')
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  })
  it('uses server pages, disables pagination while pending and supports previous', async () => {
    const api = provider(), pending = deferred<AdminPage<AdminUser>>()
    api.users.mockResolvedValueOnce(adminPage([adminUser], 0, 21)).mockReturnValueOnce(pending.promise).mockResolvedValue(adminPage([adminUser], 0, 21))
    setup({ api })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading users')
    await act(async () => pending.resolve(adminPage([{ ...adminUser, fullName: 'Second page user' }], 1, 21)))
    await screen.findByText('Second page user')
    expect(api.users).toHaveBeenLastCalledWith(1, expect.any(AbortSignal))
    expect(screen.queryByText(adminUser.fullName!)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Previous page' }))
    await screen.findByText(adminUser.fullName!)
  })
  it('renders staff fields, applies database filters and resets page to zero', async () => {
    const api = provider()
    api.staff.mockResolvedValue(adminPage([adminStaff], 0, 21))
    setup({ api, path: '/admin/staff' })
    await screen.findByRole('link', { name: `View ${adminStaff.staffId}` })
    expect(screen.getByText('Not assigned')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(api.staff).toHaveBeenCalledWith(1, {}, expect.any(AbortSignal)))
    await user.selectOptions(screen.getByLabelText('Business role'), 'DISPATCHER')
    await user.selectOptions(screen.getByLabelText('Business status'), 'SUSPENDED')
    await user.type(screen.getByLabelText('Assigned zone (exact match)'), 'ZONE-A')
    api.staff.mockResolvedValue(adminPage([]))
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    await screen.findByText('No staff match these filters on this page.')
    expect(api.staff).toHaveBeenLastCalledWith(0, { staffRole: 'DISPATCHER', businessStatus: 'SUSPENDED', assignedZone: 'ZONE-A' }, expect.any(AbortSignal))
  })
  it('can return to page zero when concurrent deletion makes the selected page empty', async () => {
    const api = provider()
    api.users.mockResolvedValueOnce(adminPage([adminUser], 0, 21)).mockResolvedValueOnce(adminPage([], 1, 1)).mockResolvedValue(adminPage([adminUser]))
    setup({ api })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    await user.click(await screen.findByRole('button', { name: 'Return to first page' }))
    await screen.findByText(adminUser.fullName!)
    expect(api.users).toHaveBeenLastCalledWith(0, expect.any(AbortSignal))
  })
  it('recovers a user provider failure only through explicit retry', async () => {
    const api = provider()
    api.users.mockRejectedValueOnce(new AdminManagementError('unavailable'))
    setup({ api })
    expect(await screen.findByRole('alert')).toHaveTextContent('service is unavailable')
    expect(api.users).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Retry read' }))
    await screen.findByText(adminUser.fullName!)
  })
  it('shows detail and pending identity evidence without treating it as active synchronization', async () => {
    setup({ path: detailPath })
    await screen.findByText('Identity reconciliation is pending. Business access must not be assumed active.')
    expect(screen.getByText('Latest recorded identity outcome:')).toHaveTextContent('PENDING RETRY')
    expect(screen.getByText(staffAudit.initiatedBy, { exact: false })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /activate|deactivate/i })).not.toBeInTheDocument()
  })
  it('shows unknown identity synchronization when audit is empty', async () => {
    const api = provider(); api.audit.mockResolvedValue([])
    setup({ api, path: detailPath })
    await screen.findByText('No staff reconciliation records returned. Identity synchronization is unknown.')
  })
  it('keeps profile usable while audit read fails and offers explicit retry', async () => {
    const api = provider(); api.audit.mockRejectedValueOnce(new AdminManagementError('unavailable'))
    setup({ api, path: detailPath })
    expect(await screen.findByRole('alert')).toHaveTextContent('service is unavailable')
    expect(screen.getByRole('heading', { name: adminStaff.staffId })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Suspend staff' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Retry read' }))
    await screen.findByText('Latest recorded identity outcome:')
  })
  it('shows a missing staff profile without exposing mutations', async () => {
    const api = provider(); api.detail.mockRejectedValue(new AdminManagementError('not-found'))
    setup({ api, path: detailPath })
    expect(await screen.findByRole('alert')).toHaveTextContent('was not found')
    expect(screen.queryByRole('button', { name: 'Suspend staff' })).not.toBeInTheDocument()
  })
})

describe('Controlled staff commands and confirmations', () => {
  it('names immutable target, traps focus, supports Escape/cancel and restores focus', async () => {
    const api = provider(); setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction()
    expect(dialog).toHaveTextContent(adminStaff.profileId)
    expect(dialog).toHaveTextContent(adminStaff.keycloakSub)
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' }), submit = within(dialog).getByRole('button', { name: 'Confirm action' })
    expect(cancel).toHaveFocus()
    await user.tab({ shift: true }); expect(submit).toHaveFocus()
    await user.tab(); expect(cancel).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Suspend staff' })).toHaveFocus()
    expect(api.command).not.toHaveBeenCalled()
  })
  it('prevents duplicate submission and refreshes authoritative profile/audit after success', async () => {
    const api = provider(), pending = deferred<void>()
    api.command.mockReturnValue(pending.promise)
    setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction()
    await user.dblClick(within(dialog).getByRole('button', { name: 'Confirm action' }))
    expect(api.command).toHaveBeenCalledTimes(1)
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeDisabled()
    await user.keyboard('{Escape}'); expect(screen.getByRole('dialog')).toBeInTheDocument()
    api.detail.mockResolvedValue({ ...adminStaff, businessStatus: 'SUSPENDED' })
    await act(async () => pending.resolve())
    await screen.findByText(/server confirmed the operation/)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.detail).toHaveBeenCalledTimes(2); expect(api.audit).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: 'Suspend staff' })).toBeDisabled()
  })
  it.each(['conflict', 'pending', 'unconfirmed'] as const)('does not claim %s as success and refreshes state without replay', async kind => {
    const api = provider(); api.command.mockRejectedValue(new AdminManagementError(kind))
    setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    await screen.findByRole('alert')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByText(/server confirmed/)).not.toBeInTheDocument()
    expect(api.command).toHaveBeenCalledTimes(1); expect(api.detail).toHaveBeenCalledTimes(2)
  })
  it('sends recovery only for a returned pending audit and names its target', async () => {
    const api = provider(); setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction(`Recover audit ${staffAudit.id}`)
    expect(dialog).toHaveTextContent(staffAudit.id)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    await waitFor(() => expect(api.command).toHaveBeenCalledWith({ action: 'retry', staff: adminStaff, auditId: staffAudit.id }, expect.any(AbortSignal)))
  })
  it('shows failed identity operations with the recovery path, but no recovery for DENIED', async () => {
    const api = provider(); api.audit.mockResolvedValue([{ ...staffAudit, outcome: 'DENIED' }])
    setup({ api, path: detailPath })
    await screen.findByText('The identity operation did not complete successfully. Review the business profile before recovery.')
    expect(screen.queryByRole('button', { name: /Recover audit/ })).not.toBeInTheDocument()
  })
  it('synchronizes an inactive profile without activating it', async () => {
    const api = provider(); api.detail.mockResolvedValue({ ...adminStaff, businessStatus: 'INACTIVE' })
    setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction('Synchronize identity role')
    expect(dialog).toHaveTextContent('does not activate an inactive profile')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    await waitFor(() => expect(api.command).toHaveBeenCalledWith({ action: 'sync', staff: { ...adminStaff, businessStatus: 'INACTIVE' } }, expect.any(AbortSignal)))
  })
  it('requires valid provisioning fields before confirmation', async () => {
    const api = provider(); setup({ api, path: '/admin/staff' })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Review provisioning' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Existing identity subject'), '   ')
    await user.type(screen.getByLabelText('Staff ID', { exact: true }), adminStaff.staffId)
    await user.type(screen.getByLabelText('Department', { exact: true }), adminStaff.department)
    await user.click(screen.getByRole('button', { name: 'Review provisioning' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Identity subject is required')
    expect(api.provision).not.toHaveBeenCalled()
    expect(screen.queryByLabelText(/password|token/i)).not.toBeInTheDocument()
  })
  it('confirms controlled provisioning once and navigates only after server confirmation', async () => {
    const api = provider(), pending = deferred<string>(); api.provision.mockReturnValue(pending.promise)
    setup({ api, path: '/admin/staff' })
    const { user, dialog } = await fillProvision()
    expect(dialog).toHaveTextContent(adminStaff.keycloakSub)
    await user.dblClick(within(dialog).getByRole('button', { name: 'Confirm action' }))
    expect(api.provision).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('heading', { name: 'Staff details' })).not.toBeInTheDocument()
    await act(async () => pending.resolve(adminStaff.profileId))
    await screen.findByRole('heading', { name: 'Staff details' })
  })
  it.each(['conflict', 'pending', 'not-found'] as const)('does not fake a record after failed provisioning: %s', async kind => {
    const api = provider(); api.provision.mockRejectedValue(new AdminManagementError(kind))
    setup({ api, path: '/admin/staff' })
    const { user, dialog } = await fillProvision()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    await screen.findByRole('alert')
    expect(api.provision).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/server confirmed/)).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Staff details' })).not.toBeInTheDocument()
  })
})

describe('Protected session isolation', () => {
  it.each([anonymousAuth, auth(['CUSTOMER'], 'synthetic-customer'), auth(['DISPATCHER'], 'synthetic-admin-a')])('removes protected content and cache on logout, cross-role login or privilege loss %#', async current => {
    const api = provider(), view = setup({ api, path: detailPath })
    await screen.findByText('Latest recorded identity outcome:')
    view.rerenderAuth(current)
    expect(screen.queryByText(adminStaff.keycloakSub)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suspend staff' })).not.toBeInTheDocument()
    await waitFor(() => expect(view.cache.getQueryCache().findAll({ queryKey: ['private', 'admin-user-staff'] })).toHaveLength(0))
  })
  it('discards Admin A read after Admin B signs in even when transport ignores cancellation', async () => {
    const api = provider(), old = deferred<AdminPage<AdminUser>>(), next = provider()
    api.users.mockReturnValue(old.promise); next.users.mockResolvedValue(adminPage([{ ...adminUser, fullName: 'Admin B data' }]))
    const view = setup({ api })
    await waitFor(() => expect(api.users).toHaveBeenCalled())
    const oldSignal = api.users.mock.calls[0]![1]
    view.rerenderAuth(auth(['ADMIN'], 'synthetic-admin-b'), next)
    await screen.findByText('Admin B data')
    expect(oldSignal.aborted).toBe(true)
    await act(async () => old.resolve(adminPage([adminUser])))
    expect(screen.queryByText(adminUser.fullName!)).not.toBeInTheDocument()
    expect(JSON.stringify(view.cache.getQueryCache().getAll().map(query => query.queryKey))).not.toContain('synthetic-admin-a')
  })
  it('cancels a paginated request when the session expires', async () => {
    const api = provider(), pending = deferred<AdminPage<AdminUser>>()
    api.users.mockResolvedValueOnce(adminPage([adminUser], 0, 21)).mockReturnValueOnce(pending.promise)
    const view = setup({ api })
    await userEvent.click(await screen.findByRole('button', { name: 'Next page' }))
    const oldSignal = api.users.mock.calls[1]![1]
    view.rerenderAuth(anonymousAuth)
    expect(oldSignal.aborted).toBe(true)
    await act(async () => pending.resolve(adminPage([adminUser], 1, 21)))
    expect(screen.queryByText(adminUser.fullName!)).not.toBeInTheDocument()
  })
  it.each(['auth-required', 'forbidden'] as const)('removes data and capabilities after command %s without replay', async kind => {
    const api = provider(), current = auth(); api.command.mockRejectedValue(new AdminManagementError(kind))
    const view = setup({ api, current, path: detailPath })
    const { user, dialog } = await confirmAction()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    await waitFor(() => expect(screen.queryByText(adminStaff.keycloakSub)).not.toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suspend staff' })).not.toBeInTheDocument()
    expect(api.command).toHaveBeenCalledTimes(1)
    expect(current.handleSessionExpiry).toHaveBeenCalledTimes(kind === 'auth-required' ? 1 : 0)
    await waitFor(() => expect(view.cache.getQueryCache().getAll()).toHaveLength(0))
  })
  it('denies the whole workspace when an audit read is forbidden instead of retaining the profile', async () => {
    const api = provider(); api.audit.mockRejectedValue(new AdminManagementError('forbidden'))
    setup({ api, path: detailPath })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('administration is denied'))
    expect(screen.queryByText(adminStaff.keycloakSub)).not.toBeInTheDocument()
  })
  it.each([undefined, new AdminManagementError('pending')])('ignores a mutation result after logout, including failure %#', async error => {
    const api = provider(), pending = deferred<void>(); api.command.mockReturnValue(pending.promise)
    const view = setup({ api, path: detailPath })
    const { user, dialog } = await confirmAction()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    const oldSignal = api.command.mock.calls[0]![1]
    view.rerenderAuth(anonymousAuth)
    expect(oldSignal.aborted).toBe(true)
    await act(async () => { if (error) pending.reject(error); else pending.resolve() })
    expect(screen.queryByText(/server confirmed|pending recovery/)).not.toBeInTheDocument()
    expect(view.cache.getQueryCache().getAll()).toHaveLength(0)
  })
  it('suppresses previous-session provisioning success and navigation after another admin signs in', async () => {
    const api = provider(), pending = deferred<string>(), next = provider(); api.provision.mockReturnValue(pending.promise)
    const view = setup({ api, path: '/admin/staff' })
    const { user, dialog } = await fillProvision()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm action' }))
    view.rerenderAuth(auth(['ADMIN'], 'synthetic-admin-b'), next)
    await act(async () => pending.resolve(adminStaff.profileId))
    expect(screen.queryByRole('heading', { name: 'Staff details' })).not.toBeInTheDocument()
    expect(screen.queryByText(/server confirmed/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Existing identity subject')).toHaveValue('')
    expect(next.detail).not.toHaveBeenCalled()
  })
  it('calls shared expiry handling on an unauthorized list and does not retry', async () => {
    const api = provider(), current = auth(); api.users.mockRejectedValue(new AdminManagementError('auth-required'))
    setup({ api, current })
    await waitFor(() => expect(current.handleSessionExpiry).toHaveBeenCalledTimes(1))
    expect(api.users).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Retry read' })).not.toBeInTheDocument()
  })
})
