import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CourierProofError, type createCourierProofApi } from '../api/proof'
import type {
  AssignmentResponse,
  ParcelDetailResponse,
} from '../api/proofContracts'
import { CourierProofWorkspace } from './CourierProofWorkspace'

const MOCK_PARCEL_1 = '00000000-0000-0000-0000-000000000001'
const MOCK_PARCEL_2 = '00000000-0000-0000-0000-000000000002'

const mockAssignments: AssignmentResponse[] = [
  {
    assignmentId: 'assign-1',
    parcelId: MOCK_PARCEL_1,
    courierSub: 'courier-test-sub',
    active: true,
    assignedAt: '2026-10-26T09:00:00Z',
    assignedBy: 'dispatcher-1',
  },
  {
    assignmentId: 'assign-2',
    parcelId: MOCK_PARCEL_2,
    courierSub: 'courier-test-sub',
    active: true,
    assignedAt: '2026-10-26T09:30:00Z',
    assignedBy: 'dispatcher-1',
  },
]

const mockParcel1: ParcelDetailResponse = {
  parcelId: MOCK_PARCEL_1,
  trackingNumber: 'SWD-20261026-0001',
  createdBySub: 'customer-1',
  merchantId: null,
  currentStatus: 'OUT_FOR_DELIVERY',
  version: 2,
  dropoffAddress: 'Colombo 03',
  createdAt: '2026-10-26T08:00:00Z',
}

const mockParcel2: ParcelDetailResponse = {
  parcelId: MOCK_PARCEL_2,
  trackingNumber: 'SWD-20261026-0002',
  createdBySub: 'customer-2',
  merchantId: null,
  currentStatus: 'OUT_FOR_DELIVERY',
  version: 1,
  dropoffAddress: 'Kandy Road, Kelaniya',
  createdAt: '2026-10-26T08:30:00Z',
}

function courierAuth(subject = 'courier-test-sub'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles: ['COURIER'] },
    getAccessToken: async () => 'test-token',
  }
}

