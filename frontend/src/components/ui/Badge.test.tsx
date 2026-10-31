import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Badge } from './Badge'

describe('Badge primitive component', () => {
  it('renders default badge with children', () => {
    render(<Badge>Delivered</Badge>)
    const badge = screen.getByText('Delivered')
    expect(badge).toBeInTheDocument()
    expect(badge.parentElement).toHaveClass('sd-badge', 'sd-badge-neutral')
  })

  it('renders with variants and pulse dot', () => {
    const { container } = render(
      <Badge variant="success" pulse size="sm">
        Live In-Transit
      </Badge>,
    )
    const badge = container.querySelector('.sd-badge')
    expect(badge).toHaveClass('sd-badge-success', 'sd-badge-sm', 'has-pulse')
    expect(container.querySelector('.sd-badge-pulse-dot')).toBeInTheDocument()
  })

  it('renders optional icon', () => {
    render(
      <Badge icon={<span data-testid="badge-icon">★</span>}>
        Featured
      </Badge>,
    )
    expect(screen.getByTestId('badge-icon')).toBeInTheDocument()
    expect(screen.getByText('Featured')).toBeInTheDocument()
  })
})

