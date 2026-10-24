import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { AccountsError, createAccountsApi, normalizeApprovalStatus } from '../api/merchant'
import { createCommunicationsApi, CommunicationsError } from '../api/webhooks'
import type {
  CreateWebhookSubscriptionRequest,
  WebhookDeliveryLogListResponse,
  WebhookSubscription,
} from '../api/webhookContracts'
import { MerchantNav } from '../components/MerchantNav'
import { MerchantApprovalBanner } from '../components/MerchantApprovalBanner'
import { WebhookSecretAlert } from '../components/WebhookSecretAlert'
import { WebhookSubscriptionForm } from '../components/WebhookSubscriptionForm'
import { WebhookSubscriptionList } from '../components/WebhookSubscriptionList'
import { WebhookDeliveryLogsModal } from '../components/WebhookDeliveryLogsModal'
import { WebhookDeleteConfirmModal } from '../components/WebhookDeleteConfirmModal'
import { FeedbackState } from '../../../components/ui/FeedbackState'

export function MerchantWebhooksPage() {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const queryClient = useQueryClient()

  // 1. Query merchant account profile from Accounts Service
  const profileQuery = useQuery({
    queryKey: ['private', 'profile', subject],
    enabled: auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT'),
    queryFn: async ({ signal }) => {
      const api = createAccountsApi({ getAccessToken: auth.getAccessToken, fetch: auth.profileFetch })
      const profile = await api.getCurrentUser(signal)
      if (signal.aborted || profile.keycloakSub !== subject) throw new AccountsError('forbidden')
      return profile
    },
    gcTime: 0,
  })

  const merchantProfile = profileQuery.data?.merchantProfile
  const approvalStatus = normalizeApprovalStatus(merchantProfile?.status)
  const isApproved = approvalStatus === 'APPROVED'

  // 2. Query registered webhook subscriptions from Communications Service
  const commsApi = createCommunicationsApi({
    getAccessToken: auth.getAccessToken,
  })

  const subscriptionsQuery = useQuery({
    queryKey: ['merchant', 'webhooks', merchantProfile?.merchantId],
    enabled: isApproved && Boolean(merchantProfile?.merchantId),
    queryFn: async ({ signal }) => {
      return commsApi.listSubscriptions(signal)
    },
  })

  // 3. UI State Management
  const [showForm, setShowForm] = useState(false)
  const [formSubmitting, setFormSubmitting] = useState(false)
  const [formApiError, setFormApiError] = useState<string | null>(null)

  const [oneTimeSecret, setOneTimeSecret] = useState<{ secret: string; endpointUrl: string } | null>(null)
  const [inspectingSub, setInspectingSub] = useState<WebhookSubscription | null>(null)
  const [deletingSub, setDeletingSub] = useState<WebhookSubscription | null>(null)
  const [isDeleting, setIsDeleting] = useState(false)

  // 4. Mutation Handlers
  async function handleCreateSubscription(request: CreateWebhookSubscriptionRequest) {
    setFormSubmitting(true)
    setFormApiError(null)
    try {
      const created = await commsApi.createSubscription(request)
      setOneTimeSecret({
        secret: created.hmacSigningSecret,
        endpointUrl: created.httpsUrl,
      })
      setShowForm(false)
      await queryClient.invalidateQueries({ queryKey: ['merchant', 'webhooks', merchantProfile?.merchantId] })
    } catch (err) {
      if (err instanceof CommunicationsError) {
        setFormApiError(err.message)
      } else {
        setFormApiError('Failed to register webhook destination. Communications Service may be unavailable.')
      }
    } finally {
      setFormSubmitting(false)
    }
  }

  async function handleConfirmDelete() {
    if (!deletingSub) return
    setIsDeleting(true)
    try {
      await commsApi.deleteSubscription(deletingSub.subscriptionId)
      setDeletingSub(null)
      await queryClient.invalidateQueries({ queryKey: ['merchant', 'webhooks', merchantProfile?.merchantId] })
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to deactivate subscription.')
    } finally {
      setIsDeleting(false)
    }
  }

  async function handleFetchDeliveries(subscriptionId: string): Promise<WebhookDeliveryLogListResponse> {
    return commsApi.listDeliveries(subscriptionId, 0, 20)
  }

  // 5. Auth / Role Fallbacks
  if (auth.snapshot.status !== 'authenticated') {
    return (
      <div className="page-container merchant-page">
        <FeedbackState title="Merchant space requires sign-in">
          Sign-in is required to view merchant webhooks.
        </FeedbackState>
      </div>
    )
  }

  if (!auth.snapshot.roles.includes('MERCHANT')) {
    return (
      <div className="page-container merchant-page">
        <FeedbackState title="Merchant profile unavailable" tone="error">
          This workspace is restricted to authorized Merchant accounts.
        </FeedbackState>
      </div>
    )
  }

  return (
    <div className="page-container merchant-page merchant-webhooks-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>Event webhooks.<br />Real-time status integration.</h1>
        <p className="muted">
          Subscribe external HTTPS endpoints to parcel status change events with HMAC-SHA256 signature verification.
        </p>
      </header>

      <MerchantNav />

      {/* Profile Loading Feedback */}
      {profileQuery.isPending && (
        <FeedbackState title="Checking merchant credentials" tone="loading">
          Retrieving merchant authorization status...
        </FeedbackState>
      )}

      {/* Approval Status Restriction */}
      {!profileQuery.isPending && !isApproved && (
        <div className="approval-restriction-container">
          <MerchantApprovalBanner rawStatus={merchantProfile?.status} />
          <aside className="card locked-webhooks-notice" role="status">
            <h3 className="section-heading">Webhook Management Locked</h3>
            <p className="muted">
              In accordance with SwiftDrop baseline security policy (<code>DEC-MER-03</code>), webhook endpoint registration, signature generation, and delivery inspection are reserved for verified accounts.
            </p>
            <p className="muted">
              Once your commercial registration is approved by SwiftDrop administration, webhook configuration will unlock automatically.
            </p>
          </aside>
        </div>
      )}

      {/* Approved Merchant Workflow */}
      {!profileQuery.isPending && isApproved && (
        <div className="profile-grid webhooks-grid">
          <main className="webhooks-main-column">
            {/* One-Time Secret Alert */}
            {oneTimeSecret && (
              <WebhookSecretAlert
                secret={oneTimeSecret.secret}
                endpointUrl={oneTimeSecret.endpointUrl}
                onDismiss={() => setOneTimeSecret(null)}
              />
            )}

            {/* Registration Form (Collapsible) */}
            {showForm && (
              <WebhookSubscriptionForm
                onSubmit={handleCreateSubscription}
                onCancel={() => { setShowForm(false); setFormApiError(null) }}
                isSubmitting={formSubmitting}
                apiError={formApiError}
              />
            )}

            {/* Subscriptions List */}
            <WebhookSubscriptionList
              subscriptions={subscriptionsQuery.data || []}
              isLoading={subscriptionsQuery.isPending}
              isError={subscriptionsQuery.isError}
              errorMessage={
                subscriptionsQuery.error instanceof CommunicationsError
                  ? subscriptionsQuery.error.message
                  : 'Communications Service is currently unreachable (Backend implementation scheduled under Issue #116).'
              }
              onRetry={() => { void subscriptionsQuery.refetch() }}
              onInspectDeliveries={(sub) => setInspectingSub(sub)}
              onDeleteSubscription={(sub) => setDeletingSub(sub)}
              onOpenRegisterForm={() => { setShowForm(true); setFormApiError(null) }}
            />
          </main>

          <aside className="card webhooks-info-sidebar" aria-labelledby="webhook-spec-heading">
            <h2 id="webhook-spec-heading" className="section-heading">Security & Specifications</h2>
            
            <dl className="spec-list">
              <div className="spec-item">
                <dt className="field-label">Signature Header</dt>
                <dd className="field-value"><code>X-SwiftDrop-Signature</code></dd>
              </div>
              <div className="spec-item">
                <dt className="field-label">Algorithm</dt>
                <dd className="field-value">HMAC-SHA256 (<code>t=timestamp,v1=hex_digest</code>)</dd>
              </div>
              <div className="spec-item">
                <dt className="field-label">Replay Window</dt>
                <dd className="field-value">5 minutes (300s timestamp tolerance)</dd>
              </div>
              <div className="spec-item">
                <dt className="field-label">Retry Policy</dt>
                <dd className="field-value">5 attempts with exponential backoff & jitter (max 6h)</dd>
              </div>
              <div className="spec-item">
                <dt className="field-label">Terminal Status</dt>
                <dd className="field-value">Transitions to <code>EXHAUSTED</code> after 5 failed attempts</dd>
              </div>
              <div className="spec-item">
                <dt className="field-label">Contract Standard</dt>
                <dd className="field-value">OpenAPI v1.0.0 / Communications Service</dd>
              </div>
            </dl>

            <div className="sidebar-action-box">
              <p className="muted small-note">
                Need to rotate your signing secret? Deactivate the old destination and register a new endpoint to generate a fresh secret.
              </p>
            </div>
          </aside>
        </div>
      )}

      {/* Modals */}
      {inspectingSub && (
        <WebhookDeliveryLogsModal
          subscription={inspectingSub}
          onClose={() => setInspectingSub(null)}
          fetchDeliveries={handleFetchDeliveries}
        />
      )}

      {deletingSub && (
        <WebhookDeleteConfirmModal
          subscription={deletingSub}
          onConfirm={handleConfirmDelete}
          onCancel={() => setDeletingSub(null)}
          isDeleting={isDeleting}
        />
      )}
    </div>
  )
}
