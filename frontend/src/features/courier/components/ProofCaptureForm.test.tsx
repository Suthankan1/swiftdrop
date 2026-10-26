import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CourierProofError, type createCourierProofApi } from '../api/proof'
import type {
  ParcelDetailResponse,
  ProofDetailResponse,
  ProofResponse,
} from '../api/proofContracts'
import { ProofCaptureForm } from './ProofCaptureForm'

const MOCK_PARCEL_ID = '00000000-0000-0000-0000-000000000001'
const MOCK_PROOF_ID = '00000000-0000-0000-0000-000000000099'

const outForDeliveryParcel: ParcelDetailResponse = {
  parcelId: MOCK_PARCEL_ID,
  trackingNumber: 'SWD-20261026-0001',
  createdBySub: 'customer-test-sub',
  merchantId: null,
  currentStatus: 'OUT_FOR_DELIVERY',
  version: 3,
  weightKg: 2.0,
  dimensions: '10x10x10',
  amountMinorUnits: 1500,
  currency: 'LKR',
  rateVersion: 'v1.0',
  pickupAddress: 'Colombo 03',
  dropoffAddress: '45 Kandy Road, Gampaha',
  activeCourierSub: 'courier-test-sub',
  createdAt: '2026-10-26T08:00:00Z',
}

const createdParcel: ParcelDetailResponse = {
  ...outForDeliveryParcel,
  currentStatus: 'CREATED',
}

const deliveredTerminalParcel: ParcelDetailResponse = {
  ...outForDeliveryParcel,
  currentStatus: 'DELIVERED',
}

const mockProofResponse: ProofResponse = {
  proofId: MOCK_PROOF_ID,
  parcelId: MOCK_PARCEL_ID,
  uploadedBySub: 'courier-test-sub',
  uploadedAt: '2026-10-26T12:00:00Z',
}

const mockProofDetail: ProofDetailResponse = {
  proofId: MOCK_PROOF_ID,
  parcelId: MOCK_PARCEL_ID,
  objectReference: 'proof/internal/key.jpg',
  evidenceType: 'PHOTO',
  contentType: 'image/jpeg',
  contentLength: 500000,
  recipientName: 'Alice Silva',
  deliveryNote: 'Left with security guard',
  uploadedBySub: 'courier-test-sub',
  uploadedAt: '2026-10-26T12:00:00Z',
}

