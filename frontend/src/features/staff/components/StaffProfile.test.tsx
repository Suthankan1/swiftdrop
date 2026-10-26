import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { StaffProfile } from './StaffProfile'
import { courierStaffFixture } from '../../../test/fixtures'

describe('StaffProfile component', () => {
  it('renders loading feedback with a loading indicator', () => {
    render(<StaffProfile state={{ status: 'loading' }} />)

    expect(screen.getByRole('status')).toHaveTextContent(/loading staff details/i)
    expect(screen.getByText(/please wait while we retrieve your staff account/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 2, name: courierStaffFixture.displayName })).not.toBeInTheDocument()
  })

  it('renders auth-required feedback when sign-in is required', () => {
    render(<StaffProfile state={{ status: 'auth-required' }} />)

    expect(screen.getByRole('status')).toHaveTextContent(/staff space requires sign-in/i)
    expect(screen.getByText(/sign-in is required to view your staff account/i)).toBeInTheDocument()
  })

  it('renders not-found feedback when the staff profile is not yet provisioned', () => {
    render(<StaffProfile state={{ status: 'not-found' }} />)

    expect(screen.getByRole('status')).toHaveTextContent(/staff profile not provisioned/i)
    expect(screen.getByText(/no staff business profile has been provisioned yet/i)).toBeInTheDocument()
  })

  it('renders inactive feedback when the staff business status is inactive or suspended', () => {
    const inactiveProfile = { ...courierStaffFixture, businessStatus: 'SUSPENDED' as const }
    render(<StaffProfile state={{ status: 'inactive', profile: inactiveProfile }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/staff profile inactive/i)
    expect(screen.getByText(/your staff profile is currently suspended \(non-operational\)/i)).toBeInTheDocument()
    expect(screen.queryByText(inactiveProfile.staffId)).not.toBeInTheDocument()
  })

  it('renders role mismatch feedback when profile role does not match expected workspace role', () => {
    render(<StaffProfile state={{ status: 'wrong-role', expectedRole: 'COURIER', actualRole: 'ADMIN' }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/staff role mismatch/i)
    expect(screen.getByText(/access is restricted to authorized courier staff/i)).toBeInTheDocument()
  })

  it('renders access denied feedback for forbidden status', () => {
    render(<StaffProfile state={{ status: 'forbidden' }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/staff profile access denied/i)
    expect(screen.getByText(/this staff profile cannot be displayed for the current session/i)).toBeInTheDocument()
  })

  it('renders unavailable feedback with retry action and responds to user click', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(<StaffProfile state={{ status: 'unavailable' }} onRetry={onRetry} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/staff profile temporarily unavailable/i)
    const retryBtn = screen.getByRole('button', { name: /retry profile/i })
    expect(retryBtn).toBeInTheDocument()

    await user.click(retryBtn)
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('renders invalid-response feedback when payload is unrecognized', () => {
    render(<StaffProfile state={{ status: 'invalid-response' }} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/invalid provider response/i)
    expect(screen.getByText(/the staff profile provider returned an unrecognized response/i)).toBeInTheDocument()
  })

  it('renders full authorized staff profile details when ready', () => {
    render(<StaffProfile state={{ status: 'ready', profile: courierStaffFixture }} />)

    expect(screen.getByRole('heading', { level: 2, name: courierStaffFixture.displayName })).toBeInTheDocument()
    expect(screen.getByText(`${courierStaffFixture.role} · ${courierStaffFixture.businessStatus}`)).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.staffId)).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.email)).toBeInTheDocument()
    expect(screen.getByText(courierStaffFixture.phoneNumber!)).toBeInTheDocument()
    expect(screen.getAllByText(courierStaffFixture.department)).toHaveLength(2)
    expect(screen.getByText(courierStaffFixture.assignedZone!)).toBeInTheDocument()
  })

  it('renders default fallback text when optional phone or zone are missing', () => {
    const minimalProfile = {
      ...courierStaffFixture,
      phoneNumber: null,
      assignedZone: null,
    }
    render(<StaffProfile state={{ status: 'ready', profile: minimalProfile }} />)

    expect(screen.getByText('Not provided')).toBeInTheDocument()
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })
})
