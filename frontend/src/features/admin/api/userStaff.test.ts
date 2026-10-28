import { describe, expect, it, vi } from 'vitest'
import { adminPage, adminStaff, adminUser, provisionStaff, staffAudit, deferred } from '../../../test/adminManagementFixture'
import { adminHttpError, canRecover, canSuspend, createAdminManagementApi, parseAdminPage, parseAdminStaff, parseAdminUser, parseStaffAudit, provisionProblem, type ProvisionStaff, type StaffRole, type StaffStatus } from './userStaff'

const signal = () => new AbortController().signal
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function setup(body: unknown = adminPage([adminUser]), status = 200) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(response(body, status))
  const getAccessToken = vi.fn(async (): Promise<string | null> => 'synthetic-memory-token')
  return { api: createAdminManagementApi({ getAccessToken, fetch, gatewayUrl: 'https://gateway.example.test/' }), fetch, getAccessToken }
}
const reconciliation = (outcome = 'SUCCESS') => ({ subject: adminStaff.keycloakSub, roleName: adminStaff.role, outcome, message: 'INTERNAL MUST NOT BE SHOWN', timestamp: adminStaff.updatedAt })

describe('Accounts admin DTO validation', () => {
  it('projects only authorized user fields and accepts nullable profile fields', () => {
    expect(parseAdminUser({ ...adminUser, credential: 'must be discarded' })).toEqual(adminUser)
    expect(parseAdminUser({ ...adminUser, fullName: null, email: null })).toMatchObject({ fullName: null, email: null })
  })
  it.each([{ profileId: '../escape' }, { keycloakSub: '' }, { email: {} }, { createdAt: 'yesterday' }, { fullName: 42 }])('rejects malformed users %j', extra => {
    expect(() => parseAdminUser({ ...adminUser, ...extra })).toThrow('unsupported response')
  })
  it('projects staff business status separately from identity state', () => {
    expect(parseAdminStaff({ ...adminStaff, outcome: 'PENDING_RETRY', password: 'discard' })).toEqual(adminStaff)
  })
  it.each([{ profileId: 'mutable-staff-id' }, { role: 'STAFF' }, { role: 'CUSTOMER' }, { businessStatus: 'PENDING_RETRY' }, { department: null }, { accountProfileId: 'invalid' }, { assignedZone: {} }, { updatedAt: null }])('rejects malformed staff %j', extra => {
    expect(() => parseAdminStaff({ ...adminStaff, ...extra })).toThrow('unsupported response')
  })
  it.each<StaffStatus>(['ACTIVE', 'SUSPENDED', 'INACTIVE'])('uses business status %s to enable suspension', businessStatus => {
    expect(canSuspend({ ...adminStaff, businessStatus })).toBe(businessStatus === 'ACTIVE')
  })
  it('validates real server pagination and allows a now-empty last page', () => {
    expect(parseAdminPage(adminPage([adminUser]), 0, 20, parseAdminUser).last).toBe(true)
    expect(parseAdminPage(adminPage([], 3, 2), 3, 20, parseAdminUser).content).toEqual([])
  })
  it.each([{ page: 1 }, { size: 100 }, { totalElements: -1 }, { totalPages: 9 }, { last: false }, { first: false }, { content: [adminUser, adminUser], totalElements: 2 }, { content: Array(21).fill(adminUser), totalElements: 21, totalPages: 2, last: false }])('rejects inconsistent pagination %j', extra => {
    expect(() => parseAdminPage({ ...adminPage([adminUser]), ...extra }, 0, 20, parseAdminUser)).toThrow('unsupported response')
  })
  it('drops provider error text and unrelated merchant audit and sorts by update', () => {
    const newer = { ...staffAudit, id: '40000000-0000-0000-0000-000000000004', updatedAt: '2026-10-28T03:00:00Z' }
    expect(parseStaffAudit([{ ...staffAudit, errorMessage: 'private provider detail' }, newer, { ...staffAudit, id: '50000000-0000-0000-0000-000000000005', targetRole: 'MERCHANT' }], adminStaff)).toEqual([newer, staffAudit])
    expect(canRecover(staffAudit)).toBe(true)
    expect(canRecover({ ...staffAudit, outcome: 'DENIED' })).toBe(false)
  })
  it.each([{ targetSub: 'another-person' }, { outcome: 'COMPLETE' }, { id: 'bad' }, { attemptCount: -1 }, { updatedAt: '' }])('rejects malformed/mismatched audit %j', extra => {
    expect(() => parseStaffAudit([{ ...staffAudit, ...extra }], adminStaff)).toThrow('unsupported response')
  })
  it.each([{ keycloakSub: '' }, { staffId: ' '.repeat(2) }, { department: 'x'.repeat(101) }, { role: 'STAFF' as StaffRole }, { accountProfileId: 'not-a-uuid' }, { assignedZone: 'x'.repeat(101) }, { department: 'bad\u0000input' }])('rejects invalid provisioning %j', extra => {
    expect(provisionProblem({ ...provisionStaff, ...extra })).not.toBeNull()
  })
  it.each<StaffRole>(['COURIER', 'DISPATCHER', 'ADMIN'])('allows only the actual backend staff roles: %s', role => {
    expect(provisionProblem({ ...provisionStaff, role })).toBeNull()
  })
})

