import { useId, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { categories, validMoney, type Card, type Category, type PricingCommand, type Rule, type Zone } from '../api/pricingAdmin'

export type Editor = { kind: 'zone'; zone?: Zone } | { kind: 'draft' } | { kind: 'rule'; card: Card; rule?: Rule }
type Fields = Record<string, string>
interface Field { key: string; label: string; max?: number; optional?: boolean; pattern?: string; help?: string }
function initial(editor: Editor): Fields {
  if (editor.kind === 'zone') return { zoneCode: editor.zone?.zoneCode ?? '', name: editor.zone?.name ?? '', description: editor.zone?.description ?? '', standardTransitHours: String(editor.zone?.standardTransitHours ?? 24) }
  if (editor.kind === 'draft') return { versionCode: '', currency: 'LKR', effectiveFrom: '', effectiveTo: '', description: '' }
  const r = editor.rule
  return { pickupZoneCode: r?.pickupZoneCode ?? '', dropoffZoneCode: r?.dropoffZoneCode ?? '', sizeCategory: r?.sizeCategory ?? 'SMALL', minWeightKg: String(r?.minWeightKg ?? '0.01'), maxWeightKg: String(r?.maxWeightKg ?? 1), baseFeeMinorUnits: r?.baseFeeMinorUnits ?? '', perKgSurchargeMinorUnits: r?.perKgSurchargeMinorUnits ?? '', weightThresholdKg: String(r?.weightThresholdKg ?? 0) }
}
const zonePattern = '[A-Z][A-Z0-9_]*'
const weightPattern = '\\d+(\\.\\d{1,2})?'
function definitions(editor: Editor): Field[] {
  if (editor.kind === 'zone') return [{ key: 'zoneCode', label: 'Zone code', max: 32, pattern: zonePattern }, { key: 'name', label: 'Zone name', max: 128 }, { key: 'description', label: 'Description', max: 512, optional: true }, { key: 'standardTransitHours', label: 'Standard transit hours', pattern: '[1-9]\\d*', help: 'Positive whole hours.' }]
  if (editor.kind === 'draft') return [{ key: 'versionCode', label: 'Version code', max: 64 }, { key: 'currency', label: 'Currency' }, { key: 'effectiveFrom', label: 'Effective from (UTC)', help: 'ISO timestamp, for example 2026-10-28T00:00:00Z. Publication must occur within this period; no scheduled activation.' }, { key: 'effectiveTo', label: 'Effective to (UTC)', optional: true, help: 'Optional exclusive end, in ISO UTC format.' }, { key: 'description', label: 'Description', max: 512, optional: true }]
  return [{ key: 'pickupZoneCode', label: 'Pickup zone code', max: 32, pattern: zonePattern }, { key: 'dropoffZoneCode', label: 'Dropoff zone code', max: 32, pattern: zonePattern }, { key: 'sizeCategory', label: 'Parcel category' }, { key: 'minWeightKg', label: 'Minimum weight (kg)', pattern: weightPattern }, { key: 'maxWeightKg', label: 'Maximum weight (kg)', pattern: weightPattern, help: 'Inclusive band. Category limits: SMALL 1, MEDIUM 5, LARGE 15, OVERSIZED 30 kg.' }, { key: 'weightThresholdKg', label: 'Surcharge threshold (kg)', pattern: weightPattern }, { key: 'baseFeeMinorUnits', label: 'Base fee (LKR minor units)', pattern: '(0|[1-9]\\d*)', help: 'Integer minor units: 35000 means LKR 350.00.' }, { key: 'perKgSurchargeMinorUnits', label: 'Surcharge per kg (LKR minor units)', pattern: '(0|[1-9]\\d*)' }]
}
function errors(editor: Editor, fields: Fields): Fields {
  const result: Fields = {}
  for (const field of definitions(editor)) {
    const value = (fields[field.key] ?? '').trim()
    if (!value && !field.optional) result[field.key] = `${field.label} is required.`
    else if ((field.max && value.length > field.max) || (value && field.pattern && !new RegExp(`^(?:${field.pattern})$`).test(value)) || Array.from(value).some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) result[field.key] = `Check the format and length of ${field.label.toLowerCase()}.`
  }
  if (editor.kind === 'zone' && (!Number.isSafeInteger(Number(fields.standardTransitHours)) || Number(fields.standardTransitHours) > 2147483647)) result.standardTransitHours = 'Enter positive whole hours up to 2147483647.'
  if (editor.kind === 'draft') {
    for (const key of ['effectiveFrom', 'effectiveTo']) if ((fields[key] || key === 'effectiveFrom') && (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(fields[key] ?? '') || !Number.isFinite(Date.parse(fields[key] ?? '')))) result[key] = 'Enter an ISO UTC timestamp ending in Z.'
    if (fields.effectiveTo && Date.parse(fields.effectiveTo) <= Date.parse(fields.effectiveFrom ?? '')) result.effectiveTo = 'The end must be after the start.'
  }
  if (editor.kind === 'rule') {
    const max = { SMALL: 1, MEDIUM: 5, LARGE: 15, OVERSIZED: 30 }[fields.sizeCategory as Category]
    if (Number(fields.minWeightKg) < .01 || Number(fields.minWeightKg) > 30) result.minWeightKg = 'Minimum weight must be 0.01 to 30.00 kg.'
    if (Number(fields.maxWeightKg) < Number(fields.minWeightKg) || Number(fields.maxWeightKg) > max) result.maxWeightKg = 'Maximum weight must cover the minimum and fit the selected category.'
    if (Number(fields.weightThresholdKg) > 30 || Number(fields.weightThresholdKg) < 0) result.weightThresholdKg = 'Threshold must be 0 to 30.00 kg.'
    for (const key of ['baseFeeMinorUnits', 'perKgSurchargeMinorUnits']) if (!validMoney(fields[key] ?? '')) result[key] = 'Enter integer minor units from 0 to 9223372036854775807.'
  }
  return result
}
export function PricingForm({ editor, busy, blocked, onCancel, onReview }: { editor: Editor; busy: boolean; blocked: boolean; onCancel: () => void; onReview: (command: PricingCommand) => void }) {
  const [fields, setFields] = useState(() => initial(editor)), [problems, setProblems] = useState<Fields>({})
  const formId = useId()
  const title = editor.kind === 'zone' ? editor.zone ? `Edit ${editor.zone.zoneCode}` : 'Create zone' : editor.kind === 'draft' ? 'Create unpublished draft' : editor.rule ? 'Edit draft rule' : 'Add draft rule'
  return <form className="card pricing-form" aria-label={title} noValidate onSubmit={event => {
    event.preventDefault()
    if (busy || blocked) return
    const found = errors(editor, fields); setProblems(found)
    if (Object.keys(found).length) { document.getElementById(`${formId}-${Object.keys(found)[0]}`)?.focus(); return }
    const v = Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, value.trim()]))
    if (editor.kind === 'zone') {
      const input = { zoneCode: v.zoneCode!, name: v.name!, description: v.description!, standardTransitHours: Number(v.standardTransitHours) }
      onReview(editor.zone ? { action: 'update-zone', zone: editor.zone, input } : { action: 'create-zone', input })
    } else if (editor.kind === 'draft') onReview({ action: 'draft', input: { versionCode: v.versionCode!, currency: 'LKR', effectiveFrom: v.effectiveFrom!, effectiveTo: v.effectiveTo || null, description: v.description! } })
    else onReview({ action: 'rule', card: editor.card, ruleId: editor.rule?.id, input: { pickupZoneCode: v.pickupZoneCode!, dropoffZoneCode: v.dropoffZoneCode!, sizeCategory: v.sizeCategory as Category, minWeightKg: Number(v.minWeightKg), maxWeightKg: Number(v.maxWeightKg), weightThresholdKg: Number(v.weightThresholdKg), baseFeeMinorUnits: v.baseFeeMinorUnits!, perKgSurchargeMinorUnits: v.perKgSurchargeMinorUnits! } })
  }}>
    <h2 tabIndex={-1} ref={node => { if (node && !node.dataset.focused) { node.focus(); node.dataset.focused = 'true' } }}>{title}</h2>
    {editor.kind === 'rule' && <p>{editor.card.versionCode} · DRAFT · LKR · parent revision {editor.card.revision}. Pricing validates active references, inclusive overlaps and complete publication coverage.</p>}
    {editor.kind === 'zone' && editor.zone && <p>Zone {editor.zone.id} · revision {editor.zone.revision}. Code and enabled state cannot be changed here.</p>}
    <fieldset disabled={busy}><legend>Configuration fields</legend><div className="pricing-fields">
      {definitions(editor).map(field => { const key = `${formId}-${field.key}`, problem = problems[field.key]; return <div key={field.key}>
        <label htmlFor={key}>{field.label}{field.optional ? ' (optional)' : ''}</label>
        {field.key === 'sizeCategory' ? <select id={key} value={fields[field.key]} onChange={event => setFields({ ...fields, [field.key]: event.target.value })}>{categories.map(c => <option key={c}>{c}</option>)}</select> : <input id={key} value={fields[field.key] ?? ''} required={!field.optional} maxLength={field.max} pattern={field.pattern} readOnly={field.key === 'currency' || (field.key === 'zoneCode' && editor.kind === 'zone' && !!editor.zone)} aria-invalid={!!problem} aria-describedby={`${key}-help ${problem ? `${key}-error` : ''}`} onChange={event => setFields({ ...fields, [field.key]: event.target.value })} />}
        <small id={`${key}-help`}>{field.help}</small>{problem && <p role="alert" id={`${key}-error`}>{problem}</p>}
      </div> })}
    </div></fieldset>
    <div className="admin-management-toolbar">
      <Button type="submit" variant="primary" disabled={busy || blocked}>Review changes</Button>
      <Button variant="secondary" disabled={busy} onClick={onCancel}>Cancel editing</Button>
    </div>
  </form>
}
