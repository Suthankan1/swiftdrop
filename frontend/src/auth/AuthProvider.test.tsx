import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import type Keycloak from 'keycloak-js'
import { AuthProvider } from './AuthProvider'
import { AuthSession } from './session'
import { useAuth } from './context'
import { AppRoutes } from '../app/router/routes'
import { CustomerPage } from '../features/customer/pages/CustomerPage'
import { customerFixture } from '../test/fixtures'
function setup(role = 'CUSTOMER', profileSubject = 'subject-a') {
  const client = { authenticated: true, token: 'memory-test', tokenParsed: { sub: 'subject-a', exp: 9999999999 },
    init: vi.fn().mockResolvedValue(true), updateToken: vi.fn().mockResolvedValue(false),
    hasRealmRole: (r: string) => r === role, isTokenExpired: () => false, clearToken: vi.fn(),
  }
  const session = new AuthSession(client as unknown as Keycloak)
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const request = vi.fn().mockResolvedValue(Response.json({ ...customerFixture, keycloakSub: profileSubject }))
  vi.stubGlobal('fetch', request)
  function Probe() {
    const auth = useAuth()
    return <><span>{auth.snapshot.status}</span><button onClick={() => { void auth.handleSessionExpiry() }}>Expire test session</button><CustomerPage /></>
  }
  return { client, session, cache, request, Probe }
}
describe('React auth integration', () => {
  it('initializes once across StrictMode mounts and clears private cache on expiry', async () => {
    const { client, session, cache, Probe } = setup()
    const userEvent = (await import('@testing-library/user-event')).default.setup()
    render(<StrictMode><QueryClientProvider client={cache}><AuthProvider session={session}><Probe /></AuthProvider></QueryClientProvider></StrictMode>)
    await screen.findByText(customerFixture.email)
    expect(client.init).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Expire test session' }))
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
    expect(cache.getQueryData(['private', 'profile', 'subject-a'])).toBeUndefined()
  })
  it('never displays a profile for another subject', async () => {
    const { session, cache, Probe, request } = setup('CUSTOMER', 'subject-b')
    render(<QueryClientProvider client={cache}><AuthProvider session={session}><Probe /></AuthProvider></QueryClientProvider>)
    await screen.findByText('Profile access unavailable')
    expect(request).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
  })
  it('blocks customer entry for another role without querying Accounts', async () => {
    const { session, cache, request } = setup('COURIER')
    render(<QueryClientProvider client={cache}><AuthProvider session={session}><MemoryRouter initialEntries={['/customer']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>)
    await screen.findByText('Access unavailable')
    expect(request).not.toHaveBeenCalled()
  })
  it('shows a genuine unavailable state for an absent Accounts provider', async () => {
    const { session, cache, request, Probe } = setup()
    request.mockRejectedValue(new TypeError('network failure'))
    render(<QueryClientProvider client={cache}><AuthProvider session={session}><Probe /></AuthProvider></QueryClientProvider>)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Profile temporarily unavailable'))
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
  })
})