describe('CourierProofWorkspace component', () => {
  beforeEach(() => {
    window.URL.createObjectURL = vi.fn(() => 'blob:http://localhost:5173/synthetic-blob')
    window.URL.revokeObjectURL = vi.fn()
  })

  function createMockApi(overrides: Partial<ReturnType<typeof createCourierProofApi>> = {}) {
    return {
      getMyAssignments: vi.fn(async () => mockAssignments),
      getParcel: vi.fn(async (id: string) => (id === MOCK_PARCEL_2 ? mockParcel2 : mockParcel1)),
      getProofMetadata: vi.fn(async () => {
        throw new CourierProofError('not-found', 'No proof recorded')
      }),
      getProofContent: vi.fn(async () => new Blob()),
      uploadProof: vi.fn(async () => ({
        proofId: 'proof-123',
        parcelId: MOCK_PARCEL_1,
        uploadedBySub: 'courier-test-sub',
        uploadedAt: '2026-10-26T12:00:00Z',
      })),
      ...overrides,
    } as unknown as ReturnType<typeof createCourierProofApi>
  }

  it('renders active assignments pills and selects first parcel automatically', async () => {
    const api = createMockApi()
    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByRole('heading', { name: /assigned deliveries for proof capture/i })).toBeInTheDocument()
    expect(screen.getByText('2 Parcels')).toBeInTheDocument()

    // Confirms first parcel context loaded
    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(screen.getByText('Colombo 03')).toBeInTheDocument()
  })

  it('allows switching between assigned parcels', async () => {
    const user = userEvent.setup()
    const api = createMockApi()
    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()

    // Click the second parcel pill
    const pills = screen.getAllByRole('tab')
    expect(pills).toHaveLength(2)
    await user.click(pills[1]!)

    expect(await screen.findByText('SWD-20261026-0002')).toBeInTheDocument()
    expect(screen.getByText('Kandy Road, Kelaniya')).toBeInTheDocument()
  })

  it('displays empty state when courier has zero active assignments', async () => {
    const api = createMockApi({
      getMyAssignments: vi.fn(async () => []),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText(/no active delivery assignments found/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /capture handover evidence/i })).not.toBeInTheDocument()
  })

  it('displays error banner and offers working retry when assignment service fails', async () => {
    const user = userEvent.setup()
    let calls = 0
    const getMyAssignments = vi.fn(async () => {
      calls++
      if (calls === 1) {
        throw new CourierProofError('service-unavailable', 'Assignment service unreachable')
      }
      return mockAssignments
    })
    const api = createMockApi({ getMyAssignments })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/assignment service unreachable/i)

    const retryBtn = screen.getByRole('button', { name: /retry/i })
    await user.click(retryBtn)

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(getMyAssignments).toHaveBeenCalledTimes(2)
  })

  it('toggles delivery exception reporting section on button click', async () => {
    const user = userEvent.setup()
    const api = createMockApi()

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()

    const toggleBtn = screen.getByRole('button', { name: /unable to deliver\? report exception/i })
    await user.click(toggleBtn)

    expect(screen.getByRole('heading', { name: /record failed delivery reason/i })).toBeInTheDocument()

    // Toggle back
    await user.click(screen.getByRole('button', { name: /hide exception form/i }))
    expect(screen.queryByRole('heading', { name: /record failed delivery reason/i })).not.toBeInTheDocument()
  })

  it('immediately removes previous parcel details from DOM when switching parcels', async () => {
    const user = userEvent.setup()
    let resolveParcel2: ((p: ParcelDetailResponse) => void) | undefined
    const parcel2Promise = new Promise<ParcelDetailResponse>((resolve) => {
      resolveParcel2 = resolve
    })

    const api = createMockApi({
      getParcel: vi.fn(async (id: string) => {
        if (id === MOCK_PARCEL_2) {
          return parcel2Promise
        }
        return mockParcel1
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(screen.getByText('Colombo 03')).toBeInTheDocument()

    // Switch to second parcel
    const pills = screen.getAllByRole('tab')
    await user.click(pills[1]!)

    // Old parcel details must be IMMEDIATELY removed from DOM
    expect(screen.queryByText('SWD-20261026-0001')).not.toBeInTheDocument()
    expect(screen.queryByText('Colombo 03')).not.toBeInTheDocument()
    expect(screen.getByText(/loading parcel handover context/i)).toBeInTheDocument()

    // Resolve parcel 2
    resolveParcel2!(mockParcel2)

    expect(await screen.findByText('SWD-20261026-0002')).toBeInTheDocument()
    expect(screen.getByText('Kandy Road, Kelaniya')).toBeInTheDocument()
  })

  it('immediately removes previous courier assignments and parcel data from visible DOM on logout or subject change', async () => {
    const api = createMockApi()
    const { rerender } = render(
      <TestProviders auth={courierAuth('courier-a')}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(screen.getByText('2 Parcels')).toBeInTheDocument()

    // Courier logs out (unauthenticated / empty subject)
    rerender(
      <TestProviders auth={anonymousAuth}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    // DOM must immediately remove all Courier A data
    expect(screen.queryByText('SWD-20261026-0001')).not.toBeInTheDocument()
    expect(screen.queryByText('2 Parcels')).not.toBeInTheDocument()
    expect(screen.queryByText('Colombo 03')).not.toBeInTheDocument()
    expect(screen.getByText('0 Parcels')).toBeInTheDocument()
  })

  it('fails closed and disables proof capture when proof metadata lookup encounters a server error', async () => {
    const api = createMockApi({
      getProofMetadata: vi.fn(async () => {
        throw new CourierProofError('service-unavailable', 'Proof service timed out')
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()

    // Fail-closed alert is shown
    expect(await screen.findByText(/proof service timed out/i)).toBeInTheDocument()
    expect(screen.getByText(/proof submission is locked/i)).toBeInTheDocument()

    // Submit button is disabled
    const submitBtn = screen.getByRole('button', { name: /submit proof of delivery/i })
    expect(submitBtn).toBeDisabled()

    // File input is disabled
    const fileInput = document.getElementById(`proof-file-input-${MOCK_PARCEL_1}`) as HTMLInputElement
    expect(fileInput).toBeDisabled()
  })

  it('fails closed and displays access denied when proof metadata lookup returns 403', async () => {
    const api = createMockApi({
      getProofMetadata: vi.fn(async () => {
        throw new CourierProofError('forbidden', 'Forbidden: You are not assigned to this parcel')
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(await screen.findByText(/forbidden: you are not assigned/i)).toBeInTheDocument()
  })

  it('allows retrying proof verification after an ambiguous error and unlocks form if clean 404', async () => {
    const user = userEvent.setup()
    let calls = 0
    const getProofMetadata = vi.fn(async () => {
      calls++
      if (calls === 1) {
        throw new CourierProofError('service-unavailable', 'Temporary storage failure')
      }
      throw new CourierProofError('not-found', 'No proof recorded')
    })

    const api = createMockApi({ getProofMetadata })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierProofWorkspace apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText(/temporary storage failure/i)).toBeInTheDocument()

    const retryBtn = screen.getByRole('button', { name: /retry verification/i })
    await user.click(retryBtn)

    await waitFor(() => {
      expect(getProofMetadata).toHaveBeenCalledTimes(2)
    })

    // Once clean 404 is confirmed, the lock alert is removed and file input is unlocked
    expect(screen.queryByText(/proof submission is locked/i)).not.toBeInTheDocument()
    const fileInput = document.getElementById(`proof-file-input-${MOCK_PARCEL_1}`) as HTMLInputElement
    expect(fileInput).not.toBeDisabled()
  })
})
