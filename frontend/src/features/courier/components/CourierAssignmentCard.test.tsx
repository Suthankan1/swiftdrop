import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CourierAssignmentCard } from './CourierAssignmentCard'
import type { AssignmentResponse } from '../api/courierContracts'

describe('CourierAssignmentCard component', () => {
  const sampleAssignment: AssignmentResponse = {
    assignmentId: 'assign-101',
    parcelId: '00000000-0000-0000-0000-000000000001',
    courierSub: 'courier-test-sub',
    active: true,
    assignedAt: '2026-10-26T10:15:00Z',
    assignedBy: 'dispatcher-1',
  }

  it('renders assignment details including parcel snippet and active badge', () => {
    render(
      <CourierAssignmentCard
        assignment={sampleAssignment}
        isSelected={false}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByText('00000000...')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('00000000-0000-0000-0000-000000000001')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /view parcel details for 00000000/i }),
    ).toBeInTheDocument()
    expect(screen.getByText(/view details & handover/i)).toBeInTheDocument()
  })

  it('calls onSelect when view details button is clicked', async () => {
    const user = userEvent.setup()
    const handleSelect = vi.fn()

    render(
      <CourierAssignmentCard
        assignment={sampleAssignment}
        isSelected={false}
        onSelect={handleSelect}
      />,
    )

    const button = screen.getByRole('button', {
      name: /view parcel details for 00000000/i,
    })
    await user.click(button)

    expect(handleSelect).toHaveBeenCalledWith('00000000-0000-0000-0000-000000000001')
  })

  it('shows selected state text when isSelected is true', () => {
    render(
      <CourierAssignmentCard
        assignment={sampleAssignment}
        isSelected={true}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByText(/viewing details/i)).toBeInTheDocument()
  })
})
