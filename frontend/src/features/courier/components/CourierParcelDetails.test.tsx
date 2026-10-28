import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CourierProofError, type createCourierProofApi } from '../api/proof'
import type { ParcelDetailResponse } from '../api/proofContracts'
import { CourierParcelDetails } from './CourierParcelDetails'

function courierAuth(subject = 'courier-test-sub'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles: ['COURIER'] },
    getAccessToken: async () => 'test-token',
  }
}

describe('CourierParcelDetails component', () => {
  beforeEach(() => {
    window.URL.createObjectURL = vi.fn(() => 'blob:http://localhost:5173/synthetic-blob')
    window.URL.revokeObjectURL = vi.fn()
  })

  const sampleParcel: ParcelDetailResponse = {
    parcelId: 'parcel-101',
    trackingNumber: 'SWD-20261026-9999',
    createdBySub: 'customer-1',
    merchantId: 'merchant-1',
    currentStatus: 'OUT_FOR_DELIVERY',
    version: 4,
    weightKg: 3.5,
    dimensions: '25 × 15 × 10 cm',
    pickupAddress: 'Warehouse 12, Colombo 01',
    dropoffAddress: '77 Galle Road, Colombo 03',
    activeCourierSub: 'courier-test-sub',
    createdAt: '2026-10-26T08:00:00Z',
  }

  function createMockApi(overrides: Partial<ReturnType<typeof createCourierProofApi>> = {}) {
    return {
      getParcel: vi.fn(async () => sampleParcel),
      getProofMetadata: vi.fn(async () => {
        throw new CourierProofError('not-found', 'No proof recorded')
      }),
      getProofContent: vi.fn(async () => new Blob()),
      uploadProof: vi.fn(async () => ({
        proofId: 'proof-101',
        parcelId: 'parcel-101',
        uploadedBySub: 'courier-test-sub',
        uploadedAt: '2026-10-26T12:00:00Z',
      })),
      getMyAssignments: vi.fn(),
      ...overrides,
    } as unknown as ReturnType<typeof createCourierProofApi>
  }

  it('renders authorized parcel details including tracking, specs, pickup, and dropoff', async () => {
    const api = createMockApi()
    const handleClose = vi.fn()

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-101"
          onClose={handleClose}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('heading', { name: 'SWD-20261026-9999' })).toBeInTheDocument()
    expect(screen.getAllByText('OUT_FOR_DELIVERY').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('v4')).toBeInTheDocument()
    expect(screen.getByText('3.5 kg')).toBeInTheDocument()
    expect(screen.getByText('25 × 15 × 10 cm')).toBeInTheDocument()
    expect(screen.getByText('Warehouse 12, Colombo 01')).toBeInTheDocument()
    expect(screen.getAllByText('77 Galle Road, Colombo 03').length).toBeGreaterThanOrEqual(1)
  })

  it('displays neutral "Not available" when optional addresses or specs are missing', async () => {
    const sparseParcel: ParcelDetailResponse = {
      parcelId: 'parcel-102',
      trackingNumber: 'SWD-20261026-8888',
      createdBySub: 'customer-1',
      merchantId: null,
      currentStatus: 'OUT_FOR_DELIVERY',
      version: 1,
      activeCourierSub: 'courier-test-sub',
      createdAt: '2026-10-26T08:00:00Z',
    }

    const api = createMockApi({
      getParcel: vi.fn(async () => sparseParcel),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-102"
          onClose={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('heading', { name: 'SWD-20261026-8888' })).toBeInTheDocument()
    // Should display 'Not available' for missing weight, dimensions, pickup, dropoff
    const notAvailableElements = screen.getAllByText('Not available')
    expect(notAvailableElements.length).toBeGreaterThanOrEqual(3)
  })

  it('enforces object-level authorization and denies access if parcel belongs to another courier', async () => {
    const otherCourierParcel: ParcelDetailResponse = {
      ...sampleParcel,
      activeCourierSub: 'someone-else-courier',
    }
    const api = createMockApi({
      getParcel: vi.fn(async () => otherCourierParcel),
    })

    render(
      <TestProviders auth={courierAuth('courier-test-sub')}>
        <CourierParcelDetails
          parcelId="parcel-101"
          onClose={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /access denied: you are not the active courier assigned to this parcel/i,
    )
    expect(screen.queryByRole('heading', { name: 'SWD-20261026-9999' })).not.toBeInTheDocument()
  })

  it('handles HTTP 403 forbidden response from Delivery REST provider', async () => {
    const api = createMockApi({
      getParcel: vi.fn(async () => {
        throw new CourierProofError('forbidden', 'Forbidden parcel read', undefined, 403)
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-101"
          onClose={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /access denied: you are not authorized to view this parcel/i,
    )
  })

  it('handles HTTP 404 not found or reassigned parcel', async () => {
    const api = createMockApi({
      getParcel: vi.fn(async () => {
        throw new CourierProofError('not-found', 'Parcel not found', undefined, 404)
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-missing"
          onClose={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /parcel not found or no longer assigned to you/i,
    )
  })

  it('calls onClose when back button is clicked', async () => {
    const user = userEvent.setup()
    const handleClose = vi.fn()
    const api = createMockApi()

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-101"
          onClose={handleClose}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('heading', { name: 'SWD-20261026-9999' })).toBeInTheDocument()

    const backButton = screen.getByRole('button', { name: /back to assignments list/i })
    await user.click(backButton)

    expect(handleClose).toHaveBeenCalledOnce()
  })

  it('integrates stage-filtered exception reporting in the parcel workflow', async () => {
    const api = createMockApi()

    render(
      <TestProviders auth={courierAuth()}>
        <CourierParcelDetails
          parcelId="parcel-101"
          onClose={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('heading', { name: 'SWD-20261026-9999' })).toBeInTheDocument()

    expect(
      await screen.findByRole('heading', { name: /record failed delivery reason/i }),
    ).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /recipient unavailable/i })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /parcel package physically damaged/i })).not.toBeInTheDocument()
  })
})
