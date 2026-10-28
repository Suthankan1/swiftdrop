/** Accounts #122/#107 DTO projections. All requests go through the configured Gateway. */
export const staffRoles = ['COURIER', 'DISPATCHER', 'ADMIN'] as const
export const staffStatuses = ['ACTIVE', 'SUSPENDED', 'INACTIVE'] as const
export const reconciliationOutcomes = ['SUCCESS', 'NO_OP', 'FAILED', 'PENDING_RETRY', 'DENIED'] as const
export type StaffRole = typeof staffRoles[number]
export type StaffStatus = typeof staffStatuses[number]
export type ReconciliationOutcome = typeof reconciliationOutcomes[number]
export interface AdminUser { profileId: string; keycloakSub: string; email: string | null; fullName: string | null; createdAt: string }
export interface AdminStaff {
  profileId: string; accountProfileId: string | null; staffId: string; keycloakSub: string
  role: StaffRole; department: string; assignedZone: string | null; businessStatus: StaffStatus
  createdAt: string; updatedAt: string
}
export interface AdminPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean }
export interface StaffFilters { staffRole?: StaffRole; businessStatus?: StaffStatus; assignedZone?: string }
export interface ProvisionStaff {
  keycloakSub: string; staffId: string; role: StaffRole; department: string; assignedZone: string | null; accountProfileId: string | null
}
export interface StaffAudit {
  id: string; targetSub: string; targetRole: StaffRole; action: string; outcome: ReconciliationOutcome
  initiatedBy: string; attemptCount: number; createdAt: string; updatedAt: string
}
export type StaffCommand = { action: 'suspend' | 'sync'; staff: AdminStaff } | { action: 'retry'; staff: AdminStaff; auditId: string }
export type AdminFailure = 'auth-required' | 'forbidden' | 'not-found' | 'conflict' | 'validation' | 'rate-limited' | 'unavailable' | 'pending' | 'invalid-response' | 'unconfirmed'
const messages: Record<AdminFailure, string> = {
  'auth-required': 'Your session expired. Please sign in again.',
  forbidden: 'Access to user and staff administration is denied.',
  'not-found': 'The requested staff profile or identity was not found. Refresh before continuing.',
  conflict: 'The record changed or this identity or staff ID is already bound. Review the refreshed state before continuing.',
  validation: 'The request was rejected. Check the fields and current staff state.',
  'rate-limited': 'Too many requests. Wait a moment before trying again.',
  unavailable: 'The service is unavailable. Retry the read to check the current state.',
  pending: 'Identity reconciliation is unavailable or pending recovery. The operation is not confirmed. Refresh the audit and use its recovery action after the provider recovers.',
  'invalid-response': 'The service returned an unsupported response. Retry the read.',
  unconfirmed: 'The operation outcome is not confirmed. Refresh the business profile and audit before trying again. The command was not replayed.',
}
export class AdminManagementError extends Error {
  readonly kind: AdminFailure
  constructor(kind: AdminFailure) { super(messages[kind]); this.name = 'AdminManagementError'; this.kind = kind }
}
export function adminFailure(error: unknown): AdminManagementError {
  return error instanceof AdminManagementError ? error : new AdminManagementError('unavailable')
}
export function adminHttpError(status: number): AdminManagementError {
  const kinds: Record<number, AdminFailure> = { 400: 'validation', 401: 'auth-required', 403: 'forbidden', 404: 'not-found', 409: 'conflict', 412: 'conflict', 422: 'validation', 429: 'rate-limited', 503: 'pending' }
  return new AdminManagementError(kinds[status] ?? 'unavailable')
}
function invalid(): never { throw new AdminManagementError('invalid-response') }
function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid()
}
function hasControls(value: string): boolean { return Array.from(value).some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) }
function string(value: unknown, max = 255): string {
  return typeof value === 'string' && value.trim() && value.length <= max && !hasControls(value) ? value : invalid()
}
function nullable(value: unknown, max = 255): string | null { return value === null ? null : string(value, max) }
export function isProfileId(value: string): boolean { return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) }
function uuid(value: unknown): string { const result = string(value, 36); return isProfileId(result) ? result : invalid() }
function date(value: unknown): string {
  const result = string(value, 40)
  return /^\d{4}-\d{2}-\d{2}T/.test(result) && Number.isFinite(Date.parse(result)) ? result : invalid()
}
function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T { return allowed.find(item => item === value) ?? invalid() }
function integer(value: unknown): number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : invalid() }
export function parseAdminUser(value: unknown): AdminUser {
  const v = record(value)
  return { profileId: uuid(v.profileId), keycloakSub: string(v.keycloakSub), email: nullable(v.email, 320), fullName: nullable(v.fullName), createdAt: date(v.createdAt) }
}
export function parseAdminStaff(value: unknown): AdminStaff {
  const v = record(value)
  return { profileId: uuid(v.profileId), accountProfileId: v.accountProfileId === null ? null : uuid(v.accountProfileId), staffId: string(v.staffId, 50), keycloakSub: string(v.keycloakSub), role: oneOf(v.role, staffRoles), department: string(v.department, 100), assignedZone: nullable(v.assignedZone, 100), businessStatus: oneOf(v.businessStatus, staffStatuses), createdAt: date(v.createdAt), updatedAt: date(v.updatedAt) }
}
export function parseAdminPage<T extends { profileId: string }>(value: unknown, page: number, size: number, parse: (item: unknown) => T): AdminPage<T> {
  const v = record(value)
  const totalElements = integer(v.totalElements), totalPages = integer(v.totalPages)
  if (!Array.isArray(v.content) || v.page !== page || v.size !== size || v.content.length > size ||
    typeof v.first !== 'boolean' || typeof v.last !== 'boolean' || v.first !== (page === 0) ||
    totalPages !== Math.ceil(totalElements / size) || v.last !== (page >= totalPages - 1) ||
    v.content.length !== Math.min(size, Math.max(0, totalElements - page * size))) invalid()
  const content = v.content.map(parse)
  if (new Set(content.map(item => item.profileId)).size !== content.length) invalid()
  return { content, page, size, totalElements, totalPages, first: v.first, last: v.last }
}
export function parseStaffAudit(value: unknown, staff: Pick<AdminStaff, 'keycloakSub' | 'role'>): StaffAudit[] {
  if (!Array.isArray(value)) invalid()
  const entries = value.map(item => {
    const v = record(item)
    if (v.targetSub !== staff.keycloakSub) invalid()
    return { id: uuid(v.id), targetSub: string(v.targetSub), targetRole: string(v.targetRole), action: string(v.action, 50), outcome: oneOf(v.outcome, reconciliationOutcomes), initiatedBy: string(v.initiatedBy), attemptCount: integer(v.attemptCount), createdAt: date(v.createdAt), updatedAt: date(v.updatedAt) }
  })
  if (new Set(entries.map(item => item.id)).size !== entries.length) invalid()
  // A subject may also have merchant audit rows. Never offer their recovery in a staff screen.
  return entries.filter((item): item is StaffAudit => item.targetRole === staff.role)
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
}
export function provisionProblem(value: ProvisionStaff): string | null {
  if (!staffRoles.includes(value.role)) return 'Select an approved staff role.'
  for (const [label, text, max] of [['Identity subject', value.keycloakSub, 255], ['Staff ID', value.staffId, 50], ['Department', value.department, 100]] as const) {
    if (!text.trim() || text.length > max || hasControls(text)) return `${label} is required and must be at most ${max} characters without control characters.`
  }
  if (value.assignedZone !== null && (!value.assignedZone.trim() || value.assignedZone.length > 100 || hasControls(value.assignedZone))) return 'Assigned zone must be at most 100 characters without control characters.'
  if (value.accountProfileId !== null && !isProfileId(value.accountProfileId)) return 'Account profile ID must be a UUID.'
  return null
}
export function canSuspend(staff: AdminStaff): boolean { return staff.businessStatus === 'ACTIVE' }
export function canRecover(audit: StaffAudit): boolean { return audit.outcome === 'PENDING_RETRY' || audit.outcome === 'FAILED' }
export interface AdminManagementApi {
  users: (page: number, signal: AbortSignal) => Promise<AdminPage<AdminUser>>
  staff: (page: number, filters: StaffFilters, signal: AbortSignal) => Promise<AdminPage<AdminStaff>>
  detail: (id: string, signal: AbortSignal) => Promise<AdminStaff>
  audit: (staff: AdminStaff, signal: AbortSignal) => Promise<StaffAudit[]>
  provision: (value: ProvisionStaff, signal: AbortSignal) => Promise<string>
  command: (value: StaffCommand, signal: AbortSignal) => Promise<void>
}
export function createAdminManagementApi({ getAccessToken, gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080', fetch: fetchRequest = (...args) => globalThis.fetch(...args) }: {
  getAccessToken: () => Promise<string | null>; gatewayUrl?: string; fetch?: typeof globalThis.fetch
}): AdminManagementApi {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1/admin`
  // profileFetch is deliberately restricted to self-profile GETs. Reuse the shared
  // in-memory token getter and the feature adapter convention; never replay a command.
  async function request(path: string, signal: AbortSignal, method: 'GET' | 'POST' = 'GET', body?: unknown): Promise<unknown> {
    signal.throwIfAborted()
    let token: string | null
    try { token = await getAccessToken() } catch { throw new AdminManagementError('auth-required') }
    signal.throwIfAborted()
    if (!token?.trim()) throw new AdminManagementError('auth-required')
    let response: Response
    try {
      response = await fetchRequest(`${base}${path}`, { method, headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), signal, credentials: 'omit', cache: 'no-store', redirect: 'error' })
    } catch (error) { if (signal.aborted) throw error; throw new AdminManagementError(method === 'POST' ? 'unconfirmed' : 'unavailable') }
    signal.throwIfAborted()
    if (!response.ok) throw adminHttpError(response.status)
    try { const result: unknown = await response.json(); signal.throwIfAborted(); return result }
    catch (error) { if (signal.aborted) throw error; throw new AdminManagementError(method === 'POST' ? 'unconfirmed' : 'invalid-response') }
  }
  function pagePath(page: number): string {
    if (!Number.isSafeInteger(page) || page < 0) throw new AdminManagementError('validation')
    return `page=${page}&size=20`
  }
  return {
    users: async (page, signal) => parseAdminPage(await request(`/users?${pagePath(page)}`, signal), page, 20, parseAdminUser),
    staff: async (page, filters, signal) => {
      if ((filters.staffRole && !staffRoles.includes(filters.staffRole)) || (filters.businessStatus && !staffStatuses.includes(filters.businessStatus)) || (filters.assignedZone && (filters.assignedZone.length > 100 || hasControls(filters.assignedZone)))) throw new AdminManagementError('validation')
      const params = new URLSearchParams({ ...(filters.staffRole ? { staffRole: filters.staffRole } : {}), ...(filters.businessStatus ? { businessStatus: filters.businessStatus } : {}), ...(filters.assignedZone?.trim() ? { assignedZone: filters.assignedZone.trim() } : {}) })
      return parseAdminPage(await request(`/staff?${pagePath(page)}${params.size ? `&${params}` : ''}`, signal), page, 20, parseAdminStaff)
    },
    detail: async (id, signal) => {
      if (!isProfileId(id)) throw new AdminManagementError('validation')
      const result = parseAdminStaff(await request(`/staff/${id}`, signal))
      if (result.profileId !== id) invalid()
      return result
    },
    audit: async (staff, signal) => parseStaffAudit(await request(`/reconciliation/audit/${encodeURIComponent(string(staff.keycloakSub))}`, signal), staff),
    provision: async (value, signal) => {
      if (provisionProblem(value)) throw new AdminManagementError('validation')
      // Explicit projection drops manipulated extra fields (status/roles/passwords).
      const body: ProvisionStaff = { keycloakSub: value.keycloakSub.trim(), staffId: value.staffId.trim(), role: value.role, department: value.department.trim(), assignedZone: value.assignedZone?.trim() || null, accountProfileId: value.accountProfileId }
      const raw = await request('/reconciliation/staff/provision', signal, 'POST', body)
      try {
        const v = record(raw)
        if (v.keycloakSub !== body.keycloakSub || v.staffId !== body.staffId || v.role !== body.role || v.businessStatus !== 'ACTIVE' || v.department !== body.department || v.assignedZone !== body.assignedZone) invalid()
        date(v.createdAt); date(v.updatedAt)
        return uuid(v.profileId)
      } catch { throw new AdminManagementError('unconfirmed') }
    },
    command: async (value, signal) => {
      if (!['suspend', 'sync', 'retry'].includes(value.action) || (value.action === 'suspend' && !canSuspend(value.staff))) throw new AdminManagementError('validation')
      const subject = string(value.staff.keycloakSub)
      const path = value.action === 'retry' ? `/reconciliation/retry/${uuid(value.auditId)}` : `/reconciliation/staff/${encodeURIComponent(subject)}/${value.action}`
      const raw = await request(path, signal, 'POST')
      try {
        const v = record(raw)
        if (v.subject !== subject || v.roleName !== value.staff.role) invalid()
        date(v.timestamp)
        const outcome = oneOf(v.outcome, reconciliationOutcomes)
        if (outcome === 'PENDING_RETRY') throw new AdminManagementError('pending')
        if (outcome !== 'SUCCESS' && outcome !== 'NO_OP') throw new AdminManagementError('unconfirmed')
      } catch (error) { if (error instanceof AdminManagementError && error.kind === 'pending') throw error; throw new AdminManagementError('unconfirmed') }
    },
  }
}
