import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { merchantFixture } from '../../../test/fixtures'
import type { WebhookSubscription } from '../api/webhookContracts'

const approvedMerchantData = {
  ...merchantFixture,
  merchantProfile: {
    merchantId: 'merch-approved-01',
    businessName: 'Lanka Logistics Hub',
    registrationNumber: 'PV-009876',
    status: 'APPROVED',
  },
}

const pendingMerchantData = {
  ...merchantFixture,
  merchantProfile: {
    merchantId: 'merch-pending-01',
    businessName: 'Colombo Wholesale Store',
    registrationNumber: 'PV-001234',
    status: 'PENDING',
  },
}

const mockSubscription1: WebhookSubscription = {
  subscriptionId: 'sub-uuid-001',
  merchantId: 'merch-approved-01',
  httpsUrl: 'https://warehouse.example.com/api/v1/swiftdrop-callbacks',
  allowedEventTypes: ['parcel.status.changed.v1'],
  active: true,
  createdAt: '2026-10-24T00:00:00Z',
}

function authenticatedMerchant(
  roles: readonly SwiftDropRole[] = ['MERCHANT'],
  profileData = approvedMerchantData,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject: profileData.keycloakSub, roles },
    getAccessToken: async () => 'test-bearer-token',
    profileFetch: vi.fn(async () => new Response(JSON.stringify(profileData))),
  }
}

