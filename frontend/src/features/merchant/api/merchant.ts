import type { CurrentUserProfile, MerchantProfile } from '../../customer/api/contracts'
export { createAccountsApi, AccountsError, type ProfileFailure } from '../../customer/api/accounts'
export type { CurrentUserProfile, MerchantProfile }

export type MerchantApprovalStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'UNKNOWN'

export * from './webhookContracts'
export * from './webhooks'
export * from './credentialsContracts'
export * from './credentials'
export * from './enrollmentContracts'
export * from './enrollment'

/**
 * Normalizes raw backend status into canonical domain states.
 * Accounts Service MerchantStatus enum: PENDING, APPROVED, SUSPENDED, REJECTED.
 * Contract documentation also references DENIED as synonymous with REJECTED.
 */
export function normalizeApprovalStatus(rawStatus?: string | null): MerchantApprovalStatus {
  if (!rawStatus) return 'UNKNOWN'
  const normalized = rawStatus.toUpperCase().trim()
  if (normalized === 'PENDING') return 'PENDING'
  if (normalized === 'APPROVED') return 'APPROVED'
  if (normalized === 'SUSPENDED') return 'SUSPENDED'
  if (normalized === 'REJECTED' || normalized === 'DENIED') return 'REJECTED'
  return 'UNKNOWN'
}
