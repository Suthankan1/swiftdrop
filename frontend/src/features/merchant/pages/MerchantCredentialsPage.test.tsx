import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { merchantFixture } from '../../../test/fixtures'

const approvedMerchantFixture = {
  ...merchantFixture,
  merchantProfile: {
    ...merchantFixture.merchantProfile!,
    status: 'APPROVED',
  },
}

function authenticatedMerchant(
  roles: readonly SwiftDropRole[] = ['MERCHANT'],
  userProfile = approvedMerchantFixture,
  token = 'test-merchant-token',
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: {
      status: 'authenticated',
      subject: userProfile.keycloakSub,
      roles,
    },
    getAccessToken: async () => token,
    profileFetch: vi.fn(async () => new Response(JSON.stringify(userProfile))),
  }
}

function renderCredentials(auth: AuthContextValue, route = '/merchant/credentials') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

const mockKeys = [
  {
    keyId: 'k1-1111-2222-3333-444455556666',
    name: 'Warehouse Dispatch Integration',
    keyPrefix: 'swd_live_7a8b',
    scopes: ['parcels:create', 'parcels:read'],
    createdAt: '2026-10-20T10:00:00Z',
    expiresAt: '2027-01-20T10:00:00Z',
    isRevoked: false,
  },
  {
    keyId: 'k2-2222-3333-4444-555566667777',
    name: 'Readonly Tracking Script',
    keyPrefix: 'swd_live_9c0d',
    scopes: ['parcels:read'],
    createdAt: '2026-10-15T12:00:00Z',
    expiresAt: '2027-01-15T12:00:00Z',
    isRevoked: true,
  },
]