function renderWebhooks(auth: AuthContextValue, route = '/merchant/webhooks') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Merchant Webhook Subscription UI (Issue #21)', () => {
  it('redirects unauthenticated users to login without provider requests', () => {
    const globalFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', globalFetch)

    renderWebhooks(anonymousAuth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /event webhooks/i })).not.toBeInTheDocument()
    expect(globalFetch).not.toHaveBeenCalled()
  })

  it('denies non-MERCHANT roles without rendering webhooks or making communications calls', () => {
    const globalFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', globalFetch)

    renderWebhooks(authenticatedMerchant(['CUSTOMER']))

    expect(screen.getByRole('alert')).toHaveTextContent(/access unavailable/i)
    expect(screen.queryByRole('heading', { name: /event webhooks/i })).not.toBeInTheDocument()
    expect(globalFetch).not.toHaveBeenCalled()
  })

  it('locks webhook configuration for PENDING merchants per DEC-MER-03', async () => {
    const globalFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], pendingMerchantData)
    renderWebhooks(auth)

    // Wait for profile resolution and pending banner
    expect(await screen.findByRole('status', { name: /approval status: pending/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Webhook Management Locked' })).toBeInTheDocument()
    expect(screen.getByText(/DEC-MER-03/i)).toBeInTheDocument()

    // Communications API should never be queried when not approved
    expect(globalFetch).not.toHaveBeenCalled()
  })

  it('renders empty subscription state when approved merchant has no registered endpoints', async () => {
    const globalFetch = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.includes('/api/v1/merchants/webhooks')) {
        return new Response(JSON.stringify({ subscriptions: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    expect(await screen.findByRole('heading', { name: 'No Webhook Subscriptions Active' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Register Webhook Destination' })).toBeInTheDocument()
  })


  it('renders registered webhook subscriptions with details, badges, and action buttons', async () => {
    const globalFetch = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.includes('/api/v1/merchants/webhooks')) {
        return new Response(JSON.stringify({ subscriptions: [mockSubscription1] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    expect(await screen.findByRole('heading', { name: /configured webhooks \(1\)/i })).toBeInTheDocument()
    expect(screen.getByText(mockSubscription1.httpsUrl)).toBeInTheDocument()
    expect(screen.getByText('parcel.status.changed.v1')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delivery Logs' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument()
  })

  it('validates destination URL and rejects non-HTTPS or SSRF private destinations', async () => {
    const user = userEvent.setup()
    const globalFetch = vi.fn<typeof fetch>(async () =>
      new Response(JSON.stringify({ subscriptions: [] }), { status: 200 }),
    )
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    const openBtn = await screen.findByRole('button', { name: 'Register Webhook Destination' })
    await user.click(openBtn)

    expect(screen.getByRole('heading', { name: 'Register Webhook Destination' })).toBeInTheDocument()
    const urlInput = screen.getByLabelText(/https callback destination url/i)

    // 1. Try plain HTTP
    await user.type(urlInput, 'http://api.merchant.com/callbacks')
    await user.click(screen.getByRole('button', { name: 'Register Destination' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/only secure https/i)

    // 2. Try localhost / private IP
    await user.clear(urlInput)
    await user.type(urlInput, 'https://localhost:8080/webhook')
    await user.click(screen.getByRole('button', { name: 'Register Destination' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/localhost and private/i)

    // 3. Try RFC 1918 private IPv4
    await user.clear(urlInput)
    await user.type(urlInput, 'https://192.168.1.100/webhook')
    await user.click(screen.getByRole('button', { name: 'Register Destination' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/private and link-local ip/i)
  })

  it('creates subscription, displays one-time signing secret, and allows copying/dismissing', async () => {
    const user = userEvent.setup()
    let currentSubscriptions: WebhookSubscription[] = []

    const globalFetch = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      const method = init?.method || 'GET'

      if (url.endsWith('/api/v1/merchants/webhooks') && method === 'GET') {
        return new Response(JSON.stringify({ subscriptions: currentSubscriptions }), { status: 200 })
      }

      if (url.endsWith('/api/v1/merchants/webhooks') && method === 'POST') {
        const body = JSON.parse(String(init?.body))
        const created: WebhookSubscription = {
          subscriptionId: 'sub-new-123',
          merchantId: 'merch-approved-01',
          httpsUrl: body.httpsUrl,
          allowedEventTypes: body.allowedEventTypes,
          active: true,
          createdAt: new Date().toISOString(),
        }
        currentSubscriptions = [created]
        return new Response(
          JSON.stringify({
            ...created,
            hmacSigningSecret: 'mock-whsec-secret-abcdef123456',
          }),
          { status: 201 },
        )
      }

      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    // Open form
    await user.click(await screen.findByRole('button', { name: 'Register Webhook Destination' }))

    // Type valid HTTPS URL
    const urlInput = screen.getByLabelText(/https callback destination url/i)
    await user.type(urlInput, 'https://warehouse.swiftdrop-partner.lk/api/webhooks')

    // Submit registration
    await user.click(screen.getByRole('button', { name: 'Register Destination' }))

    // Expect One-Time Secret Alert
    expect(await screen.findByRole('alert', { name: /one-time webhook signing secret/i })).toBeInTheDocument()
    expect(screen.getByTestId('webhook-signing-secret')).toHaveTextContent('mock-whsec-secret-abcdef123456')


    // Click copy button
    const copyBtn = screen.getByRole('button', { name: /copy/i })
    await user.click(copyBtn)
    expect(screen.getByText(/✓ copied!/i)).toBeInTheDocument()

    // Dismiss secret
    const dismissBtn = screen.getByRole('button', { name: /i have saved my secret/i })
    await user.click(dismissBtn)

    // Secret must be unmounted and no longer in DOM
    expect(screen.queryByTestId('webhook-signing-secret')).not.toBeInTheDocument()

    // Subscriptions list must now show the newly created endpoint
    expect(await screen.findByText('https://warehouse.swiftdrop-partner.lk/api/webhooks')).toBeInTheDocument()
  })

  it('opens delivery logs modal and displays attempt history', async () => {
    const user = userEvent.setup()

    const mockDeliveries = {
      totalElements: 1,
      deliveries: [
        {
          deliveryId: 'deliv-test-01',
          eventId: 'evt-test-01',
          parcelId: 'parcel-test-01',
          status: 'SUCCESS',
          attemptCount: 1,
          lastAttemptAt: '2026-10-24T00:10:00Z',
          lastHttpStatusCode: 200,
          lastResponseTimeMs: 145,
          errorSummary: null,
        },
      ],
    }

    const globalFetch = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.includes('/deliveries')) {
        return new Response(JSON.stringify(mockDeliveries), { status: 200 })
      }
      if (url.includes('/api/v1/merchants/webhooks')) {
        return new Response(JSON.stringify({ subscriptions: [mockSubscription1] }), { status: 200 })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    const logsBtn = await screen.findByRole('button', { name: 'Delivery Logs' })
    await user.click(logsBtn)

    // Dialog opens
    expect(await screen.findByRole('dialog', { name: /webhook delivery logs/i })).toBeInTheDocument()
    expect(screen.getByText('SUCCESS')).toBeInTheDocument()
    expect(screen.getByText('145 ms')).toBeInTheDocument()
    expect(screen.getByText('200')).toBeInTheDocument()
    expect(screen.getByText(/third-party response bodies are intentionally omitted/i)).toBeInTheDocument()

    // Close dialog
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('prompts confirmation modal before deactivating a subscription', async () => {
    const user = userEvent.setup()
    let deleted = false

    const globalFetch = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      const method = init?.method || 'GET'

      if (url.endsWith('/api/v1/merchants/webhooks') && method === 'GET') {
        return new Response(JSON.stringify({ subscriptions: deleted ? [] : [mockSubscription1] }), { status: 200 })
      }
      if (url.includes('/api/v1/merchants/webhooks/sub-uuid-001') && method === 'DELETE') {
        deleted = true
        return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    const deactivateBtn = await screen.findByRole('button', { name: 'Deactivate' })
    await user.click(deactivateBtn)

    // Modal appears
    const modal = await screen.findByRole('dialog', { name: /deactivate webhook endpoint/i })
    expect(within(modal).getByText(mockSubscription1.httpsUrl)).toBeInTheDocument()

    // Confirm deletion
    await user.click(within(modal).getByRole('button', { name: 'Confirm Deactivation' }))


    // Refetches and renders empty state
    expect(await screen.findByRole('heading', { name: 'No Webhook Subscriptions Active' })).toBeInTheDocument()
  })

  it('surfaces service unavailable state when Communications backend is unreachable', async () => {
    const globalFetch = vi.fn<typeof fetch>(async () => {
      throw new Error('Connection refused')
    })
    vi.stubGlobal('fetch', globalFetch)

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantData)
    renderWebhooks(auth)

    expect(await screen.findByRole('heading', { name: 'Webhook service unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry connection' })).toBeInTheDocument()
  })
})
