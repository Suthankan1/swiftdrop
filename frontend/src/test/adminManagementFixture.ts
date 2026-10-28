import type { AdminPage, AdminStaff, AdminUser, ProvisionStaff, StaffAudit } from '../features/admin/api/userStaff'

// Synthetic DTOs matching Accounts records; never used by production code.
export const adminUser: AdminUser = {
  profileId: '10000000-0000-0000-0000-000000000001', keycloakSub: 'synthetic-user', fullName: 'Synthetic User', email: 'user@example.test', createdAt: '2026-10-28T01:00:00Z',
}
export const adminStaff: AdminStaff = {
  profileId: '20000000-0000-0000-0000-000000000002', accountProfileId: null, keycloakSub: 'synthetic-staff', staffId: 'SYNTHETIC-42', role: 'COURIER', department: 'Operations', assignedZone: null, businessStatus: 'ACTIVE', createdAt: '2026-10-28T01:00:00Z', updatedAt: '2026-10-28T01:00:00Z',
}
export const staffAudit: StaffAudit = {
  id: '30000000-0000-0000-0000-000000000003', targetSub: adminStaff.keycloakSub, targetRole: 'COURIER', action: 'STATUS_SYNC', outcome: 'PENDING_RETRY', initiatedBy: 'synthetic-admin', attemptCount: 1, createdAt: '2026-10-28T01:00:00Z', updatedAt: '2026-10-28T01:00:00Z',
}
export const provisionStaff: ProvisionStaff = {
  keycloakSub: adminStaff.keycloakSub, staffId: adminStaff.staffId, role: adminStaff.role, department: adminStaff.department, assignedZone: null, accountProfileId: null,
}
export function adminPage<T>(content: T[], page = 0, totalElements = content.length): AdminPage<T> {
  return { content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20), first: page === 0, last: page >= Math.ceil(totalElements / 20) - 1 }
}
export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
