import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { customerFixture, merchantFixture } from '../../../test/fixtures'
import { CustomerProfile } from './CustomerProfile'

describe('CustomerProfile', () => {
  it('announces loading without displaying profile data', () => {
    render(<CustomerProfile state={{ status: 'loading' }} />)

    expect(screen.getByRole('status')).toHaveTextContent(/loading your profile/i)
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
  })

  it('explains an empty profile', () => {
    render(<CustomerProfile state={{ status: 'empty' }} />)

    expect(screen.getByRole('heading', { name: /no profile to show/i })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/not available yet/i)
  })

  it('announces profile service failure without technical error details', () => {
    render(<CustomerProfile state={{ status: 'unavailable' }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/profile temporarily unavailable/i)
    expect(screen.getByRole('alert')).toHaveTextContent(/try again later/i)
    expect(screen.queryByText(/localhost|stack trace|bearer/i)).not.toBeInTheDocument()
  })

  it('shows a safe access-denied state', () => {
    render(<CustomerProfile state={{ status: 'forbidden' }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/profile access unavailable/i)
    expect(screen.queryByText(customerFixture.keycloakSub)).not.toBeInTheDocument()
  })

  it('shows an honest authentication prerequisite', () => {
    render(<CustomerProfile state={{ status: 'auth-required' }} />)

    expect(screen.getByRole('status')).toHaveTextContent(/sign-in is not available yet/i)
    expect(screen.queryByText(customerFixture.email)).not.toBeInTheDocument()
    expect(screen.queryByText(/logged in successfully/i)).not.toBeInTheDocument()
  })

  it('renders the agreed customer profile fields from test-only data', () => {
    render(<CustomerProfile state={{ status: 'ready', profile: customerFixture }} />)

    expect(screen.getByRole('heading', { name: customerFixture.fullName })).toBeInTheDocument()
    for (const value of [customerFixture.email, customerFixture.phoneNumber, customerFixture.id, customerFixture.keycloakSub, 'CUSTOMER', '15 October 2026']) {
      expect(screen.getByText(value)).toBeInTheDocument()
    }
    expect(screen.queryByRole('heading', { name: 'Merchant profile' })).not.toBeInTheDocument()
  })

  it('renders merchant information without implying business approval', () => {
    render(<CustomerProfile state={{ status: 'ready', profile: merchantFixture }} />)

    expect(screen.getByRole('heading', { name: 'Merchant profile' })).toBeInTheDocument()
    expect(screen.getByText('Synthetic Test Business')).toBeInTheDocument()
    expect(screen.getByText('test-merchant')).toBeInTheDocument()
    expect(screen.getByText('TEST-ONLY-001')).toBeInTheDocument()
    expect(screen.getByText('PENDING')).toBeInTheDocument()
  })

  it('handles missing optional display values and an invalid date safely', () => {
    render(<CustomerProfile state={{ status: 'ready', profile: { ...customerFixture, phoneNumber: '', roles: [], createdAt: 'invalid-date' } }} />)

    expect(screen.getByText('Not provided')).toBeInTheDocument()
    expect(screen.getByText('No roles assigned')).toBeInTheDocument()
    expect(screen.getByText('Unavailable')).toBeInTheDocument()
  })
})
