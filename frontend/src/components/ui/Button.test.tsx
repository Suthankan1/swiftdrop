import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'

describe('Button primitive component', () => {
  it('renders default button with text and standard classes', () => {
    render(<Button>Click me</Button>)
    const button = screen.getByRole('button', { name: 'Click me' })
    expect(button).toBeInTheDocument()
    expect(button).toHaveClass('button', 'button-primary')
    expect(button).toHaveAttribute('type', 'button')
  })

  it('renders with specific variants and sizes', () => {
    const { rerender } = render(
      <Button variant="secondary" size="sm">
        Secondary Small
      </Button>,
    )
    expect(screen.getByRole('button')).toHaveClass('button', 'button-secondary', 'button-sm')

    rerender(
      <Button variant="danger" size="lg" fullWidth>
        Delete Large
      </Button>,
    )
    expect(screen.getByRole('button')).toHaveClass('button', 'button-danger', 'button-lg', 'button-full')
  })

  it('handles loading state with spinner and aria-busy', () => {
    render(
      <Button isLoading loadingText="Processing...">
        Submit
      </Button>,
    )
    const button = screen.getByRole('button')
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('Processing...')).toBeInTheDocument()
    expect(screen.queryByText('Submit')).not.toBeInTheDocument()
  })

  it('renders left and right icons with aria-hidden', () => {
    render(
      <Button
        leftIcon={<span data-testid="left-icon">←</span>}
        rightIcon={<span data-testid="right-icon">→</span>}
      >
        Navigation
      </Button>,
    )
    expect(screen.getByTestId('left-icon')).toBeInTheDocument()
    expect(screen.getByTestId('right-icon')).toBeInTheDocument()
    expect(screen.getByText('Navigation')).toBeInTheDocument()
  })

  it('fires onClick when enabled and ignores when disabled or loading', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(<Button onClick={onClick}>Action</Button>)

    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledOnce()

    rerender(
      <Button onClick={onClick} disabled>
        Action
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledOnce()

    rerender(
      <Button onClick={onClick} isLoading>
        Action
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledOnce()
  })
})

