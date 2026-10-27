import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import { AuthContext, type AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth } from '../../../test/authFixture'
import { MerchantReviewPage } from './MerchantReviewPage'
import {
  merchantReviewHttpError,
  type MerchantReviewApi, type MerchantReviewDetail, type MerchantReviewPageData,
} from '../api/merchantReview'

// Synthetic presentation fixtures live only in tests. No runtime fixture adapter.
const merchant: MerchantReviewDetail = {
  id: 'synthetic-merchant-a', businessName: 'Synthetic business A', registrationNumber: 'TEST-A',
  status: 'PENDING', submittedAt: '2026-10-26T10:00:00Z', reviewToken: 'opaque-revision-a',
  actions: [{ action: 'approve' }],
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function auth(roles: readonly SwiftDropRole[] = ['ADMIN'], subject = 'synthetic-admin-a'): AuthContextValue {
  return { ...anonymousAuth, snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: vi.fn(async () => 'synthetic-memory-token'), handleSessionExpiry: vi.fn(async () => {}) }
}
function provider(detail = merchant) {
  return {
    list: vi.fn(async (page: number): Promise<MerchantReviewPageData> => ({ items: [detail], page, hasNext: false })),
    detail: vi.fn(async () => detail), supportedActions: ['approve', 'suspend'], change: vi.fn(async () => {}),
  } satisfies MerchantReviewApi
}
function setup(api?: MerchantReviewApi, initialAuth = auth(), route = false) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
  function tree(current: AuthContextValue, currentApi = api) {
    return <QueryClientProvider client={cache}><AuthContext value={current}>
      <MemoryRouter initialEntries={['/admin/merchants']}>
        {route ? <AppRoutes /> : <MerchantReviewPage api={currentApi} />}
      </MemoryRouter>
    </AuthContext></QueryClientProvider>
  }
  const view = render(tree(initialAuth))
  return { ...view, cache, rerenderAuth: (current: AuthContextValue, nextApi = api) => view.rerender(tree(current, nextApi)) }
}
async function openDetail() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: `Review ${merchant.businessName}` }))
  await screen.findByRole('heading', { name: merchant.businessName })
  return user
}
async function openConfirm(action = 'Approve merchant') {
  const user = await openDetail()
  await user.click(screen.getByRole('button', { name: action }))
  return { user, dialog: screen.getByRole('dialog') }
}

describe('Merchant review route and production boundary', () => {
  it('redirects anonymous navigation through the existing login route', () => {
    setup(undefined, anonymousAuth, true)
    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
  })
  it('waits for shared authentication', () => {
    setup(undefined, { ...anonymousAuth, snapshot: { status: 'loading', subject: null, roles: [] } }, true)
    expect(screen.getByRole('status')).toHaveTextContent('Checking session')
  })
  it.each<{ roles: SwiftDropRole[] }>([{ roles: ['CUSTOMER'] }, { roles: ['MERCHANT'] }, { roles: ['COURIER'] }, { roles: ['DISPATCHER'] }, { roles: [] }])('denies roles $roles at the route', ({ roles }) => {
    setup(undefined, auth(roles), true)
    expect(screen.getByRole('alert')).toHaveTextContent('Access unavailable')
    expect(screen.queryByRole('heading', { name: 'Merchant review' })).not.toBeInTheDocument()
  })
  it('does not treat pending enrollment or URL/storage flags as ADMIN', () => {
    sessionStorage.setItem('status', 'PENDING')
    localStorage.setItem('role', 'ADMIN')
    try {
      const api = provider()
      setup(api, auth(['CUSTOMER']))
      expect(screen.getByRole('alert')).toHaveTextContent('requires an authenticated ADMIN')
      expect(api.list).not.toHaveBeenCalled()
    } finally { sessionStorage.clear(); localStorage.clear() }
  })
  it('accepts a genuine ADMIN role alongside other roles', () => {
    setup(undefined, auth(['CUSTOMER', 'ADMIN']), true)
    expect(screen.getByRole('heading', { name: 'Merchant review' })).toBeInTheDocument()
  })
  it('connects to Accounts review service with authenticated Gateway requests when rendered in production routes', async () => {
    const fetchRequest = vi.fn(async () => new Response(JSON.stringify({ content: [], page: 0, last: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchRequest)
    setup(undefined, auth(), true)
    await screen.findByRole('heading', { name: 'Merchant applications' })
    expect(fetchRequest).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/admin/merchants?page=0&size=20'),
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer synthetic-memory-token',
        }),
      }),
    )
  })
  it('renders unavailable fallback when review API is explicitly unavailable', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthContext value={auth()}>
          <MemoryRouter>
            <MerchantReviewPage api={null as any} />
          </MemoryRouter>
        </AuthContext>
      </QueryClientProvider>,
    )
    expect(screen.getByRole('heading', { name: 'Merchant review unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Suspend merchant' })).toBeDisabled()
  })
})

