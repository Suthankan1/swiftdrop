import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { customerFixture } from '../../../test/fixtures'
import type { QuoteResponse, ZoneListResponse } from '../api/pricingContracts'
import type { ParcelDetailResponse } from '../api/deliveryContracts'

const testZonesResponse: ZoneListResponse = {
  activeRateVersion: 'RATE-2026-V1',
  zones: [
    {
      zoneCode: 'ZONE_1',
      name: 'Colombo Municipal District',
      description: 'Intra-city delivery',
      standardTransitHours: 24,
    },
    {
      zoneCode: 'ZONE_2',
      name: 'Western Province Suburbs',
      description: 'Suburban regional',
      standardTransitHours: 48,
    },
  ],
}

const testQuoteResponse: QuoteResponse = {
  quoteId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
  rateVersion: 'RATE-2026-V1',
  pickupZone: 'ZONE_1',
  dropoffZone: 'ZONE_2',
  weightKg: 2.5,
  volumetricWeightKg: 1.8,
  chargeableWeightKg: 2.5,
  sizeCategory: 'MEDIUM',
  baseFeeMinorUnits: 35000,
  weightSurchargeMinorUnits: 15000,
  totalAmountMinorUnits: 50000,
  currency: 'LKR',
  createdAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
  isExpired: false,
}

const testCreatedParcel: ParcelDetailResponse = {
  parcelId: 'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
  trackingNumber: 'SWD-20261026-A1B2C3',
  createdBySub: customerFixture.keycloakSub,
  merchantId: null,
  currentStatus: 'CREATED',
  version: 1,
  weightKg: 2.5,
  dimensions: '30x20x15',
  amountMinorUnits: 50000,
  currency: 'LKR',
  rateVersion: 'RATE-2026-V1',
  pickupAddress: '123 Galle Road, Colombo 03',
  dropoffAddress: '45 Kandy Road, Gampaha',
  activeCourierSub: null,
  createdAt: new Date().toISOString(),
}

