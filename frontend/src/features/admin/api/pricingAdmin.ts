/** Pricing OpenAPI 1.1 / #123. Monetary int64 values stay decimal strings. */
export const categories = ['SMALL', 'MEDIUM', 'LARGE', 'OVERSIZED'] as const
export type Category = typeof categories[number]
export type CardStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED'
export interface Zone { id: string; zoneCode: string; name: string; description: string; standardTransitHours: number; active: boolean; revision: number; createdAt: string; updatedAt: string }
export interface Card { id: string; versionCode: string; currency: 'LKR'; status: CardStatus; effectiveFrom: string; effectiveTo: string | null; description: string | null; revision: number; createdAt: string; activatedAt: string | null; archivedAt: string | null }
export interface Rule { id: string; rateVersionId: string; pickupZoneCode: string; dropoffZoneCode: string; sizeCategory: Category; minWeightKg: number; maxWeightKg: number; baseFeeMinorUnits: string; perKgSurchargeMinorUnits: string; weightThresholdKg: number; revision: number }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number }
export interface ZoneInput { zoneCode: string; name: string; description: string; standardTransitHours: number }
export interface DraftInput { versionCode: string; currency: 'LKR'; effectiveFrom: string; effectiveTo: string | null; description: string }
export type RuleInput = Omit<Rule, 'id' | 'rateVersionId' | 'revision'>
export type PricingCommand =
  | { action: 'create-zone'; input: ZoneInput }
  | { action: 'update-zone'; zone: Zone; input: ZoneInput }
  | { action: 'disable'; zone: Zone }
  | { action: 'draft'; input: DraftInput }
  | { action: 'rule'; card: Card; ruleId?: string; input: RuleInput }
  | { action: 'activate'; card: Card; active: Card | null }
  | { action: 'archive'; card: Card }
