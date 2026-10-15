import type { CurrentUserProfile } from '../api/contracts'
import { FeedbackState } from '../../../components/ui/FeedbackState'

export type ProfileState =
  | { status: 'auth-required' | 'loading' | 'empty' | 'unavailable' | 'forbidden' }
  | { status: 'ready'; profile: CurrentUserProfile }

export function CustomerProfile({ state }: { state: ProfileState }) {
  if (state.status === 'auth-required') {
    return <FeedbackState title="Your profile, kept private">Sign-in is not available yet. Your personal details will appear here once secure sign-in is connected.</FeedbackState>
  }
  if (state.status === 'loading') {
    return <FeedbackState title="Loading your profile" tone="loading">Please wait while we retrieve your account details.</FeedbackState>
  }
  if (state.status === 'empty') {
    return <FeedbackState title="No profile to show">Your account details are not available yet. Please try again later.</FeedbackState>
  }
  if (state.status === 'unavailable') {
    return <FeedbackState title="Profile temporarily unavailable" tone="error">We could not reach the profile service. Please try again later.</FeedbackState>
  }
  if (state.status === 'forbidden') {
    return <FeedbackState title="Profile access unavailable" tone="error">This profile cannot be displayed for the current session.</FeedbackState>
  }

  if (state.status !== 'ready') return null
  const { profile } = state
  const createdAt = new Date(profile.createdAt)

  return (
    <section className="card" aria-labelledby="profile-heading">
      <div className="section-heading">
        <div><p className="eyebrow">Account details</p><h2 id="profile-heading">{profile.fullName}</h2></div>
        <span className="badge">{profile.roles.join(' · ') || 'No roles assigned'}</span>
      </div>
      <dl className="profile-details">
        <ProfileField label="Email" value={profile.email} />
        <ProfileField label="Phone number" value={profile.phoneNumber || 'Not provided'} />
        <ProfileField label="Profile ID" value={profile.id} />
        <ProfileField label="Identity reference" value={profile.keycloakSub} />
        <ProfileField label="Member since" value={Number.isNaN(createdAt.getTime()) ? 'Unavailable' : createdAt.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })} />
      </dl>
      {profile.merchantProfile && (
        <section aria-labelledby="merchant-heading">
          <h3 id="merchant-heading">Merchant profile</h3>
          <dl className="profile-details">
            <ProfileField label="Business" value={profile.merchantProfile.businessName} />
            <ProfileField label="Merchant ID" value={profile.merchantProfile.merchantId} />
            <ProfileField label="Registration number" value={profile.merchantProfile.registrationNumber} />
            <ProfileField label="Approval status" value={profile.merchantProfile.status} />
          </dl>
        </section>
      )}
    </section>
  )
}

function ProfileField({ label, value }: { label: string; value: string }) {
  return <div className="field"><dt className="field-label">{label}</dt><dd className="field-value">{value}</dd></div>
}
