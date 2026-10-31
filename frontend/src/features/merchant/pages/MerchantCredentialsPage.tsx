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
import { MerchantNav } from '../components/MerchantNav'
import { MerchantApprovalBanner } from '../components/MerchantApprovalBanner'
import { ApiKeyListTable } from '../components/ApiKeyListTable'
import { ApiKeyCreateFormModal } from '../components/ApiKeyCreateFormModal'
import { ApiKeyCreatedModal } from '../components/ApiKeyCreatedModal'
import { ApiKeyRevokeConfirmModal } from '../components/ApiKeyRevokeConfirmModal'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { Button } from '../../../components/ui/Button'

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
  })

  const merchantProfile = profileQuery.data?.merchantProfile
  const approvalStatus = normalizeApprovalStatus(merchantProfile?.status)
  const isApproved = approvalStatus === 'APPROVED'

  // 2. Query registered API keys from Accounts Service
  const credentialsApi = createMerchantCredentialsApi({
    getAccessToken: auth.getAccessToken,
  })

  const keysQuery = useQuery({
    queryKey: ['merchant', 'api-keys', merchantProfile?.merchantId],
    enabled: isApproved && Boolean(merchantProfile?.merchantId),
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
  const [isRevoking, setIsRevoking] = useState(false)
  const [actionSuccessMessage, setActionSuccessMessage] = useState<string | null>(null)

  async function handleCreateApiKey(request: CreateApiKeyRequest) {
    if (!merchantProfile?.merchantId) return

    setIsSubmitting(true)
    setCreateApiError(null)

    try {
      const result = await credentialsApi.generateApiKey(merchantProfile.merchantId, request)
      setShowCreateModal(false)
      // Open one-time reveal dialog
      setCreatedKeyResponse(result)
      setActionSuccessMessage(`Machine key "${result.name}" issued successfully.`)
      void queryClient.invalidateQueries({ queryKey: ['merchant', 'api-keys', merchantProfile.merchantId] })
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
    if (!merchantProfile?.merchantId) return

    setIsRevoking(true)
    try {
      await credentialsApi.revokeApiKey(merchantProfile.merchantId, keyId)
      const revokedName = keyToRevoke?.name || 'API Key'
      setKeyToRevoke(null)
      setActionSuccessMessage(`API Key "${revokedName}" was durably revoked.`)
      void queryClient.invalidateQueries({ queryKey: ['merchant', 'api-keys', merchantProfile.merchantId] })
    } catch (err) {
      if (err instanceof MerchantCredentialsError) {
        alert(err.message)
      } else {
        alert('Failed to revoke API key.')
      }
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
        <p className="eyebrow">Merchant space</p>
        <h1>Machine Credentials.<br />Automated Dispatching.</h1>
        <p className="muted">
          Manage scoped high-entropy API keys for machine-to-machine order integration and webhook authorization.
        </p>
      </header>

      <MerchantNav />

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

      {/* Approval status banner */}
      <MerchantApprovalBanner rawStatus={merchantProfile?.status} />

      {!isApproved ? (
        <section className="card credential-locked-card" aria-labelledby="locked-heading">
          <h2 id="locked-heading">Credential Settings Restricted</h2>
          <p className="muted">
            {approvalStatus === 'PENDING'
              ? 'API key management requires verified merchant approval. Your application is currently under administrative review.'
              : approvalStatus === 'SUSPENDED'
              ? 'Your merchant account is suspended. All active API keys are rejected at order intake. Contact support@swiftdrop.test.'
              : 'Merchant credentials are unavailable for this account standing.'}
          </p>
          <div className="locked-actions">
            <Button
              variant="primary"
              disabled
              title="Disabled until merchant account is approved"
            >
              + Generate API Key (Disabled)
            </Button>
          </div>
        </section>
      ) : (
        <ApiKeyListTable
          keys={keysQuery.data || []}
          isLoading={keysQuery.isPending}
          isError={keysQuery.isError}
          errorMessage={keysQuery.error instanceof Error ? keysQuery.error.message : null}
          onRetry={() => { void keysQuery.refetch() }}
          onOpenGenerateModal={() => {
            setCreateApiError(null)
            setShowCreateModal(true)
          }}
          onInitiateRevoke={(keyItem) => setKeyToRevoke(keyItem)}
          canGenerate={isApproved}
        />
      )}

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
