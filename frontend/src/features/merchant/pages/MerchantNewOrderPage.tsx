import { useMemo, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { createDeliveryApi, DeliveryError } from '../../customer/api/delivery'
import { createPricingApi, PricingError } from '../../customer/api/pricing'
import type { ParcelDetailResponse } from '../../customer/api/deliveryContracts'
import { formatCurrency } from '../../customer/components/customerUiUtils'
import { MerchantNav } from '../components/MerchantNav'
import {
  bookMerchantOrder,
  merchantOrderProblem,
  newBookingAttempt,
  type BookingAttempt,
  type MerchantOrderInput,
} from '../api/merchantBooking'

const EMPTY = {
  pickupZone: '', dropoffZone: '', weightKg: '1', lengthCm: '20', widthCm: '15', heightCm: '10',
  pickupAddress: '', dropoffAddress: '', recipientName: '', recipientPhone: '', merchantReference: '', deliveryNotes: '',
  serviceTier: 'STANDARD' as 'STANDARD' | 'EXPRESS',
}

/** Merchant order entry (finding 64): prices the parcel, then books it for the merchant tenant. */
export function MerchantNewOrderPage() {
  const auth = useAuth()
  const apis = useMemo(() => ({
    pricing: createPricingApi({ getAccessToken: auth.getAccessToken }),
    delivery: createDeliveryApi({ getAccessToken: auth.getAccessToken }),
  }), [auth.getAccessToken])
  const zones = useQuery({
    queryKey: ['public', 'pricing-zones'],
    queryFn: async ({ signal }) => (await apis.pricing.listZones(signal)).zones,
    staleTime: 60_000,
  })
  const [form, setForm] = useState(EMPTY)
  const [problem, setProblem] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [booked, setBooked] = useState<ParcelDetailResponse | null>(null)
  const attempt = useRef<BookingAttempt | null>(null)

  const set = (field: keyof typeof EMPTY) => (event: { target: { value: string } }) => {
    attempt.current = null // a changed order needs a fresh quote and idempotency key
    setForm(current => ({ ...current, [field]: event.target.value }))
  }

  const order: MerchantOrderInput = {
    pickupZone: form.pickupZone, dropoffZone: form.dropoffZone, weightKg: Number(form.weightKg),
    lengthCm: Number(form.lengthCm), widthCm: Number(form.widthCm), heightCm: Number(form.heightCm),
    pickupAddress: form.pickupAddress, dropoffAddress: form.dropoffAddress, recipientName: form.recipientName,
    recipientPhone: form.recipientPhone, merchantReference: form.merchantReference,
    deliveryNotes: form.deliveryNotes || undefined, serviceTier: form.serviceTier,
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    const invalid = merchantOrderProblem(order)
    if (invalid) { setProblem(invalid); return }
    setProblem(null)
    setSubmitting(true)
    attempt.current ??= newBookingAttempt()
    try {
      setBooked(await bookMerchantOrder(apis, order, attempt.current))
      attempt.current = null
    } catch (error) {
      const definite = error instanceof PricingError ||
        (error instanceof DeliveryError && ['validation-error', 'forbidden', 'expired', 'conflict'].includes(error.kind))
      if (definite) attempt.current = null
      setProblem(error instanceof Error && error.message
        ? `${error.message}${definite ? '' : ' Submitting again is safe: it will not book twice.'}`
        : 'The order could not be booked.')
      if (error instanceof DeliveryError && error.kind === 'auth-required') await auth.handleSessionExpiry()
    } finally {
      setSubmitting(false)
    }
  }

  const zoneOptions = zones.data ?? []
  const field = (label: string, name: keyof typeof EMPTY, props: Record<string, unknown> = {}) => (
    <div className="form-group">
      <label htmlFor={`new-order-${name}`}>{label}</label>
      <input id={`new-order-${name}`} className="form-control" value={form[name]} onChange={set(name)} disabled={submitting} {...props} />
    </div>
  )
  const zoneSelect = (label: string, name: 'pickupZone' | 'dropoffZone') => (
    <div className="form-group">
      <label htmlFor={`new-order-${name}`}>{label}</label>
      <select id={`new-order-${name}`} className="form-control" value={form[name]} onChange={set(name)} disabled={submitting || !zoneOptions.length} required>
        <option value="">Select a zone</option>
        {zoneOptions.map(zone => <option key={zone.zoneCode} value={zone.zoneCode}>{zone.name} ({zone.zoneCode})</option>)}
      </select>
    </div>
  )

  return (
    <div className="page-container merchant-page merchant-new-order-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>New order.</h1>
        <p className="muted">Book one parcel for your store. For many orders at once, use Bulk CSV Upload on the Orders Dashboard.</p>
      </header>
      <MerchantNav />

      {booked ? (
        <section className="card" role="status" aria-labelledby="order-booked-heading">
          <h2 id="order-booked-heading">Order booked</h2>
          <p>Tracking number <strong>{booked.trackingNumber}</strong> · {formatCurrency(booked.amountMinorUnits, booked.currency)}</p>
          <p>
            <Link to={`/merchant/orders/${encodeURIComponent(booked.parcelId)}`}>View order</Link>
            {' · '}
            <Button type="button" variant="secondary" size="sm" onClick={() => { setBooked(null); setForm(EMPTY) }}>Book another</Button>
          </p>
        </section>
      ) : (
        <form className="card" onSubmit={event => void submit(event)} noValidate aria-busy={submitting}>
          {zones.isError && (
            <div className="feedback-banner is-error" role="alert">
              <span>Delivery zones could not be loaded, so orders cannot be priced right now.</span>
              <Button type="button" variant="secondary" size="sm" onClick={() => void zones.refetch()}>Retry</Button>
            </div>
          )}
          {problem && <p className="form-error-banner" role="alert">{problem}</p>}
          <fieldset disabled={submitting}>
            <legend>Parcel</legend>
            {zoneSelect('Pickup zone', 'pickupZone')}
            {zoneSelect('Drop-off zone', 'dropoffZone')}
            {field('Weight (kg)', 'weightKg', { type: 'number', min: 0.01, max: 50, step: 0.01, inputMode: 'decimal' })}
            {field('Length (cm)', 'lengthCm', { type: 'number', min: 1, max: 300, step: 1 })}
            {field('Width (cm)', 'widthCm', { type: 'number', min: 1, max: 300, step: 1 })}
            {field('Height (cm)', 'heightCm', { type: 'number', min: 1, max: 300, step: 1 })}
            <div className="form-group">
              <label htmlFor="new-order-serviceTier">Delivery speed</label>
              <select id="new-order-serviceTier" className="form-control" value={form.serviceTier} onChange={set('serviceTier')}>
                <option value="STANDARD">Standard</option>
                <option value="EXPRESS">Same Day (surcharge applies)</option>
              </select>
            </div>
          </fieldset>
          <fieldset disabled={submitting}>
            <legend>Addresses and recipient</legend>
            {field('Pickup address', 'pickupAddress', { maxLength: 2000, required: true })}
            {field('Drop-off address', 'dropoffAddress', { maxLength: 2000, required: true })}
            {field('Recipient name', 'recipientName', { maxLength: 255, required: true, autoComplete: 'off' })}
            {field('Recipient phone', 'recipientPhone', { maxLength: 25, required: true, type: 'tel', autoComplete: 'off' })}
            {field('Your order reference', 'merchantReference', { maxLength: 255, required: true })}
            {field('Delivery notes (optional)', 'deliveryNotes', { maxLength: 2000 })}
          </fieldset>
          <Button type="submit" variant="primary" isLoading={submitting} disabled={submitting || !zoneOptions.length}>
            Price and book order
          </Button>
        </form>
      )}
    </div>
  )
}
