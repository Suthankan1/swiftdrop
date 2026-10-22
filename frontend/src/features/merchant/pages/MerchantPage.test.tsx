import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { merchantFixture } from '../../../test/fixtures'

function authenticatedMerchant(
  roles: readonly SwiftDropRole[] = ['MERCHANT'],
  subject = merchantFixture.keycloakSub,
  fetchResponse?: Response,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'test-bearer-token',
    profileFetch: fetchResponse
      ? vi.fn(async () => fetchResponse)
      : vi.fn(async () => new Response(JSON.stringify(merchantFixture))),
  }
}

function renderMerchant(auth: AuthContextValue, route = '/merchant') {
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

describe('Merchant role landing and approval-state UI', () => {
  it('redirects an unauthenticated visit to login without provider requests', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)

    renderMerchant(anonymousAuth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /commercial logistics/i })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('displays session loading feedback during auth initialization without provider requests', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)
    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'loading', subject: null, roles: [] },
    }

    renderMerchant(auth)

    expect(screen.getByRole('status')).toHaveTextContent(/checking session/i)
    expect(request).not.toHaveBeenCalled()
  })

  it.each<SwiftDropRole>(['CUSTOMER', 'COURIER', 'DISPATCHER', 'ADMIN'])(
    'denies the %s role without rendering merchant content or making a provider request',
    (role) => {
      const request = vi.fn<typeof fetch>()
      vi.stubGlobal('fetch', request)

      renderMerchant(authenticatedMerchant([role]))

      expect(screen.getByRole('alert')).toHaveTextContent(/access unavailable/i)
      expect(screen.queryByRole('heading', { name: /commercial logistics/i })).not.toBeInTheDocument()
      expect(screen.queryByText(/synthetic test business/i)).not.toBeInTheDocument()
      expect(request).not.toHaveBeenCalled()
    },
  )

  it('renders PENDING approval state with review guidance and business context', async () => {
    const pendingData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-pending-01',
        businessName: 'Colombo Wholesale Store',
        registrationNumber: 'PV-001234',
        status: 'PENDING',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(pendingData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Colombo Wholesale Store' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: pending/i })).toBeInTheDocument()
    expect(screen.getByText('Approval Pending')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Application under review' })).toBeInTheDocument()
    expect(screen.getByText(/commercial dispatching, batch order entry, and webhook API access remain locked/i)).toBeInTheDocument()
    expect(screen.getByText(/standard verification takes 1–2 business days/i)).toBeInTheDocument()

    // Verifies business details are displayed correctly
    expect(screen.getByText('PV-001234')).toBeInTheDocument()
    expect(screen.getByText('merch-pending-01')).toBeInTheDocument()
    expect(screen.getByText(merchantFixture.fullName)).toBeInTheDocument()
    expect(screen.getByText(merchantFixture.email)).toBeInTheDocument()
  })

  it('renders APPROVED status with active operational access', async () => {
    const approvedData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-approved-02',
        businessName: 'Silva Supermarket LK',
        registrationNumber: 'PV-987654',
        status: 'APPROVED',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(approvedData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Silva Supermarket LK' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: approved/i })).toBeInTheDocument()
    expect(screen.getByText('Account Approved')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Verified merchant account' })).toBeInTheDocument()
    expect(screen.getByText(/your merchant account is fully active and authorized for operational parcel dispatch/i)).toBeInTheDocument()
    expect(screen.getByText('PV-987654')).toBeInTheDocument()
    expect(screen.getByText('merch-approved-02')).toBeInTheDocument()
  })

  it('renders REJECTED status with appeal guidance and support contact', async () => {
    const rejectedData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-rejected-03',
        businessName: 'Rejected Enterprise',
        registrationNumber: 'PV-555555',
        status: 'REJECTED',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(rejectedData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Rejected Enterprise' })).toBeInTheDocument()
    expect(screen.getByRole('alert', { name: /approval status: rejected/i })).toBeInTheDocument()
    expect(screen.getByText('Registration Rejected')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Application not approved' })).toBeInTheDocument()
    expect(screen.getByText(/your merchant registration could not be approved/i)).toBeInTheDocument()
    const mailLink = screen.getByRole('link', { name: 'support@swiftdrop.test' })
    expect(mailLink).toHaveAttribute('href', 'mailto:support@swiftdrop.test')
  })

  it('treats synonymous DENIED status as rejected with available next steps', async () => {
    const deniedData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-denied-04',
        businessName: 'Denied Imports LK',
        registrationNumber: 'PV-444444',
        status: 'DENIED',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(deniedData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Denied Imports LK' })).toBeInTheDocument()
    expect(screen.getByRole('alert', { name: /approval status: rejected/i })).toBeInTheDocument()
    expect(screen.getByText('Registration Rejected')).toBeInTheDocument()
  })

  it('renders SUSPENDED status with account hold guidance', async () => {
    const suspendedData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-suspended-05',
        businessName: 'Suspended Trading',
        registrationNumber: 'PV-777777',
        status: 'SUSPENDED',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(suspendedData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Suspended Trading' })).toBeInTheDocument()
    expect(screen.getByRole('alert', { name: /approval status: suspended/i })).toBeInTheDocument()
    expect(screen.getByText('Account Suspended')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Operational activities suspended' })).toBeInTheDocument()
    expect(screen.getByText(/temporarily suspended from initiating deliveries/i)).toBeInTheDocument()
  })

  it('renders safe fallback for unknown or unrecognized approval status', async () => {
    const unknownData = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-unknown-06',
        businessName: 'Legacy Mart',
        registrationNumber: 'PV-888888',
        status: 'UNDER_INVESTIGATION',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(unknownData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Legacy Mart' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: /approval status: unknown/i })).toBeInTheDocument()
    expect(screen.getByText('Status Unrecognized')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Approval status unavailable' })).toBeInTheDocument()
    expect(screen.getByText(/UNDER_INVESTIGATION/)).toBeInTheDocument()
  })

  it('handles missing/null merchantProfile safely without crashing', async () => {
    const noProfileData = {
      ...merchantFixture,
      merchantProfile: null,
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(noProfileData)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'No merchant profile linked' })).toBeInTheDocument()
    expect(screen.getByText(/no commercial merchant profile is currently registered/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Application under review' })).not.toBeInTheDocument()
  })

  it('renders loading state while profile request is in-flight', () => {
    const pendingPromise = new Promise<Response>(() => {})
    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: merchantFixture.keycloakSub, roles: ['MERCHANT'] },
      getAccessToken: async () => 'test-token',
      profileFetch: vi.fn(() => pendingPromise),
    }

    renderMerchant(auth)

    expect(screen.getByRole('status')).toHaveTextContent(/loading merchant details/i)
  })

  it('renders error state when profile service is unavailable, and supports retrying', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const profileFetch = vi.fn(async () => {
      attempts++
      if (attempts === 1) {
        throw new TypeError('Network connection failed')
      }
      return new Response(JSON.stringify(merchantFixture))
    })

    const auth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: merchantFixture.keycloakSub, roles: ['MERCHANT'] },
      getAccessToken: async () => 'test-token',
      profileFetch,
    }

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Merchant details temporarily unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/we could not reach the account service/i)

    const retryButton = screen.getByRole('button', { name: /retry profile/i })
    await user.click(retryButton)

    expect(await screen.findByRole('heading', { name: merchantFixture.merchantProfile!.businessName })).toBeInTheDocument()
  })

  it('enforces subject matching to prevent cross-tenant IDOR leakage', async () => {
    const foreignProfile = {
      ...merchantFixture,
      keycloakSub: 'foreign-subject-id',
      merchantProfile: {
        merchantId: 'foreign-merchant',
        businessName: 'Foreign Compromised Business',
        registrationNumber: 'PV-ATTACK',
        status: 'APPROVED',
      },
    }
    const auth = authenticatedMerchant(
      ['MERCHANT'],
      merchantFixture.keycloakSub,
      new Response(JSON.stringify(foreignProfile)),
    )

    renderMerchant(auth)

    expect(await screen.findByRole('heading', { name: 'Merchant profile unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('Foreign Compromised Business')).not.toBeInTheDocument()
  })

  it('executes shared logout action when Sign out is clicked', async () => {
    const user = userEvent.setup()
    const logout = vi.fn(async () => {})
    const auth = {
      ...authenticatedMerchant(),
      logout,
    }

    renderMerchant(auth)
    await screen.findByRole('heading', { name: merchantFixture.merchantProfile!.businessName })

    await user.click(screen.getByRole('button', { name: /sign out/i }))
    expect(logout).toHaveBeenCalledOnce()
  })

  it('shows Merchant navigation link only to an authenticated Merchant', () => {
    const { unmount } = renderMerchant(authenticatedMerchant())
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).getByRole('link', { name: /merchant space/i }),
    ).toHaveAttribute('href', '/merchant')
    unmount()

    renderMerchant(authenticatedMerchant(['CUSTOMER']))
    expect(
      within(screen.getByRole('navigation', { name: /main navigation/i })).queryByRole('link', { name: /merchant space/i }),
    ).not.toBeInTheDocument()
  })

  it('removes merchant content and redirects to login when session is lost', async () => {
    const { rerender } = renderMerchant(authenticatedMerchant())
    expect(await screen.findByRole('heading', { name: merchantFixture.merchantProfile!.businessName })).toBeInTheDocument()

    rerender(
      <TestProviders auth={anonymousAuth}>
        <MemoryRouter initialEntries={['/merchant']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: merchantFixture.merchantProfile!.businessName })).not.toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    })
  })
})
