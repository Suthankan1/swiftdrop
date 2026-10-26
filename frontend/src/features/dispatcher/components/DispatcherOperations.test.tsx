import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../../../auth/context'
import { anonymousAuth } from '../../../test/authFixture'
import { DispatcherOperations } from './DispatcherOperations'
import { dashboardFixture, assignmentFixture } from '../testFixtures'

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const dashboard = (value = dashboardFixture) => json({ data: { dispatcherDashboard: value } })
const auth: AuthContextValue = { ...anonymousAuth, snapshot: { status: 'authenticated', subject: 'dispatcher-1', roles: ['DISPATCHER'] }, getAccessToken: async () => 'test-token' }
function setup(value = auth) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = (session: AuthContextValue) => <QueryClientProvider client={client}><AuthContext value={session}><DispatcherOperations key={session.snapshot.subject} /></AuthContext></QueryClientProvider>
  const view = render(tree(value))
  return { ...view, client, switchAccount: (session: AuthContextValue) => view.rerender(tree(session)) }
}
async function select() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Select SWD-TEST-1' }))
  await user.selectOptions(screen.getByLabelText('Eligible courier'), 'courier-1')
  return user
}
beforeEach(() => vi.stubGlobal('fetch', vi.fn<typeof fetch>()))
afterEach(() => vi.unstubAllGlobals())
describe('Dispatcher operations', () => {
  it('shows loading separately from an empty queue', async () => {
    let resolve!: (v: Response) => void
    vi.mocked(fetch).mockImplementation(() => new Promise(r => { resolve = r }))
    setup()
    expect(screen.getByRole('status')).toHaveTextContent('Loading queue')
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    await act(async () => resolve(dashboard({ ...dashboardFixture, unassignedParcels: [], availableCouriers: [] })))
    expect(await screen.findByText('No unassigned parcels on this page.')).toBeInTheDocument()
    expect(screen.getByText('No eligible couriers available.')).toBeInTheDocument()
  })
  it('shows workloads, provider eligibility and selected parcel details', async () => {
    vi.mocked(fetch).mockResolvedValue(dashboard())
    setup()
    await select()
    expect(screen.getByText(/Test Courier · 2 active assignments · Capacity 5 · Eligible/)).toBeInTheDocument()
    expect(screen.getByText(/Inactive Courier · 0 active assignments · Capacity 5 · Not eligible/)).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Inactive Courier/ })).not.toBeInTheDocument()
    expect(screen.getByText(/Parcel parcel-1 · Version 4/)).toBeInTheDocument()
    expect(screen.getByText('Assign SWD-TEST-1 to Test Courier?')).toBeInTheDocument()
  })
  it('requests next and previous pages from the server', async () => {
    vi.mocked(fetch).mockImplementation(async (_url, init) => {
      const page = JSON.parse(String(init?.body)).variables.pagination.page
      return dashboard({ ...dashboardFixture, pageInfo: { ...dashboardFixture.pageInfo, currentPage: page, hasNext: page === 0 } })
    })
    setup()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    expect(await screen.findByText(/Page 2 · 20 per page/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(await screen.findByText(/Page 1 · 20 per page/)).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('waits for acknowledgment, prevents duplicate commands and refreshes both projections', async () => {
    let resolve!: (v: Response) => void
    vi.mocked(fetch).mockResolvedValueOnce(dashboard()).mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValueOnce(dashboard({ ...dashboardFixture, unassignedParcels: [], availableCouriers: dashboardFixture.availableCouriers.map(c => ({ ...c, activeAssignmentsCount: 3 })) }))
    setup()
    const user = await select()
    await user.dblClick(screen.getByRole('button', { name: 'Confirm assignment' }))
    expect(screen.getByRole('button', { name: 'Assigning…' })).toBeDisabled()
    expect(screen.queryByText('Assigned SWD-TEST-1 to Test Courier.')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
    await act(async () => resolve(json(assignmentFixture)))
    expect(await screen.findByText('Assigned SWD-TEST-1 to Test Courier.')).toBeInTheDocument()
    expect(await screen.findByText(/Test Courier · 3 active assignments/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Select SWD-TEST-1' })).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it.each([409, 400, 422, 403, 404, 503])('clears selection and refreshes after command rejection %s', async status => {
    vi.mocked(fetch).mockResolvedValueOnce(dashboard()).mockResolvedValueOnce(json({}, status)).mockResolvedValueOnce(dashboard())
    setup()
    const user = await select()
    await user.click(screen.getByRole('button', { name: 'Confirm assignment' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Confirm courier assignment' })).not.toBeInTheDocument())
    expect(fetch).toHaveBeenCalledTimes(3)
    if (status === 409) expect(screen.getByRole('alert')).toHaveTextContent('already assigned')
  })
  it('clears selection on cancel without submitting', async () => {
    vi.mocked(fetch).mockResolvedValue(dashboard())
    setup()
    const user = await select()
    await user.click(screen.getByRole('button', { name: 'Cancel assignment' }))
    expect(screen.queryByLabelText('Eligible courier')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('requires a selected eligible courier', async () => {
    vi.mocked(fetch).mockResolvedValue(dashboard({ ...dashboardFixture, availableCouriers: [] }))
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Select SWD-TEST-1' }))
    expect(screen.getByRole('button', { name: 'Confirm assignment' })).toBeDisabled()
  })
  it('shows unavailable instead of an empty queue and can retry', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(json({ errors: [{ extensions: { code: 'UNAVAILABLE' } }], data: null })).mockResolvedValueOnce(dashboard())
    setup()
    expect(await screen.findByRole('alert')).toHaveTextContent('Operations provider unavailable')
    expect(screen.queryByText('No unassigned parcels on this page.')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Refresh operations' }))
    expect(await screen.findByRole('button', { name: 'Select SWD-TEST-1' })).toBeInTheDocument()
  })
  it('delegates unauthorized GraphQL responses to shared session expiry', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ errors: [{ extensions: { code: 'UNAUTHENTICATED' } }] }))
    const handleSessionExpiry = vi.fn(async () => {})
    setup({ ...auth, handleSessionExpiry })
    expect(await screen.findByRole('alert')).toHaveTextContent('session expired')
    expect(handleSessionExpiry).toHaveBeenCalledOnce()
  })
  it('aborts a pending old-account query and never renders its response', async () => {
    let resolve!: (v: Response) => void
    let oldSignal: AbortSignal | undefined | null
    vi.mocked(fetch).mockImplementationOnce((_url, init) => { oldSignal = init?.signal; return new Promise(r => { resolve = r }) }).mockResolvedValueOnce(dashboard({ ...dashboardFixture, unassignedParcels: [] }))
    const view = setup()
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    view.switchAccount({ ...auth, snapshot: { status: 'authenticated', subject: 'dispatcher-2', roles: ['DISPATCHER'] } })
    await act(async () => resolve(dashboard()))
    expect(oldSignal?.aborted).toBe(true)
    expect(await screen.findByText('No unassigned parcels on this page.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Select SWD-TEST-1' })).not.toBeInTheDocument()
  })
  it('aborts an in-flight command on unmount without publishing late success', async () => {
    let resolve!: (v: Response) => void
    let signal: AbortSignal | undefined | null
    vi.mocked(fetch).mockResolvedValueOnce(dashboard()).mockImplementationOnce((_url, init) => { signal = init?.signal; return new Promise(r => { resolve = r }) })
    const view = setup()
    const user = await select()
    await user.click(screen.getByRole('button', { name: 'Confirm assignment' }))
    view.unmount()
    await act(async () => resolve(json(assignmentFixture)))
    expect(signal?.aborted).toBe(true)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('refreshes after a network failure with unknown command outcome without replay', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(dashboard()).mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(dashboard())
    setup()
    const user = await select()
    await user.click(screen.getByRole('button', { name: 'Confirm assignment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('provider unavailable')
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3))
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/assignments'))).toHaveLength(1)
    expect(screen.queryByLabelText('Eligible courier')).not.toBeInTheDocument()
  })
  it('does not leave assignment controls enabled when canonical refresh fails', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(dashboard()).mockResolvedValueOnce(json(assignmentFixture)).mockResolvedValueOnce(json({}, 503))
    setup()
    const user = await select()
    await user.click(screen.getByRole('button', { name: 'Confirm assignment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('provider unavailable')
    expect(screen.queryByLabelText('Eligible courier')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Select SWD-TEST-1' })).not.toBeInTheDocument()
  })
})
