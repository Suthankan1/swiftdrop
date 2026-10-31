import { Link } from 'react-router'
import { MerchantApprovalBanner } from './MerchantApprovalBanner'
import type { MerchantProfileResponse } from '../api/enrollmentContracts'
import type { MerchantApprovalStatus } from '../api/merchant'
import { Button } from '../../../components/ui/Button'

interface MerchantPendingCardProps {
  merchant: MerchantProfileResponse
  isRefreshing: boolean
  hasMerchantRole: boolean
  onRefreshStatus: () => void
  onRefreshSession?: () => void
}

export function MerchantPendingCard({
  merchant,
  isRefreshing,
  hasMerchantRole,
  onRefreshStatus,
  onRefreshSession,
}: MerchantPendingCardProps) {
  const status: MerchantApprovalStatus = merchant.status || 'PENDING'
  const isPending = status === 'PENDING'
  const isApproved = status === 'APPROVED'
  const isSuspended = status === 'SUSPENDED'
  const isRejected = status === 'REJECTED'

  const createdDate = merchant.createdAt ? new Date(merchant.createdAt) : null
  const formattedDate =
    createdDate && !Number.isNaN(createdDate.getTime())
      ? createdDate.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })
      : null

  return (
    <section className="card pending-enrollment-card" aria-labelledby="pending-card-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Application status</p>
          <h2 id="pending-card-heading">{merchant.businessName}</h2>
        </div>
        <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>
      </div>

      <MerchantApprovalBanner rawStatus={status} />

      <div className="pending-details-grid">
        <div className="field">
          <dt className="field-label">Business legal name</dt>
          <dd className="field-value"><strong>{merchant.businessName}</strong></dd>
        </div>
        <div className="field">
          <dt className="field-label">Registration number</dt>
          <dd className="field-value"><code>{merchant.registrationNumber}</code></dd>
        </div>
        <div className="field">
          <dt className="field-label">Tenant ID</dt>
          <dd className="field-value"><small>{merchant.id}</small></dd>
        </div>
        {formattedDate && (
          <div className="field">
            <dt className="field-label">Application submitted</dt>
            <dd className="field-value">{formattedDate}</dd>
          </div>
        )}
      </div>

      {isPending && (
        <div className="pending-next-steps">
          <h3>Next Steps</h3>
          <ol className="steps-list">
            <li>
              <strong>Administrative Review:</strong> Our operations team verifies your business registration against regulatory databases.
            </li>
            <li>
              <strong>Approval &amp; Claim Sync:</strong> Once verified, an administrator will approve your tenant. The <code>MERCHANT</code> role will be synchronized to your account.
            </li>
            <li>
              <strong>Portal Access:</strong> When approved, use the "Refresh Status" button below to update your session and unlock commercial dispatching, batch order entry, and machine API keys.
            </li>
          </ol>
        </div>
      )}

      {isApproved && (
        <div className="approved-callout" role="status">
          <h3>🎉 Congratulations! Your Merchant Account is Approved.</h3>
          <p>
            Your organization has been successfully verified. You are authorized to access commercial dispatching and machine API credentials.
          </p>
          <div className="actions-cluster">
            {hasMerchantRole ? (
              <Link to="/merchant" className="button button-primary">
                Open Merchant Space ↗
              </Link>
            ) : (
              <Button
                variant="primary"
                onClick={onRefreshSession || onRefreshStatus}
              >
                Sync Session &amp; Enter Portal ↗
              </Button>
            )}
          </div>
        </div>
      )}

      {(isRejected || isSuspended) && (
        <div className="support-resolution-box">
          <h3>Need Assistance?</h3>
          <p>
            If you need to provide supplementary legal documentation or dispute this decision, please reach out directly to the SwiftDrop Merchant Onboarding Desk at{' '}
            <a href="mailto:support@swiftdrop.test">support@swiftdrop.test</a> with your Tenant ID (<code>{merchant.id}</code>).
          </p>
        </div>
      )}

      <div className="pending-actions-bar">
        <Button
          variant="secondary"
          onClick={onRefreshStatus}
          isLoading={isRefreshing}
          disabled={isRefreshing}
        >
          {isRefreshing ? 'Checking Status...' : '↻ Refresh Approval Status'}
        </Button>
      </div>
    </section>
  )
}