describe('Merchant review reads and pagination', () => {
  it('shows loading, then empty data and deterministic page controls', async () => {
    const waiting = deferred<MerchantReviewPageData>()
    const api = { ...provider(), list: vi.fn(() => waiting.promise) }
    setup(api)
    expect(screen.getByRole('status')).toHaveTextContent('Loading merchant applications')
    await act(async () => waiting.resolve({ items: [], page: 0, hasNext: false }))
    expect(await screen.findByText('No merchant applications on this page.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(api.list).toHaveBeenCalledWith(0, 20, expect.any(AbortSignal))
  })
  it('requests server pages, clears selection and never shows a previous page as new data', async () => {
    const next = deferred<MerchantReviewPageData>()
    const api = { ...provider(), list: vi.fn(async (page: number) => page === 0
      ? { items: [merchant], page, hasNext: true } : next.promise) }
    setup(api)
    const user = await openDetail()
    const detailSignal = api.detail.mock.calls[0]
    expect(detailSignal).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    expect(screen.queryByRole('heading', { name: 'Merchant details' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Review Synthetic business A/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh list' })).toBeDisabled()
    await act(async () => next.resolve({ items: [], page: 1, hasNext: false }))
    await screen.findByText('Page 2')
    expect(api.list).toHaveBeenLastCalledWith(1, 20, expect.any(AbortSignal))
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(await screen.findByRole('button', { name: /Review Synthetic business A/ })).toBeInTheDocument()
  })
  it('distinguishes all statuses in text and projects only safe fields', async () => {
    const items = (['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED', 'UNKNOWN'] as const).map((status, i) => ({
      ...merchant, status, id: `synthetic-${i}`, businessName: `Synthetic ${i}`, apiKey: 'SECRET-MUST-NOT-RENDER',
    }))
    setup({ ...provider(), list: async () => ({ items, page: 0, hasNext: false }) })
    const table = await screen.findByRole('table')
    for (const status of ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED', 'UNKNOWN']) expect(within(table).getByText(status)).toBeInTheDocument()
    expect(screen.queryByText('SECRET-MUST-NOT-RENDER')).not.toBeInTheDocument()
  })
  it('sanitizes a network/read failure and retries only after explicit user action', async () => {
    const api = provider()
    api.list.mockRejectedValueOnce(new Error('sensitive backend stack trace'))
    setup(api)
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    expect(screen.queryByText(/sensitive backend/)).not.toBeInTheDocument()
    expect(api.list).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Retry list' }))
    await screen.findByRole('table')
    expect(api.list).toHaveBeenCalledTimes(2)
  })
  it('rejects wrong page metadata instead of mislabeling records', async () => {
    setup({ ...provider(), list: async () => ({ items: [merchant], page: 9, hasNext: false }) })
    expect(await screen.findByRole('alert')).toHaveTextContent('unsupported response')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
  it('focuses details and shows missing audit without manufacturing an approval timeline', async () => {
    setup(provider())
    const user = await openDetail()
    expect(screen.getByRole('heading', { name: 'Merchant details' })).toHaveFocus()
    expect(screen.getByText('Audit history is unavailable from the review service.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Suspend merchant' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Close details' }))
    expect(screen.getByRole('button', { name: `Review ${merchant.businessName}` })).toHaveFocus()
  })
  it('shows only explicitly supplied audit metadata and no secrets from extra fields', async () => {
    const detail = { ...merchant, audit: [{ id: 'audit-a', actor: 'Synthetic operator', action: 'APPROVED',
      occurredAt: '2026-10-27T10:00:00Z', reason: 'Synthetic reviewed evidence', digest: 'SECRET-DIGEST' }], token: 'SECRET-TOKEN' }
    setup(provider(detail))
    await openDetail()
    expect(screen.getByRole('list')).toHaveTextContent('Synthetic operator')
    expect(screen.getByRole('list')).toHaveTextContent('Synthetic reviewed evidence')
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
  })
  it('shows an exposed empty audit separately from an unavailable audit', async () => {
    setup(provider({ ...merchant, audit: [] }))
    await openDetail()
    expect(screen.getByText('No audit records were returned.')).toBeInTheDocument()
  })
  it.each([403, 404, 500])('handles detail HTTP %s without stale actions', async status => {
    const api = provider()
    api.detail.mockRejectedValueOnce(merchantReviewHttpError(status))
    setup(api)
    await userEvent.click(await screen.findByRole('button', { name: /Review Synthetic business A/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent(merchantReviewHttpError(status).message)
    expect(screen.queryByRole('button', { name: 'Approve merchant' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry details' }))
    expect(await screen.findByRole('button', { name: 'Approve merchant' })).toBeEnabled()
  })
  it('rejects a mismatched merchant detail response', async () => {
    setup({ ...provider(), detail: async () => ({ ...merchant, id: 'another-merchant' }) })
    await userEvent.click(await screen.findByRole('button', { name: /Review Synthetic business A/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('unsupported response')
    expect(screen.queryByText('another-merchant')).not.toBeInTheDocument()
  })
  it('uses shared session expiry handling for a 401 read', async () => {
    const current = auth()
    const api = provider()
    api.list.mockRejectedValueOnce(merchantReviewHttpError(401))
    setup(api, current)
    await waitFor(() => expect(current.handleSessionExpiry).toHaveBeenCalledOnce())
    expect(api.list).toHaveBeenCalledTimes(1)
  })
})

describe('Merchant review confirmation and commands (isolated port only)', () => {
  it('names the target, traps keyboard focus, cancels on Escape and restores focus', async () => {
    const api = provider()
    const view = setup(api)
    const { user, dialog } = await openConfirm()
    expect(view.container.inert).toBe(true)
    expect(dialog).toHaveTextContent(merchant.businessName)
    expect(dialog).toHaveTextContent(merchant.id)
    expect(dialog).toHaveTextContent(merchant.registrationNumber!)
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.tab({ shift: true })
    expect(within(dialog).getByRole('button', { name: 'Confirm approve' })).toHaveFocus()
    await user.tab()
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toHaveFocus()
    expect(view.container.inert).toBe(false)
    expect(api.change).not.toHaveBeenCalled()
  })
  it('waits for real command resolution, prevents duplicate submission and refreshes list/details', async () => {
    const result = deferred<void>()
    let current = merchant
    const api: MerchantReviewApi = { ...provider(), detail: vi.fn(async () => current), change: vi.fn(() => result.promise) }
    setup(api)
    const { user, dialog } = await openConfirm()
    const confirm = within(dialog).getByRole('button', { name: 'Confirm approve' })
    await user.dblClick(confirm)
    expect(api.change).toHaveBeenCalledTimes(1)
    expect(api.change).toHaveBeenCalledWith({ merchantId: merchant.id, action: 'approve', reviewToken: merchant.reviewToken }, expect.any(AbortSignal))
    expect(screen.queryByText(/server accepted/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submitting…' })).toBeDisabled()
    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    current = { ...merchant, status: 'APPROVED', actions: [] }
    await act(async () => result.resolve())
    expect(await screen.findByText(/server accepted/)).toBeInTheDocument()
    await waitFor(() => expect(api.detail).toHaveBeenCalledTimes(2))
    expect(api.list).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  it.each(['APPROVED', 'SUSPENDED', 'REJECTED', 'UNKNOWN'] as const)('cannot approve %s even with an advertised action', async status => {
    setup(provider({ ...merchant, status }))
    await openDetail()
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
  })
  it('requires a supported command and authoritative concurrency evidence', async () => {
    setup(provider({ ...merchant, reviewToken: undefined }))
    await openDetail()
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
  })
  it('does not offer a command absent from the integrated provider capabilities', async () => {
    setup({ ...provider(), supportedActions: [], change: undefined })
    await openDetail()
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Suspend merchant' })).toBeDisabled()
  })
  it('requires the provider reason policy and sends a trimmed suspension reason', async () => {
    const api = provider({ ...merchant, status: 'APPROVED', actions: [{ action: 'suspend', reason: { required: true, maxLength: 80 } }] })
    setup(api)
    const { user, dialog } = await openConfirm('Suspend merchant')
    const confirm = within(dialog).getByRole('button', { name: 'Confirm suspend' })
    expect(confirm).toBeDisabled()
    const reason = within(dialog).getByRole('textbox', { name: 'Reason (required)' })
    expect(reason).toHaveAttribute('maxlength', '80')
    await user.type(reason, '  Reviewed synthetic evidence  ')
    await user.click(confirm)
    await waitFor(() => expect(api.change).toHaveBeenCalledWith(expect.objectContaining({ action: 'suspend', reason: 'Reviewed synthetic evidence' }), expect.any(AbortSignal)))
  })
  it.each([400, 401, 403, 404, 409, 422, 500])('sanitizes command HTTP %s, refreshes without replay and requires a new confirmation', async status => {
    const api = provider()
    api.change.mockRejectedValueOnce(merchantReviewHttpError(status))
    const current = auth()
    setup(api, current)
    const { user, dialog } = await openConfirm()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm approve' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(status === 500 ? 'outcome could not be confirmed' : merchantReviewHttpError(status).message)
    await waitFor(() => expect(api.detail).toHaveBeenCalledTimes(2))
    expect(api.list).toHaveBeenCalledTimes(2)
    expect(api.change).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/server accepted/)).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    if (status === 401) expect(current.handleSessionExpiry).toHaveBeenCalledOnce()
    if (status === 409) {
      await user.click(screen.getByRole('button', { name: 'Approve merchant' }))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(api.change).toHaveBeenCalledTimes(1)
    }
  })
  it('never exposes raw unexpected command errors or reports success', async () => {
    const api = provider()
    api.change.mockRejectedValueOnce(new Error('SECRET raw backend credentials'))
    setup(api)
    const { user, dialog } = await openConfirm()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm approve' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('outcome could not be confirmed')
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
    expect(screen.queryByText(/server accepted/)).not.toBeInTheDocument()
  })
  it('waits for a fresh conflict read and submits only the newly reviewed concurrency token', async () => {
    const refreshed = deferred<MerchantReviewDetail>()
    const api = provider()
    api.detail.mockImplementationOnce(async () => merchant).mockImplementationOnce(() => refreshed.promise)
    api.change.mockRejectedValueOnce(merchantReviewHttpError(409))
    setup(api)
    const { user, dialog } = await openConfirm()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm approve' }))
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeDisabled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    api.detail.mockResolvedValue({ ...merchant, reviewToken: 'fresh-revision' })
    await act(async () => refreshed.resolve({ ...merchant, reviewToken: 'fresh-revision' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve merchant' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Approve merchant' }))
    expect(api.change).toHaveBeenCalledTimes(1)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm approve' }))
    await waitFor(() => expect(api.change).toHaveBeenLastCalledWith(expect.objectContaining({ reviewToken: 'fresh-revision' }), expect.any(AbortSignal)))
  })
  it('hides stale actions if authoritative refresh fails after server success', async () => {
    const api = provider()
    api.detail.mockResolvedValueOnce(merchant).mockRejectedValueOnce(merchantReviewHttpError(503))
    setup(api)
    const { user, dialog } = await openConfirm()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm approve' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    expect(screen.queryByRole('button', { name: 'Approve merchant' })).not.toBeInTheDocument()
    expect(api.change).toHaveBeenCalledTimes(1)
  })
})

describe('Merchant review session and stale response isolation', () => {
  it('aborts a pending list and removes its private cache on logout', async () => {
    const waiting = deferred<MerchantReviewPageData>()
    let signal: AbortSignal | undefined
    const api: MerchantReviewApi = { ...provider(), list: vi.fn((_page, _size, current) => { signal = current; return waiting.promise }) }
    const view = setup(api)
    view.rerenderAuth(anonymousAuth)
    expect(signal?.aborted).toBe(true)
    await act(async () => waiting.resolve({ items: [merchant], page: 0, hasNext: false }))
    expect(screen.queryByText(merchant.businessName)).not.toBeInTheDocument()
    expect(view.cache.getQueryCache().getAll()).toHaveLength(0)
  })
  it('does not reuse another subject or a new same-subject session cache', async () => {
    const api = provider()
    const view = setup(api)
    await openDetail()
    view.rerenderAuth(auth(['ADMIN'], 'synthetic-admin-b'))
    await screen.findByRole('table')
    expect(screen.queryByRole('heading', { name: merchant.businessName })).not.toBeInTheDocument()
    expect(api.list).toHaveBeenCalledTimes(2)
    const keys = view.cache.getQueryCache().getAll().map(item => item.queryKey)
    expect(keys.every(key => key.includes('synthetic-admin-b'))).toBe(true)
    view.rerenderAuth(anonymousAuth)
    view.rerenderAuth(auth(['ADMIN'], 'synthetic-admin-b'))
    await screen.findByRole('table')
    expect(api.list).toHaveBeenCalledTimes(3)
  })
  it('ignores a detail response that arrives after selecting another merchant', async () => {
    const waiting = deferred<MerchantReviewDetail>()
    const other = { ...merchant, id: 'synthetic-b', businessName: 'Synthetic business B' }
    let signal: AbortSignal | undefined
    const api: MerchantReviewApi = { ...provider(), list: async () => ({ items: [merchant, other], page: 0, hasNext: false }),
      detail: vi.fn(async (id, current) => { if (id === merchant.id) { signal = current; return waiting.promise } return other }) }
    setup(api)
    await userEvent.click(await screen.findByRole('button', { name: `Review ${merchant.businessName}` }))
    await userEvent.click(screen.getByRole('button', { name: `Review ${other.businessName}` }))
    expect(await screen.findByRole('heading', { name: other.businessName })).toBeInTheDocument()
    expect(signal?.aborted).toBe(true)
    await act(async () => waiting.resolve(merchant))
    expect(screen.queryByRole('heading', { name: merchant.businessName })).not.toBeInTheDocument()
  })
  it('aborts an in-flight command and ignores late success after same-subject role revocation', async () => {
    const waiting = deferred<void>()
    let signal: AbortSignal | undefined
    const api: MerchantReviewApi = { ...provider(), change: vi.fn((_command, current) => { signal = current; return waiting.promise }) }
    const view = setup(api)
    const { user, dialog } = await openConfirm()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm approve' }))
    view.rerenderAuth(auth(['CUSTOMER']))
    expect(signal?.aborted).toBe(true)
    await act(async () => waiting.resolve())
    expect(screen.queryByText(/server accepted/)).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(view.cache.getQueryCache().getAll()).toHaveLength(0)
  })
})
