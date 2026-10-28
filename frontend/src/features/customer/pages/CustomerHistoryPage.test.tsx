import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../../app/router/routes'
import type { AuthContextValue } from '../../../auth/context'
import type { SwiftDropRole } from '../../../auth/contracts'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { customerFixture } from '../../../test/fixtures'
import type {
  CustomerDeliveriesPage,
  ParcelDetailResponse,
} from '../api/deliveryContracts'

const testDeliveriesPage: CustomerDeliveriesPage = {
  items: [
    {
      parcelId: 'f47ac10b-58cc-4372-a567-0e02b2c3d401',
      trackingNumber: 'SWD-20261025-A101',
      currentStatus: 'DELIVERED',
      amountMinorUnits: 55000,
      currency: 'LKR',
      pickupAddress: '123 Galle Rd, Colombo 03',
      dropoffAddress: '45 Kandy Rd, Gampaha',
      createdAt: '2026-10-25T08:00:00Z',
    },
    {
      parcelId: 'f47ac10b-58cc-4372-a567-0e02b2c3d402',
      trackingNumber: 'SWD-20261025-A102',
      currentStatus: 'IN_TRANSIT',
      amountMinorUnits: 32000,
      currency: 'LKR',
      pickupAddress: '78 High Level Rd, Nugegoda',
      dropoffAddress: '12 Station Rd, Negombo',
      createdAt: '2026-10-25T09:30:00Z',
    },
    {
      parcelId: 'f47ac10b-58cc-4372-a567-0e02b2c3d403',
      trackingNumber: 'SWD-20261025-A103',
      currentStatus: 'FAILED',
      amountMinorUnits: 25000,
      currency: 'LKR',
      pickupAddress: '10 Temple Rd, Maharagama',
      dropoffAddress: '88 Main St, Panadura',
      createdAt: '2026-10-25T07:15:00Z',
    },
  ],
  totalElements: 3,
  totalPages: 1,
  currentPage: 0,
  pageSize: 10,
  hasNext: false,
  hasPrevious: false,
}

const testParcelDetail: ParcelDetailResponse = {
  parcelId: 'f47ac10b-58cc-4372-a567-0e02b2c3d401',
  trackingNumber: 'SWD-20261025-A101',
  createdBySub: customerFixture.keycloakSub,
  merchantId: null,
  currentStatus: 'DELIVERED',
  version: 4,
  weightKg: 2.5,
  dimensions: '30x20x15',
  amountMinorUnits: 55000,
  currency: 'LKR',
  rateVersion: 'RATE-2026-V1',
  pickupAddress: '123 Galle Rd, Colombo 03',
  dropoffAddress: '45 Kandy Rd, Gampaha',
  activeCourierSub: 'courier-sub-1',
  createdAt: '2026-10-25T08:00:00Z',
}

function authenticatedCustomer(
  roles: readonly SwiftDropRole[] = ['CUSTOMER'],
  subject = customerFixture.keycloakSub,
): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles },
    getAccessToken: async () => 'test-bearer-token',
    profileFetch: async () => new Response(JSON.stringify(customerFixture)),
  }
}

function renderCustomerHistory(auth: AuthContextValue, route = '/customer/history') {
  return render(
    <TestProviders auth={auth}>
      <MemoryRouter initialEntries={[route]}>
        <AppRoutes />
      </MemoryRouter>
    </TestProviders>,
  )
}

