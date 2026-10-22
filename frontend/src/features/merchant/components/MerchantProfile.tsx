import type { CurrentUserProfile, MerchantProfile as MerchantProfileData } from '../api/merchant'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { MerchantApprovalBanner } from './MerchantApprovalBanner'

export type MerchantProfileState =
  | { status: 'auth-required' | 'loading' | 'empty' | 'unavailable' | 'forbidden' }
  | { status: 'ready'; profile: CurrentUserProfile; merchant: MerchantProfileData }

interface MerchantProfileProps {
  state: MerchantProfileState
}

export function MerchantProfile({ state }: MerchantProfileProps) {
  if (state.status === 'auth-required') {
    return (
      <FeedbackState title="Merchant space requires sign-in">
        Sign-in is required to view your merchant account. Please sign in with an authorized merchant account.
      </FeedbackState>
    )
  }

  if (state.status === 'loading') {
    return (
      <FeedbackState title="Loading merchant details" tone="loading">
        Please wait while we retrieve your merchant account details and approval status.
      </FeedbackState>
    )
  }

  if (state.status === 'empty') {
    return (
      <FeedbackState title="No merchant profile linked" tone="neutral">
        Your account is authenticated with the Merchant role, but no commercial merchant profile is currently registered.
        Please complete merchant registration or contact support to bind your organization.
      </FeedbackState>
    )
  }

  if (state.status === 'unavailable') {
    return (
      <FeedbackState title="Merchant details temporarily unavailable" tone="error">
        We could not reach the account service. Please check your network connection and try again.
      </FeedbackState>
    )
  }

  if (state.status === 'forbidden') {
    return (
      <FeedbackState title="Merchant profile unavailable" tone="error">
        This merchant profile cannot be displayed for the current session.
      </FeedbackState>
    )
  }

  if (state.status !== 'ready') return null

  const { profile, merchant } = state
  const createdAt = new Date(profile.createdAt)

  return (
    <section className="card" aria-labelledby="merchant-profile-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Merchant account</p>
          <h2 id="merchant-profile-heading">{merchant.businessName}</h2>
        </div>
        <span className="badge">{profile.roles.join(' · ') || 'MERCHANT'}</span>
      </div>

      <MerchantApprovalBanner rawStatus={merchant.status} />

      <dl className="profile-details">
        <ProfileField label="Business name" value={merchant.businessName} />
        <ProfileField label="Registration number" value={merchant.registrationNumber} />
        <ProfileField label="Merchant ID" value={merchant.merchantId} />
        <ProfileField label="Account owner" value={profile.fullName} />
        <ProfileField label="Contact email" value={profile.email} />
        <ProfileField label="Phone number" value={profile.phoneNumber || 'Not provided'} />
        <ProfileField label="Identity reference" value={profile.keycloakSub} />
        <ProfileField
          label="Member since"
          value={
            Number.isNaN(createdAt.getTime())
              ? 'Unavailable'
              : createdAt.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })
          }
        />
      </dl>
    </section>
  )
}

function ProfileField({ label, value }: { label: string; value: string }) {
  return (
    <div className="field">
      <dt className="field-label">{label}</dt>
      <dd className="field-value">{value}</dd>
    </div>
  )
}
