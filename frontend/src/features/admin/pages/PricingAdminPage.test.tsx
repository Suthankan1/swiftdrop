import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import { AuthContext, type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth } from '../../../test/authFixture'
import { active, draft, pricingPage, rule, zone } from '../../../test/pricingAdminFixture'
import { deferred } from '../../../test/adminManagementFixture'
import { PricingError, type PricingApi } from '../api/pricingAdmin'
import { PricingAdminPage } from './PricingAdminPage'

function auth(roles: readonly SwiftDropRole[] = ['ADMIN'], subject = 'synthetic-admin'): AuthContextValue {
  return { ...anonymousAuth, snapshot: { status: 'authenticated', subject, roles }, getAccessToken: vi.fn(async () => 'synthetic-memory-token'), handleSessionExpiry: vi.fn(async () => {}), profileFetch: vi.fn(async () => { throw Error('Only self-profile reads are supported') }) }
}
function provider() {
  return {
    zones: vi.fn<PricingApi['zones']>().mockResolvedValue(pricingPage([zone])),
    zone: vi.fn<PricingApi['zone']>().mockResolvedValue(zone),
    cards: vi.fn<PricingApi['cards']>().mockResolvedValue(pricingPage([active, draft])),
    review: vi.fn<PricingApi['review']>().mockResolvedValue([active, draft]),
    rules: vi.fn<PricingApi['rules']>().mockResolvedValue(pricingPage([rule])),
    command: vi.fn<PricingApi['command']>().mockResolvedValue(undefined),
  } satisfies PricingApi
}
function setup(api = provider(), current = auth(), routes = false) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  function tree(value: AuthContextValue) { return <QueryClientProvider client={cache}><AuthContext value={value}><MemoryRouter initialEntries={['/admin/pricing']}>{routes ? <AppRoutes /> : <PricingAdminPage api={api} />}</MemoryRouter></AuthContext></QueryClientProvider> }
  const view = render(tree(current))
  return { ...view, api, cache, current, user: userEvent.setup(), session: (value: AuthContextValue) => view.rerender(tree(value)) }
}
async function loaded() { await screen.findByRole('button', { name: 'Edit ZONE_1' }); await waitFor(() => expect(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })).toBeEnabled()) }
async function zoneForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Create zone' }))
  await user.type(screen.getByLabelText('Zone code'), 'ZONE_NEW')
  await user.type(screen.getByLabelText('Zone name'), ' New district ')
}
async function confirm(user: ReturnType<typeof userEvent.setup>) { await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm action' })) }
describe('Pricing access and session isolation', () => {
  it.each<SwiftDropRole>(['DISPATCHER', 'CUSTOMER', 'MERCHANT', 'COURIER'])('denies %s without any feature reads', role => {
    const { api } = setup(provider(), auth([role])); expect(screen.getByRole('alert')).toHaveTextContent('authenticated ADMIN')
    expect(api.zones).not.toHaveBeenCalled(); expect(api.cards).not.toHaveBeenCalled(); expect(api.review).not.toHaveBeenCalled()
  })
  it('denies unauthenticated feature access', () => { const { api } = setup(provider(), anonymousAuth); expect(screen.getByRole('alert')).toHaveTextContent('authenticated ADMIN'); expect(api.zones).not.toHaveBeenCalled() })
  it.each<SwiftDropRole>(['DISPATCHER', 'CUSTOMER', 'MERCHANT', 'COURIER'])('production route denies %s', role => {
    setup(provider(), auth([role]), true); expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable'); expect(screen.queryByRole('heading', { name: 'Zones and pricing' })).not.toBeInTheDocument()
  })
  it('production anonymous route uses shared login', () => { setup(provider(), anonymousAuth, true); expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument() })
  it.each(['session', 'denied'] as const)('handles backend %s independently and removes privileged content', async kind => {
    const api = provider(); api.zones.mockRejectedValue(new PricingError(kind)); const view = setup(api)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Create zone' })).not.toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent(kind === 'session' ? 'session expired' : 'denied')
    expect(view.current.handleSessionExpiry).toHaveBeenCalledTimes(kind === 'session' ? 1 : 0)
  })
  it('aborts outstanding reads and removes caches on logout', async () => {
    const api = provider(); api.zones.mockReturnValue(new Promise(() => {})); const view = setup(api); await waitFor(() => expect(api.zones).toHaveBeenCalled()); const signal = api.zones.mock.calls[0]![1]
    view.session(anonymousAuth); expect(signal.aborted).toBe(true); expect(view.cache.getQueryCache().findAll({ queryKey: ['private', 'admin-pricing'] })).toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Create zone' })).not.toBeInTheDocument()
  })
  it('resets selection, forms and confirmation on account change', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); expect(screen.getByRole('dialog')).toBeInTheDocument()
    view.session(auth(['ADMIN'], 'another-admin')); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('aborts an in-flight write on session expiry and suppresses late success', async () => {
    const api = provider(), pending = deferred<void>(); api.command.mockReturnValue(pending.promise)
    const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); await confirm(view.user)
    const signal = api.command.mock.calls[0]![1]; view.session(anonymousAuth); expect(signal.aborted).toBe(true)
    await act(async () => pending.resolve()); expect(screen.queryByText(/Pricing confirmed persistence/)).not.toBeInTheDocument()
  })
  it('uses the real token adapter instead of restricted self-profile fetching', async () => {
    const current = auth(), fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async input => new Response(JSON.stringify(String(input).includes('/zones?') ? pricingPage([zone]) : pricingPage([active, draft], 0, 2, String(input).includes('size=50') ? 50 : 20))))
    vi.stubGlobal('fetch', fetch)
    try {
      const cache = new QueryClient(); render(<QueryClientProvider client={cache}><AuthContext value={current}><MemoryRouter><PricingAdminPage /></MemoryRouter></AuthContext></QueryClientProvider>)
      await loaded(); expect(current.getAccessToken).toHaveBeenCalled(); expect(current.profileFetch).not.toHaveBeenCalled(); expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/v1/admin/pricing/'), expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer synthetic-memory-token' }) }))
    } finally { vi.unstubAllGlobals() }
  })
})
describe('Zone management', () => {
  it('renders real bounded records, status, metadata and revision', async () => {
    const { api } = setup(); await loaded()
    const table = screen.getByRole('table', { name: 'Server zone configuration' }); expect(table).toHaveTextContent(zone.id); expect(table).toHaveTextContent('Enabled'); expect(table).toHaveTextContent('24 hours'); expect(table).toHaveTextContent('3')
    expect(api.zones).toHaveBeenCalledWith(0, expect.any(AbortSignal))
  })
  it('shows empty zones truthfully', async () => { const api = provider(); api.zones.mockResolvedValue(pricingPage([])); setup(api); expect(await screen.findByText('No zones on this page.')).toBeInTheDocument() })
  it('shows loading without fake rows', async () => {
    const api = provider(); api.zones.mockReturnValue(new Promise(() => {})); setup(api)
    expect(screen.getAllByText('Loading server configuration…').length).toBeGreaterThan(0); expect(screen.queryByRole('button', { name: 'Edit ZONE_1' })).not.toBeInTheDocument()
  })
  it('allows manual retry of failed reads', async () => {
    const api = provider(); api.zones.mockRejectedValueOnce(new PricingError('unavailable')); const { user } = setup(api)
    await user.click(await screen.findByRole('button', { name: 'Retry read' })); await loaded(); expect(api.zones).toHaveBeenCalledTimes(2)
  })
  it('paginates with server bounds', async () => {
    const api = provider(); api.zones.mockImplementation(async page => pricingPage(page ? [] : [zone], page, 21)); const { user } = setup(api); await loaded()
    const pages = within(screen.getByRole('region', { name: 'Zone table' }).parentElement!).getByRole('navigation', { name: 'Configuration pages' })
    await user.click(within(pages).getByRole('button', { name: 'Next page' })); await waitFor(() => expect(api.zones).toHaveBeenCalledWith(1, expect.any(AbortSignal))); expect(await screen.findByRole('button', { name: 'Return to first page' })).toBeInTheDocument()
  })
  it('validates required fields and focuses the first error', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Create zone' })); await view.user.click(screen.getByRole('button', { name: 'Review changes' }))
    expect(screen.getByText('Zone code is required.')).toBeInTheDocument(); expect(screen.getByLabelText('Zone code')).toHaveFocus(); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('creates only after explicit confirmation and preserves trimmed fields', async () => {
    const view = setup(); await loaded(); await zoneForm(view.user); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(view.api.command).not.toHaveBeenCalled(); await confirm(view.user)
    await waitFor(() => expect(screen.getByText(/Pricing confirmed persistence/)).toBeInTheDocument()); expect(view.api.command).toHaveBeenCalledWith({ action: 'create-zone', input: { zoneCode: 'ZONE_NEW', name: 'New district', description: '', standardTransitHours: 24 } }, expect.any(AbortSignal))
    expect(view.api.zones.mock.calls.length).toBeGreaterThan(1)
  })
  it('cancel editing never mutates', async () => { const view = setup(); await loaded(); await zoneForm(view.user); await view.user.click(screen.getByRole('button', { name: 'Cancel editing' })); expect(view.api.command).not.toHaveBeenCalled(); expect(screen.queryByLabelText('Zone name')).not.toBeInTheDocument() })
  it('edits only supported zone fields with expected revision', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Edit ZONE_1' })); expect(screen.getByLabelText('Zone code')).toHaveAttribute('readonly')
    await view.user.clear(screen.getByLabelText('Zone name')); await view.user.type(screen.getByLabelText('Zone name'), 'Renamed district'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); await confirm(view.user)
    expect(view.api.command).toHaveBeenCalledWith(expect.objectContaining({ action: 'update-zone', zone: expect.objectContaining({ revision: 3 }), input: expect.objectContaining({ name: 'Renamed district' }) }), expect.any(AbortSignal))
  })
  it('rejects whitespace and fractional edit input', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Edit ZONE_1' })); await view.user.clear(screen.getByLabelText('Zone name')); await view.user.type(screen.getByLabelText('Zone name'), '  '); await view.user.clear(screen.getByLabelText('Standard transit hours')); await view.user.type(screen.getByLabelText('Standard transit hours'), '1.5'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(screen.getByLabelText('Zone name')).toHaveAttribute('aria-invalid', 'true'); expect(screen.getByLabelText('Standard transit hours')).toHaveAttribute('aria-invalid', 'true'); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('identifies the zone, explains blockers and returns focus on disable cancellation', async () => {
    const view = setup(); await loaded(); const disable = screen.getByRole('button', { name: 'Disable ZONE_1' }); await view.user.click(disable)
    expect(screen.getByRole('dialog')).toHaveTextContent(zone.id); expect(screen.getByRole('dialog')).toHaveTextContent('unexpired-quote references block disabling'); expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await view.user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(disable).toHaveFocus(); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('traps confirmation focus in both directions', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); const dialog = within(screen.getByRole('dialog'))
    await view.user.tab({ shift: true }); expect(dialog.getByRole('button', { name: 'Confirm action' })).toHaveFocus(); await view.user.tab(); expect(dialog.getByRole('button', { name: 'Cancel' })).toHaveFocus()
  })
  it('disables with the server revision', async () => { const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); await confirm(view.user); expect(view.api.command).toHaveBeenCalledWith({ action: 'disable', zone }, expect.any(AbortSignal)) })
  it('shows backend disable denial without success', async () => {
    const api = provider(); api.command.mockRejectedValue(new PricingError('validation', 'ZONE_REFERENCED')); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); await confirm(view.user)
    expect(await screen.findByText(/cannot be disabled while referenced/)).toBeInTheDocument(); expect(screen.queryByText(/Pricing confirmed persistence/)).not.toBeInTheDocument(); expect(api.command).toHaveBeenCalledTimes(1)
  })
  it('does not offer disable for an already disabled zone', async () => { const api = provider(); api.zones.mockResolvedValue(pricingPage([{ ...zone, active: false }])); setup(api); await loaded(); expect(screen.queryByRole('button', { name: 'Disable ZONE_1' })).not.toBeInTheDocument(); expect(screen.getByText('Disabled')).toBeInTheDocument() })
})
describe('Versioned rate management', () => {
  it('distinguishes current ACTIVE from unpublished DRAFT and historical snapshots', async () => {
    setup(); await loaded(); expect(screen.getByRole('region', { name: 'Current active pricing' })).toHaveTextContent(active.versionCode); expect(screen.getByText('Unpublished')).toBeInTheDocument(); expect(screen.getByText(/Accepted booking and receipt snapshots keep their original prices/)).toBeInTheDocument()
  })
  it('creates a draft without publishing', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Create draft' })); await view.user.type(screen.getByLabelText('Version code'), 'SYNTHETIC-V3'); await view.user.type(screen.getByLabelText('Effective from (UTC)'), '2020-01-01T00:00:00Z'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(screen.getByRole('dialog')).toHaveTextContent('does not affect quotes'); await confirm(view.user)
    expect(view.api.command).toHaveBeenCalledWith(expect.objectContaining({ action: 'draft', input: expect.objectContaining({ versionCode: 'SYNTHETIC-V3', currency: 'LKR', effectiveTo: null }) }), expect.any(AbortSignal))
  })
  it('rejects invalid effective intervals', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Create draft' })); await view.user.type(screen.getByLabelText('Version code'), 'V3'); await view.user.type(screen.getByLabelText('Effective from (UTC)'), '2026-10-28T00:00:00Z'); await view.user.type(screen.getByLabelText('Effective to (UTC) (optional)'), '2026-10-27T00:00:00Z'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(screen.getByText('The end must be after the start.')).toBeInTheDocument(); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('displays exact currency, units, rule count and allows draft edits', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: `Inspect ${draft.versionCode}` })); expect(await screen.findByRole('cell', { name: /LKR 350.00/ })).toBeInTheDocument(); expect(screen.getByText('LKR 150.00 / kg')).toBeInTheDocument(); expect(screen.getByRole('region', { name: 'Rate rules' })).toHaveTextContent('1 rules'); await view.user.click(screen.getByRole('button', { name: `Edit rule ${rule.id}` })); await view.user.clear(screen.getByLabelText('Base fee (LKR minor units)')); await view.user.type(screen.getByLabelText('Base fee (LKR minor units)'), '40000'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); await confirm(view.user)
    expect(view.api.command).toHaveBeenCalledWith(expect.objectContaining({ action: 'rule', ruleId: rule.id, card: expect.objectContaining({ revision: 7 }), input: expect.objectContaining({ baseFeeMinorUnits: '40000' }) }), expect.any(AbortSignal))
  })
  it('validates integer amounts and category weight bands before adding a rule', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: `Inspect ${draft.versionCode}` })); await view.user.click(await screen.findByRole('button', { name: 'Add draft rule' })); await view.user.type(screen.getByLabelText('Pickup zone code'), 'ZONE_1'); await view.user.type(screen.getByLabelText('Dropoff zone code'), 'ZONE_1'); await view.user.clear(screen.getByLabelText('Maximum weight (kg)')); await view.user.type(screen.getByLabelText('Maximum weight (kg)'), '2'); await view.user.type(screen.getByLabelText('Base fee (LKR minor units)'), '1.5'); await view.user.type(screen.getByLabelText('Surcharge per kg (LKR minor units)'), '0'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(screen.getByLabelText('Maximum weight (kg)')).toHaveAttribute('aria-invalid', 'true'); expect(screen.getByLabelText('Base fee (LKR minor units)')).toHaveAttribute('aria-invalid', 'true'); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('adds a new draft rule through the parent revision', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: `Inspect ${draft.versionCode}` })); await view.user.click(await screen.findByRole('button', { name: 'Add draft rule' })); await view.user.type(screen.getByLabelText('Pickup zone code'), 'ZONE_1'); await view.user.type(screen.getByLabelText('Dropoff zone code'), 'ZONE_1'); await view.user.type(screen.getByLabelText('Base fee (LKR minor units)'), '35000'); await view.user.type(screen.getByLabelText('Surcharge per kg (LKR minor units)'), '0'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); await confirm(view.user); expect(view.api.command).toHaveBeenCalledWith(expect.objectContaining({ action: 'rule', ruleId: undefined, card: draft }), expect.any(AbortSignal))
  })
  it.each(['ACTIVE', 'SUPERSEDED', 'ARCHIVED'] as const)('keeps %s rules immutable', async status => {
    const api = provider(), historical = { ...draft, status }; api.cards.mockResolvedValue(pricingPage([historical])); api.review.mockResolvedValue([historical]); const view = setup(api); await screen.findByRole('button', { name: `Inspect ${draft.versionCode}` }); await view.user.click(screen.getByRole('button', { name: `Inspect ${draft.versionCode}` })); expect(await screen.findByText('Historical rules are immutable')).toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Add draft rule' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /Edit rule/ })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /Review publication/ })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /Archive/ }) !== null).toBe(status === 'SUPERSEDED')
  })
  it('reviews publication, names the superseded version and preserves snapshots', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); const dialog = await screen.findByRole('dialog'); expect(dialog).toHaveTextContent(`ACTIVE ${active.versionCode}`); expect(dialog).toHaveTextContent('will be superseded'); expect(dialog).toHaveTextContent('booking and receipt snapshots retain their original prices'); expect(view.api.command).not.toHaveBeenCalled(); await confirm(view.user)
    expect(view.api.command).toHaveBeenCalledWith({ action: 'activate', card: draft, active }, expect.any(AbortSignal)); expect(await screen.findByText(/Pricing confirmed persistence/)).toBeInTheDocument()
  })
  it('keeps server publication denial separate from success', async () => {
    const api = provider(); api.command.mockRejectedValue(new PricingError('validation', 'INCOMPLETE_RATE_CARD')); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); await screen.findByRole('dialog'); await confirm(view.user); expect(await screen.findByText(/Publication requires complete/)).toBeInTheDocument(); expect(screen.queryByText(/Pricing confirmed persistence/)).not.toBeInTheDocument()
  })
  it('does not publish empty drafts', async () => {
    const api = provider(); api.rules.mockResolvedValue(pricingPage([])); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); expect(await screen.findByText(/Publication requires complete/)).toBeInTheDocument(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(api.command).not.toHaveBeenCalled()
  })
  it('archives only a superseded card after confirmation', async () => {
    const api = provider(), superseded = { ...active, status: 'SUPERSEDED' as const }; api.cards.mockResolvedValue(pricingPage([superseded, draft])); api.review.mockResolvedValue([superseded, draft]); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: `Archive ${active.versionCode}` })); expect(screen.getByRole('dialog')).toHaveTextContent('Historical rules and quote eligibility are preserved'); await confirm(view.user); expect(api.command).toHaveBeenCalledWith({ action: 'archive', card: superseded }, expect.any(AbortSignal))
  })
})
describe('Concurrency and uncertain writes', () => {
  it('locks synchronous double submissions and disables dialog escape while pending', async () => {
    const api = provider(), pending = deferred<void>(); api.command.mockReturnValue(pending.promise); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); const button = within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm action' }); await view.user.dblClick(button); expect(api.command).toHaveBeenCalledTimes(1); expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Submitting…' })).toBeDisabled(); await view.user.keyboard('{Escape}'); expect(screen.getByRole('dialog')).toBeInTheDocument(); await act(async () => pending.resolve())
  })
  it('preserves inputs on stale zone writes and requires reviewing the latest revision', async () => {
    const api = provider(); api.command.mockRejectedValueOnce(new PricingError('conflict', 'STALE_REVISION')); api.zone.mockResolvedValue({ ...zone, name: 'Concurrent name', revision: 4 }); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Edit ZONE_1' })); await view.user.clear(screen.getByLabelText('Zone name')); await view.user.type(screen.getByLabelText('Zone name'), 'My pending name'); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); await confirm(view.user)
    expect(await screen.findByText('Concurrent name')).toBeInTheDocument(); expect(screen.getByLabelText('Zone name')).toHaveValue('My pending name'); expect(screen.getByRole('button', { name: 'Review changes' })).toBeDisabled(); expect(api.command).toHaveBeenCalledTimes(1)
    await view.user.click(screen.getByRole('button', { name: 'Reapply to reviewed revision' })); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); expect(screen.getByRole('dialog')).toHaveTextContent('revision 4'); await confirm(view.user)
    expect(api.command.mock.calls[1]![0]).toMatchObject({ action: 'update-zone', zone: { revision: 4 }, input: { name: 'My pending name' } })
  })
  it('preserves recoverable validation inputs without success', async () => {
    const api = provider(); api.command.mockRejectedValue(new PricingError('validation', 'DUPLICATE_ZONE_CODE')); const view = setup(api); await loaded(); await zoneForm(view.user); await view.user.click(screen.getByRole('button', { name: 'Review changes' })); await confirm(view.user); expect(await screen.findByText('This zone code already exists.')).toBeInTheDocument(); expect(screen.getByLabelText('Zone code')).toHaveValue('ZONE_NEW'); expect(screen.queryByText(/Pricing confirmed persistence/)).not.toBeInTheDocument()
  })
  it('rejects a draft changed during publication preflight without opening confirmation', async () => {
    const api = provider(); const view = setup(api); await loaded(); api.review.mockResolvedValue([active, { ...draft, revision: 8 }]); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); expect(await screen.findByText(/Configuration changed or conflicts/)).toBeInTheDocument(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(api.command).not.toHaveBeenCalled()
  })
  it('invalidates publication confirmation after a canonical revision update', async () => {
    const view = setup(); await loaded(); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); await screen.findByRole('dialog')
    const key = view.cache.getQueryCache().findAll({ queryKey: ['private', 'admin-pricing'] }).find(q => q.queryKey.includes('catalogue'))!.queryKey
    await act(async () => { view.cache.setQueryData(key, [active, { ...draft, revision: 8 }]) })
    await waitFor(() => expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm action' })).toBeDisabled()); expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' })).toBeEnabled(); await view.user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' })); expect(view.api.command).not.toHaveBeenCalled()
  })
  it('requires a new review after server rejects an active-version race', async () => {
    const api = provider(); api.command.mockRejectedValue(new PricingError('conflict', 'ACTIVE_VERSION_CHANGED')); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: `Review publication ${draft.versionCode}` })); await screen.findByRole('dialog'); await confirm(view.user); expect(await screen.findByText(/active card changed/)).toBeInTheDocument(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(api.command).toHaveBeenCalledTimes(1)
  })
  it('reconciles an unknown outcome through reads and blocks further writes until deliberate review', async () => {
    const api = provider(); api.command.mockRejectedValue(new PricingError('unknown')); const view = setup(api); await loaded(); await view.user.click(screen.getByRole('button', { name: 'Disable ZONE_1' })); await confirm(view.user); expect(await screen.findByRole('heading', { name: 'Review the unknown outcome' })).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Create zone' })).toBeDisabled(); expect(screen.getByRole('button', { name: 'I reviewed the server state' })).toBeDisabled(); expect(api.command).toHaveBeenCalledTimes(1)
    await view.user.click(screen.getByRole('button', { name: 'Refresh configuration' })); await waitFor(() => expect(screen.getByRole('button', { name: 'I reviewed the server state' })).toBeEnabled()); await view.user.click(screen.getByRole('button', { name: 'I reviewed the server state' })); expect(screen.getByRole('button', { name: 'Create zone' })).toBeEnabled(); expect(api.command).toHaveBeenCalledTimes(1)
  })
})
