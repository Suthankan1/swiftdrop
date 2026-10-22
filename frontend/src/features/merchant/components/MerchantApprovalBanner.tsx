import { normalizeApprovalStatus } from '../api/merchant'

interface MerchantApprovalBannerProps {
  rawStatus?: string | null
}

export function MerchantApprovalBanner({ rawStatus }: MerchantApprovalBannerProps) {
  const status = normalizeApprovalStatus(rawStatus)

  if (status === 'PENDING') {
    return (
      <aside className="approval-banner approval-banner-pending" role="status" aria-label="Approval status: Pending">
        <div className="section-heading">
          <h3>Application under review</h3>
          <span className="badge badge-pending">Approval Pending</span>
        </div>
        <p>
          Your merchant registration is currently being verified by the SwiftDrop platform administration.
          During this review phase, commercial dispatching, batch order entry, and webhook API access remain locked.
        </p>
        <p className="approval-notice">
          Standard verification takes 1–2 business days. You will receive email confirmation once your account is fully verified.
        </p>
      </aside>
    )
  }

  if (status === 'APPROVED') {
    return (
      <aside className="approval-banner approval-banner-approved" role="status" aria-label="Approval status: Approved">
        <div className="section-heading">
          <h3>Verified merchant account</h3>
          <span className="badge badge-approved">Account Approved</span>
        </div>
        <p>
          Your business credentials have been verified. Your merchant account is fully active and authorized for operational parcel dispatch.
        </p>
        <p className="approval-notice">
          Batch order booking, shipping label generation, and webhook tracking will unlock in upcoming delivery milestones.
        </p>
      </aside>
    )
  }

  if (status === 'REJECTED') {
    return (
      <aside className="approval-banner approval-banner-rejected" role="alert" aria-label="Approval status: Rejected">
        <div className="section-heading">
          <h3>Application not approved</h3>
          <span className="badge badge-rejected">Registration Rejected</span>
        </div>
        <p>
          Your merchant registration could not be approved based on the business information provided.
        </p>
        <p className="approval-notice">
          If you believe this decision was made in error or wish to submit updated commercial documentation, please contact SwiftDrop Merchant Support at{' '}
          <a href="mailto:support@swiftdrop.test">support@swiftdrop.test</a>.
        </p>
      </aside>
    )
  }

  if (status === 'SUSPENDED') {
    return (
      <aside className="approval-banner approval-banner-suspended" role="alert" aria-label="Approval status: Suspended">
        <div className="section-heading">
          <h3>Operational activities suspended</h3>
          <span className="badge badge-suspended">Account Suspended</span>
        </div>
        <p>
          This merchant account has been temporarily suspended from initiating deliveries or accessing merchant dispatch queues.
        </p>
        <p className="approval-notice">
          Please reach out to platform operations at{' '}
          <a href="mailto:support@swiftdrop.test">support@swiftdrop.test</a> to review your account standing and resolve the hold.
        </p>
      </aside>
    )
  }

  return (
    <aside className="approval-banner approval-banner-unknown" role="status" aria-label="Approval status: Unknown">
      <div className="section-heading">
        <h3>Approval status unavailable</h3>
        <span className="badge">Status Unrecognized</span>
      </div>
      <p>
        The approval state received from the server ({rawStatus || 'unspecified'}) could not be resolved to a known lifecycle state.
      </p>
      <p className="approval-notice">
        Please retry loading your profile or contact <a href="mailto:support@swiftdrop.test">support@swiftdrop.test</a> for assistance.
      </p>
    </aside>
  )
}
