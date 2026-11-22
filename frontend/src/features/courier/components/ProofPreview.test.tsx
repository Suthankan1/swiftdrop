import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProofPreview } from './ProofPreview'

describe('ProofPreview component', () => {
  let createObjectURLSpy: ReturnType<typeof vi.fn>
  let revokeObjectURLSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    createObjectURLSpy = vi.fn(() => 'blob:http://localhost:5173/synthetic-blob-uuid')
    revokeObjectURLSpy = vi.fn(() => {})
    window.URL.createObjectURL = createObjectURLSpy as unknown as typeof window.URL.createObjectURL
    window.URL.revokeObjectURL = revokeObjectURLSpy as unknown as typeof window.URL.revokeObjectURL
  })



  afterEach(() => {
    vi.restoreAllMocks()
  })

  it.each([
    ['courier-photo.jpg', 'image/jpeg'],
    ['courier-photo.png', 'image/png'],
    ['courier-photo.webp', 'image/webp'],
  ])('renders a selected %s preview through its browser object URL', (fileName, mimeType) => {
    const file = new File([new Uint8Array(1024)], fileName, { type: mimeType })

    render(
      <ProofPreview
        source={file}
        title="Delivery Proof Preview"
        altText="Handover image for parcel"
      />,
    )

    expect(screen.getByRole('heading', { name: 'Delivery Proof Preview' })).toBeInTheDocument()
    const img = screen.getByRole('img', { name: 'Handover image for parcel' })
    expect(img).toHaveAttribute('src', 'blob:http://localhost:5173/synthetic-blob-uuid')
    expect(screen.getByText(fileName)).toBeInTheDocument()
    expect(screen.getByText('1.0 KB')).toBeInTheDocument()
    expect(screen.getByText(mimeType)).toBeInTheDocument()
  })

  it('renders an authorized stored-proof Blob without exposing a storage URL', () => {
    const blob = new Blob([new Uint8Array(256)], { type: 'image/webp' })

    render(
      <ProofPreview
        source={blob}
        title="Stored Proof Evidence (Private Stream)"
        fileName="stored-proof.webp"
      />,
    )

    expect(screen.getByRole('img', { name: /captured proof of delivery photo/i })).toHaveAttribute(
      'src',
      'blob:http://localhost:5173/synthetic-blob-uuid',
    )
    expect(screen.getByText('stored-proof.webp')).toBeInTheDocument()
    expect(screen.getByText('image/webp')).toBeInTheDocument()
  })

  it('revokes the created object URL when the component unmounts to prevent memory leaks', () => {
    const file = new File([new Uint8Array(500)], 'evidence.png', { type: 'image/png' })
    const { unmount } = render(<ProofPreview source={file} />)

    expect(createObjectURLSpy).toHaveBeenCalledWith(file)
    expect(revokeObjectURLSpy).not.toHaveBeenCalled()

    unmount()

    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:http://localhost:5173/synthetic-blob-uuid')
  })

  it('calls onRemove when the remove button is clicked', async () => {
    const user = userEvent.setup()
    const onRemove = vi.fn()
    const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })

    render(<ProofPreview source={file} onRemove={onRemove} />)

    const removeBtn = screen.getByRole('button', { name: /remove selected proof image/i })
    await user.click(removeBtn)

    expect(onRemove).toHaveBeenCalledOnce()
  })

  it('calls onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const blob = new Blob([new Uint8Array(100)], { type: 'image/jpeg' })

    render(<ProofPreview source={blob} onClose={onClose} />)

    const closeBtn = screen.getByRole('button', { name: /close proof preview/i })
    await user.click(closeBtn)

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('never displays backend storage references or S3 URLs', () => {
    const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
    const { container } = render(<ProofPreview source={file} />)

    expect(container.textContent).not.toContain('minio')
    expect(container.textContent).not.toContain('s3')
    expect(container.textContent).not.toContain('proof/')
  })
})
