import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FailedDeliveryReason } from './FailedDeliveryReason'

describe('FailedDeliveryReason component', () => {
  it('renders standard failure reason codes and lifecycle boundary notice', () => {
    render(<FailedDeliveryReason parcelId="parcel-123" trackingNumber="SWD-20261026-0001" />)

    expect(screen.getByRole('heading', { name: /record failed delivery reason/i })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent(/lifecycle boundary notice/i)
    expect(screen.getByRole('combobox', { name: /standard failure reason/i })).toBeInTheDocument()

    // Confirms standard options exist
    expect(screen.getByRole('option', { name: /recipient not available at address/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /premises locked/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /package damaged before handover/i })).toBeInTheDocument()
  })

  it('allows selecting a reason, entering notes, and notifies onSubmitReady', async () => {
    const user = userEvent.setup()
    const onSubmitReady = vi.fn()

    render(
      <FailedDeliveryReason
        parcelId="parcel-123"
        trackingNumber="SWD-20261026-0001"
        onSubmitReady={onSubmitReady}
      />,
    )

    const select = screen.getByRole('combobox', { name: /standard failure reason/i })
    await user.selectOptions(select, 'ACCESS_RESTRICTED')

    const notesInput = screen.getByRole('textbox', { name: /operational notes/i })
    await user.type(notesInput, 'Gate code not provided on ticket.')

    const submitBtn = screen.getByRole('button', { name: /prepare failure metadata/i })
    await user.click(submitBtn)

    expect(onSubmitReady).toHaveBeenCalledWith('ACCESS_RESTRICTED', 'Gate code not provided on ticket.')
    expect(screen.getByRole('status')).toHaveTextContent(/failure reason recorded locally: ACCESS_RESTRICTED/i)
  })

  it('disables controls when disabled prop is true', () => {
    render(<FailedDeliveryReason parcelId="parcel-123" disabled />)

    expect(screen.getByRole('combobox')).toBeDisabled()
    expect(screen.getByRole('textbox')).toBeDisabled()
    expect(screen.getByRole('button', { name: /prepare failure metadata/i })).toBeDisabled()
  })
})
