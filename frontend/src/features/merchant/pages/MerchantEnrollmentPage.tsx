import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  createAccountsApi,
  createMerchantEnrollmentApi,
  MerchantEnrollmentError,
  normalizeApprovalStatus,
  type MerchantApprovalStatus,
} from '../api/merchant'
import type {
  MerchantProfileResponse,
  MerchantRegistrationRequest,
} from '../api/enrollmentContracts'
import { MerchantEnrollmentForm } from '../components/MerchantEnrollmentForm'
import { MerchantPendingCard } from '../components/MerchantPendingCard'
import { FeedbackState } from '../../../components/ui/FeedbackState'

export function MerchantEnrollmentPage() {
  const auth = useAuth()
  const subject = auth.snapshot.subject
  const hasMerchantRole = auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT')
  const queryClient = useQueryClient()

  // 1. Resolve current user profile and/or merchant tenant
  const userProfileQuery = useQuery({
    queryKey: ['private', 'profile', subject],
    enabled: auth.snapshot.status === 'authenticated',
    queryFn: async ({ signal }) => {
      const api = createAccountsApi({ getAccessToken: auth.getAccessToken, fetch: auth.profileFetch })
      const profile = await api.getCurrentUser(signal)
      if (signal.aborted || profile.keycloakSub !== subject) {
        throw new MerchantEnrollmentError('forbidden', 'Subject identity mismatch.')
      }
      return profile
    },
    gcTime: 0,
  })

  // 2. Query merchant-specific record directly if needed
  const merchantQuery = useQuery({
    queryKey: ['private', 'merchant-tenant', subject],
    enabled: auth.snapshot.status === 'authenticated',
    queryFn: async ({ signal }) => {
      const enrollmentApi = createMerchantEnrollmentApi({ getAccessToken: auth.getAccessToken })
      return enrollmentApi.getCurrentMerchant(signal)
    },
    gcTime: 0,
  })

  // 3. UI and submission state
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [locallyEnrolled, setLocallyEnrolled] = useState<MerchantProfileResponse | null>(null)

  // Determine current merchant state
  let merchantData: MerchantProfileResponse | null = locallyEnrolled

  if (!merchantData) {
    if (merchantQuery.data) {
      merchantData = merchantQuery.data
    } else if (userProfileQuery.data?.merchantProfile) {
      const m = userProfileQuery.data.merchantProfile
      const status: MerchantApprovalStatus = normalizeApprovalStatus(m.status)
      merchantData = {
        id: m.merchantId,
        keycloakSub: userProfileQuery.data.keycloakSub,
        businessName: m.businessName,
        registrationNumber: m.registrationNumber,
        status,
        createdAt: userProfileQuery.data.createdAt,
        updatedAt: userProfileQuery.data.createdAt,
      }
    }
  }

  async function handleEnrollSubmit(request: MerchantRegistrationRequest) {
    setIsSubmitting(true)
    setSubmitError(null)

    try {
      const enrollmentApi = createMerchantEnrollmentApi({ getAccessToken: auth.getAccessToken })
      const created = await enrollmentApi.enrollMerchant(request)
      setLocallyEnrolled(created)
      void queryClient.invalidateQueries({ queryKey: ['private', 'merchant-tenant'] })
      void queryClient.invalidateQueries({ queryKey: ['private', 'profile'] })
    } catch (err) {
      if (err instanceof MerchantEnrollmentError) {
        if (err.kind === 'conflict') {
          setSubmitError(err.message || 'You already have an active merchant application or this registration number is registered.')
          // Refresh existing profile to show their actual pending application
          void merchantQuery.refetch()
          void userProfileQuery.refetch()
        } else if (err.kind === 'validation-error') {
          setSubmitError(err.message || 'Please check the submitted business details.')
        } else {
          setSubmitError(err.message || 'Unable to submit merchant application. Please try again.')
        }
      } else {
        setSubmitError('An unexpected network error occurred.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleRefreshStatus() {
    await Promise.all([userProfileQuery.refetch(), merchantQuery.refetch()])
  }

  async function handleRefreshSession() {
    try {
      await auth.getAccessToken()
      await handleRefreshStatus()
    } catch {
      // Ignore
    }
  }

  const isLoading = userProfileQuery.isPending && merchantQuery.isPending && !merchantData

  if (auth.snapshot.status !== 'authenticated') {
    return (
      <div className="page-container merchant-enrollment-page">
        <FeedbackState title="Authentication required" tone="neutral">
          Please sign in to SwiftDrop to submit or track your merchant organization application.
        </FeedbackState>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="page-container merchant-enrollment-page">
        <FeedbackState title="Checking enrollment standing" tone="loading">
          Retrieving merchant enrollment details from Accounts Service...
        </FeedbackState>
      </div>
    )
  }

  return (
    <div className="page-container merchant-enrollment-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant onboarding</p>
        <h1>Commercial logistics.<br />Join SwiftDrop.</h1>
        <p className="muted">
          Connect your retail or warehouse operation to SwiftDrop's fast, verified delivery network.
        </p>
      </header>

      <div className="enrollment-layout-grid">
        <div className="enrollment-main-column">
          {merchantData ? (
            <MerchantPendingCard
              merchant={merchantData}
              isRefreshing={userProfileQuery.isFetching || merchantQuery.isFetching}
              hasMerchantRole={hasMerchantRole}
              onRefreshStatus={() => { void handleRefreshStatus() }}
              onRefreshSession={() => { void handleRefreshSession() }}
            />
          ) : (
            <MerchantEnrollmentForm
              isSubmitting={isSubmitting}
              apiError={submitError}
              onSubmit={handleEnrollSubmit}
            />
          )}
        </div>

        <aside className="card enrollment-sidebar" aria-labelledby="sidebar-heading">
          <h2 id="sidebar-heading">How Enrollment Works</h2>
          <dl className="sidebar-faq">
            <dt>1. Submit Application</dt>
            <dd>Enter your registered business legal name and commercial identifier.</dd>
            <dt>2. Administrative Review</dt>
            <dd>SwiftDrop administrators verify your commercial standing (1–2 business days).</dd>
            <dt>3. Automated Role Sync</dt>
            <dd>Upon approval, your Keycloak account is assigned the Merchant realm role.</dd>
            <dt>4. API &amp; Dispatch Access</dt>
            <dd>Unlock machine API keys, batch parcel intake, and webhook event delivery.</dd>
          </dl>

          <div className="sidebar-actions">
            <button
              type="button"
              className="button button-secondary"
              onClick={() => { void auth.logout() }}
            >
              Sign out
            </button>
            {merchantData && (
              <button
                type="button"
                className="button button-secondary"
                onClick={() => { void handleRefreshStatus() }}
                disabled={userProfileQuery.isFetching || merchantQuery.isFetching}
              >
                Refresh status
              </button>
            )}
          </div>
        </aside>
      </div>
    </div>
  )
}
