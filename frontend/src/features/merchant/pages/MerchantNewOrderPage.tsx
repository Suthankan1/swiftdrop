import { useEffect, useMemo, useRef, useState, type FormEvent, type InputHTMLAttributes } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { ButtonLink } from '../../../components/ui/ButtonLink'
import { ArrowRightIcon, TruckIcon } from '../../../components/ui/icons'
import { createDeliveryApi, DeliveryError } from '../../customer/api/delivery'
import { createPricingApi, PricingError } from '../../customer/api/pricing'
import type { ParcelDetailResponse } from '../../customer/api/deliveryContracts'
import type { QuoteResponse } from '../../customer/api/pricingContracts'
import { formatCurrency } from '../../customer/components/customerUiUtils'
import {
  bookMerchantOrder,
  merchantOrderProblem,
  newBookingAttempt,
  type BookingAttempt,
  type MerchantOrderInput,
} from '../api/merchantBooking'
import './MerchantNewOrder.css'

const phoneError = 'Recipient phone must be a valid phone number.'
const quoteTime = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Colombo',
})

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
  const [quote, setQuote] = useState<QuoteResponse | null>(null)
  const attempt = useRef<BookingAttempt | null>(null)
  const busy = useRef(false)
  const [uncertain, setUncertain] = useState(false)

  const [now, setNow] = useState(Date.now)
  const [copyStatus, setCopyStatus] = useState('')
  const stage = booked ? 'booked' : quote ? 'review' : 'details'
  const previousStage = useRef(stage)
  const stageHeading = useRef<HTMLHeadingElement>(null)
  const expiresAt = quote ? Date.parse(quote.expiresAt) : NaN
  const expired = Boolean(quote && (quote.isExpired || !(expiresAt > now)))

  useEffect(() => {
    if (!quote || booked || expired || uncertain) return
    const remaining = expiresAt - Date.now()
    const timeout = window.setTimeout(() => setNow(Date.now()), Math.min(Math.max(remaining, 1), 60_000))
    return () => window.clearTimeout(timeout)
  }, [booked, expired, expiresAt, now, quote, uncertain])

  useEffect(() => {
    if (previousStage.current === stage) return
    previousStage.current = stage
    stageHeading.current?.focus()
  }, [stage])

  const set = (field: keyof typeof EMPTY) => (event: { target: { value: string } }) => {
    attempt.current = null // a changed order needs a fresh quote and idempotency key
    setQuote(null)
    setProblem(null)
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
    if (busy.current) return
    const invalid = merchantOrderProblem(order)
    if (invalid) {
      setProblem(invalid)
      if (invalid === phoneError) document.getElementById('new-order-recipientPhone')?.focus()
      return
    }
    setProblem(null)
    busy.current = true
    setSubmitting(true)
    try {
      if (!quote || (!uncertain && (quote.isExpired || !(expiresAt > Date.now())))) {
        // If the quote expired just before confirmation, refresh the price without booking.
        attempt.current = null
        const priced = await apis.pricing.calculateQuote({
          pickupZone: order.pickupZone, dropoffZone: order.dropoffZone, weightKg: order.weightKg,
          lengthCm: order.lengthCm, widthCm: order.widthCm, heightCm: order.heightCm,
          serviceTier: order.serviceTier,
        })
        setNow(Date.now())
        setQuote(priced)
        attempt.current = { ...newBookingAttempt(), quoteId: priced.quoteId }
        return
      }
      attempt.current ??= { ...newBookingAttempt(), quoteId: quote.quoteId }
      setBooked(await bookMerchantOrder(apis, order, attempt.current))
      setQuote(null)
      attempt.current = null
      setUncertain(false)
    } catch (error) {
      const definite = error instanceof PricingError ||
        (error instanceof DeliveryError && ['validation-error', 'forbidden', 'expired'].includes(error.kind))
      if (definite) { attempt.current = null; setQuote(null) }
      setUncertain(!definite && !!attempt.current)
      setProblem(definite ? 'The request was rejected. Check the form and review a fresh quote.'
        : attempt.current
          ? 'The booking could not be confirmed. Retry this order to avoid duplicates.'
          : 'The price could not be loaded. Please try again.')
      if (error instanceof DeliveryError && error.kind === 'auth-required') await auth.handleSessionExpiry()
    } finally {
      busy.current = false
      setSubmitting(false)
    }
  }

  const zoneOptions = zones.data ?? []
  const zoneName = (code: string) => zoneOptions.find(zone => zone.zoneCode === code)?.name ?? code
  const speed = form.serviceTier === 'EXPRESS' ? 'Same Day' : 'Standard'
  const field = (label: string, name: keyof typeof EMPTY, props: InputHTMLAttributes<HTMLInputElement> = {}) => {
    const id = `new-order-${name}`
    const error = name === 'recipientPhone' && problem === phoneError
    return (
      <div className="form-group">
        <label htmlFor={id}>{label}</label>
        <input
          id={id}
          className="form-control"
          value={form[name]}
          onChange={set(name)}
          aria-invalid={error || undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          {...props}
        />
        {error && <p id={`${id}-error`} className="new-order-field-error" role="alert">{phoneError}</p>}
      </div>
    )
  }
  const routeFields = (label: string, zoneField: 'pickupZone' | 'dropoffZone', addressField: 'pickupAddress' | 'dropoffAddress') => (
    <div className="new-order-route-fields">
      <div className="form-group">
        <label htmlFor={`new-order-${zoneField}`}>{label} zone</label>
        <select id={`new-order-${zoneField}`} className="form-control" value={form[zoneField]} onChange={set(zoneField)} disabled={!zoneOptions.length} required>
          <option value="">{zones.isPending ? 'Loading zones...' : 'Select a zone'}</option>
          {zoneOptions.map(zone => <option key={zone.zoneCode} value={zone.zoneCode}>{zone.name}</option>)}
        </select>
      </div>
      <div className="form-group">
        <label htmlFor={`new-order-${addressField}`}>{label} address</label>
        <textarea id={`new-order-${addressField}`} className="form-control" rows={2} maxLength={2000} value={form[addressField]} onChange={set(addressField)} required />
      </div>
    </div>
  )

  function resetOrder() {
    setBooked(null)
    setQuote(null)
    setForm(EMPTY)
    setProblem(null)
    setUncertain(false)
    setCopyStatus('')
    attempt.current = null
  }

  async function copyTrackingNumber(trackingNumber: string) {
    try {
      await navigator.clipboard.writeText(trackingNumber)
      setCopyStatus('Tracking number copied.')
    } catch {
      setCopyStatus('Copy unavailable. Select the tracking number to copy it.')
    }
  }

  return (
    <div className="page-container merchant-page merchant-new-order-page">
      <header className="new-order-header">
        <h1>New order</h1>
        <Link to="/merchant/orders">Bulk CSV upload</Link>
      </header>

      <ol className="new-order-progress" aria-label="Booking progress">
        <li aria-current={stage === 'details' ? 'step' : undefined}>Details</li>
        <li aria-current={stage === 'review' ? 'step' : undefined}>Review price</li>
        <li aria-current={stage === 'booked' ? 'step' : undefined}>Booked</li>
      </ol>

      {booked ? (
        <section className="card new-order-booked" aria-labelledby="order-booked-heading">
          <span className="new-order-check" aria-hidden="true">&#10003;</span>
          <h2 id="order-booked-heading" ref={stageHeading} tabIndex={-1}>Order booked</h2>
          <p className="muted">Tracking number</p>
          <div className="new-order-tracking">
            <strong>{booked.trackingNumber}</strong>
            <Button variant="secondary" size="sm" onClick={() => void copyTrackingNumber(booked.trackingNumber)}>Copy</Button>
          </div>
          <p role="status">{copyStatus}</p>
          <p>Total: <strong>{formatCurrency(booked.amountMinorUnits, booked.currency)}</strong></p>
          <div className="new-order-actions">
            <ButtonLink to={`/merchant/orders/${encodeURIComponent(booked.parcelId)}`}>View order</ButtonLink>
            <Button variant="secondary" onClick={resetOrder}>Book another</Button>
          </div>
        </section>
      ) : (
        <form className="new-order-grid" onSubmit={event => void submit(event)} noValidate aria-busy={submitting}>
          <div className="new-order-main">
            {zones.isError && (
              <div className="feedback-banner is-error" role="alert">
                <span>Delivery zones could not be loaded.</span>
                <Button variant="secondary" size="sm" disabled={zones.isFetching} onClick={() => void zones.refetch()}>Retry</Button>
              </div>
            )}
            {!quote && problem && problem !== phoneError && <p className="form-error-banner" role="alert">{problem}</p>}
            {quote ? (
              <section className="card new-order-review" aria-labelledby="new-order-review-heading">
                <h2 id="new-order-review-heading" ref={stageHeading} tabIndex={-1}>Review your quote</h2>
                <dl className="new-order-review-details">
                  <div><dt>Pickup</dt><dd>{form.pickupAddress}</dd></div>
                  <div><dt>Drop-off</dt><dd>{form.dropoffAddress}</dd></div>
                  <div><dt>Recipient</dt><dd>{form.recipientName} &middot; {form.recipientPhone}</dd></div>
                  <div><dt>Order reference</dt><dd>{form.merchantReference}</dd></div>
                  <div><dt>Dimensions</dt><dd>{form.lengthCm} &times; {form.widthCm} &times; {form.heightCm} cm</dd></div>
                  {form.deliveryNotes && <div><dt>Delivery note</dt><dd>{form.deliveryNotes}</dd></div>}
                </dl>
                <Button variant="secondary" disabled={submitting || uncertain} onClick={() => {
                  setQuote(null); attempt.current = null; setProblem(null)
                }}>Edit details</Button>
              </section>
            ) : (
              <>
                <section className="card new-order-section">
                  <h2 ref={stageHeading} tabIndex={-1}>Pickup &amp; delivery</h2>
                  <fieldset className="new-order-fields" aria-label="Pickup and delivery" disabled={submitting}>
                    {routeFields('Pickup', 'pickupZone', 'pickupAddress')}
                    {routeFields('Drop-off', 'dropoffZone', 'dropoffAddress')}
                  </fieldset>
                </section>
                <section className="card new-order-section">
                  <h2>Recipient</h2>
                  <fieldset className="new-order-fields" aria-label="Recipient details" disabled={submitting}>
                    {field('Recipient name', 'recipientName', { maxLength: 255, required: true, autoComplete: 'off' })}
                    {field('Recipient phone', 'recipientPhone', { maxLength: 25, required: true, type: 'tel', autoComplete: 'off' })}
                    {field('Your order reference', 'merchantReference', { maxLength: 255, required: true })}
                    <details className="new-order-note" open={Boolean(form.deliveryNotes) || undefined}>
                      <summary>Add delivery note</summary>
                      <label htmlFor="new-order-deliveryNotes">Delivery notes (optional)</label>
                      <textarea id="new-order-deliveryNotes" className="form-control" rows={2} maxLength={2000} value={form.deliveryNotes} onChange={set('deliveryNotes')} />
                    </details>
                  </fieldset>
                </section>
                <section className="card new-order-section">
                  <h2>Parcel</h2>
                  <fieldset className="new-order-parcel" aria-label="Parcel details" disabled={submitting}>
                    <div className="new-order-measurements">
                      {field('Weight (kg)', 'weightKg', { type: 'number', min: 0.01, max: 30, step: 0.01, inputMode: 'decimal' })}
                      {field('Length (cm)', 'lengthCm', { type: 'number', min: 1, max: 300, step: 1 })}
                      {field('Width (cm)', 'widthCm', { type: 'number', min: 1, max: 300, step: 1 })}
                      {field('Height (cm)', 'heightCm', { type: 'number', min: 1, max: 300, step: 1 })}
                    </div>
                    <fieldset className="new-order-speed">
                      <legend>Delivery speed</legend>
                      <label><input type="radio" name="serviceTier" value="STANDARD" checked={form.serviceTier === 'STANDARD'} onChange={set('serviceTier')} /> Standard</label>
                      <label><input type="radio" name="serviceTier" value="EXPRESS" checked={form.serviceTier === 'EXPRESS'} onChange={set('serviceTier')} /> <span>Same Day <small>Surcharge applies</small></span></label>
                    </fieldset>
                  </fieldset>
                </section>
              </>
            )}
          </div>
          <aside className="card new-order-summary" aria-labelledby="new-order-summary-heading">
            <h2 id="new-order-summary-heading"><TruckIcon /> Order summary</h2>
            <dl>
              <div><dt>From</dt><dd>{zoneName(form.pickupZone) || 'Choose pickup zone'}</dd></div>
              <div><dt>To</dt><dd>{zoneName(form.dropoffZone) || 'Choose drop-off zone'}</dd></div>
              <div><dt>Weight</dt><dd>{order.weightKg > 0 ? `${order.weightKg} kg` : '—'}</dd></div>
              <div><dt>Speed</dt><dd>{speed}</dd></div>
            </dl>
            {quote && (
              <div className="new-order-price">
                <span>Total</span>
                <strong>{formatCurrency(quote.totalAmountMinorUnits, quote.currency)}</strong>
                {expired && !uncertain ? <p role="status">Quote expired.</p> : !uncertain && (
                  <p>Valid until {quoteTime.format(new Date(quote.expiresAt))} (Sri Lanka time)</p>
                )}
              </div>
            )}
            {quote && problem && <p className="form-error-banner" role="alert">{problem}</p>}
            <Button type="submit" fullWidth isLoading={submitting} disabled={!quote && !zoneOptions.length} rightIcon={<ArrowRightIcon />}
              loadingText={quote && (!expired || uncertain) ? 'Booking...' : 'Getting price...'}>
              {uncertain ? 'Retry confirmed order' : !quote ? 'Review price' : expired ? 'Get fresh quote' : 'Confirm and book order'}
            </Button>
          </aside>
        </form>
      )}
    </div>
  )
}
