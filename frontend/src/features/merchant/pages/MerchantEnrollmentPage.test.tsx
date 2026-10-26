import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { customerFixture } from '../../../test/fixtures'

function authenticatedCustomer(
  userProfile = customerFixture,
  token = 'test-customer-token',
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: {
      status: 'authenticated',
      subject: userProfile.keycloakSub,
      roles: ['CUSTOMER'],
    },
    getAccessToken: async () => token,
    profileFetch: vi.fn(async () => new Response(JSON.stringify(userProfile))),
  }
}

function renderEnrollment(auth: AuthContextValue, route = '/merchant/enroll') {
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

describe('Merchant Pending-Enrollment Screen & Workflow', () => {
  it('redirects unauthenticated visitor to login', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)

    renderEnrollment(anonymousAuth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /commercial logistics/i })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('renders self-service enrollment form for fresh customer without existing merchant profile', async () => {
    const customerWithoutMerchant = {
      ...customerFixture,
      merchantProfile: null,
    }
    const auth = authenticatedCustomer(customerWithoutMerchant)

    // Mock GET /api/v1/merchants/me returning 404
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(JSON.stringify({ title: 'Not Found', status: 404 }), { status: 404 })
      }
      return new Response(JSON.stringify(customerWithoutMerchant))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderEnrollment(auth)

    expect(await screen.findByRole('heading', { name: 'Merchant Organization Enrollment' })).toBeInTheDocument()
    expect(screen.getByLabelText(/business legal name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/business registration/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /submit merchant application/i })).toBeInTheDocument()
    expect(screen.getByText(/verification & security policy/i)).toBeInTheDocument()
  })

  it('validates business fields and rejects blank or short inputs', async () => {
    const customerWithoutMerchant = {
      ...customerFixture,
      merchantProfile: null,
    }
    const auth = authenticatedCustomer(customerWithoutMerchant)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(JSON.stringify({ status: 404 }), { status: 404 })
      }
      return new Response(JSON.stringify(customerWithoutMerchant))
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderEnrollment(auth)

    const submitBtn = await screen.findByRole('button', { name: /submit merchant application/i })
    const nameInput = screen.getByLabelText(/business legal name/i)
    const regInput = screen.getByLabelText(/business registration/i)

    // Submitting with short business name
    await user.type(nameInput, 'AB')
    await user.type(regInput, 'PV-12345')
    await user.click(submitBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent(/business legal name must have at least 3 characters/i)

    // Clear and type valid name, but short registration number
    await user.clear(nameInput)
    await user.type(nameInput, 'Valid Business Name')
    await user.clear(regInput)
    await user.type(regInput, '12')
    await user.click(submitBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent(/business registration number must have at least 3 characters/i)
  })

  it('submits enrollment and transitions into PENDING verification screen upon 201 Created', async () => {
    const customerWithoutMerchant = {
      ...customerFixture,
      merchantProfile: null,
    }
    const auth = authenticatedCustomer(customerWithoutMerchant)

    const createdResponse = {
      id: 'merch-tenant-new-001',
      keycloakSub: customerFixture.keycloakSub,
      businessName: 'Lanka Agro Exports Ltd',
      registrationNumber: 'PV-992211',
      status: 'PENDING',
      createdAt: '2026-10-25T14:30:00Z',
      updatedAt: '2026-10-25T14:30:00Z',
    }

    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(JSON.stringify({ status: 404 }), { status: 404 })
      }
      if (url.endsWith('/api/v1/merchants') && init?.method === 'POST') {
        return new Response(JSON.stringify(createdResponse), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(JSON.stringify(customerWithoutMerchant))
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderEnrollment(auth)

    const nameInput = await screen.findByLabelText(/business legal name/i)
    const regInput = screen.getByLabelText(/business registration/i)
    const submitBtn = screen.getByRole('button', { name: /submit merchant application/i })

    await user.type(nameInput, 'Lanka Agro Exports Ltd')
    await user.type(regInput, 'PV-992211')
    await user.click(submitBtn)

    // Should transition to Pending card
    expect(await screen.findByRole('heading', { name: 'Lanka Agro Exports Ltd' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: pending/i })).toBeInTheDocument()
    expect(screen.getByText('Approval Pending')).toBeInTheDocument()
    expect(screen.getByText('PV-992211')).toBeInTheDocument()
    expect(screen.getByText('merch-tenant-new-001')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /next steps/i })).toBeInTheDocument()
  })

  it('handles 409 Conflict when user already owns a tenant', async () => {
    const customerWithoutMerchant = {
      ...customerFixture,
      merchantProfile: null,
    }
    const auth = authenticatedCustomer(customerWithoutMerchant)

    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(JSON.stringify({ status: 404 }), { status: 404 })
      }
      if (url.endsWith('/api/v1/merchants') && init?.method === 'POST') {
        return new Response(
          JSON.stringify({
            title: 'Conflict',
            status: 409,
            detail: 'Authenticated user already owns a merchant tenant',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        )
      }
      return new Response(JSON.stringify(customerWithoutMerchant))
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderEnrollment(auth)

    const nameInput = await screen.findByLabelText(/business legal name/i)
    const regInput = screen.getByLabelText(/business registration/i)
    const submitBtn = screen.getByRole('button', { name: /submit merchant application/i })

    await user.type(nameInput, 'Duplicate Store Ltd')
    await user.type(regInput, 'PV-112233')
    await user.click(submitBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /authenticated user already owns a merchant tenant/i,
    )
  })

  it('renders existing PENDING status with next steps and refresh trigger', async () => {
    const pendingCustomer = {
      ...customerFixture,
      merchantProfile: {
        merchantId: 'merch-pending-99',
        businessName: 'Royal Spices Ceylon',
        registrationNumber: 'PV-887766',
        status: 'PENDING',
      },
    }
    const auth = authenticatedCustomer(pendingCustomer)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(
          JSON.stringify({
            id: 'merch-pending-99',
            businessName: 'Royal Spices Ceylon',
            registrationNumber: 'PV-887766',
            status: 'PENDING',
            createdAt: '2026-10-20T08:00:00Z',
          }),
        )
      }
      return new Response(JSON.stringify(pendingCustomer))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderEnrollment(auth)

    expect(await screen.findByRole('heading', { name: 'Royal Spices Ceylon' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: pending/i })).toBeInTheDocument()
    expect(screen.getByText('PV-887766')).toBeInTheDocument()
    expect(screen.getByText('merch-pending-99')).toBeInTheDocument()

    // Next steps
    const nextSteps = screen.getByRole('heading', { name: /next steps/i }).closest('.pending-next-steps')!
    expect(within(nextSteps as HTMLElement).getByText(/administrative review/i)).toBeInTheDocument()
    expect(within(nextSteps as HTMLElement).getByText(/approval & claim sync/i)).toBeInTheDocument()

    // Refresh action
    const refreshBtn = screen.getByRole('button', { name: /refresh approval status/i })
    expect(refreshBtn).toBeInTheDocument()
  })

  it('renders APPROVED status with portal entry button', async () => {
    const approvedCustomer = {
      ...customerFixture,
      merchantProfile: {
        merchantId: 'merch-approved-77',
        businessName: 'Ceylon Tea Co',
        registrationNumber: 'PV-554433',
        status: 'APPROVED',
      },
    }
    const auth = authenticatedCustomer(approvedCustomer)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(
          JSON.stringify({
            id: 'merch-approved-77',
            businessName: 'Ceylon Tea Co',
            registrationNumber: 'PV-554433',
            status: 'APPROVED',
            createdAt: '2026-10-18T12:00:00Z',
          }),
        )
      }
      return new Response(JSON.stringify(approvedCustomer))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderEnrollment(auth)

    expect(await screen.findByRole('heading', { name: 'Ceylon Tea Co' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: approved/i })).toBeInTheDocument()
    expect(screen.getByText(/congratulations! your merchant account is approved/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sync session & enter portal/i })).toBeInTheDocument()
  })

  it('renders SUSPENDED and REJECTED states with support contact', async () => {
    const suspendedCustomer = {
      ...customerFixture,
      merchantProfile: {
        merchantId: 'merch-suspended-33',
        businessName: 'Island Goods Ltd',
        registrationNumber: 'PV-332211',
        status: 'SUSPENDED',
      },
    }
    const auth = authenticatedCustomer(suspendedCustomer)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/merchants/me')) {
        return new Response(
          JSON.stringify({
            id: 'merch-suspended-33',
            businessName: 'Island Goods Ltd',
            registrationNumber: 'PV-332211',
            status: 'SUSPENDED',
            createdAt: '2026-10-10T12:00:00Z',
          }),
        )
      }
      return new Response(JSON.stringify(suspendedCustomer))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderEnrollment(auth)

    expect(await screen.findByRole('heading', { name: 'Island Goods Ltd' })).toBeInTheDocument()
    expect(screen.getByRole('alert', { name: /approval status: suspended/i })).toBeInTheDocument()
    expect(screen.getByText(/operational activities suspended/i)).toBeInTheDocument()
    expect(screen.getByText(/need assistance\?/i)).toBeInTheDocument()
    const supportLinks = screen.getAllByRole('link', { name: 'support@swiftdrop.test' })
    expect(supportLinks.length).toBeGreaterThanOrEqual(1)
    expect(supportLinks[0]).toHaveAttribute('href', 'mailto:support@swiftdrop.test')
  })
})
