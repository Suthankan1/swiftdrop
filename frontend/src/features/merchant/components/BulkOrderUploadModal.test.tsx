import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createDeliveryApi } from '../../customer/api/delivery'
import { createPricingApi } from '../../customer/api/pricing'
import { MERCHANT_ORDER_CSV_SAMPLE } from '../api/merchantBooking'
import { BulkOrderUploadModal } from './BulkOrderUploadModal'

describe('BulkOrderUploadModal', () => {
  it('does not render when isOpen is false', () => {
    const { container } = render(
      <BulkOrderUploadModal isOpen={false} onClose={vi.fn()} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders modal dialog when open', () => {
    render(<BulkOrderUploadModal isOpen={true} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Import Orders via CSV')).toBeInTheDocument()
    expect(screen.getByText('📥 Download Sample Template')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('triggers download sample CSV template', async () => {
    const user = userEvent.setup()
    window.URL.createObjectURL = vi.fn(() => 'blob:synthetic-csv')
    window.URL.revokeObjectURL = vi.fn()

    render(<BulkOrderUploadModal isOpen={true} onClose={vi.fn()} />)
    const downloadBtn = screen.getByRole('button', { name: '📥 Download Sample Template' })
    await user.click(downloadBtn)

    expect(window.URL.createObjectURL).toHaveBeenCalled()
  })

  it('displays accessible alert when non-csv file is selected', () => {
    render(<BulkOrderUploadModal isOpen={true} onClose={vi.fn()} />)

    const file = new File(['binary content'], 'document.pdf', { type: 'application/pdf' })
    const input = document.querySelector('input[type="file"]') as HTMLInputElement

    fireEvent.change(input, { target: { files: [file] } })

    const alertBanner = screen.getByRole('alert')
    expect(alertBanner).toBeInTheDocument()
    expect(alertBanner).toHaveTextContent('Please upload a valid .csv file.')
  })

  it('keeps keyboard focus in the dialog when the import button becomes disabled', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const options = { getAccessToken: async () => null }
    const bookingApis = {
      pricing: {
        ...createPricingApi(options),
        calculateQuote: vi.fn().mockResolvedValue({ quoteId: 'quote-focus' }),
      },
      delivery: {
        ...createDeliveryApi(options),
        bookParcel: vi.fn().mockResolvedValue({ trackingNumber: 'SWD-TEST-FOCUS' }),
      },
    }
    render(<BulkOrderUploadModal isOpen onClose={onClose} bookingApis={bookingApis} />)
    const [header, row] = MERCHANT_ORDER_CSV_SAMPLE.split('\r\n')
    const file = new File([header + '\r\n' + row], 'orders.csv', { type: 'text/csv' })
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
    fireEvent.change(input, { target: { files: [file] } })

    await user.click(await screen.findByRole('button', { name: 'Import 1 orders' }))
    await screen.findByText('1 of 1 valid orders booked.')
    expect(screen.getByRole('dialog')).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })
})