describe('Customer History & Tracking View (Issue #27 / LATER-01)', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('route protection & role authorization', () => {
    it('redirects unauthenticated users to the login route', () => {
      renderCustomerHistory(anonymousAuth)
      expect(screen.getByRole('heading', { name: 'Sign in to SwiftDrop' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: /delivery history/i })).not.toBeInTheDocument()
    })

    it('denies access to non-customer roles', () => {
      const courierAuth = authenticatedCustomer(['COURIER'])
      renderCustomerHistory(courierAuth)
      expect(screen.getByText('Access unavailable')).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: /delivery history/i })).not.toBeInTheDocument()
    })
  })

  describe('delivery history listing & pagination', () => {
    it('displays populated deliveries with status badges, routes, and amounts', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(testDeliveriesPage), { status: 200 }),
      )

      renderCustomerHistory(authenticatedCustomer())

      expect(await screen.findByRole('heading', { name: /delivery history/i })).toBeInTheDocument()
      expect(await screen.findByText('SWD-20261025-A101')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261025-A102')).toBeInTheDocument()
      expect(screen.getByText('SWD-20261025-A103')).toBeInTheDocument()

      // Check status pills
      expect(screen.getByText('Delivered')).toBeInTheDocument()
      expect(screen.getByText('In Transit')).toBeInTheDocument()
      expect(screen.getByText('Failed')).toBeInTheDocument()

      // Check financial amount
      expect(screen.getByText('LKR 550.00')).toBeInTheDocument()
      expect(screen.getByText('LKR 320.00')).toBeInTheDocument()
    })

    it('displays empty state when customer has no delivery history', async () => {
      const emptyPage: CustomerDeliveriesPage = {
        items: [],
        totalElements: 0,
        totalPages: 0,
        currentPage: 0,
        pageSize: 10,
        hasNext: false,
        hasPrevious: false,
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(emptyPage), { status: 200 }),
      )

      renderCustomerHistory(authenticatedCustomer())

      expect(await screen.findByText('No delivery history yet')).toBeInTheDocument()
      expect(
        screen.getByText(/your booked deliveries, live journey milestones, and official PDF receipts/i),
      ).toBeInTheDocument()
    })

    it('handles pagination navigation and requests next page correctly', async () => {
      const page0: CustomerDeliveriesPage = {
        ...testDeliveriesPage,
        totalElements: 12,
        totalPages: 2,
        currentPage: 0,
        hasNext: true,
        hasPrevious: false,
      }
      const page1: CustomerDeliveriesPage = {
        ...testDeliveriesPage,
        items: [
          {
            parcelId: 'p-104',
            trackingNumber: 'SWD-20261025-B999',
            currentStatus: 'DELIVERED',
            amountMinorUnits: 55000,
            currency: 'LKR',
            pickupAddress: '123 Galle Rd, Colombo 03',
            dropoffAddress: '45 Kandy Rd, Gampaha',
            createdAt: '2026-10-25T08:00:00Z',
          },
        ],
        totalElements: 12,
        totalPages: 2,
        currentPage: 1,
        hasNext: false,
        hasPrevious: true,
      }

      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(page0), { status: 200 }))
        .mockResolvedValueOnce(new Response(JSON.stringify(page1), { status: 200 }))

      const user = userEvent.setup()
      renderCustomerHistory(authenticatedCustomer())

      expect(await screen.findByText('SWD-20261025-A101')).toBeInTheDocument()
      expect(screen.getByText(/12 total deliveries/i)).toHaveTextContent('Page 1 of 2')

      const prevBtn = screen.getByRole('button', { name: /previous page/i })
      const nextBtn = screen.getByRole('button', { name: /next page/i })

      expect(prevBtn).toBeDisabled()
      expect(nextBtn).toBeEnabled()

      await user.click(nextBtn)

      expect(await screen.findByText('SWD-20261025-B999')).toBeInTheDocument()
      expect(screen.getByText(/12 total deliveries/i)).toHaveTextContent('Page 2 of 2')
      expect(screen.getByRole('button', { name: /next page/i })).toBeDisabled()
      expect(screen.getByRole('button', { name: /previous page/i })).toBeEnabled()
    })
  })

  describe('secure PDF receipt download wiring', () => {
    it('enables receipt download for DELIVERED parcel and triggers browser download', async () => {
      const user = userEvent.setup()
      const createObjectURL = vi.fn().mockReturnValue('blob:http://localhost:5173/receipt-uuid')
      const revokeObjectURL = vi.fn()
      window.URL.createObjectURL = createObjectURL
      window.URL.revokeObjectURL = revokeObjectURL

      const clickSpy = vi.fn()
      vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
        const el = document.createElementNS('http://www.w3.org/1999/xhtml', tagName)
        if (tagName === 'a') el.click = clickSpy
        return el
      })

      const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46])
      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))
        .mockResolvedValueOnce(
          new Response(pdfBytes, {
            status: 200,
            headers: {
              'Content-Type': 'application/pdf',
              'Content-Disposition': 'attachment; filename="receipt-SWD-20261025-A101.pdf"',
            },
          }),
        )

      renderCustomerHistory(authenticatedCustomer())

      expect(await screen.findByText('SWD-20261025-A101')).toBeInTheDocument()

      // The DELIVERED row should have an active Download button
      const downloadBtn = screen.getByRole('button', {
        name: /download pdf receipt for delivery SWD-20261025-A101/i,
      })
      expect(downloadBtn).toBeEnabled()

      // Non-delivered parcels show ineligible badges
      expect(screen.getByText('Not Yet Eligible')).toBeInTheDocument()
      expect(screen.getByText('Receipt Unavailable')).toBeInTheDocument()

      await user.click(downloadBtn)

      await waitFor(() => {
        expect(createObjectURL).toHaveBeenCalled()
        expect(clickSpy).toHaveBeenCalled()
        expect(revokeObjectURL).toHaveBeenCalledWith('blob:http://localhost:5173/receipt-uuid')
      })

      expect(
        await screen.findByText(/official receipt \(receipt-SWD-20261025-A101.pdf\) downloaded successfully/i),
      ).toBeInTheDocument()

      vi.restoreAllMocks()
    })

    it('handles receipt download 403 Forbidden with ownership error feedback', async () => {
      const user = userEvent.setup()
      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              title: 'Forbidden',
              status: 403,
              detail: 'Access is denied. You do not own this parcel.',
            }),
            {
              status: 403,
              headers: { 'Content-Type': 'application/problem+json' },
            },
          ),
        )

      renderCustomerHistory(authenticatedCustomer())

      const downloadBtn = await screen.findByRole('button', {
        name: /download pdf receipt for delivery SWD-20261025-A101/i,
      })

      await user.click(downloadBtn)

      expect(
        await screen.findByText(/you are not authorized to download this receipt \(ownership mismatch\)/i),
      ).toBeInTheDocument()

      // Download button is restored and enabled for retry
      expect(downloadBtn).toBeEnabled()
    })

    it('handles receipt download 404 Not Found gracefully', async () => {
      const user = userEvent.setup()
      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              title: 'Not Found',
              status: 404,
              detail: 'Receipt not found.',
            }),
            {
              status: 404,
              headers: { 'Content-Type': 'application/problem+json' },
            },
          ),
        )

      renderCustomerHistory(authenticatedCustomer())

      const downloadBtn = await screen.findByRole('button', {
        name: /download pdf receipt for delivery SWD-20261025-A101/i,
      })

      await user.click(downloadBtn)

      expect(
        await screen.findByText(/receipt is unavailable or delivery is not yet completed/i),
      ).toBeInTheDocument()
    })
  })

  describe('customer tracking modal & milestone view', () => {
    it('opens tracking modal when clicking tracking link and displays details', async () => {
      const user = userEvent.setup()
      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))
        .mockResolvedValueOnce(new Response(JSON.stringify(testParcelDetail), { status: 200 }))

      renderCustomerHistory(authenticatedCustomer())

      const trackingLink = await screen.findByRole('button', {
        name: /view tracking details for SWD-20261025-A101/i,
      })

      await user.click(trackingLink)

      // Modal should open
      const dialog = await screen.findByRole('dialog')
      expect(dialog).toBeInTheDocument()
      expect(within(dialog).getByRole('heading', { name: 'SWD-20261025-A101' })).toBeInTheDocument()

      // Should display timeline checkpoints
      expect(within(dialog).getByText('Pickup Location')).toBeInTheDocument()
      expect(within(dialog).getByText('123 Galle Rd, Colombo 03')).toBeInTheDocument()
      expect(within(dialog).getByText('Destination Location')).toBeInTheDocument()
      expect(within(dialog).getByText('45 Kandy Rd, Gampaha')).toBeInTheDocument()

      // Should show metadata
      expect(within(dialog).getByText('2.5 kg')).toBeInTheDocument()
      expect(within(dialog).getByText('30x20x15 cm')).toBeInTheDocument()

      // Should display real-time SSE disclaimer
      expect(within(dialog).getByText(/real-time updates/i)).toBeInTheDocument()

      // Close modal
      const closeBtn = within(dialog).getByRole('button', { name: /close tracking details/i })
      await user.click(closeBtn)

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('allows searching a tracking number via quick lookup', async () => {
      const user = userEvent.setup()
      mockFetch
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))
        .mockResolvedValueOnce(new Response(JSON.stringify(testParcelDetail), { status: 200 }))

      renderCustomerHistory(authenticatedCustomer())

      await screen.findByText('SWD-20261025-A101')

      const input = screen.getByRole('textbox', { name: /tracking number or parcel reference/i })
      const trackBtn = screen.getByRole('button', { name: /track shipment/i })

      await user.type(input, 'f47ac10b-58cc-4372-a567-0e02b2c3d401')
      await user.click(trackBtn)

      expect(await screen.findByRole('dialog')).toBeInTheDocument()
    })
  })

  describe('error states & recovery', () => {
    it('displays error panel and allows retrying when backend is unavailable', async () => {
      const user = userEvent.setup()
      mockFetch
        .mockRejectedValueOnce(new Error('Network error'))
        .mockResolvedValueOnce(new Response(JSON.stringify(testDeliveriesPage), { status: 200 }))

      renderCustomerHistory(authenticatedCustomer())

      expect(await screen.findByRole('heading', { name: /unable to load delivery history/i })).toBeInTheDocument()

      const retryBtn = screen.getByRole('button', { name: /^retry$/i })
      await user.click(retryBtn)

      expect(await screen.findByText('SWD-20261025-A101')).toBeInTheDocument()
    })
  })
})