describe('CustomerQuotePage & Booking Workflow (Issue #118)', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  const customerAuth: AuthContextValue = {
    ...anonymousAuth,
    snapshot: {
      status: 'authenticated',
      subject: customerFixture.keycloakSub,
      roles: ['CUSTOMER'],
    },
    getAccessToken: async () => 'test-valid-customer-token',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn<typeof fetch>().mockImplementation((url: string | URL | Request, init?: RequestInit) => {
      const urlStr = url.toString()

      // Zones list
      if (urlStr.includes('/api/v1/zones')) {
        return Promise.resolve(
          new Response(JSON.stringify(testZonesResponse), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }

      // Quote calculation
      if (urlStr.includes('/api/v1/quotes') && init?.method === 'POST') {
        return Promise.resolve(
          new Response(JSON.stringify(testQuoteResponse), {
            status: 201,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }

      // Parcel booking
      if (urlStr.includes('/api/v1/parcels') && init?.method === 'POST') {
        return Promise.resolve(
          new Response(JSON.stringify(testCreatedParcel), {
            status: 201,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }

      // Parcel retrieval by id
      if (urlStr.includes('/api/v1/parcels/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11')) {
        return Promise.resolve(
          new Response(JSON.stringify(testCreatedParcel), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }

      return Promise.reject(new Error(`Unhandled mock URL: ${urlStr}`))
    })
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('redirects an unauthenticated user to the login screen', async () => {
    render(
      <TestProviders auth={anonymousAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /sign in/i })).toBeInTheDocument()
    })
  })

  it('renders the quote calculator form with loaded zones and default parameters', async () => {
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    expect(screen.getByRole('heading', { name: /delivery quote & booking dispatch/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/pickup zone/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/dropoff zone/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/physical weight/i)).toHaveValue(2.5)
    expect(screen.getByLabelText(/length/i)).toHaveValue(30)
    expect(screen.getByLabelText(/width/i)).toHaveValue(20)
    expect(screen.getByLabelText(/height/i)).toHaveValue(15)

    // Verify volumetric weight preview (30 * 20 * 15) / 5000 = 1.80
    expect(screen.getByText('1.80 kg')).toBeInTheDocument()
  })

  it('validates physical weight and dimensions client-side before calling quote API', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    const weightInput = screen.getByLabelText(/physical weight/i)
    await user.clear(weightInput)
    await user.type(weightInput, '45.0') // exceeds max 30.00 kg

    const calcButton = screen.getByRole('button', { name: /calculate delivery quote/i })
    await user.click(calcButton)

    expect(await screen.findByText(/weight must be between 0.01 kg and 30.00 kg/i)).toBeInTheDocument()

    // Ensure quote POST was not executed
    expect(mockFetch).not.toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/quotes'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('associates quote validation errors with fields and focuses the first invalid control', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    const weight = screen.getByLabelText(/physical weight/i)
    const length = screen.getByLabelText(/^length/i)
    const width = screen.getByLabelText(/^width/i)
    const height = screen.getByLabelText(/^height/i)
    await user.clear(weight)
    await user.type(weight, '45')
    await user.clear(length)
    await user.type(length, '0')
    await user.clear(width)
    await user.type(width, '151')
    await user.clear(height)
    await user.type(height, '0')
    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))

    expect(weight).toHaveAttribute('aria-invalid', 'true')
    expect(length).toHaveAttribute('aria-invalid', 'true')
    expect(width).toHaveAttribute('aria-invalid', 'true')
    expect(height).toHaveAttribute('aria-invalid', 'true')
    for (const field of [weight, length, width, height]) {
      const describedBy = field.getAttribute('aria-describedby')!.split(' ')
      expect(describedBy.some((id) => document.getElementById(id)?.textContent)).toBe(true)
    }
    expect(document.activeElement).toBe(weight)

    await user.clear(weight)
    await user.type(weight, '2.5')
    expect(weight).toHaveAttribute('aria-invalid', 'false')
    expect(weight.getAttribute('aria-describedby')).toBe('weight-hint')
  })

  it('calculates quote, displays guaranteed price breakdown, and shows live timer', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    const calcButton = screen.getByRole('button', { name: /calculate delivery quote/i })
    await user.click(calcButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /guaranteed price/i })).toBeInTheDocument()
    })

    // Verify quote amounts formatted in currency
    expect(screen.getByText('LKR 500.00')).toBeInTheDocument()
    expect(screen.getByText('LKR 350.00')).toBeInTheDocument()
    expect(screen.getByText('LKR 150.00')).toBeInTheDocument()
    expect(screen.getByText(/medium parcel/i)).toBeInTheDocument()
    expect(screen.getByText(/quote valid for/i)).toBeInTheDocument()
  })

  it('handles quote calculation API failure gracefully', async () => {
    mockFetch.mockImplementation((url: string | URL | Request) => {
      const urlStr = url.toString()
      if (urlStr.includes('/api/v1/zones')) {
        return Promise.resolve(new Response(JSON.stringify(testZonesResponse), { status: 200 }))
      }
      if (urlStr.includes('/api/v1/quotes')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              title: 'Unprocessable Entity',
              status: 422,
              detail: 'Routing combination between chosen zones is currently suspended.',
            }),
            { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        )
      }
      return Promise.reject(new Error(`Unhandled mock URL: ${urlStr}`))
    })

    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    const calcButton = screen.getByRole('button', { name: /calculate delivery quote/i })
    await user.click(calcButton)

    expect(
      await screen.findByText(/routing combination between chosen zones is currently suspended/i),
    ).toBeInTheDocument()
  })

  it('validates booking address and recipient fields client-side', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    // First calculate quote
    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /complete booking information/i })).toBeInTheDocument()
    })

    // Attempt booking with empty fields
    const bookButton = screen.getByRole('button', { name: /confirm & book delivery/i })
    await user.click(bookButton)

    expect(screen.getByText(/pickup street address is required/i)).toBeInTheDocument()
    expect(screen.getByText(/dropoff street address is required/i)).toBeInTheDocument()
    expect(screen.getByText(/recipient name is required/i)).toBeInTheDocument()
    expect(screen.getByText(/recipient phone number is required/i)).toBeInTheDocument()

    // Ensure booking POST was not sent
    expect(mockFetch).not.toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/parcels'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('associates booking errors with fields and focuses the first invalid address', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))
    await screen.findByRole('heading', { name: /complete booking information/i })
    await user.click(screen.getByRole('button', { name: /confirm & book delivery/i }))

    const pickup = screen.getByLabelText(/pickup street address/i)
    const dropoff = screen.getByLabelText(/dropoff street address/i)
    const recipient = screen.getByLabelText(/recipient full name/i)
    expect(pickup).toHaveAttribute('aria-invalid', 'true')
    expect(dropoff).toHaveAttribute('aria-invalid', 'true')
    expect(recipient).toHaveAttribute('aria-invalid', 'true')
    expect(pickup.getAttribute('aria-describedby')).toMatch(/-error/)
    expect(document.activeElement).toBe(pickup)

    await user.type(pickup, '123 Galle Road')
    expect(pickup).toHaveAttribute('aria-invalid', 'false')
    expect(pickup.getAttribute('aria-describedby')).toBeNull()
  })

  it('submits booking with stable Idempotency-Key and navigates to confirmed screen on 201 Created', async () => {
    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    // 1. Calculate quote
    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /complete booking information/i })).toBeInTheDocument()
    })

    // 2. Fill booking fields
    await user.type(screen.getByLabelText(/pickup street address/i), '123 Galle Road, Colombo 03')
    await user.type(screen.getByLabelText(/dropoff street address/i), '45 Kandy Road, Gampaha')
    await user.type(screen.getByLabelText(/recipient full name/i), 'Nimal Perera')
    await user.type(screen.getByLabelText(/recipient phone number/i), '+94771234567')
    await user.type(screen.getByLabelText(/special delivery instructions/i), 'Leave at front desk')

    // 3. Confirm booking
    const bookButton = screen.getByRole('button', { name: /confirm & book delivery/i })
    await user.click(bookButton)

    // 4. Verify arrival at Confirmed Booking screen
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: /booking confirmed/i })).toBeInTheDocument()
      expect(screen.getByText('SWD-20261026-A1B2C3')).toBeInTheDocument()
    })

    // Verify POST /api/v1/parcels was called with Idempotency-Key
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/parcels',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer test-valid-customer-token',
          'Idempotency-Key': expect.any(String),
          'Content-Type': 'application/json',
        }),
      }),
    )
  }, 15_000)

  it('handles 409 Conflict when quote was already consumed with error recovery', async () => {
    mockFetch.mockImplementation((url: string | URL | Request, init?: RequestInit) => {
      const urlStr = url.toString()
      if (urlStr.includes('/api/v1/zones')) {
        return Promise.resolve(new Response(JSON.stringify(testZonesResponse), { status: 200 }))
      }
      if (urlStr.includes('/api/v1/quotes')) {
        return Promise.resolve(new Response(JSON.stringify(testQuoteResponse), { status: 201 }))
      }
      if (urlStr.includes('/api/v1/parcels') && init?.method === 'POST') {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              title: 'Conflict',
              status: 409,
              detail: 'Quote already booked.',
            }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        )
      }
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    })

    const user = userEvent.setup({ delay: null })
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    // Calculate quote
    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /complete booking information/i })).toBeInTheDocument()
    })

    // Fill fields
    await user.type(screen.getByLabelText(/pickup street address/i), '123 Galle Road, Colombo 03')
    await user.type(screen.getByLabelText(/dropoff street address/i), '45 Kandy Road, Gampaha')
    await user.type(screen.getByLabelText(/recipient full name/i), 'Nimal Perera')
    await user.type(screen.getByLabelText(/recipient phone number/i), '+94771234567')

    // Submit
    await user.click(screen.getByRole('button', { name: /confirm & book delivery/i }))

    expect(
      await screen.findByText(/this quote has already been booked or an idempotency conflict occurred/i),
    ).toBeInTheDocument()
  })

  it('handles quote expiration state and provides recalculate action', async () => {
    const expiredQuote: QuoteResponse = {
      ...testQuoteResponse,
      expiresAt: new Date(Date.now() - 60 * 1000).toISOString(),
      isExpired: true,
    }

    mockFetch.mockImplementation((url: string | URL | Request) => {
      const urlStr = url.toString()
      if (urlStr.includes('/api/v1/zones')) {
        return Promise.resolve(new Response(JSON.stringify(testZonesResponse), { status: 200 }))
      }
      if (urlStr.includes('/api/v1/quotes')) {
        return Promise.resolve(new Response(JSON.stringify(expiredQuote), { status: 201 }))
      }
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    })

    const user = userEvent.setup()
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/quote']}>
          <AppRoutes />
        </MemoryRouter>
      </TestProviders>,
    )

    await user.click(screen.getByRole('button', { name: /calculate delivery quote/i }))

    await waitFor(() => {
      expect(screen.getByText(/quote expired — recalculate to book/i)).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: /refresh quote rate/i })).toBeInTheDocument()
    // Booking form should not be shown when expired
    expect(screen.queryByRole('heading', { name: /complete booking information/i })).not.toBeInTheDocument()
  })
})
