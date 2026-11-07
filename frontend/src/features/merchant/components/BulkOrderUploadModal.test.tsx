import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
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
})

