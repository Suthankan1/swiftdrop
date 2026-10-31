import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AiDeliveryAssistant } from './AiDeliveryAssistant'

describe('AiDeliveryAssistant (Bonus Feature B02)', () => {
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    mockFetch = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function renderAssistant() {
    return render(
      <MemoryRouter>
        <AiDeliveryAssistant />
      </MemoryRouter>,
    )
  }

  it('renders launcher button in closed state initially', () => {
    renderAssistant()
    const launcher = screen.getByRole('button', { name: /open ai logistics assistant/i })
    expect(launcher).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).not.toBeInTheDocument()
  })

  it('toggles open on click and shows welcome message and suggested prompt chips', async () => {
    const user = userEvent.setup()
    renderAssistant()

    const launcher = screen.getByRole('button', { name: /open ai logistics assistant/i })
    await user.click(launcher)

    expect(screen.getByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'SwiftDrop AI Assistant' })).toBeInTheDocument()
    expect(screen.getByText(/i am your swiftdrop logistics assistant/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /track parcel swd-20261026-a1b2c3/i })).toBeInTheDocument()
  })

  it('dismisses when clicking close button or pressing Escape key', async () => {
    const user = userEvent.setup()
    renderAssistant()

    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))
    expect(screen.getByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).toBeInTheDocument()

    // Test close button
    await user.click(screen.getByRole('button', { name: /dismiss ai assistant/i }))
    expect(screen.queryByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).not.toBeInTheDocument()

    // Re-open and test Escape key
    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))
    expect(screen.getByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: /swiftdrop ai logistics assistant/i })).not.toBeInTheDocument()
  })

  it('executes parcel tracking tool with live backend API call', async () => {
    const user = userEvent.setup()
    const mockTrackingResponse = {
      trackingNumber: 'SWD-20261026-A1B2C3',
      currentStatus: 'IN_TRANSIT',
      originCity: 'Colombo 03',
      destinationCity: 'Gampaha',
      timeline: [
        {
          status: 'IN_TRANSIT',
          occurredAt: '2026-10-26T12:00:00Z',
          cityOrZone: 'Colombo 03',
        },
      ],
    }

    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(mockTrackingResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderAssistant()
    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))

    const input = screen.getByRole('textbox', { name: /ask swiftdrop ai assistant/i })
    await user.type(input, 'track SWD-20261026-A1B2C3')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => {
      expect(screen.getByText(/authoritative live status for shipment/i)).toBeInTheDocument()
    })

    expect(screen.getByText('IN_TRANSIT')).toBeInTheDocument()
    expect(screen.getByText('Colombo 03')).toBeInTheDocument()
    expect(screen.getByText('Gampaha')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open live checkpoints/i })).toHaveAttribute(
      'href',
      '/tracking?trackingNumber=SWD-20261026-A1B2C3',
    )
  })

  it('executes rate quote calculation tool with live backend API call', async () => {
    const user = userEvent.setup()
    const mockQuoteResponse = {
      quoteId: 'quote-test-123',
      rateVersion: 'RATE-2026-V1',
      pickupZone: 'ZONE_1',
      dropoffZone: 'ZONE_2',
      weightKg: 2.5,
      volumetricWeightKg: 1.5,
      chargeableWeightKg: 2.5,
      sizeCategory: 'MEDIUM',
      baseFeeMinorUnits: 35000,
      weightSurchargeMinorUnits: 15000,
      totalAmountMinorUnits: 50000,
      currency: 'LKR',
      createdAt: '2026-10-26T10:00:00Z',
      expiresAt: '2026-10-26T11:00:00Z',
      isExpired: false,
    }

    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(mockQuoteResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderAssistant()
    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))

    const input = screen.getByRole('textbox', { name: /ask swiftdrop ai assistant/i })
    await user.type(input, 'quote for 2.5kg from ZONE_1 to ZONE_2')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => {
      expect(screen.getByText(/guaranteed rate quote for a/i)).toBeInTheDocument()
    })

    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
    expect(screen.getByText('LKR 500.00')).toBeInTheDocument()
    expect(screen.getByText(/rate-2026-v1/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /book this delivery/i })).toHaveAttribute('href', '/customer/quote')
  })

  it('queries delivery zones tool and displays authoritative zones list', async () => {
    const user = userEvent.setup()
    const mockZonesResponse = {
      activeRateVersion: 'RATE-2026-V1',
      zones: [
        { zoneCode: 'ZONE_1', name: 'Colombo Central', description: 'Colombo 1-15', standardTransitHours: 4 },
        { zoneCode: 'ZONE_2', name: 'Greater Colombo', description: 'Dehiwala, Nugegoda', standardTransitHours: 8 },
      ],
    }

    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(mockZonesResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderAssistant()
    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))

    await user.click(screen.getByRole('button', { name: /what are the western province delivery zones\?/i }))

    await waitFor(() => {
      expect(screen.getByText(/operates across/i)).toBeInTheDocument()
    })

    expect(screen.getByText('Colombo Central')).toBeInTheDocument()
    expect(screen.getByText('Greater Colombo')).toBeInTheDocument()
    expect(screen.getByText('~4h')).toBeInTheDocument()
    expect(screen.getByText('~8h')).toBeInTheDocument()
  })

  it('provides guided answers for merchant organization questions', async () => {
    const user = userEvent.setup()
    renderAssistant()
    await user.click(screen.getByRole('button', { name: /open ai logistics assistant/i }))

    await user.click(screen.getByRole('button', { name: /how do i register as a merchant\?/i }))

    await waitFor(() => {
      expect(screen.getByText(/commercial merchants can enroll for self-service verification/i)).toBeInTheDocument()
    })
  })
})
