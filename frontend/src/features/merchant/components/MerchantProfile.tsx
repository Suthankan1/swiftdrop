import type { CurrentUserProfile, MerchantProfile as MerchantProfileData } from '../api/merchant'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { normalizeApprovalStatus } from '../api/merchant'

const approvalContent = {
  PENDING: {
    label: 'Approval Pending', title: 'Application under review',
    detail: 'You can book orders once your account is approved.',
  },
  APPROVED: {
    label: 'Account Approved', title: 'Verified merchant account',
    detail: 'Your merchant account is active.',
  },
  REJECTED: {
    label: 'Registration Rejected', title: 'Application not approved',
    detail: 'Your merchant registration could not be approved.',
  },
  SUSPENDED: {
    label: 'Account Suspended', title: 'Operational activities suspended',
    detail: 'New bookings are paused for this account.',
  },
  UNKNOWN: {
    label: 'Status Unrecognized', title: 'Approval status unavailable',
    detail: 'Contact support to confirm your account status.',
  },
} as const

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
        Checking your business account.
      </FeedbackState>
    )
  }

  if (state.status === 'empty') {
    return (
      <FeedbackState title="No merchant profile linked" tone="neutral">
        No commercial merchant profile is currently registered. Complete merchant registration or contact support.
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

  const approval = normalizeApprovalStatus(merchant.status)
  const content = approvalContent[approval]
  const needsSupport = approval === 'REJECTED' || approval === 'SUSPENDED' || approval === 'UNKNOWN'

  return (
    <section className="card merchant-overview-business" aria-labelledby="merchant-profile-heading">
      <header className="merchant-overview-business-header">
        <p className="eyebrow">Business account</p>
        <h2 id="merchant-profile-heading">{merchant.businessName}</h2>
      </header>

      <div className="merchant-overview-status" data-approval={approval}
        role={approval === 'REJECTED' || approval === 'SUSPENDED' ? 'alert' : 'status'}
        aria-label={`Approval status: ${approval.toLowerCase()}`}>
        <span className="merchant-overview-status-label">{content.label}</span>
        <div>
          <h3>{content.title}</h3>
          <p>{content.detail}</p>
          {needsSupport && <a href="mailto:support@swiftdrop.test">support@swiftdrop.test</a>}
        </div>
      </div>

      <dl className="merchant-overview-profile-fields">
        <ProfileField label="Account owner" value={profile.fullName} />
        <ProfileField label="Contact email" value={profile.email} />
        <ProfileField label="Phone number" value={profile.phoneNumber || 'Not provided'} />
      </dl>

      <section className="merchant-overview-profile-details" aria-labelledby="merchant-registration-heading">
        <h3 id="merchant-registration-heading">Registration details</h3>
        <dl className="merchant-overview-profile-fields">
          <ProfileField label="Registration number" value={merchant.registrationNumber} />
          <ProfileField label="Merchant ID" value={merchant.merchantId} />
          <ProfileField
            label="Member since"
            value={Number.isNaN(createdAt.getTime())
              ? 'Unavailable'
              : createdAt.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })}
          />
        </dl>
      </section>
    </section>
  )
}

function ProfileField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
