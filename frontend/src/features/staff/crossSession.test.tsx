import { render, screen, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../../auth/context'
import { CourierPage } from '../courier/pages/CourierPage'
import { DispatcherPage } from '../dispatcher/pages/DispatcherPage'
import { courierStaffFixture, dispatcherStaffFixture } from '../../test/fixtures'
import type { StaffProfile } from './api/contracts'

const courierA: StaffProfile = {
  ...courierStaffFixture,
  keycloakSub: 'sub-courier-a',
  displayName: 'Courier Alpha',
  staffId: 'STF-ALPHA-01',
}

const courierB: StaffProfile = {
  ...courierStaffFixture,
  keycloakSub: 'sub-courier-b',
  displayName: 'Courier Bravo',
  staffId: 'STF-BRAVO-02',
}

function createAuthContext(
  subject: string | null,
  roles: ('COURIER' | 'DISPATCHER')[] = ['COURIER'],
  fetchFn?: typeof fetch,
): AuthContextValue {
  return {
    snapshot: subject
      ? { status: 'authenticated', subject, roles }
      : { status: 'unauthenticated', subject: null, roles: [] },
    problem: null,
    getAccessToken: vi.fn(async () => (subject ? `token-${subject}` : null)),
    login: vi.fn(async () => {}),
    logout: vi.fn(async () => {}),
    register: vi.fn(async () => {}),
    googleLogin: vi.fn(async () => {}),
    handleSessionExpiry: vi.fn(async () => {}),
    profileFetch: fetchFn ?? vi.fn<typeof fetch>(),
  }
}

describe('Staff cross-session isolation and regression tests', () => {
  it('clears profile on logout and does not leak Courier A private details to Courier B', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const fetchA = vi.fn(async () => new Response(JSON.stringify(courierA), { status: 200 }))
    const authA = createAuthContext('sub-courier-a', ['COURIER'], fetchA)

    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authA}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    // Courier A profile is rendered
    expect(await screen.findByRole('heading', { level: 2, name: courierA.displayName })).toBeInTheDocument()
    expect(screen.getByText(courierA.staffId)).toBeInTheDocument()

    // Courier A logs out
    queryClient.cancelQueries()
    queryClient.clear()
    const loggedOutAuth = createAuthContext(null, [])

    rerender(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={loggedOutAuth}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    expect(screen.queryByText(courierA.displayName)).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/staff space requires sign-in/i)

    // Courier B logs in
    const fetchB = vi.fn(async () => new Response(JSON.stringify(courierB), { status: 200 }))
    const authB = createAuthContext('sub-courier-b', ['COURIER'], fetchB)

    rerender(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authB}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { level: 2, name: courierB.displayName })).toBeInTheDocument()
    expect(screen.getByText(courierB.staffId)).toBeInTheDocument()
    expect(screen.queryByText(courierA.displayName)).not.toBeInTheDocument()
    expect(screen.queryByText(courierA.staffId)).not.toBeInTheDocument()
  })

  it('ignores a pending slow request from previous subject after subject switch', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    let resolveSlowFetchA!: (res: Response) => void
    const slowFetchPromise = new Promise<Response>((resolve) => {
      resolveSlowFetchA = resolve
    })
    const slowFetchA = vi.fn(() => slowFetchPromise)

    const authA = createAuthContext('sub-courier-a', ['COURIER'], slowFetchA)

    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authA}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    expect(screen.getByRole('status')).toHaveTextContent(/loading staff details/i)

    // Subject switches to Courier B before Courier A response resolves
    queryClient.cancelQueries()
    queryClient.clear()

    const fetchB = vi.fn(async () => new Response(JSON.stringify(courierB), { status: 200 }))
    const authB = createAuthContext('sub-courier-b', ['COURIER'], fetchB)

    rerender(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authB}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    // Now resolve Courier A's slow fetch
    await act(async () => {
      resolveSlowFetchA(new Response(JSON.stringify(courierA), { status: 200 }))
    })

    // Courier B is rendered, never Courier A
    expect(await screen.findByRole('heading', { level: 2, name: courierB.displayName })).toBeInTheDocument()
    expect(screen.queryByText(courierA.displayName)).not.toBeInTheDocument()
    expect(screen.queryByText(courierA.staffId)).not.toBeInTheDocument()
  })

  it('resets query cache and presents correct role profile during cross-role switch', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const fetchCourier = vi.fn(async () => new Response(JSON.stringify(courierA), { status: 200 }))
    const authCourier = createAuthContext('sub-courier-a', ['COURIER'], fetchCourier)

    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authCourier}>
          <CourierPage />
        </AuthContext>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { level: 2, name: courierA.displayName })).toBeInTheDocument()

    // Dispatcher signs in
    queryClient.cancelQueries()
    queryClient.clear()

    const fetchDispatcher = vi.fn(async () => new Response(JSON.stringify(dispatcherStaffFixture), { status: 200 }))
    const authDispatcher = createAuthContext(dispatcherStaffFixture.keycloakSub, ['DISPATCHER'], fetchDispatcher)

    rerender(
      <QueryClientProvider client={queryClient}>
        <AuthContext value={authDispatcher}>
          {/* DispatcherPage links to its reports route, so it needs router context as in the app. */}
          <MemoryRouter><DispatcherPage /></MemoryRouter>
        </AuthContext>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { level: 2, name: dispatcherStaffFixture.displayName })).toBeInTheDocument()
    expect(screen.getByText(dispatcherStaffFixture.staffId)).toBeInTheDocument()
    expect(screen.queryByText(courierA.displayName)).not.toBeInTheDocument()
    expect(screen.queryByText(courierA.staffId)).not.toBeInTheDocument()
  })
})