describe('Gateway administration adapter', () => {
  it('requests a real server user page with the in-memory bearer and safe transport options', async () => {
    const { api, fetch } = setup()
    const abort = signal()
    await expect(api.users(0, abort)).resolves.toEqual(adminPage([adminUser]))
    expect(fetch).toHaveBeenCalledExactlyOnceWith('https://gateway.example.test/api/v1/admin/users?page=0&size=20', { method: 'GET', headers: { Accept: 'application/json', Authorization: 'Bearer synthetic-memory-token' }, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: abort })
  })
  it('encodes only supported staff filters and never adds a search parameter', async () => {
    const { api, fetch } = setup(adminPage([adminStaff]))
    await api.staff(0, { staffRole: 'COURIER', businessStatus: 'ACTIVE', assignedZone: ' Zone / A ' }, signal())
    expect(String(fetch.mock.calls[0]![0])).toBe('https://gateway.example.test/api/v1/admin/staff?page=0&size=20&staffRole=COURIER&businessStatus=ACTIVE&assignedZone=Zone+%2F+A')
  })
  it('fetches detail by immutable UUID and rejects another profile', async () => {
    const { api, fetch } = setup(adminStaff)
    await expect(api.detail(adminStaff.profileId, signal())).resolves.toEqual(adminStaff)
    expect(String(fetch.mock.calls[0]![0])).toEqual(expect.stringContaining(`/staff/${adminStaff.profileId}`))
    await expect(api.detail(adminUser.profileId, signal())).rejects.toMatchObject({ kind: 'invalid-response' })
  })
  it('validates request identifiers/page/filter inputs before network access', async () => {
    const { api, fetch } = setup()
    await expect(api.detail('../escape', signal())).rejects.toMatchObject({ kind: 'validation' })
    await expect(api.users(-1, signal())).rejects.toMatchObject({ kind: 'validation' })
    await expect(api.staff(0, { staffRole: 'STAFF' as StaffRole }, signal())).rejects.toMatchObject({ kind: 'validation' })
    await expect(api.provision({ ...provisionStaff, staffId: '' }, signal())).rejects.toMatchObject({ kind: 'validation' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('reads audit by encoded verified subject', async () => {
    const staff = { ...adminStaff, keycloakSub: 'subject/opaque' }
    const { api, fetch } = setup([{ ...staffAudit, targetSub: staff.keycloakSub }])
    await api.audit(staff, signal())
    expect(String(fetch.mock.calls[0]![0])).toEqual(expect.stringContaining('/reconciliation/audit/subject%2Fopaque'))
  })
  it('sends the exact provisioning payload, discards spoofed extras and verifies the confirmed binding', async () => {
    const { api, fetch } = setup(adminStaff)
    await expect(api.provision({ ...provisionStaff, roles: ['ADMIN'], businessStatus: 'ACTIVE' } as ProvisionStaff, signal())).resolves.toBe(adminStaff.profileId)
    expect(fetch.mock.calls[0]![1]).toMatchObject({ method: 'POST', body: JSON.stringify(provisionStaff) })
    expect(String(fetch.mock.calls[0]![0])).toEqual(expect.stringContaining('/reconciliation/staff/provision'))
  })
  it.each([{ keycloakSub: 'other' }, { role: 'ADMIN' }, { businessStatus: 'SUSPENDED' }, { profileId: 'invalid' }, { department: 'Other' }])('does not confirm an incorrect provisioning result %j', extra => {
    const { api } = setup({ ...adminStaff, ...extra })
    return expect(api.provision(provisionStaff, signal())).rejects.toMatchObject({ kind: 'unconfirmed' })
  })
  it.each(['suspend', 'sync', 'retry'] as const)('sends reviewed %s exactly once without client role/state overrides', async action => {
    const { api, fetch } = setup(reconciliation())
    await api.command(action === 'retry' ? { action, staff: adminStaff, auditId: staffAudit.id } : { action, staff: adminStaff }, signal())
    const path = action === 'retry' ? `retry/${staffAudit.id}` : `staff/${adminStaff.keycloakSub}/${action}`
    expect(String(fetch.mock.calls[0]![0])).toEqual(expect.stringContaining(`/reconciliation/${path}`))
    expect(fetch.mock.calls[0]![1]).toMatchObject({ method: 'POST' })
    expect(fetch.mock.calls[0]![1]).not.toHaveProperty('body')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('rejects unapproved lifecycle commands and inactive suspension before fetching', async () => {
    const { api, fetch } = setup()
    await expect(api.command({ action: 'activate', staff: adminStaff } as never, signal())).rejects.toMatchObject({ kind: 'validation' })
    await expect(api.command({ action: 'suspend', staff: { ...adminStaff, businessStatus: 'INACTIVE' } }, signal())).rejects.toMatchObject({ kind: 'validation' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each(['FAILED', 'DENIED', 'PENDING_RETRY', 'UNKNOWN'])('does not turn a 200 %s into completed success', async outcome => {
    const { api } = setup(reconciliation(outcome))
    await expect(api.command({ action: 'sync', staff: adminStaff }, signal())).rejects.toMatchObject({ kind: outcome === 'PENDING_RETRY' ? 'pending' : 'unconfirmed' })
  })
  it.each([{ subject: 'wrong' }, { roleName: 'ADMIN' }, { timestamp: 'invalid' }])('rejects mismatched reconciliation result %j', async extra => {
    const { api } = setup({ ...reconciliation(), ...extra })
    await expect(api.command({ action: 'sync', staff: adminStaff }, signal())).rejects.toMatchObject({ kind: 'unconfirmed' })
  })
  it.each([[400, 'validation'], [401, 'auth-required'], [403, 'forbidden'], [404, 'not-found'], [409, 'conflict'], [429, 'rate-limited'], [500, 'unavailable'], [503, 'pending']])('maps HTTP %s to %s and never exposes ProblemDetail', async (status, kind) => {
    const { api, fetch } = setup({ detail: 'PRIVATE STACK TRACE' }, status as number)
    await expect(api.users(0, signal())).rejects.toMatchObject({ kind })
    expect(adminHttpError(status as number).message).not.toContain('PRIVATE')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it.each([401, 403, 409, 503])('never replays a command after HTTP %s', async status => {
    const { api, fetch } = setup({}, status)
    await expect(api.command({ action: 'sync', staff: adminStaff }, signal())).rejects.toBeDefined()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('handles network failures without leaking exception text', async () => {
    const { api, fetch } = setup()
    fetch.mockRejectedValue(new Error('PRIVATE HOST DETAILS'))
    await expect(api.users(0, signal())).rejects.toMatchObject({ kind: 'unavailable' })
    await expect(api.provision(provisionStaff, signal())).rejects.toMatchObject({ kind: 'unconfirmed' })
  })
  it('rejects unreadable successful responses', async () => {
    const { api, fetch } = setup()
    fetch.mockImplementation(async () => new Response('not json'))
    await expect(api.users(0, signal())).rejects.toMatchObject({ kind: 'invalid-response' })
    await expect(api.command({ action: 'sync', staff: adminStaff }, signal())).rejects.toMatchObject({ kind: 'unconfirmed' })
  })
  it('does not fetch without a token or when token retrieval fails', async () => {
    const { api, fetch, getAccessToken } = setup()
    getAccessToken.mockResolvedValue(null)
    await expect(api.users(0, signal())).rejects.toMatchObject({ kind: 'auth-required' })
    getAccessToken.mockRejectedValue(new Error('PRIVATE'))
    await expect(api.users(0, signal())).rejects.toMatchObject({ kind: 'auth-required' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('checks cancellation before and after token acquisition', async () => {
    const { api, fetch, getAccessToken } = setup()
    const controller = new AbortController(), token = deferred<string | null>()
    getAccessToken.mockReturnValue(token.promise)
    const pending = api.users(0, controller.signal)
    controller.abort(); token.resolve('synthetic-late-token')
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await expect(api.users(0, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('ignores a response that arrives after cancellation even if transport ignores abort', async () => {
    const { api, fetch } = setup(), pending = deferred<Response>(), controller = new AbortController()
    fetch.mockReturnValue(pending.promise)
    const read = api.users(0, controller.signal)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())
    controller.abort(); pending.resolve(response(adminPage([adminUser])))
    await expect(read).rejects.toMatchObject({ name: 'AbortError' })
  })
})
