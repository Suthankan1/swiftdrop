import { useCallback, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { merchantReadRetry } from '../api/merchantReadRetry'
import { useAuth } from '../../../auth/context'
import { AccountsError, createAccountsApi, normalizeApprovalStatus } from '../api/merchant'
import { createCommunicationsApi, CommunicationsError } from '../api/webhooks'
import type {
  CreateWebhookSubscriptionRequest,
  WebhookDeliveryLogListResponse,
  WebhookSubscription,
} from '../api/webhookContracts'
import { MerchantApprovalBanner } from '../components/MerchantApprovalBanner'
import { WebhookSecretAlert } from '../components/WebhookSecretAlert'
import { WebhookSubscriptionForm } from '../components/WebhookSubscriptionForm'
import { WebhookSubscriptionList } from '../components/WebhookSubscriptionList'
import { WebhookDeliveryLogsModal } from '../components/WebhookDeliveryLogsModal'
import { WebhookDeleteConfirmModal } from '../components/WebhookDeleteConfirmModal'
import { Button } from '../../../components/ui/Button'
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
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
    gcTime: 0,
  })

  const merchantProfile = profileQuery.data?.merchantProfile
  const approvalStatus = normalizeApprovalStatus(merchantProfile?.status)
  const isApproved = approvalStatus === 'APPROVED'

  // 2. Query registered webhook subscriptions from Communications Service
  const commsApi = useMemo(() => createCommunicationsApi({
    getAccessToken: auth.getAccessToken,
  }), [auth.getAccessToken])

  const subscriptionsQuery = useQuery({
    queryKey: ['private', 'merchant', 'webhooks', subject, merchantProfile?.merchantId],
    enabled: isApproved && Boolean(merchantProfile?.merchantId),
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
    gcTime: 0,
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
  const [actionError, setActionError] = useState<string | null>(null)

  // 4. Mutation Handlers
  async function handleCreateSubscription(request: CreateWebhookSubscriptionRequest) {
    if (formSubmitting) return
    setFormSubmitting(true)
    setFormApiError(null)
    try {
      const created = await commsApi.createSubscription(request)
      setOneTimeSecret({
        secret: created.hmacSigningSecret,
        endpointUrl: created.httpsUrl,
      })
      setShowForm(false)
      await queryClient.invalidateQueries({ queryKey: ['private', 'merchant', 'webhooks', subject, merchantProfile?.merchantId] })
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
    if (!deletingSub || isDeleting) return
    setIsDeleting(true)
    setActionError(null)
    try {
      await commsApi.deleteSubscription(deletingSub.subscriptionId)
      setDeletingSub(null)
      await queryClient.invalidateQueries({ queryKey: ['private', 'merchant', 'webhooks', subject, merchantProfile?.merchantId] })
    } catch (err) {
      setDeletingSub(null)
      setActionError(err instanceof Error ? err.message : 'Failed to deactivate subscription.')
    } finally {
      setIsDeleting(false)
    }
  }

  const handleFetchDeliveries = useCallback((subscriptionId: string, signal?: AbortSignal): Promise<WebhookDeliveryLogListResponse> =>
    commsApi.listDeliveries(subscriptionId, 0, 20, signal), [commsApi])

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
        <h1>Webhooks</h1>
        <p className="muted">Send delivery updates to your store software.</p>
      </header>

      {actionError && (
        <div className="feedback-banner is-error" role="alert">
          <span>{actionError}</span>
          <button type="button" className="feedback-dismiss" onClick={() => setActionError(null)} aria-label="Dismiss error">✕</button>
        </div>
      )}

      {/* Profile Loading Feedback */}
      {profileQuery.isPending && (
        <FeedbackState title="Checking merchant credentials" tone="loading">
          Retrieving merchant authorization status...
        </FeedbackState>
      )}

      {profileQuery.isError && (
        <FeedbackState title="Merchant account unavailable" tone="error" action={
          <Button disabled={profileQuery.isFetching} onClick={() => {
            if (!profileQuery.isFetching) void profileQuery.refetch({ cancelRefetch: false })
          }}>Retry account</Button>
        }>
          {profileQuery.error instanceof AccountsError && profileQuery.error.kind === 'auth-required'
            ? 'Your session has expired. Please sign in again.'
            : 'Your merchant approval could not be checked.'}
        </FeedbackState>
      )}
      {!profileQuery.isPending && !profileQuery.isError && !isApproved && (
        <MerchantApprovalBanner rawStatus={merchantProfile?.status} />
      )}

      {/* Approved Merchant Workflow */}
      {!profileQuery.isPending && isApproved && (
        <div className="webhooks-main-column">
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
                : 'The webhook service is unreachable right now. Please try again shortly.'
            }
            onRetry={() => { if (!subscriptionsQuery.isFetching) void subscriptionsQuery.refetch({ cancelRefetch: false }) }}
            onInspectDeliveries={(sub) => setInspectingSub(sub)}
            onDeleteSubscription={(sub) => setDeletingSub(sub)}
            onOpenRegisterForm={() => { setShowForm(true); setFormApiError(null) }}
          />
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
