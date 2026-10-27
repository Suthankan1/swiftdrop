import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { createCourierProofApi } from '../api/proof'
import type { ParcelDetailResponse, ParcelStatus, ProofDetailResponse } from '../api/proofContracts'
import type { createCourierStatusApi } from '../api/status'
import { CourierStatusError } from '../api/statusContracts'
import { CourierStageActions, type CourierStageActionsProps } from './CourierStageActions'

const subject = 'courier-153'

function parcel(status: ParcelStatus, version = 2): ParcelDetailResponse {
  return {
    parcelId: 'parcel-153',
    trackingNumber: 'SWD-20261027-0153',
    createdBySub: 'customer-1',
    merchantId: null,
    currentStatus: status,
    version,
    activeCourierSub: subject,
    createdAt: '2026-10-27T08:00:00Z',
  }
}

const proof: ProofDetailResponse = {
  proofId: 'proof-153',
  parcelId: 'parcel-153',
  objectReference: 'private/internal/object',
  evidenceType: 'PHOTO',
  contentType: 'image/jpeg',
  contentLength: 1234,
  recipientName: 'Recipient',
  deliveryNote: null,
  uploadedBySub: subject,
  uploadedAt: '2026-10-27T08:15:00Z',
}

const targetByStatus = {
  CREATED: 'PICKED_UP',
  PICKED_UP: 'IN_TRANSIT',
  IN_TRANSIT: 'OUT_FOR_DELIVERY',
  OUT_FOR_DELIVERY: 'DELIVERED',
} as const

const labelByStatus = {
  CREATED: 'Confirm Pickup',
  PICKED_UP: 'Mark In Transit',
  IN_TRANSIT: 'Mark Out for Delivery',
  OUT_FOR_DELIVERY: 'Complete Delivery',
} as const

function setup(
  current: ParcelDetailResponse,
  options: {
    reconciled?: ParcelDetailResponse[]
    statusApi?: Partial<ReturnType<typeof createCourierStatusApi>>
    proofApi?: Partial<ReturnType<typeof createCourierProofApi>>
    proofRecord?: ProofDetailResponse | null
    proofStatus?: CourierStageActionsProps['proofLookupStatus']
  } = {},
) {
  const target = targetByStatus[current.currentStatus as keyof typeof targetByStatus] || 'FAILED'
  const reconciled = options.reconciled || [current, parcel(target, current.version + 1)]
  const getParcel = vi.fn(async () => reconciled.shift() || current)
  const getProofMetadata = vi.fn(async () => proof)
  const proofApi = {
    getParcel,
    getProofMetadata,
    ...options.proofApi,
  } as unknown as ReturnType<typeof createCourierProofApi>
  const updateStatus = vi.fn(async () => ({
    parcelId: current.parcelId,
    trackingNumber: current.trackingNumber,
    previousStatus: current.currentStatus,
    currentStatus: target,
    stateVersion: current.version + 1,
    updatedParcelVersion: current.version + 1,
    occurredAt: '2026-10-27T08:30:00Z',
  }))
  const statusApi = {
    updateStatus,
    ...options.statusApi,
  } as ReturnType<typeof createCourierStatusApi>
  const onParcelRefresh = vi.fn()
  const onProofRefresh = vi.fn()
  const onAssignmentLost = vi.fn()

  render(
    <CourierStageActions
      parcel={current}
      subject={subject}
      proof={options.proofRecord === undefined ? null : options.proofRecord}
      proofLookupStatus={options.proofStatus || 'verified-none'}
      proofApi={proofApi}
      statusApi={statusApi}
      onParcelRefresh={onParcelRefresh}
      onProofRefresh={onProofRefresh}
      onAssignmentLost={onAssignmentLost}
    />,
  )

  return { getParcel, getProofMetadata, updateStatus, onParcelRefresh, onProofRefresh, onAssignmentLost }
}

