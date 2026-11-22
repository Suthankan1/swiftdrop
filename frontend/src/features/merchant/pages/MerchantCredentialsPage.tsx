import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  AccountsError,
  createAccountsApi,
  createMerchantCredentialsApi,
  MerchantCredentialsError,
  normalizeApprovalStatus,
  type ApiKeyCreatedResponse,
  type ApiKeySummaryItem,
  type CreateApiKeyRequest,
} from '../api/merchant'
import { MerchantApprovalBanner } from '../components/MerchantApprovalBanner'
import { ApiKeyListTable } from '../components/ApiKeyListTable'
import { ApiKeyCreateFormModal } from '../components/ApiKeyCreateFormModal'
import { ApiKeyCreatedModal } from '../components/ApiKeyCreatedModal'
import { ApiKeyRevokeConfirmModal } from '../components/ApiKeyRevokeConfirmModal'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { Button } from '../../../components/ui/Button'
import { merchantReadRetry } from '../api/merchantReadRetry'

export function MerchantCredentialsPage() {
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
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
  })

  const merchantProfile = profileQuery.data?.merchantProfile
  const approvalStatus = normalizeApprovalStatus(merchantProfile?.status)
  const isApproved = approvalStatus === 'APPROVED'

  // 2. Query registered API keys from Accounts Service
  const credentialsApi = createMerchantCredentialsApi({
    getAccessToken: auth.getAccessToken,
  })

  const keysQuery = useQuery({
    queryKey: ['private', 'merchant', 'api-keys', subject, merchantProfile?.merchantId],
    enabled: isApproved && Boolean(merchantProfile?.merchantId),
    gcTime: 0,
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
    queryFn: async ({ signal }) => {
      return credentialsApi.listApiKeys(merchantProfile!.merchantId, signal)
    },
  })

  // 3. UI State Management
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [createApiError, setCreateApiError] = useState<string | null>(null)

  // One-time secret reveal (destroyed from state memory upon modal dismissal)
  const [createdKeyResponse, setCreatedKeyResponse] = useState<ApiKeyCreatedResponse | null>(null)

  // Revocation modal state
  const [keyToRevoke, setKeyToRevoke] = useState<ApiKeySummaryItem | null>(null)
  const [actionErrorMessage, setActionErrorMessage] = useState<string | null>(null)
  const [isRevoking, setIsRevoking] = useState(false)
  const [actionSuccessMessage, setActionSuccessMessage] = useState<string | null>(null)

  async function handleCreateApiKey(request: CreateApiKeyRequest) {
    if (!merchantProfile?.merchantId || isSubmitting) return

    setIsSubmitting(true)
    setCreateApiError(null)

    try {
      const result = await credentialsApi.generateApiKey(merchantProfile.merchantId, request)
      setShowCreateModal(false)
      // Open one-time reveal dialog
      setCreatedKeyResponse(result)
      setActionSuccessMessage(`Machine key "${result.name}" issued successfully.`)
      void queryClient.invalidateQueries({ queryKey: ['private', 'merchant', 'api-keys', subject, merchantProfile.merchantId] })
    } catch (err) {
      if (err instanceof MerchantCredentialsError) {
        setCreateApiError(err.message)
      } else {
        setCreateApiError('Failed to generate machine API key. Please check network connectivity.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleConfirmRevoke(keyId: string) {
    if (!merchantProfile?.merchantId || isRevoking) return

    setIsRevoking(true)
    try {
      await credentialsApi.revokeApiKey(merchantProfile.merchantId, keyId)
      const revokedName = keyToRevoke?.name || 'API Key'
      setKeyToRevoke(null)
      setActionSuccessMessage(`API Key "${revokedName}" was durably revoked.`)
      void queryClient.invalidateQueries({ queryKey: ['private', 'merchant', 'api-keys', subject, merchantProfile.merchantId] })
    } catch (err) {
      setKeyToRevoke(null)
      setActionErrorMessage(err instanceof MerchantCredentialsError ? err.message : 'Failed to revoke API key.')
    } finally {
      setIsRevoking(false)
    }
  }

  // Dismissing the one-time reveal modal destroys the plaintext secret from component memory
  function handleDismissCreatedModal() {
    setCreatedKeyResponse(null)
  }

  if (auth.snapshot.status !== 'authenticated') {
    return (
      <div className="page-container merchant-page">
        <FeedbackState title="Authentication required">
          Please sign in to access merchant credential settings.
        </FeedbackState>
      </div>
    )
  }

  if (!auth.snapshot.roles.includes('MERCHANT')) {
    return (
      <div className="page-container merchant-page">
        <FeedbackState title="Merchant space unavailable" tone="error">
          Your current account does not hold the Merchant role. If you recently enrolled, your application is pending administrative approval.
        </FeedbackState>
      </div>
    )
  }

  return (
    <div className="page-container merchant-page">
      <header className="page-heading">
        <h1>API Credentials</h1>
        <p className="muted">Create and manage API keys for your store software.</p>
      </header>

      {actionErrorMessage && (
        <div className="feedback-banner is-error" role="alert">
          <span>{actionErrorMessage}</span>
          <button type="button" className="feedback-dismiss" onClick={() => setActionErrorMessage(null)} aria-label="Dismiss error">✕</button>
        </div>
      )}

      {actionSuccessMessage && (
        <aside className="alert-banner alert-banner-success" role="status">
          <p>{actionSuccessMessage}</p>
          <button
            type="button"
            className="alert-dismiss-btn"
            onClick={() => setActionSuccessMessage(null)}
            aria-label="Dismiss message"
          >
            ×
          </button>
        </aside>
      )}

      {profileQuery.isPending && <FeedbackState title="Checking merchant account" tone="loading">Loading approval status...</FeedbackState>}
      {profileQuery.isError && <FeedbackState title="Merchant account unavailable" tone="error" action={
        <Button disabled={profileQuery.isFetching} onClick={() => { if (!profileQuery.isFetching) void profileQuery.refetch({ cancelRefetch: false }) }}>Retry account</Button>
      }>Your approval status could not be checked. Please retry.</FeedbackState>}
      {!profileQuery.isPending && !profileQuery.isError && (!isApproved ? (
        <MerchantApprovalBanner rawStatus={merchantProfile?.status} />
      ) : (
        <ApiKeyListTable
          keys={keysQuery.data || []}
          isLoading={keysQuery.isPending}
          isError={keysQuery.isError}
          errorMessage={keysQuery.error instanceof Error ? keysQuery.error.message : null}
          onRetry={() => { if (!keysQuery.isFetching) void keysQuery.refetch({ cancelRefetch: false }) }}
          onOpenGenerateModal={() => {
            setCreateApiError(null)
            setShowCreateModal(true)
          }}
          onInitiateRevoke={(keyItem) => setKeyToRevoke(keyItem)}
          canGenerate={isApproved}
        />
      ))}

      {/* Generate API Key Form Modal */}
      <ApiKeyCreateFormModal
        isOpen={showCreateModal}
        isSubmitting={isSubmitting}
        apiError={createApiError}
        onClose={() => {
          if (!isSubmitting) setShowCreateModal(false)
        }}
        onSubmit={handleCreateApiKey}
      />

      {/* One-Time Plaintext Secret Reveal Dialog */}
      {createdKeyResponse && (
        <ApiKeyCreatedModal
          createdKey={createdKeyResponse}
          onClose={handleDismissCreatedModal}
        />
      )}

      {/* Revocation Confirmation Dialog */}
      <ApiKeyRevokeConfirmModal
        keyItem={keyToRevoke}
        isRevoking={isRevoking}
        onClose={() => {
          if (!isRevoking) setKeyToRevoke(null)
        }}
        onConfirm={handleConfirmRevoke}
      />
    </div>
  )
}