export type Failure = 'session' | 'denied' | 'missing' | 'conflict' | 'validation' | 'unavailable' | 'unknown' | 'limit'
const messages: Record<Failure, string> = {
  session: 'Your session expired. Please sign in again.', denied: 'Access to Pricing administration is denied.',
  missing: 'This Pricing resource is missing or unavailable. Refresh the configuration.',
  conflict: 'Configuration changed or conflicts with another record. Review the latest server values and deliberately reapply your changes.',
  validation: 'Pricing rejected this configuration. Check the fields, references and effective period.',
  unavailable: 'Pricing is unavailable. Retry the read to check the current configuration.',
  unknown: 'The write outcome is unknown. It may have been saved. The command was not replayed. Review server configuration before another write.',
  limit: 'Publication review is limited to 500 rate cards. A bounded active-card provider is required for larger histories.',
}
const codes: Record<string, string> = {
  STALE_REVISION: 'Another administrator changed this revision. Review the latest server values before reapplying.',
  ACTIVE_VERSION_CHANGED: 'The active card changed. Review publication again before confirming.',
  DUPLICATE_ZONE_CODE: 'This zone code already exists.', DUPLICATE_RATE_VERSION: 'This version code already exists.',
  DUPLICATE_RATE_RULE: 'This route, category and weight band already exists.',
  ZONE_REFERENCED: 'The zone cannot be disabled while referenced by active rules or unexpired quotes.',
  ZONE_UNAVAILABLE: 'A rule references a missing or disabled zone.',
  OVERLAPPING_RATE_RULE: 'Inclusive weight bands overlap for this route and category.',
  INCOMPLETE_RATE_CARD: 'Publication requires complete active-zone route and category coverage without weight gaps.',
  INVALID_EFFECTIVE_INTERVAL: 'The effective period is invalid. Publication requires a period containing the current server time; scheduling is unavailable.',
  RATE_NOT_EFFECTIVE: 'Publication requires an effective period containing the current server time. Scheduling is unavailable.',
  INVALID_RATE_CARD: 'A stored rule violates weight or currency bounds. Review the draft rules.',
  RATE_VERSION_IMMUTABLE: 'Published rules are immutable. Prepare a new draft.',
  INVALID_RATE_TRANSITION: 'This lifecycle transition is not permitted.',
  INVALID_RATE_RULE: 'The weight band exceeds its category limit or has invalid bounds.',
  ARITHMETIC_OVERFLOW: 'The rule amount exceeds the supported monetary range.',
}
export class PricingError extends Error {
  readonly kind: Failure
  readonly code?: string
  constructor(kind: Failure, code?: string) { super(code && codes[code] || messages[kind]); this.name = 'PricingError'; this.kind = kind; this.code = code }
}
export function pricingFailure(error: unknown): PricingError { return error instanceof PricingError ? error : new PricingError('unavailable') }
function invalid(): never { throw new PricingError('unavailable') }
function object(v: unknown): Record<string, unknown> { return v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid() }
function text(v: unknown, max = 512): string { return typeof v === 'string' && v.length <= max ? v : invalid() }
function id(v: unknown): string { const s = text(v, 36); return /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(s) ? s : invalid() }
function int(v: unknown): number { return typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : invalid() }
function date(v: unknown): string { const s = text(v, 40); return /^\d{4}-\d\d-\d\dT/.test(s) && Number.isFinite(Date.parse(s)) ? s : invalid() }
function optionalDate(v: unknown): string | null { return v === null ? null : date(v) }
function weight(v: unknown): number { return typeof v === 'number' && v >= 0 && v <= 30 && /^\d+(\.\d{1,2})?$/.test(String(v)) ? v : invalid() }
export function validMoney(v: string): boolean { return /^(0|[1-9]\d{0,18})$/.test(v) && BigInt(v) <= 9223372036854775807n }
function money(v: unknown): string { const s = typeof v === 'number' && Number.isSafeInteger(v) ? String(v) : text(v, 19); return validMoney(s) ? s : invalid() }
export function formatLkr(v: string): string { const n = BigInt(v); return `LKR ${n / 100n}.${String(n % 100n).padStart(2, '0')}` }
export function parseZone(raw: unknown): Zone {
  const v = object(raw)
  if (typeof v.active !== 'boolean' || !/^[A-Z][A-Z0-9_]{0,31}$/.test(text(v.zoneCode)) || int(v.standardTransitHours) < 1 || !text(v.name, 128).trim()) invalid()
  return { id: id(v.id), zoneCode: text(v.zoneCode, 32), name: text(v.name, 128), description: text(v.description), standardTransitHours: int(v.standardTransitHours), active: v.active, revision: int(v.revision), createdAt: date(v.createdAt), updatedAt: date(v.updatedAt) }
}
export function parseCard(raw: unknown): Card {
  const v = object(raw)
  if (v.currency !== 'LKR' || !['DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED'].includes(text(v.status)) || !text(v.versionCode, 64).trim()) invalid()
  return { id: id(v.id), versionCode: text(v.versionCode, 64), currency: 'LKR', status: v.status as CardStatus, effectiveFrom: date(v.effectiveFrom), effectiveTo: optionalDate(v.effectiveTo), description: v.description === null ? null : text(v.description), revision: int(v.revision), createdAt: date(v.createdAt), activatedAt: optionalDate(v.activatedAt), archivedAt: optionalDate(v.archivedAt) }
}
export function parseRule(raw: unknown): Rule {
  const v = object(raw)
  if (!categories.includes(v.sizeCategory as Category) || !/^[A-Z][A-Z0-9_]{0,31}$/.test(text(v.pickupZoneCode)) || !/^[A-Z][A-Z0-9_]{0,31}$/.test(text(v.dropoffZoneCode)) || weight(v.minWeightKg) < .01 || weight(v.maxWeightKg) < weight(v.minWeightKg)) invalid()
  return { id: id(v.id), rateVersionId: id(v.rateVersionId), pickupZoneCode: text(v.pickupZoneCode), dropoffZoneCode: text(v.dropoffZoneCode), sizeCategory: v.sizeCategory as Category, minWeightKg: weight(v.minWeightKg), maxWeightKg: weight(v.maxWeightKg), weightThresholdKg: weight(v.weightThresholdKg), baseFeeMinorUnits: money(v.baseFeeMinorUnits), perKgSurchargeMinorUnits: money(v.perKgSurchargeMinorUnits), revision: int(v.revision) }
}
function page<T extends { id: string }>(raw: unknown, p: number, size: number, parse: (v: unknown) => T): Page<T> {
  const v = object(raw), total = int(v.totalElements)
  if (v.page !== p || v.size !== size || !Array.isArray(v.items) || v.items.length > size || v.items.length > total) invalid()
  const items = (v.items as unknown[]).map(parse)
  if (new Set(items.map(item => item.id)).size !== items.length) invalid()
  return { items, page: p, size, totalElements: total }
}
/** Skip quoted JSON tokens; preserve only unsafe integer tokens before JSON.parse. */
export function parsePricingJson(raw: string): unknown {
  return JSON.parse(raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    return /^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token)) ? JSON.stringify(token) : token
  })) as unknown
}
export interface PricingApi {
  zones: (page: number, signal: AbortSignal) => Promise<Page<Zone>>
  zone: (id: string, signal: AbortSignal) => Promise<Zone>
  cards: (page: number, signal: AbortSignal) => Promise<Page<Card>>
  review: (signal: AbortSignal) => Promise<Card[]>
  rules: (cardId: string, page: number, signal: AbortSignal) => Promise<Page<Rule>>
  command: (command: PricingCommand, signal: AbortSignal) => Promise<void>
}
export function createPricingApi({ getAccessToken, gatewayUrl = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8080', fetch: fetchRequest = (...args) => globalThis.fetch(...args) }: {
  getAccessToken: () => Promise<string | null>; gatewayUrl?: string; fetch?: typeof globalThis.fetch
}): PricingApi {
  const base = `${gatewayUrl.replace(/\/$/, '')}/api/v1/admin/pricing`
  async function request(path: string, signal: AbortSignal, method = 'GET', body?: object): Promise<unknown> {
    const bounded = AbortSignal.any([signal, AbortSignal.timeout(15000)])
    bounded.throwIfAborted()
    let token: string | null
    try { token = await getAccessToken() } catch { throw new PricingError('session') }
    bounded.throwIfAborted()
    if (!token?.trim()) throw new PricingError('session')
    try {
      const response = await fetchRequest(`${base}${path}`, { method, signal: bounded, credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body).replace(/"(baseFeeMinorUnits|perKgSurchargeMinorUnits)":"(\d+)"/g, '"$1":$2') } : {}) })
      if (!response.ok) {
        let code: string | undefined
        try { const problem = object(await response.json()); if ([400, 409, 422].includes(response.status) && typeof problem.code === 'string' && codes[problem.code]) code = problem.code } catch { /* Ignore untrusted problem text. */ }
        const kinds: Record<number, Failure> = { 400: 'validation', 401: 'session', 403: 'denied', 404: 'missing', 409: 'conflict', 412: 'conflict', 422: 'validation', 429: 'unavailable' }
        throw new PricingError(kinds[response.status] ?? (method === 'GET' ? 'unavailable' : 'unknown'), code)
      }
      const result = parsePricingJson(await response.text()); bounded.throwIfAborted(); return result
    } catch (error) {
      if (signal.aborted) throw error
      if (error instanceof PricingError) throw error
      throw new PricingError(method === 'GET' ? 'unavailable' : 'unknown')
    }
  }
  function params(p: number, size = 20) { if (!Number.isSafeInteger(p) || p < 0) throw new PricingError('validation'); return `page=${p}&size=${size}` }
  return {
    zones: async (p, signal) => page(await request(`/zones?${params(p)}`, signal), p, 20, parseZone),
    zone: async (key, signal) => { const z = parseZone(await request(`/zones/${id(key)}`, signal)); if (z.id !== key) invalid(); return z },
    cards: async (p, signal) => page(await request(`/rate-versions?${params(p)}`, signal), p, 20, parseCard),
    rules: async (key, p, signal) => { const result = page(await request(`/rate-versions/${id(key)}/rules?${params(p)}`, signal), p, 20, parseRule); if (result.items.some(r => r.rateVersionId !== key)) invalid(); return result },
    review: async signal => {
      const all: Card[] = []
      for (let p = 0; p < 10; p++) {
        const result = page(await request(`/rate-versions?${params(p, 50)}`, signal), p, 50, parseCard)
        if (result.totalElements > 500) throw new PricingError('limit')
        all.push(...result.items)
        if ((p + 1) * 50 >= result.totalElements) {
          if (new Set(all.map(c => c.id)).size !== all.length || all.filter(c => c.status === 'ACTIVE').length > 1 || all.length !== result.totalElements) invalid()
          return all
        }
      }
      throw new PricingError('limit')
    },
    command: async (command, signal) => {
      let path: string, method = 'POST', body: object
      if (command.action === 'create-zone' || command.action === 'update-zone') {
        const v = command.input
        if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(v.zoneCode) || !v.name.trim() || v.name.trim().length > 128 || v.description.length > 512 || !Number.isInteger(v.standardTransitHours) || v.standardTransitHours < 1 || v.standardTransitHours > 2147483647) throw new PricingError('validation')
        const fields = { name: v.name.trim(), description: v.description.trim(), standardTransitHours: v.standardTransitHours }
        if (command.action === 'create-zone') { path = '/zones'; body = { zoneCode: v.zoneCode, ...fields } }
        else { path = `/zones/${id(command.zone.id)}`; method = 'PUT'; body = { ...fields, expectedRevision: int(command.zone.revision) } }
      } else if (command.action === 'disable') {
        if (!command.zone.active) throw new PricingError('validation')
        path = `/zones/${id(command.zone.id)}/disable`; body = { expectedRevision: int(command.zone.revision) }
      } else if (command.action === 'draft') {
        const v = command.input
        if (!v.versionCode.trim() || v.versionCode.trim().length > 64 || v.currency !== 'LKR' || v.description.length > 512 || !Number.isFinite(Date.parse(v.effectiveFrom)) || (v.effectiveTo !== null && (!Number.isFinite(Date.parse(v.effectiveTo)) || Date.parse(v.effectiveTo) <= Date.parse(v.effectiveFrom)))) throw new PricingError('validation')
        path = '/rate-versions'; body = { versionCode: v.versionCode.trim(), currency: 'LKR', description: v.description.trim(), effectiveFrom: v.effectiveFrom, effectiveTo: v.effectiveTo }
      } else if (command.action === 'rule') {
        const v = command.input
        if (command.card.status !== 'DRAFT' || !validMoney(v.baseFeeMinorUnits) || !validMoney(v.perKgSurchargeMinorUnits) || !categories.includes(v.sizeCategory) || !/^[A-Z][A-Z0-9_]{0,31}$/.test(v.pickupZoneCode) || !/^[A-Z][A-Z0-9_]{0,31}$/.test(v.dropoffZoneCode) || weight(v.minWeightKg) < .01 || weight(v.maxWeightKg) < v.minWeightKg || v.maxWeightKg > ({ SMALL: 1, MEDIUM: 5, LARGE: 15, OVERSIZED: 30 }[v.sizeCategory])) throw new PricingError('validation')
        path = `/rate-versions/${id(command.card.id)}/rules${command.ruleId ? `/${id(command.ruleId)}` : ''}`
        if (command.ruleId) method = 'PUT'
        body = { expectedRevision: int(command.card.revision), pickupZoneCode: v.pickupZoneCode, dropoffZoneCode: v.dropoffZoneCode, sizeCategory: v.sizeCategory, minWeightKg: v.minWeightKg, maxWeightKg: v.maxWeightKg, weightThresholdKg: weight(v.weightThresholdKg), baseFeeMinorUnits: v.baseFeeMinorUnits, perKgSurchargeMinorUnits: v.perKgSurchargeMinorUnits }
      } else {
        if (command.action === 'activate' ? command.card.status !== 'DRAFT' : command.card.status !== 'SUPERSEDED') throw new PricingError('validation')
        path = `/rate-versions/${id(command.card.id)}/${command.action}`
        body = { expectedRevision: int(command.card.revision), ...(command.action === 'activate' ? { expectedActiveVersionId: command.active ? id(command.active.id) : null } : {}) }
      }
      const raw = await request(path, signal, method, body)
      try {
        if (command.action === 'rule') {
          const result = parseRule(raw), v = command.input
          if (result.rateVersionId !== command.card.id || (command.ruleId && result.id !== command.ruleId) || Object.entries(v).some(([k, value]) => result[k as keyof RuleInput] !== value)) invalid()
        } else if (command.action === 'create-zone' || command.action === 'update-zone' || command.action === 'disable') {
          const result = parseZone(raw)
          if (command.action === 'disable') { if (result.id !== command.zone.id || result.active) invalid() }
          else if (result.name !== command.input.name.trim() || result.description !== command.input.description.trim() || result.standardTransitHours !== command.input.standardTransitHours || (command.action === 'create-zone' ? result.zoneCode !== command.input.zoneCode || !result.active : result.id !== command.zone.id)) invalid()
        } else {
          const result = parseCard(raw)
          if (command.action === 'draft') {
            const endMatches = result.effectiveTo === null || command.input.effectiveTo === null ? result.effectiveTo === command.input.effectiveTo : Date.parse(result.effectiveTo) === Date.parse(command.input.effectiveTo)
            if (result.status !== 'DRAFT' || result.versionCode !== command.input.versionCode.trim() || Date.parse(result.effectiveFrom) !== Date.parse(command.input.effectiveFrom) || !endMatches || result.description !== command.input.description.trim()) invalid()
          }
          else if (result.id !== command.card.id || result.status !== (command.action === 'activate' ? 'ACTIVE' : 'ARCHIVED')) invalid()
        }
      } catch { throw new PricingError('unknown') }
    },
  }
}
