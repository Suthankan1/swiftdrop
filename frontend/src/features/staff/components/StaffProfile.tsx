import { FeedbackState } from '../../../components/ui/FeedbackState'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import type { StaffProfile as StaffProfileData, StaffRole } from '../api/contracts'

export type StaffProfileState =
  | { status: 'auth-required' }
  | { status: 'loading' }
  | { status: 'ready'; profile: StaffProfileData }
  | { status: 'not-found' }
  | { status: 'inactive'; profile: StaffProfileData }
  | { status: 'wrong-role'; expectedRole: StaffRole; actualRole?: string }
  | { status: 'forbidden' }
  | { status: 'unavailable' }
  | { status: 'invalid-response' }

export interface StaffProfileProps {
  state: StaffProfileState
  onRetry?: () => void
}

export function StaffProfile({ state, onRetry }: StaffProfileProps) {
  if (state.status === 'auth-required') {
    return (
      <FeedbackState title="Staff space requires sign-in">
        Sign-in is required to view your staff account. Please sign in with an authorized account.
      </FeedbackState>
    )
  }

  if (state.status === 'loading') {
    return (
      <FeedbackState title="Loading staff details" tone="loading">
        Please wait while we retrieve your staff account and operational profile.
      </FeedbackState>
    )
  }

  if (state.status === 'not-found') {
    return (
      <FeedbackState title="Staff profile not provisioned" tone="neutral">
        Your account is authenticated, but no staff business profile has been provisioned yet. Please contact operations or an administrator.
      </FeedbackState>
    )
  }

  if (state.status === 'inactive') {
    const statusText = state.profile.businessStatus.toLowerCase()
    return (
      <FeedbackState title="Staff profile inactive" tone="error">
        Your staff profile is currently {statusText} (non-operational). Please contact an administrator to reactivate your staff assignment.
      </FeedbackState>
    )
  }

  if (state.status === 'wrong-role') {
    return (
      <FeedbackState title="Staff role mismatch" tone="error">
        Your staff business profile role does not match this workspace. Access is restricted to authorized {state.expectedRole.toLowerCase()} staff.
      </FeedbackState>
    )
  }

  if (state.status === 'forbidden') {
    return (
      <FeedbackState title="Staff profile access denied" tone="error">
        This staff profile cannot be displayed for the current session.
      </FeedbackState>
    )
  }

  if (state.status === 'unavailable') {
    return (
      <FeedbackState
        title="Staff profile temporarily unavailable"
        tone="error"
        action={
          onRetry ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={onRetry}
            >
              Retry profile
            </Button>
          ) : undefined
        }
      >
        We could not reach the staff profile provider. Please check your connection and try again.
      </FeedbackState>
    )
  }

  if (state.status === 'invalid-response') {
    return (
      <FeedbackState title="Invalid provider response" tone="error">
        The staff profile provider returned an unrecognized response. Please contact support.
      </FeedbackState>
    )
  }

  const { profile } = state

  return (
    <section className="card staff-profile-card" aria-labelledby="staff-profile-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">{profile.department || 'Operations'}</p>
          <h2 id="staff-profile-heading">{profile.displayName}</h2>
        </div>
        <Badge variant={profile.businessStatus === 'ACTIVE' ? 'success' : 'neutral'} pulse={profile.businessStatus === 'ACTIVE'}>
          {profile.role} · {profile.businessStatus}
        </Badge>
      </div>
      <dl className="profile-details">
        <div className="field">
          <dt className="field-label">Staff ID</dt>
          <dd className="field-value">{profile.staffId}</dd>
        </div>
        <div className="field">
          <dt className="field-label">Email</dt>
          <dd className="field-value">{profile.email}</dd>
        </div>
        <div className="field">
          <dt className="field-label">Phone number</dt>
          <dd className="field-value">{profile.phoneNumber || 'Not provided'}</dd>
        </div>
        <div className="field">
          <dt className="field-label">Department</dt>
          <dd className="field-value">{profile.department}</dd>
        </div>
        <div className="field">
          <dt className="field-label">Assigned Zone</dt>
          <dd className="field-value">{profile.assignedZone || 'Unassigned'}</dd>
        </div>
        <div className="field">
          <dt className="field-label">Operational Status</dt>
          <dd className="field-value">{profile.businessStatus}</dd>
        </div>
      </dl>
    </section>
  )
}
