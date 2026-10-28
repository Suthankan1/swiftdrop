export type StaffRole = 'COURIER' | 'DISPATCHER' | 'ADMIN'
export type StaffBusinessStatus = 'ACTIVE' | 'SUSPENDED' | 'INACTIVE'

export interface StaffProfile {
  id: string
  profileId: string
  staffId: string
  keycloakSub: string
  displayName: string
  email: string
  phoneNumber: string | null
  role: StaffRole
  department: string
  assignedZone: string | null
  status: StaffBusinessStatus
  businessStatus: StaffBusinessStatus
  createdAt: string
  updatedAt: string
}

export type StaffProfileFailure =
  | 'auth-required'
  | 'forbidden'
  | 'not-found'
  | 'unavailable'
  | 'invalid-response'

export class StaffProfileError extends Error {
  readonly kind: StaffProfileFailure

  constructor(kind: StaffProfileFailure, message = 'The staff profile could not be loaded.') {
    super(message)
    this.name = 'StaffProfileError'
    this.kind = kind
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isStaffRole(role: unknown): role is StaffRole {
  return role === 'COURIER' || role === 'DISPATCHER' || role === 'ADMIN'
}

export function isStaffBusinessStatus(status: unknown): status is StaffBusinessStatus {
  return status === 'ACTIVE' || status === 'SUSPENDED' || status === 'INACTIVE'
}

export function isStaffProfile(value: unknown): value is StaffProfile {
  if (!isRecord(value)) return false

  const idVal = value.profileId ?? value.id
  const statusVal = value.businessStatus ?? value.status

  if (typeof idVal !== 'string' || !idVal.trim()) return false
  if (typeof value.staffId !== 'string' || !value.staffId.trim()) return false
  if (typeof value.keycloakSub !== 'string' || !value.keycloakSub.trim()) return false
  if (typeof value.displayName !== 'string' || !value.displayName.trim()) return false
  if (typeof value.email !== 'string' || !value.email.trim()) return false
  if (typeof value.department !== 'string') return false
  if (typeof value.createdAt !== 'string' || !value.createdAt.trim()) return false
  if (typeof value.updatedAt !== 'string' || !value.updatedAt.trim()) return false

  if (value.phoneNumber !== null && value.phoneNumber !== undefined && typeof value.phoneNumber !== 'string') {
    return false
  }
  if (value.assignedZone !== null && value.assignedZone !== undefined && typeof value.assignedZone !== 'string') {
    return false
  }

  if (!isStaffRole(value.role)) return false
  if (!isStaffBusinessStatus(statusVal)) return false

  return true
}