describe('Merchant Credential Settings Screen', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('redirects unauthenticated visitor to login', () => {
    const request = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', request)

    renderCredentials(anonymousAuth)

    expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /machine credentials/i })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('blocks authenticated user without MERCHANT role', () => {
    const customerAuth: AuthContextValue = {
      ...anonymousAuth,
      snapshot: { status: 'authenticated', subject: 'customer-sub', roles: ['CUSTOMER'] },
    }

    renderCredentials(customerAuth)

    expect(screen.getByRole('alert')).toHaveTextContent(/access unavailable/i)
    expect(screen.queryByRole('heading', { name: /machine credentials/i })).not.toBeInTheDocument()
  })

  it('disables key generation when merchant approval status is PENDING', async () => {
    const pendingMerchant = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-pending-01',
        businessName: 'Pending Goods Store',
        registrationNumber: 'PV-001122',
        status: 'PENDING',
      },
    }
    const auth = authenticatedMerchant(['MERCHANT'], pendingMerchant)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(pendingMerchant))
      }
      return new Response(JSON.stringify({ keys: [] }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCredentials(auth)

    expect(await screen.findByRole('heading', { name: /machine credentials/i })).toBeInTheDocument()
    expect(await screen.findByRole('status', { name: /approval status: pending/i })).toBeInTheDocument()
    expect(screen.getByText(/application under review/i)).toBeInTheDocument()
    expect(screen.getByText(/api key management requires verified merchant approval/i)).toBeInTheDocument()

    const generateBtn = screen.getByRole('button', { name: /\+ generate api key \(disabled\)/i })
    expect(generateBtn).toBeDisabled()
  })

  it('disables key generation when merchant approval status is SUSPENDED', async () => {
    const suspendedMerchant = {
      ...merchantFixture,
      merchantProfile: {
        merchantId: 'merch-suspended-01',
        businessName: 'Suspended Trading Co',
        registrationNumber: 'PV-003344',
        status: 'SUSPENDED',
      },
    }
    const auth = authenticatedMerchant(['MERCHANT'], suspendedMerchant)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(suspendedMerchant))
      }
      return new Response(JSON.stringify({ keys: [] }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCredentials(auth)

    expect(await screen.findByRole('heading', { name: /machine credentials/i })).toBeInTheDocument()
    expect(await screen.findByRole('alert', { name: /approval status: suspended/i })).toBeInTheDocument()
    expect(screen.getByText(/your merchant account is suspended/i)).toBeInTheDocument()

    const generateBtn = screen.getByRole('button', { name: /\+ generate api key \(disabled\)/i })
    expect(generateBtn).toBeDisabled()
  })

  it('renders active and revoked keys table for APPROVED merchant without exposing plaintext secrets', async () => {
    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantFixture)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(approvedMerchantFixture))
      }
      if (url.includes('/api-keys')) {
        return new Response(JSON.stringify({ keys: mockKeys }))
      }
      return new Response(JSON.stringify({}))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCredentials(auth)

    expect(await screen.findByRole('heading', { name: /api keys \(1\/3 active\)/i })).toBeInTheDocument()
    expect(screen.getByText('Warehouse Dispatch Integration')).toBeInTheDocument()
    expect(screen.getByText('swd_live_7a8b...')).toBeInTheDocument()
    expect(screen.getByText('Readonly Tracking Script')).toBeInTheDocument()
    expect(screen.getByText('swd_live_9c0d...')).toBeInTheDocument()

    // Plaintext secrets or hashes MUST NOT exist anywhere in the document
    expect(screen.queryByText(/swd_live_7a8b9c/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/sha256/i)).not.toBeInTheDocument()

    // Local storage and session storage must remain empty
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('enforces maximum 3 active keys rotation limit by disabling generate button', async () => {
    const threeActiveKeys = [
      {
        keyId: 'k1',
        name: 'Key 1',
        keyPrefix: 'swd_live_1111',
        scopes: ['parcels:create'],
        createdAt: '2026-10-01T00:00:00Z',
        expiresAt: '2027-01-01T00:00:00Z',
        isRevoked: false,
      },
      {
        keyId: 'k2',
        name: 'Key 2',
        keyPrefix: 'swd_live_2222',
        scopes: ['parcels:read'],
        createdAt: '2026-10-02T00:00:00Z',
        expiresAt: '2027-01-02T00:00:00Z',
        isRevoked: false,
      },
      {
        keyId: 'k3',
        name: 'Key 3',
        keyPrefix: 'swd_live_3333',
        scopes: ['webhooks:manage'],
        createdAt: '2026-10-03T00:00:00Z',
        expiresAt: '2027-01-03T00:00:00Z',
        isRevoked: false,
      },
    ]

    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantFixture)

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(approvedMerchantFixture))
      }
      if (url.includes('/api-keys')) {
        return new Response(JSON.stringify({ keys: threeActiveKeys }))
      }
      return new Response(JSON.stringify({}))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCredentials(auth)

    expect(await screen.findByRole('heading', { name: /api keys \(3\/3 active\)/i })).toBeInTheDocument()
    expect(screen.getByText(/maximum active api key limit reached \(3\/3\)/i)).toBeInTheDocument()

    const generateBtn = screen.getByRole('button', { name: /\+ generate api key/i })
    expect(generateBtn).toBeDisabled()
  })

  it('generates an API key, displays one-time reveal modal, copies key, and clears secret on close', async () => {
    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantFixture)

    const createdKey = {
      keyId: 'k-new-999',
      name: 'Automated ERP Int',
      keyPrefix: 'swd_live_a1b2',
      plaintextKey: 'swd_live_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6',
      scopes: ['parcels:create', 'parcels:read'],
      createdAt: '2026-10-25T12:00:00Z',
      expiresAt: '2027-01-25T12:00:00Z',
    }

    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(approvedMerchantFixture))
      }
      if (url.includes('/api-keys') && init?.method === 'POST') {
        return new Response(JSON.stringify(createdKey), { status: 201 })
      }
      if (url.includes('/api-keys')) {
        return new Response(JSON.stringify({ keys: [] }))
      }
      return new Response(JSON.stringify({}))
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()

    // Mock clipboard
    const writeTextMock = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: writeTextMock },
      writable: true,
      configurable: true,
    })

    renderCredentials(auth)

    const openBtn = await screen.findByRole('button', { name: /^\+ generate api key$/i })
    await user.click(openBtn)

    // Form modal opens
    expect(await screen.findByRole('heading', { name: 'Generate Merchant API Key' })).toBeInTheDocument()

    const nameInput = screen.getByLabelText(/key name/i)
    await user.type(nameInput, 'Automated ERP Int')

    const submitBtn = screen.getByRole('button', { name: /^generate key$/i })
    await user.click(submitBtn)

    // One-time reveal modal appears
    expect(await screen.findByRole('heading', { name: 'API Key Generated' })).toBeInTheDocument()
    expect(screen.getByText(/save this api key immediately/i)).toBeInTheDocument()

    const secretCode = screen.getByTestId('plaintext-api-key')
    expect(secretCode).toHaveTextContent('swd_live_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6')

    // Copy button
    const copyBtn = screen.getByRole('button', { name: /copy api key to clipboard/i })
    await user.click(copyBtn)
    expect(writeTextMock).toHaveBeenCalledWith('swd_live_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6')
    expect(screen.getByText('✓ Copied!')).toBeInTheDocument()

    // Dismiss modal
    const closeBtn = screen.getByRole('button', { name: /i have securely saved my key/i })
    await user.click(closeBtn)

    // Verify secret is destroyed from view
    await waitFor(() => {
      expect(screen.queryByTestId('plaintext-api-key')).not.toBeInTheDocument()
    })

    // Strict security check: localStorage and sessionStorage must have 0 keys
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('revokes an API key with confirmation modal and updates table', async () => {
    const auth = authenticatedMerchant(['MERCHANT'], approvedMerchantFixture)
    const user = userEvent.setup()

    let revoked = false
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/users/me')) {
        return new Response(JSON.stringify(approvedMerchantFixture))
      }
      if (url.includes('/api-keys') && init?.method === 'DELETE') {
        revoked = true
        return new Response(null, { status: 204 })
      }
      if (url.includes('/api-keys')) {
        return new Response(
          JSON.stringify({
            keys: [
              {
                ...mockKeys[0],
                isRevoked: revoked,
              },
            ],
          }),
        )
      }
      return new Response(JSON.stringify({}))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCredentials(auth)

    const revokeBtn = await screen.findByRole('button', { name: /revoke api key warehouse dispatch integration/i })
    await user.click(revokeBtn)

    // Confirmation modal opens
    expect(await screen.findByRole('heading', { name: 'Revoke API Key?' })).toBeInTheDocument()
    expect(screen.getByText(/this action cannot be undone/i)).toBeInTheDocument()

    const confirmBtn = screen.getByRole('button', { name: /revoke key permanently/i })
    await user.click(confirmBtn)

    // Modal closes and success alert appears
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Revoke API Key?' })).not.toBeInTheDocument()
    })
    expect(await screen.findByText(/was durably revoked/i)).toBeInTheDocument()
    expect(revoked).toBe(true)
  })
})
