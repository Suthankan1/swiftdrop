import { afterEach, describe, expect, it, vi } from 'vitest'
import { active, draft, pricingPage, rule, zone } from '../../../test/pricingAdminFixture'
import { createPricingApi, formatLkr, parsePricingJson, parseRule, PricingError, type PricingCommand, type RuleInput } from './pricingAdmin'

const signal = () => new AbortController().signal
const zoneInput = { zoneCode: zone.zoneCode, name: zone.name, description: zone.description, standardTransitHours: 24 }
const ruleInput: RuleInput = { pickupZoneCode: rule.pickupZoneCode, dropoffZoneCode: rule.dropoffZoneCode, sizeCategory: rule.sizeCategory, minWeightKg: .01, maxWeightKg: 1, baseFeeMinorUnits: rule.baseFeeMinorUnits, perKgSurchargeMinorUnits: rule.perKgSurchargeMinorUnits, weightThresholdKg: 0 }
function setup(raw: unknown = pricingPage([zone]), status = 200) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(new Response(JSON.stringify(raw), { status }))
  const getAccessToken = vi.fn(async () => 'synthetic-memory-token')
  const api = createPricingApi({ getAccessToken, gatewayUrl: 'https://gateway.example.test/', fetch })
  return { api, fetch, getAccessToken }
}
afterEach(() => vi.useRealTimers())
describe('Pricing transport and DTO projections', () => {
  it('uses bounded Gateway reads and in-memory bearer without cookies, redirects or caching', async () => {
    const { api, fetch } = setup()
    await expect(api.zones(0, signal())).resolves.toEqual(pricingPage([zone]))
    expect(fetch).toHaveBeenCalledWith('https://gateway.example.test/api/v1/admin/pricing/zones?page=0&size=20', expect.objectContaining({ method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'error', signal: expect.any(AbortSignal), headers: { Accept: 'application/json', Authorization: 'Bearer synthetic-memory-token' } }))
  })
  it.each([-1, .5, Number.MAX_SAFE_INTEGER + 1])('rejects invalid page %s before transport', async page => {
    const { api, fetch } = setup(); await expect(api.zones(page, signal())).rejects.toBeInstanceOf(PricingError); expect(fetch).not.toHaveBeenCalled()
  })
  it.each([null, '', ' '])('requires an access token (%s)', async token => {
    const fetch = vi.fn<typeof globalThis.fetch>(); const api = createPricingApi({ getAccessToken: async () => token, fetch })
    await expect(api.zones(0, signal())).rejects.toMatchObject({ kind: 'session' }); expect(fetch).not.toHaveBeenCalled()
  })
  it('handles failed refresh without making a request', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(); const api = createPricingApi({ getAccessToken: async () => { throw Error('sensitive provider') }, fetch })
    await expect(api.cards(0, signal())).rejects.toMatchObject({ kind: 'session' }); expect(fetch).not.toHaveBeenCalled()
  })
  it('consumes cancellation and rejects late responses', async () => {
    const stop = new AbortController(), { api, fetch } = setup()
    fetch.mockImplementation(async () => { stop.abort(); return new Response(JSON.stringify(pricingPage([zone]))) })
    await expect(api.zones(0, stop.signal)).rejects.toBeDefined()
    expect((fetch.mock.calls[0]![1]!.signal as AbortSignal).aborted).toBe(true)
  })
  it('does not start an abandoned request', async () => {
    const stop = new AbortController(); stop.abort(); const { api, fetch } = setup()
    await expect(api.zones(0, stop.signal)).rejects.toBeDefined(); expect(fetch).not.toHaveBeenCalled()
  })
  it.each([
    { ...pricingPage([zone]), page: 1 }, { ...pricingPage([zone]), size: 51 }, pricingPage([zone, zone]),
    pricingPage([{ ...zone, active: 'true' }]), pricingPage([{ ...zone, revision: 1.2 }]), pricingPage([{ ...zone, id: 'bad' }]),
    pricingPage([{ ...zone, name: '' }]), pricingPage([{ ...zone, standardTransitHours: 0 }]),
    pricingPage([{ ...zone, createdAt: 'yesterday' }]), { ...pricingPage([zone]), totalElements: -1 },
  ])('rejects malformed server zone pages %#', async raw => {
    const { api } = setup(raw); await expect(api.zones(0, signal())).rejects.toBeInstanceOf(PricingError)
  })
  it('validates zone identity and drops unneeded server properties', async () => {
    const { api } = setup({ ...zone, internal: 'private' }); expect(await api.zone(zone.id, signal())).toEqual(zone)
    const wrong = setup({ ...zone, id: draft.id }); await expect(wrong.api.zone(zone.id, signal())).rejects.toBeDefined()
  })
  it('checks a rule belongs to the selected parent', async () => {
    const { api } = setup(pricingPage([rule])); await expect(api.rules(active.id, 0, signal())).rejects.toBeDefined()
  })
  it('preserves signed-int64 money exactly across parsing and display', () => {
    const parsed = parsePricingJson(JSON.stringify(rule).replace('"35000"', '9223372036854775807'))
    expect(parseRule(parsed).baseFeeMinorUnits).toBe('9223372036854775807')
    expect(formatLkr('9223372036854775807')).toBe('LKR 92233720368547758.07')
    expect(parsePricingJson('{"description":"9223372036854775807 and \\"7\\"","n":7}')).toEqual({ description: '9223372036854775807 and "7"', n: 7 })
  })
  it.each([1.5, -1, '9223372036854775808', '1e4'])('rejects malformed money %s', amount => {
    expect(() => parseRule({ ...rule, baseFeeMinorUnits: amount })).toThrow()
  })
  it('reads the active review in at most ten bounded pages, never guessing the active ID', async () => {
    const many = Array.from({ length: 50 }, (_, i) => ({ ...draft, id: `20000000-0000-0000-0000-${String(i).padStart(12, '0')}` }))
    const { api, fetch } = setup()
    fetch.mockResolvedValueOnce(new Response(JSON.stringify(pricingPage(many, 0, 51, 50)))).mockResolvedValueOnce(new Response(JSON.stringify(pricingPage([active], 1, 51, 50))))
    expect(await api.review(signal())).toHaveLength(51)
    expect(fetch).toHaveBeenCalledTimes(2); expect(fetch.mock.calls[1]![0]).toMatch(/page=1&size=50$/)
  })
  it('fails closed when history exceeds the review cap', async () => {
    const { api, fetch } = setup(pricingPage([], 0, 501, 50)); await expect(api.review(signal())).rejects.toMatchObject({ kind: 'limit' }); expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('rejects contradictory active-card results', async () => {
    const { api } = setup(pricingPage([active, { ...active, id: draft.id }], 0, 2, 50)); await expect(api.review(signal())).rejects.toBeDefined()
  })
})
describe('Pricing commands', () => {
  const cases: [PricingCommand, string, string, object, unknown][] = [
    [{ action: 'create-zone', input: zoneInput }, '/zones', 'POST', zoneInput, zone],
    [{ action: 'update-zone', zone, input: zoneInput }, `/zones/${zone.id}`, 'PUT', { name: zone.name, description: zone.description, standardTransitHours: 24, expectedRevision: 3 }, zone],
    [{ action: 'disable', zone }, `/zones/${zone.id}/disable`, 'POST', { expectedRevision: 3 }, { ...zone, active: false }],
    [{ action: 'draft', input: { versionCode: draft.versionCode, currency: 'LKR', description: '', effectiveFrom: draft.effectiveFrom, effectiveTo: null } }, '/rate-versions', 'POST', { versionCode: draft.versionCode, currency: 'LKR', description: '', effectiveFrom: draft.effectiveFrom, effectiveTo: null }, draft],
    [{ action: 'rule', card: draft, input: ruleInput }, `/rate-versions/${draft.id}/rules`, 'POST', { ...ruleInput, baseFeeMinorUnits: 35000, perKgSurchargeMinorUnits: 15000, expectedRevision: 7 }, rule],
    [{ action: 'rule', card: draft, ruleId: rule.id, input: ruleInput }, `/rate-versions/${draft.id}/rules/${rule.id}`, 'PUT', { ...ruleInput, baseFeeMinorUnits: 35000, perKgSurchargeMinorUnits: 15000, expectedRevision: 7 }, rule],
    [{ action: 'activate', card: draft, active }, `/rate-versions/${draft.id}/activate`, 'POST', { expectedRevision: 7, expectedActiveVersionId: active.id }, { ...draft, status: 'ACTIVE' }],
    [{ action: 'archive', card: { ...active, status: 'SUPERSEDED' } }, `/rate-versions/${active.id}/archive`, 'POST', { expectedRevision: 2 }, { ...active, status: 'ARCHIVED' }],
  ]
  it.each(cases)('projects the reviewed %j command exactly', async (command, path, method, body, raw) => {
    const { api, fetch } = setup(raw)
    await api.command(command, signal())
    expect(fetch).toHaveBeenCalledTimes(1); expect(fetch.mock.calls[0]![0]).toBe(`https://gateway.example.test/api/v1/admin/pricing${path}`)
    expect(fetch.mock.calls[0]![1]!.method).toBe(method); expect(JSON.parse(fetch.mock.calls[0]![1]!.body as string)).toEqual(body)
  })
  it('sends full-range integer money as a JSON number without precision loss', async () => {
    const { api, fetch } = setup({ ...rule, baseFeeMinorUnits: '9223372036854775807' })
    await api.command({ action: 'rule', card: draft, input: { ...ruleInput, baseFeeMinorUnits: '9223372036854775807' } }, signal())
    expect(fetch.mock.calls[0]![1]!.body).toContain('"baseFeeMinorUnits":9223372036854775807')
  })
  it('accepts equivalent canonical UTC timestamp precision after draft persistence', async () => {
    const { api } = setup({ ...draft, effectiveTo: '2030-01-01T00:00:00Z' })
    await expect(api.command({ action: 'draft', input: { versionCode: draft.versionCode, currency: 'LKR', description: '', effectiveFrom: '2020-01-01T00:00:00.000Z', effectiveTo: '2030-01-01T00:00:00.000Z' } }, signal())).resolves.toBeUndefined()
  })
  it.each(['ACTIVE', 'SUPERSEDED', 'ARCHIVED'] as const)('never edits rules in %s', async status => {
    const { api, fetch } = setup(); await expect(api.command({ action: 'rule', card: { ...draft, status }, input: ruleInput }, signal())).rejects.toBeDefined(); expect(fetch).not.toHaveBeenCalled()
  })
  it.each(['ACTIVE', 'DRAFT', 'ARCHIVED'] as const)('never archives %s', async status => {
    const { api, fetch } = setup(); await expect(api.command({ action: 'archive', card: { ...draft, status } }, signal())).rejects.toBeDefined(); expect(fetch).not.toHaveBeenCalled()
  })
  it('does not send extra client authority fields', async () => {
    const { api, fetch } = setup(zone)
    await api.command({ action: 'create-zone', input: { ...zoneInput, roles: ['ADMIN'], active: false } as typeof zoneInput }, signal())
    expect(fetch.mock.calls[0]![1]!.body).not.toMatch(/roles|active/)
  })
  it.each([[400, 'validation'], [401, 'session'], [403, 'denied'], [404, 'missing'], [409, 'conflict'], [422, 'validation'], [429, 'unavailable'], [500, 'unknown'], [503, 'unknown']])('handles HTTP %s without replay or raw problem output', async (status, kind) => {
    const { api, fetch } = setup({ code: 'INTERNAL', detail: 'secret server stack' }, status as number)
    await expect(api.command({ action: 'disable', zone }, signal())).rejects.toMatchObject({ kind })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('maps stable domain codes to safe specific explanations', async () => {
    const { api } = setup({ code: 'ZONE_REFERENCED', detail: 'internal SQL' }, 422)
    await expect(api.command({ action: 'disable', zone }, signal())).rejects.toThrow(/active rules or unexpired quotes/)
  })
  it('rejects stale revisions without fetching a replacement or retrying', async () => {
    const { api, fetch } = setup({ code: 'STALE_REVISION' }, 409)
    await expect(api.command({ action: 'update-zone', zone, input: zoneInput }, signal())).rejects.toMatchObject({ kind: 'conflict', code: 'STALE_REVISION' })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('treats network interruption as unknown for writes and recoverable for reads', async () => {
    const { api, fetch } = setup(); fetch.mockRejectedValue(new TypeError('network private data'))
    await expect(api.command({ action: 'disable', zone }, signal())).rejects.toMatchObject({ kind: 'unknown' })
    await expect(api.zones(0, signal())).rejects.toMatchObject({ kind: 'unavailable' })
  })
  it.each([{}, { ...zone, active: true }])('requires a matching canonical disable response %#', async raw => {
    const { api } = setup(raw); await expect(api.command({ action: 'disable', zone }, signal())).rejects.toMatchObject({ kind: 'unknown' })
  })
  it('bounds the request signal at fifteen seconds', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout'); const { api, fetch } = setup(); await api.zones(0, signal())
    const bounded = fetch.mock.calls[0]![1]!.signal as AbortSignal
    expect(bounded.aborted).toBe(false)
    expect(timeout).toHaveBeenCalledWith(15000)
    expect(bounded).toBeInstanceOf(AbortSignal)
    timeout.mockRestore()
  })
})