describe('CourierStageActions', () => {
  it.each([
    ['CREATED', 'Confirm Pickup'],
    ['PICKED_UP', 'Mark In Transit'],
    ['IN_TRANSIT', 'Mark Out for Delivery'],
  ] as const)('renders only the permitted forward action for %s', (status, label) => {
    setup(parcel(status))

    expect(screen.getByRole('button', { name: label })).toBeInTheDocument()
    for (const otherLabel of Object.values(labelByStatus)) {
      if (otherLabel !== label) expect(screen.queryByRole('button', { name: otherLabel })).not.toBeInTheDocument()
    }
  })

  it('submits trimmed operational notes with the freshly read version and confirms through canonical reload', async () => {
    const user = userEvent.setup()
    const current = parcel('CREATED', 5)
    const result = setup(current, { reconciled: [current, parcel('PICKED_UP', 6)] })

    await user.type(screen.getByRole('textbox', { name: /operational notes/i }), '  Collected from sender desk.  ')
    await user.click(screen.getByRole('button', { name: 'Confirm Pickup' }))

    expect(result.updateStatus).toHaveBeenCalledOnce()
    expect(result.updateStatus).toHaveBeenCalledWith(
      'parcel-153',
      expect.objectContaining({
        newStatus: 'PICKED_UP',
        expectedVersion: 5,
        note: '  Collected from sender desk.  ',
      }),
      expect.any(AbortSignal),
    )
    expect(await screen.findByRole('status')).toHaveTextContent(/confirmed.*PICKED_UP/i)
  })

  it('filters failure reasons by courier actor and stage, then sends prepared reason and notes in the real command', async () => {
    const user = userEvent.setup()
    const current = parcel('PICKED_UP', 3)
    const result = setup(current, { reconciled: [current, parcel('FAILED', 4)] })

    expect(screen.getByRole('option', { name: /parcel package physically damaged/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /force majeure/i })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /cancelled by sender/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /recipient unavailable/i })).not.toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox'), 'FORCE_MAJEURE')
    await user.type(screen.getAllByRole('textbox', { name: /operational notes/i })[1]!, 'Road closed')
    await user.click(screen.getByRole('button', { name: /prepare failure metadata/i }))
    expect(result.updateStatus).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: /confirm failed status/i }))

    expect(result.updateStatus).toHaveBeenCalledWith(
      'parcel-153',
      expect.objectContaining({
        newStatus: 'FAILED',
        expectedVersion: 3,
        reason: 'FORCE_MAJEURE',
        note: 'Road closed',
      }),
      expect.any(AbortSignal),
    )
  })

  it('does not expose courier-owned failure actions at CREATED', () => {
    setup(parcel('CREATED'))

    expect(screen.queryByRole('heading', { name: /record failed delivery reason/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/cancelled by sender/i)).not.toBeInTheDocument()
  })

  it.each([
    ['verified-none', null],
    ['error', null],
    ['verified-exists', { ...proof, parcelId: 'different-parcel' }],
  ] as const)('blocks delivery when proof state is %s or belongs to a different parcel', (proofStatus, proofRecord) => {
    const current = parcel('OUT_FOR_DELIVERY', 7)
    const blocked = setup(current, { proofStatus, proofRecord })
    expect(screen.getByRole('button', { name: 'Complete Delivery' })).toBeDisabled()
    expect(blocked.updateStatus).not.toHaveBeenCalled()
  })

  it('reverifies assignment, stage, and proof immediately before completing delivery', async () => {
    const user = userEvent.setup()
    const current = parcel('OUT_FOR_DELIVERY', 7)
    const result = setup(current, {
      reconciled: [current, parcel('DELIVERED', 8)],
      proofRecord: proof,
      proofStatus: 'verified-exists',
    })

    await user.click(screen.getByRole('button', { name: 'Complete Delivery' }))

    expect(result.getProofMetadata).toHaveBeenCalledWith('parcel-153', expect.any(AbortSignal))
    expect(result.updateStatus).toHaveBeenCalledWith(
      'parcel-153',
      expect.objectContaining({
        newStatus: 'DELIVERED',
        expectedVersion: 7,
        proofId: 'proof-153',
      }),
      expect.any(AbortSignal),
    )
    expect(screen.queryByText('private/internal/object')).not.toBeInTheDocument()
    expect(await screen.findByRole('status')).toHaveTextContent(/confirmed.*DELIVERED/i)
  })

  it('reloads on conflict, never auto-repeats, and uses the new version only after a fresh user action', async () => {
    const user = userEvent.setup()
    const current = parcel('PICKED_UP', 2)
    let attempt = 0
    const updateStatus = vi.fn<ReturnType<typeof createCourierStatusApi>['updateStatus']>(async () => {
      attempt++
      if (attempt === 1) throw new CourierStatusError('conflict', 'Version conflict', 409)
      return {
        parcelId: 'parcel-153',
        trackingNumber: 'SWD-20261027-0153',
        previousStatus: 'PICKED_UP' as const,
        currentStatus: 'IN_TRANSIT' as const,
        stateVersion: 4,
        updatedParcelVersion: 4,
        occurredAt: '2026-10-27T08:30:00Z',
      }
    })
    const result = setup(current, {
      reconciled: [current, parcel('PICKED_UP', 3), parcel('PICKED_UP', 3), parcel('IN_TRANSIT', 4)],
      statusApi: { updateStatus },
    })

    const stageNote = screen.getAllByRole('textbox', { name: /operational notes/i })[0]!
    await user.type(stageNote, 'Keep this note')
    await user.click(screen.getByRole('button', { name: 'Mark In Transit' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/updated elsewhere.*no command was repeated/i)
    expect(result.getParcel).toHaveBeenCalledTimes(2)
    expect(updateStatus).toHaveBeenCalledOnce()
    expect(result.onParcelRefresh).toHaveBeenLastCalledWith(expect.objectContaining({ version: 3 }))
    expect(stageNote).toHaveValue('Keep this note')

    await user.click(screen.getByRole('button', { name: 'Mark In Transit' }))

    expect(updateStatus).toHaveBeenCalledTimes(2)
    expect(updateStatus.mock.calls[1]![1]).toMatchObject({ expectedVersion: 3 })
  })

  it('reconciles an ambiguous network outcome and reports success only when canonical state confirms it', async () => {
    const user = userEvent.setup()
    const current = parcel('IN_TRANSIT', 4)
    const updateStatus = vi.fn(async () => {
      throw new CourierStatusError('ambiguous-outcome', 'Connection lost after submission.')
    })
    setup(current, {
      reconciled: [current, parcel('OUT_FOR_DELIVERY', 5)],
      statusApi: { updateStatus },
    })

    await user.click(screen.getByRole('button', { name: 'Mark Out for Delivery' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/confirmed by the authoritative parcel state/i)
    expect(updateStatus).toHaveBeenCalledOnce()
  })

  it('removes mutation access when authoritative refresh detects reassignment', async () => {
    const user = userEvent.setup()
    const current = parcel('CREATED', 2)
    const reassigned = { ...current, activeCourierSub: 'other-courier' }
    const result = setup(current, { reconciled: [reassigned] })

    await user.click(screen.getByRole('button', { name: 'Confirm Pickup' }))

    expect(result.onAssignmentLost).toHaveBeenCalledWith(expect.stringMatching(/no longer assigned/i))
    expect(result.updateStatus).not.toHaveBeenCalled()
  })

  it.each(['DELIVERED', 'FAILED'] as const)('offers no mutation actions for terminal status %s', (status) => {
    setup(parcel(status))

    expect(screen.getByText(/terminal.*no further courier lifecycle actions/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /confirm pickup|mark in transit|complete delivery|confirm failed/i })).not.toBeInTheDocument()
  })

  it('prevents concurrent double submission', async () => {
    const user = userEvent.setup()
    const current = parcel('CREATED', 2)
    const updateStatus = vi.fn(() => new Promise<never>(() => {}))
    setup(current, { reconciled: [current], statusApi: { updateStatus } })
    const button = screen.getByRole('button', { name: 'Confirm Pickup' })

    await user.dblClick(button)

    expect(updateStatus).toHaveBeenCalledOnce()
    expect(button).toBeDisabled()
  })
})
