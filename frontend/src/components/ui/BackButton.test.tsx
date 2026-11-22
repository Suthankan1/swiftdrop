import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { BackButton } from './BackButton'

describe('BackButton component', () => {
  it('renders with label and target link', () => {
    render(
      <MemoryRouter>
        <BackButton to="/admin" label="Back to Administration" />
      </MemoryRouter>,
    )

    const link = screen.getByRole('link', { name: 'Back to Administration' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin')
    expect(link).toHaveClass('sd-back-button')
  })

  it('includes accessibility aria-hidden icon', () => {
    const { container } = render(
      <MemoryRouter>
        <BackButton to="/dispatcher" label="Back to Dispatch" />
      </MemoryRouter>,
    )

    const svg = container.querySelector('svg')
    expect(svg).toBeInTheDocument()
    expect(svg).toHaveAttribute('aria-hidden', 'true')
  })
})