describe('ProofCaptureForm component', () => {
  beforeEach(() => {
    window.URL.createObjectURL = vi.fn(() => 'blob:http://localhost:5173/synthetic-blob')
    window.URL.revokeObjectURL = vi.fn()
  })

  function createMockApi(overrides: Partial<ReturnType<typeof createCourierProofApi>> = {}) {
    return {
      getMyAssignments: vi.fn(async () => []),
      getParcel: vi.fn(async () => outForDeliveryParcel),
      getProofMetadata: vi.fn(async () => mockProofDetail),
      getProofContent: vi.fn(async () => new Blob([new Uint8Array(100)], { type: 'image/jpeg' })),
      uploadProof: vi.fn(async () => mockProofResponse),
      ...overrides,
    } as unknown as ReturnType<typeof createCourierProofApi>
  }

  it('renders parcel context and enabled upload zone when OUT_FOR_DELIVERY', () => {
    const api = createMockApi()
    render(
      <ProofCaptureForm
        parcel={outForDeliveryParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    expect(screen.getByRole('heading', { name: /capture handover evidence/i })).toBeInTheDocument()
    expect(screen.getByText('SWD-20261026-0001')).toBeInTheDocument()
    expect(screen.getByText('45 Kandy Road, Gampaha')).toBeInTheDocument()
    expect(screen.getAllByText('OUT_FOR_DELIVERY').length).toBeGreaterThanOrEqual(1)

    const submitBtn = screen.getByRole('button', { name: /submit proof of delivery/i })

    expect(submitBtn).toBeDisabled() // disabled until file is selected
  })

  it('restricts submission and displays warning when parcel is in CREATED state', () => {
    const api = createMockApi()
    render(
      <ProofCaptureForm
        parcel={createdParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    expect(
      screen.getByText(/proof upload requires parcel status OUT_FOR_DELIVERY/i),
    ).toBeInTheDocument()

    const fileInput = document.getElementById(
      `proof-file-input-${MOCK_PARCEL_ID}`,
    ) as HTMLInputElement
    expect(fileInput).toBeDisabled()
  })

  it('blocks upload and alerts when parcel is in terminal DELIVERED state', () => {
    const api = createMockApi()
    render(
      <ProofCaptureForm
        parcel={deliveredTerminalParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent(
      /parcel is in terminal state \(DELIVERED\)\. proof capture is closed\./i,
    )
  })

  it('displays client validation error when selecting an empty or invalid file', async () => {
    const user = userEvent.setup()
    const api = createMockApi()
    render(
      <ProofCaptureForm
        parcel={outForDeliveryParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    const fileInput = document.getElementById(
      `proof-file-input-${MOCK_PARCEL_ID}`,
    ) as HTMLInputElement
    const emptyFile = new File([], 'empty.jpg', { type: 'image/jpeg' })

    await user.upload(fileInput, emptyFile)

    expect(await screen.findByRole('alert')).toHaveTextContent(/empty \(0 bytes\)/i)
    expect(api.uploadProof).not.toHaveBeenCalled()
  })

  it('allows file selection, previews photo, and commits upload with progress and confirmation', async () => {
    const user = userEvent.setup()
    let progressCallback: ((percent: number) => void) | undefined
    const uploadProof = vi.fn(async (params) => {
      progressCallback = params.onProgress
      if (progressCallback) progressCallback(50)
      return mockProofResponse
    })
    const api = createMockApi({ uploadProof })

    render(
      <ProofCaptureForm
        parcel={outForDeliveryParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    const fileInput = document.getElementById(
      `proof-file-input-${MOCK_PARCEL_ID}`,
    ) as HTMLInputElement
    const validFile = new File([new Uint8Array(2048)], 'front-door.jpg', {
      type: 'image/jpeg',
    })

    await user.upload(fileInput, validFile)

    // Confirms preview is displayed
    expect(screen.getByRole('img', { name: /handover proof preview/i })).toBeInTheDocument()
    expect(screen.getByText('front-door.jpg')).toBeInTheDocument()

    // Add recipient name and delivery note
    const recipientInput = screen.getByLabelText(/recipient name/i)
    await user.type(recipientInput, 'Alice Silva')

    const noteInput = screen.getByLabelText(/delivery handover note/i)
    await user.type(noteInput, 'Left with security guard')

    // Click submit to trigger confirmation dialog
    const submitBtn = screen.getByRole('button', { name: /submit proof of delivery/i })
    expect(submitBtn).toBeEnabled()
    await user.click(submitBtn)

    // Confirmation dialog
    const dialog = screen.getByRole('dialog', { name: /confirm proof submission/i })
    expect(dialog).toBeInTheDocument()
    expect(within(dialog).getByText(/Alice Silva/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/Left with security guard/i)).toBeInTheDocument()

    // Confirm upload
    const confirmBtn = screen.getByRole('button', { name: /yes, upload proof/i })

    await user.click(confirmBtn)

    await waitFor(() => {
      expect(uploadProof).toHaveBeenCalledWith(
        expect.objectContaining({
          parcelId: MOCK_PARCEL_ID,
          recipientName: 'Alice Silva',
          deliveryNote: 'Left with security guard',
        }),
      )
    })

    // Confirms success state rendered
    expect(await screen.findByRole('heading', { name: /proof of delivery confirmed/i })).toBeInTheDocument()
    expect(screen.getByText(MOCK_PROOF_ID)).toBeInTheDocument()
  })

  it('renders existing proof status directly if proof already confirmed', () => {
    const api = createMockApi()
    render(
      <ProofCaptureForm
        parcel={outForDeliveryParcel}
        api={api}
        subject="courier-test-sub"
        existingProof={mockProofDetail}
      />,
    )

    expect(screen.getByRole('heading', { name: /proof of delivery confirmed/i })).toBeInTheDocument()
    expect(screen.getByText('Alice Silva')).toBeInTheDocument()
    expect(screen.getByText('Left with security guard')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /submit proof of delivery/i })).not.toBeInTheDocument()
  })

  it('handles ambiguous upload failure and offers server reconciliation', async () => {
    const user = userEvent.setup()
    const uploadProof = vi.fn(async () => {
      throw new CourierProofError(
        'ambiguous-failure',
        'Upload connection was interrupted. Verify server state before retrying.',
      )
    })
    const getProofMetadata = vi.fn(async () => mockProofDetail)
    const api = createMockApi({ uploadProof, getProofMetadata })

    render(
      <ProofCaptureForm
        parcel={outForDeliveryParcel}
        api={api}
        subject="courier-test-sub"
      />,
    )

    const fileInput = document.getElementById(
      `proof-file-input-${MOCK_PARCEL_ID}`,
    ) as HTMLInputElement
    const validFile = new File([new Uint8Array(1024)], 'photo.jpg', { type: 'image/jpeg' })
    await user.upload(fileInput, validFile)

    await user.click(screen.getByRole('button', { name: /submit proof of delivery/i }))
    await user.click(screen.getByRole('button', { name: /yes, upload proof/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/interrupted/i)

    // Reconcile button appears
    const reconcileBtn = screen.getByRole('button', { name: /check server status & reconcile/i })
    expect(reconcileBtn).toBeInTheDocument()

    // Click reconcile
    await user.click(reconcileBtn)

    expect(getProofMetadata).toHaveBeenCalledWith(MOCK_PARCEL_ID)
    expect(await screen.findByRole('heading', { name: /proof of delivery confirmed/i })).toBeInTheDocument()
  })
})
