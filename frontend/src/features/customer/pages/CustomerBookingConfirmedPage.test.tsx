import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { customerFixture } from '../../../test/fixtures'
import type { ParcelDetailResponse } from '../api/deliveryContracts'
import { CustomerBookingConfirmedPage } from './CustomerBookingConfirmedPage'

const testParcelDetail: ParcelDetailResponse = {
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
  createdAt: '2026-10-26T10:05:00Z',
}

describe('CustomerBookingConfirmedPage (Issue #118)', () => {
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
    mockFetch = vi.fn<typeof fetch>().mockImplementation((url: string | URL | Request) => {
      const urlStr = url.toString()
      if (urlStr.includes('/api/v1/parcels/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11')) {
        return Promise.resolve(
          new Response(JSON.stringify(testParcelDetail), {
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

  it('renders confirmed booking details from route state without network refetch', () => {
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/customer/booking/confirmed/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
              state: { parcel: testParcelDetail },
            },
          ]}
        >
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    expect(screen.getByRole('heading', { name: /booking confirmed/i })).toBeInTheDocument()
    expect(screen.getByText('SWD-20261026-A1B2C3')).toBeInTheDocument()
    expect(screen.getByText('LKR 500.00')).toBeInTheDocument()
    expect(screen.getByText('123 Galle Road, Colombo 03')).toBeInTheDocument()
    expect(screen.getByText('45 Kandy Road, Gampaha')).toBeInTheDocument()
    expect(screen.getByText('2.5 kg')).toBeInTheDocument()
    expect(screen.getByText('30x20x15 cm')).toBeInTheDocument()
    expect(screen.getByText('RATE-2026-V1')).toBeInTheDocument()
    expect(screen.getByText('Created')).toBeInTheDocument()
  })

  it('fetches parcel details from API on page refresh / direct deep link without initial state', async () => {
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter
          initialEntries={['/customer/booking/confirmed/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11']}
        >
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /booking confirmed/i })).toBeInTheDocument()
      expect(screen.getByText('SWD-20261026-A1B2C3')).toBeInTheDocument()
    })

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/parcels/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('copies tracking number to clipboard and provides visual feedback', async () => {
    const user = userEvent.setup()
    const writeTextMock = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: writeTextMock },
      writable: true,
      configurable: true,
    })

    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/customer/booking/confirmed/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
              state: { parcel: testParcelDetail },
            },
          ]}
        >
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    const copyBtn = screen.getByRole('button', { name: /copy tracking number/i })
    await user.click(copyBtn)

    expect(writeTextMock).toHaveBeenCalledWith('SWD-20261026-A1B2C3')
    expect(screen.getByRole('button', { name: /tracking number copied/i })).toBeInTheDocument()
  })

  it('handles 403 Forbidden with ownership mismatch message and provides return navigation', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          title: 'Forbidden',
          status: 403,
          detail: 'Caller does not own parcel.',
        }),
        { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )

    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/booking/confirmed/unknown-parcel-id']}>
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /unable to load booking/i })).toBeInTheDocument()
    })
    expect(
      screen.getByText(/you are not authorized to view this parcel booking/i),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /view delivery history/i })).toBeInTheDocument()
  })

  it('handles 404 Not Found gracefully when parcel is missing', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          title: 'Not Found',
          status: 404,
          detail: 'Parcel not found.',
        }),
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )

    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter initialEntries={['/customer/booking/confirmed/missing-id']}>
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    await waitFor(() => {
      expect(
        screen.getByText(/the requested parcel booking could not be located in delivery operations/i),
      ).toBeInTheDocument()
    })
  })

  it('renders navigation links to Delivery History, new quote, and Account Profile', () => {
    render(
      <TestProviders auth={customerAuth}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/customer/booking/confirmed/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
              state: { parcel: testParcelDetail },
            },
          ]}
        >
          <Routes>
            <Route
              path="/customer/booking/confirmed/:parcelId"
              element={<CustomerBookingConfirmedPage />}
            />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    )

    expect(screen.getByRole('link', { name: /view in delivery history/i })).toHaveAttribute(
      'href',
      '/customer/history',
    )
    expect(screen.getByRole('link', { name: /book another delivery/i })).toHaveAttribute(
      'href',
      '/customer/quote',
    )
    expect(screen.getByRole('link', { name: /return to profile/i })).toHaveAttribute(
      'href',
      '/customer',
    )
  })
})
