import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FailedDeliveryReason } from './FailedDeliveryReason'

describe('FailedDeliveryReason component', () => {
  it('renders approved failure reason codes and lifecycle boundary notice', () => {
    render(<FailedDeliveryReason parcelId="parcel-123" trackingNumber="SWD-20261026-0001" />)

    expect(screen.getByRole('heading', { name: /record failed delivery reason/i })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent(/lifecycle boundary notice/i)
    expect(screen.getByRole('note')).toHaveTextContent(/no status change has been committed to the backend/i)
    expect(screen.getByRole('combobox', { name: /approved failure reason/i })).toBeInTheDocument()

    // Confirms approved backend options exist
    expect(screen.getByRole('option', { name: /recipient unavailable or absent/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /delivery refused by recipient/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /parcel package physically damaged/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /force majeure/i })).toBeInTheDocument()

    // Confirms unapproved codes are rejected / not present
    expect(screen.queryByRole('option', { name: /customer_rejected/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /access_restricted/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /other operational reason/i })).not.toBeInTheDocument()
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

    const select = screen.getByRole('combobox', { name: /approved failure reason/i })
    await user.selectOptions(select, 'REFUSED_BY_RECIPIENT')

    const notesInput = screen.getByRole('textbox', { name: /operational notes/i })
    await user.type(notesInput, 'Recipient declined delivery citing wrong item order.')

    const submitBtn = screen.getByRole('button', { name: /prepare failure metadata/i })
    await user.click(submitBtn)

    expect(onSubmitReady).toHaveBeenCalledWith('REFUSED_BY_RECIPIENT', 'Recipient declined delivery citing wrong item order.')
    expect(screen.getByRole('status')).toHaveTextContent(/failure reason recorded locally: REFUSED_BY_RECIPIENT/i)
    expect(screen.getByRole('status')).toHaveTextContent(/not committed to backend/i)
  })

  it('disables controls when disabled prop is true', () => {
    render(<FailedDeliveryReason parcelId="parcel-123" disabled />)

    expect(screen.getByRole('combobox')).toBeDisabled()
    expect(screen.getByRole('textbox')).toBeDisabled()
    expect(screen.getByRole('button', { name: /prepare failure metadata/i })).toBeDisabled()
  })
})
