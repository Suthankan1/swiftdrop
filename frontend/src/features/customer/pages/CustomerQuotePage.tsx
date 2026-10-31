import { useEffect, useId, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { createPricingApi, PricingError } from '../api/pricing'
import { createDeliveryApi, DeliveryError } from '../api/delivery'
import type { QuoteRequest, QuoteResponse, ZoneItem } from '../api/pricingContracts'
import type { BookParcelRequest } from '../api/deliveryContracts'
import { CustomerNav } from '../components/CustomerNav'
import { formatCurrency } from '../components/customerUiUtils'

const DEFAULT_FALLBACK_ZONES: readonly ZoneItem[] = [
  {
    zoneCode: 'ZONE_1',
    name: 'Colombo Municipal District',
    description: 'Intra-city delivery covering Colombo postal codes 1 through 15.',
    standardTransitHours: 24,
  },
  {
    zoneCode: 'ZONE_2',
    name: 'Western Province Suburbs',
    description: 'Greater Colombo, Gampaha, and Kalutara districts.',
    standardTransitHours: 48,
  },
  {
    zoneCode: 'ZONE_3',
    name: 'Outstation Regional Hubs',
    description: 'Central, Southern, and North-Western provincial distribution.',
    standardTransitHours: 72,
  },
]

export function CustomerQuotePage() {
  const auth = useAuth()
  const navigate = useNavigate()

  // Form IDs for accessibility
  const pickupZoneId = useId()
  const dropoffZoneId = useId()
  const weightId = useId()
  const lengthId = useId()
  const widthId = useId()
  const heightId = useId()
  const pickupAddressId = useId()
  const dropoffAddressId = useId()
  const recipientNameId = useId()
  const recipientPhoneId = useId()
  const deliveryNotesId = useId()

  const pricingApi = useMemo(
    () =>
      createPricingApi({
        getAccessToken: auth.getAccessToken,
      }),
    [auth.getAccessToken],
  )

  const deliveryApi = useMemo(
    () =>
      createDeliveryApi({
        getAccessToken: auth.getAccessToken,
      }),
    [auth.getAccessToken],
  )

  // Fetch active zones
  const zonesQuery = useQuery({
    queryKey: ['public', 'pricing-zones'],
    queryFn: async ({ signal }) => {
      const res = await pricingApi.listZones(signal)
      return res.zones
    },
    staleTime: 60_000,
  })

  const availableZones = useMemo(() => {
    if (zonesQuery.data && zonesQuery.data.length > 0) {
      return zonesQuery.data
    }
    return DEFAULT_FALLBACK_ZONES
  }, [zonesQuery.data])

  // Quote calculation parameters
  const [pickupZone, setPickupZone] = useState('ZONE_1')
  const [dropoffZone, setDropoffZone] = useState('ZONE_2')
  const [weightKg, setWeightKg] = useState('2.5')
  const [lengthCm, setLengthCm] = useState('30')
  const [widthCm, setWidthCm] = useState('20')
  const [heightCm, setHeightCm] = useState('15')
  const [serviceTier, setServiceTier] = useState<'STANDARD' | 'EXPRESS'>('STANDARD')

  // Validation feedback
  const [formValidationErrors, setFormValidationErrors] = useState<Record<string, string>>({})

  // Quote State
  const [quote, setQuote] = useState<QuoteResponse | null>(null)
  const [isCalculating, setIsCalculating] = useState(false)
  const [quoteError, setQuoteError] = useState<string | null>(null)

  // Countdown timer state
  const [now, setNow] = useState(Date.now)

  // Booking Intake State
  const [pickupAddress, setPickupAddress] = useState('')
  const [dropoffAddress, setDropoffAddress] = useState('')
  const [recipientName, setRecipientName] = useState('')
  const [recipientPhone, setRecipientPhone] = useState('')
  const [deliveryNotes, setDeliveryNotes] = useState('')

  // Stable client-generated idempotency key
  const [idempotencyKey, setIdempotencyKey] = useState<string>('')

  // Booking action state
  const [isBooking, setIsBooking] = useState(false)
  const [bookingError, setBookingError] = useState<string | null>(null)
  const [bookingFieldErrors, setBookingFieldErrors] = useState<Record<string, string>>({})

  const quoteFieldIds: Record<string, string> = useMemo(() => ({
    pickupZone: pickupZoneId,
    dropoffZone: dropoffZoneId,
    weight: weightId,
    length: lengthId,
    width: widthId,
    height: heightId,
  }), [pickupZoneId, dropoffZoneId, weightId, lengthId, widthId, heightId])
  const bookingFieldIds: Record<string, string> = useMemo(() => ({
    pickupAddress: pickupAddressId,
    dropoffAddress: dropoffAddressId,
    recipientName: recipientNameId,
    recipientPhone: recipientPhoneId,
    deliveryNotes: deliveryNotesId,
  }), [pickupAddressId, dropoffAddressId, recipientNameId, recipientPhoneId, deliveryNotesId])

  useEffect(() => {
    const firstInvalid = ['pickupZone', 'dropoffZone', 'weight', 'length', 'width', 'height']
      .find((field) => formValidationErrors[field])
    const fieldId = firstInvalid ? quoteFieldIds[firstInvalid] : undefined
    if (fieldId) {
      document.getElementById(fieldId)?.focus()
    }
  }, [formValidationErrors, quoteFieldIds])

  useEffect(() => {
    const firstInvalid = ['pickupAddress', 'dropoffAddress', 'recipientName', 'recipientPhone', 'deliveryNotes']
      .find((field) => bookingFieldErrors[field])
    const fieldId = firstInvalid ? bookingFieldIds[firstInvalid] : undefined
    if (fieldId) {
      document.getElementById(fieldId)?.focus()
    }
  }, [bookingFieldErrors, bookingFieldIds])

  // Live countdown timer for quote expiry
  useEffect(() => {
    if (!quote?.expiresAt) return

    const interval = setInterval(() => {
      setNow(Date.now())
    }, 1000)

    return () => clearInterval(interval)
  }, [quote?.expiresAt])

  const secondsRemaining = quote?.expiresAt
    ? Math.max(0, Math.floor((new Date(quote.expiresAt).getTime() - now) / 1000))
    : null

  const isQuoteExpired = Boolean(
    quote && (quote.isExpired || (secondsRemaining !== null && secondsRemaining <= 0)),
  )

  // Calculated Volumetric Weight helper: (L * W * H) / 5000
  const volumetricWeightPreview = useMemo(() => {
    const l = parseFloat(lengthCm) || 0
    const w = parseFloat(widthCm) || 0
    const h = parseFloat(heightCm) || 0
    return ((l * w * h) / 5000).toFixed(2)
  }, [lengthCm, widthCm, heightCm])

  // Validate quote parameters
  function validateQuoteInputs(): boolean {
    const errors: Record<string, string> = {}
    const weightNum = parseFloat(weightKg)
    const lengthNum = parseInt(lengthCm, 10)
    const widthNum = parseInt(widthCm, 10)
    const heightNum = parseInt(heightCm, 10)

    if (isNaN(weightNum) || weightNum < 0.01 || weightNum > 30.0) {
      errors.weight = 'Weight must be between 0.01 kg and 30.00 kg.'
    }
    if (isNaN(lengthNum) || lengthNum < 1 || lengthNum > 150) {
      errors.length = 'Length must be between 1 cm and 150 cm.'
    }
    if (isNaN(widthNum) || widthNum < 1 || widthNum > 150) {
      errors.width = 'Width must be between 1 cm and 150 cm.'
    }
    if (isNaN(heightNum) || heightNum < 1 || heightNum > 150) {
      errors.height = 'Height must be between 1 cm and 150 cm.'
    }
    if (!pickupZone) {
      errors.pickupZone = 'Please select a pickup zone.'
    }
    if (!dropoffZone) {
      errors.dropoffZone = 'Please select a dropoff zone.'
    }

    setFormValidationErrors(errors)
    return Object.keys(errors).length === 0
  }

  // Handle Calculate Quote
  async function handleCalculateQuote(e?: React.FormEvent) {
    if (e) e.preventDefault()
    if (!validateQuoteInputs()) return

    setIsCalculating(true)
    setQuoteError(null)
    setBookingError(null)

    const requestPayload: QuoteRequest = {
      pickupZone,
      dropoffZone,
      weightKg: parseFloat(parseFloat(weightKg).toFixed(2)),
      lengthCm: parseInt(lengthCm, 10),
      widthCm: parseInt(widthCm, 10),
      heightCm: parseInt(heightCm, 10),
    }

    try {
      const calculatedQuote = await pricingApi.calculateQuote(requestPayload)
      setQuote(calculatedQuote)
      // Generate a stable idempotency key for this new quote session
      const newKey = typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
            const r = (Math.random() * 16) | 0
            const v = c === 'x' ? r : (r & 0x3) | 0x8
            return v.toString(16)
          })
      setIdempotencyKey(newKey)
    } catch (err) {
      if (err instanceof PricingError) {
        setQuoteError(err.message)
      } else {
        setQuoteError('Unable to calculate delivery quote. Please try again.')
      }
    } finally {
      setIsCalculating(false)
    }
  }

  // Validate booking address and recipient inputs
  function validateBookingInputs(): boolean {
    const errors: Record<string, string> = {}
    if (!pickupAddress.trim()) {
      errors.pickupAddress = 'Pickup street address is required.'
    } else if (pickupAddress.length > 2000) {
      errors.pickupAddress = 'Pickup address must not exceed 2000 characters.'
    }

    if (!dropoffAddress.trim()) {
      errors.dropoffAddress = 'Dropoff street address is required.'
    } else if (dropoffAddress.length > 2000) {
      errors.dropoffAddress = 'Dropoff address must not exceed 2000 characters.'
    }

    if (!recipientName.trim()) {
      errors.recipientName = 'Recipient name is required.'
    } else if (recipientName.length > 255) {
      errors.recipientName = 'Recipient name must not exceed 255 characters.'
    }

    if (!recipientPhone.trim()) {
      errors.recipientPhone = 'Recipient phone number is required.'
    } else if (recipientPhone.length < 7 || recipientPhone.length > 50) {
      errors.recipientPhone = 'Please provide a valid phone number (7-50 characters).'
    }

    if (deliveryNotes.length > 2000) {
      errors.deliveryNotes = 'Delivery notes must not exceed 2000 characters.'
    }

    setBookingFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  function clearQuoteFieldError(field: string) {
    setFormValidationErrors((current) => {
      if (!current[field]) return current
      const next = { ...current }
      delete next[field]
      return next
    })
  }

  function clearBookingFieldError(field: string) {
    setBookingFieldErrors((current) => {
      if (!current[field]) return current
      const next = { ...current }
      delete next[field]
      return next
    })
  }

  function fieldDescribedBy(field: string, hintId?: string) {
    const errorId = `${quoteFieldIds[field] || bookingFieldIds[field]}-error`
    return [hintId, formValidationErrors[field] || bookingFieldErrors[field] ? errorId : undefined]
      .filter(Boolean)
      .join(' ') || undefined
  }

  // Handle Confirm Booking
  async function handleConfirmBooking(e: React.FormEvent) {
    e.preventDefault()
    if (!quote) return
    if (isQuoteExpired) {
      setBookingError('Quote has expired. Please recalculate your quote to continue.')
      return
    }
    if (!validateBookingInputs()) return

    setIsBooking(true)
    setBookingError(null)

    const bookingPayload: BookParcelRequest = {
      quoteId: quote.quoteId,
      pickupAddress: pickupAddress.trim(),
      dropoffAddress: dropoffAddress.trim(),
      recipientName: recipientName.trim(),
      recipientPhone: recipientPhone.trim(),
      deliveryNotes: deliveryNotes.trim() || undefined,
    }

    try {
      const bookedParcel = await deliveryApi.bookParcel(bookingPayload, idempotencyKey)
      // Navigate to confirmed booking screen passing the created parcel model
      navigate(`/customer/booking/confirmed/${encodeURIComponent(bookedParcel.parcelId)}`, {
        state: { parcel: bookedParcel },
      })
    } catch (err) {
      if (err instanceof DeliveryError) {
        if (err.kind === 'conflict') {
          setBookingError('This quote has already been booked or an idempotency conflict occurred. Please calculate a new quote.')
        } else if (err.kind === 'expired') {
          setBookingError('This quote has expired. Please recalculate your quote before booking.')
        } else if (err.kind === 'validation-error') {
          setBookingError(err.message || 'Validation error in booking details.')
        } else if (err.kind === 'forbidden') {
          setBookingError('You do not have customer authorization to create a booking.')
        } else if (err.kind === 'auth-required') {
          setBookingError('Your session has expired. Please sign in again.')
        } else {
          setBookingError(err.message || 'Delivery service was unable to confirm your booking.')
        }
      } else {
        setBookingError('A network error occurred while booking. Please try again.')
      }
    } finally {
      setIsBooking(false)
    }
  }

  // Format seconds into MM:SS
  function formatCountdown(sec: number): string {
    const mins = Math.floor(sec / 60)
    const secs = sec % 60
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`
  }

  return (
    <div className="page-container customer-page customer-quote-page">
      <header className="page-heading">
        <p className="eyebrow">Customer space</p>
        <h1>Delivery Quote &amp; <br />Booking Dispatch.</h1>
        <p className="muted">
          Calculate an authoritative, time-guaranteed delivery rate and book your shipment with confidence.
        </p>
      </header>

      <CustomerNav />

      {/* Quote calculation error feedback */}
      {quoteError && (
        <div className="feedback-banner is-error" role="alert" aria-live="assertive">
          <span>{quoteError}</span>
          <button
            type="button"
            className="feedback-dismiss"
            onClick={() => setQuoteError(null)}
            aria-label="Dismiss error"
          >
            ✕
          </button>
        </div>
      )}

      {/* Main Quote & Booking Workflow Grid */}
      <div className="quote-workflow-grid">
        {/* Left Column: Quote Parameters Form */}
        <div className="card quote-form-card">
          <div className="card-header">
            <h2>1. Shipment &amp; Route Details</h2>
            <p className="muted text-sm">
              Origin and destination pricing zones define base transit tariffs.
            </p>
          </div>

          <form onSubmit={handleCalculateQuote} noValidate>
            <div className="form-group-grid">
              <div className="form-group">
                <label htmlFor={pickupZoneId}>Pickup Zone</label>
                <select
                  id={pickupZoneId}
                  className="form-control"
                  value={pickupZone}
                  onChange={(e) => {
                    setPickupZone(e.target.value)
                    clearQuoteFieldError('pickupZone')
                  }}
                  disabled={isCalculating || isBooking}
                  aria-invalid={Boolean(formValidationErrors.pickupZone)}
                  aria-describedby={fieldDescribedBy('pickupZone')}
                >
                  {availableZones.map((z) => (
                    <option key={z.zoneCode} value={z.zoneCode}>
                      {z.name} ({z.zoneCode}) — ~{z.standardTransitHours}h transit
                    </option>
                  ))}
                </select>
                {formValidationErrors.pickupZone && (
                  <p id={`${pickupZoneId}-error`} className="form-error" role="alert">
                    {formValidationErrors.pickupZone}
                  </p>
                )}
              </div>

              <div className="form-group">
                <label htmlFor={dropoffZoneId}>Dropoff Zone</label>
                <select
                  id={dropoffZoneId}
                  className="form-control"
                  value={dropoffZone}
                  onChange={(e) => {
                    setDropoffZone(e.target.value)
                    clearQuoteFieldError('dropoffZone')
                  }}
                  disabled={isCalculating || isBooking}
                  aria-invalid={Boolean(formValidationErrors.dropoffZone)}
                  aria-describedby={fieldDescribedBy('dropoffZone')}
                >
                  {availableZones.map((z) => (
                    <option key={z.zoneCode} value={z.zoneCode}>
                      {z.name} ({z.zoneCode})
                    </option>
                  ))}
                </select>
                {formValidationErrors.dropoffZone && (
                  <p id={`${dropoffZoneId}-error`} className="form-error" role="alert">
                    {formValidationErrors.dropoffZone}
                  </p>
                )}
              </div>
            </div>

            {/* Package Size Quick Presets */}
            <div className="size-presets-bar" style={{ marginBottom: '16px' }}>
              <label className="field-label" style={{ display: 'block', marginBottom: '6px', fontSize: '0.85rem', fontWeight: 600 }}>
                Quick Package Size Presets:
              </label>
              <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                {[
                  { label: '✉️ Document (0.3kg)', weight: '0.3', l: '30', w: '21', h: '2' },
                  { label: '📦 Small Box (1.5kg)', weight: '1.5', l: '30', w: '20', h: '15' },
                  { label: '📦 Medium Carton (5.0kg)', weight: '5.0', l: '40', w: '30', h: '25' },
                  { label: '📦 Freight (15.0kg)', weight: '15.0', l: '60', w: '50', h: '40' },
                ].map((preset) => (
                  <Button
                    key={preset.label}
                    variant="tonal"
                    size="sm"
                    style={{ minHeight: '32px', padding: '4px 10px', fontSize: '0.8rem' }}
                    onClick={() => {
                      setWeightKg(preset.weight)
                      setLengthCm(preset.l)
                      setWidthCm(preset.w)
                      setHeightCm(preset.h)
                      clearQuoteFieldError('weight')
                      clearQuoteFieldError('length')
                      clearQuoteFieldError('width')
                      clearQuoteFieldError('height')
                    }}
                  >
                    {preset.label}
                  </Button>
                ))}
              </div>
            </div>

            <div className="form-group">
              <label htmlFor={weightId}>Physical Weight (kg)</label>
              <input
                id={weightId}
                type="number"
                step="0.01"
                min="0.01"
                max="30.00"
                className="form-control"
                placeholder="e.g. 2.50"
                value={weightKg}
                onChange={(e) => {
                  setWeightKg(e.target.value)
                  clearQuoteFieldError('weight')
                }}
                disabled={isCalculating || isBooking}
                aria-invalid={Boolean(formValidationErrors.weight)}
                aria-describedby={fieldDescribedBy('weight', 'weight-hint')}
              />
              <span id="weight-hint" className="form-hint">
                Standard courier parcel limit: 0.01 kg to 30.00 kg.
              </span>
              {formValidationErrors.weight && (
              <p id={`${weightId}-error`} className="form-error" role="alert">
                {formValidationErrors.weight}
              </p>
              )}
            </div>

            <div className="dimensions-grid">
              <div className="form-group">
                <label htmlFor={lengthId}>Length (cm)</label>
                <input
                  id={lengthId}
                  type="number"
                  min="1"
                  max="150"
                  className="form-control"
                  placeholder="30"
                  value={lengthCm}
                  onChange={(e) => {
                    setLengthCm(e.target.value)
                    clearQuoteFieldError('length')
                  }}
                  disabled={isCalculating || isBooking}
                  aria-invalid={Boolean(formValidationErrors.length)}
                  aria-describedby={fieldDescribedBy('length')}
                />
                {formValidationErrors.length && (
                  <p id={`${lengthId}-error`} className="form-error" role="alert">
                    {formValidationErrors.length}
                  </p>
                )}
              </div>

              <div className="form-group">
                <label htmlFor={widthId}>Width (cm)</label>
                <input
                  id={widthId}
                  type="number"
                  min="1"
                  max="150"
                  className="form-control"
                  placeholder="20"
                  value={widthCm}
                  onChange={(e) => {
                    setWidthCm(e.target.value)
                    clearQuoteFieldError('width')
                  }}
                  disabled={isCalculating || isBooking}
                  aria-invalid={Boolean(formValidationErrors.width)}
                  aria-describedby={fieldDescribedBy('width')}
                />
                {formValidationErrors.width && (
                  <p id={`${widthId}-error`} className="form-error" role="alert">
                    {formValidationErrors.width}
                  </p>
                )}
              </div>

              <div className="form-group">
                <label htmlFor={heightId}>Height (cm)</label>
                <input
                  id={heightId}
                  type="number"
                  min="1"
                  max="150"
                  className="form-control"
                  placeholder="15"
                  value={heightCm}
                  onChange={(e) => {
                    setHeightCm(e.target.value)
                    clearQuoteFieldError('height')
                  }}
                  disabled={isCalculating || isBooking}
                  aria-invalid={Boolean(formValidationErrors.height)}
                  aria-describedby={fieldDescribedBy('height')}
                />
                {formValidationErrors.height && (
                  <p id={`${heightId}-error`} className="form-error" role="alert">
                    {formValidationErrors.height}
                  </p>
                )}
              </div>
            </div>

            <div className="volumetric-helper-note">
              <span>Estimated Volumetric Weight:</span>
              <strong>{volumetricWeightPreview} kg</strong>
              <small className="muted">(L × W × H / 5000)</small>
            </div>

            {/* Service Tier Comparison Selection */}
            <div className="service-tier-selection" style={{ margin: '14px 0', padding: '12px', background: 'var(--sd-surface-card, #f8fafc)', borderRadius: '8px', border: '1px solid var(--sd-border, #e2e8f0)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Delivery Speed Tier:</span>
                <Badge variant={serviceTier === 'EXPRESS' ? 'accent' : 'primary'} size="sm">
                  {serviceTier === 'EXPRESS' ? '⚡ Express Same Day' : '🚚 Standard Next Day'}
                </Badge>
              </div>
              <div style={{ display: 'flex', gap: '8px' }}>
                <Button
                  size="sm"
                  variant={serviceTier === 'STANDARD' ? 'primary' : 'tonal'}
                  style={{ flex: 1, minHeight: '34px', fontSize: '0.8rem' }}
                  onClick={() => setServiceTier('STANDARD')}
                >
                  Standard (24-48h)
                </Button>
                <Button
                  size="sm"
                  variant={serviceTier === 'EXPRESS' ? 'accent' : 'tonal'}
                  style={{ flex: 1, minHeight: '34px', fontSize: '0.8rem' }}
                  onClick={() => setServiceTier('EXPRESS')}
                >
                  Priority Express (Same Day)
                </Button>
              </div>
            </div>

            <div className="form-actions-row">
              <Button
                type="submit"
                variant="primary"
                disabled={isCalculating || isBooking}
                isLoading={isCalculating}
                loadingText="Calculating Rate..."
              >
                {quote ? 'Recalculate Rate' : 'Calculate Delivery Quote'}
              </Button>
            </div>
          </form>
        </div>

        {/* Right Column: Quote Result & Booking Form */}
        <div className="quote-result-column">
          {!quote && !isCalculating && (
            <aside className="card quote-info-card" aria-labelledby="pricing-info-heading">
              <h2 id="pricing-info-heading">Authoritative Pricing Model</h2>
              <p>
                SwiftDrop guarantees delivery rates through backend calculation. Once calculated, your quote is fixed for 15 minutes.
              </p>
              <ul className="pricing-benefit-list">
                <li>
                  <strong>Chargeable Weight:</strong> Higher of physical and volumetric weight is billed transparently.
                </li>
                <li>
                  <strong>Zone Matrix:</strong> Intra-city and inter-province rates adhere to active tariff tables.
                </li>
                <li>
                  <strong>Idempotent Commitment:</strong> Your booking is safely protected against double submission.
                </li>
              </ul>
            </aside>
          )}

          {isCalculating && (
            <div className="card loading-quote-card" aria-busy="true">
              <span className="btn-spinner large" aria-hidden="true" />
              <p>Computing authoritative pricing against active rate tables...</p>
            </div>
          )}

          {quote && (
            <>
              {/* Calculated Quote Details Card */}
              <div className="card quote-details-card" aria-labelledby="quote-summary-heading">
                <div className="quote-card-header">
                  <div>
                    <p className="eyebrow">Calculated Quote</p>
                    <h2 id="quote-summary-heading">Guaranteed Price</h2>
                  </div>
                  <span className={`size-badge size-badge-${quote.sizeCategory.toLowerCase()}`}>
                    {quote.sizeCategory} Parcel
                  </span>
                </div>

                <div className="quote-price-hero">
                  <div className="quote-amount font-numeric">
                    {formatCurrency(quote.totalAmountMinorUnits, quote.currency)}
                  </div>
                  <div className="quote-rate-version">
                    Rate: <code>{quote.rateVersion}</code>
                  </div>
                </div>

                {/* Expiry Countdown Banner */}
                <div
                  className={`quote-timer-badge ${
                    isQuoteExpired ? 'is-expired' : (secondsRemaining ?? 999) < 180 ? 'is-warning' : 'is-active'
                  }`}
                  role="status"
                  aria-live="polite"
                >
                  <span className="timer-icon" aria-hidden="true">⏱</span>
                  {isQuoteExpired ? (
                    <strong>Quote Expired — Recalculate to book</strong>
                  ) : secondsRemaining !== null ? (
                    <span>
                      Quote valid for: <strong>{formatCountdown(secondsRemaining)}</strong>
                    </span>
                  ) : (
                    <span>Valid quote</span>
                  )}
                </div>

                {isQuoteExpired && (
                  <div className="expired-callout">
                    <p>
                      Pricing quotes are valid for 15 minutes to guarantee network delivery capacity. Please click recalculate to refresh the rate.
                    </p>
                    <Button
                      variant="secondary"
                      onClick={() => handleCalculateQuote()}
                      disabled={isCalculating}
                    >
                      Refresh Quote Rate
                    </Button>
                  </div>
                )}

                {/* Pricing Breakdown Table */}
                <dl className="quote-breakdown-list">
                  <div className="breakdown-row">
                    <dt>Base Fee</dt>
                    <dd className="font-numeric">{formatCurrency(quote.baseFeeMinorUnits, quote.currency)}</dd>
                  </div>
                  <div className="breakdown-row">
                    <dt>Weight Surcharge</dt>
                    <dd className="font-numeric">{formatCurrency(quote.weightSurchargeMinorUnits, quote.currency)}</dd>
                  </div>
                  <div className="breakdown-row">
                    <dt>Chargeable Weight</dt>
                    <dd className="font-numeric">{quote.chargeableWeightKg.toFixed(2)} kg</dd>
                  </div>
                  <div className="breakdown-row sub-row">
                    <dt>Physical Weight</dt>
                    <dd className="font-numeric">{quote.weightKg.toFixed(2)} kg</dd>
                  </div>
                  <div className="breakdown-row sub-row">
                    <dt>Volumetric Weight</dt>
                    <dd className="font-numeric">{quote.volumetricWeightKg.toFixed(2)} kg</dd>
                  </div>
                  <div className="breakdown-row">
                    <dt>Route</dt>
                    <dd>{quote.pickupZone} → {quote.dropoffZone}</dd>
                  </div>
                  <div className="breakdown-row">
                    <dt>Quote Reference</dt>
                    <dd className="font-mono text-xs">{quote.quoteId}</dd>
                  </div>
                </dl>
              </div>

              {/* Booking Intake Card */}
              {!isQuoteExpired && (
                <div className="card booking-intake-card">
                  <div className="card-header">
                    <h2>2. Complete Booking Information</h2>
                    <p className="muted text-sm">
                      Enter pickup address and recipient contact details to confirm dispatch.
                    </p>
                  </div>

                  {bookingError && (
                    <div className="feedback-banner is-error" role="alert" aria-live="assertive">
                      <span>{bookingError}</span>
                      <button
                        type="button"
                        className="feedback-dismiss"
                        onClick={() => setBookingError(null)}
                        aria-label="Dismiss booking error"
                      >
                        ✕
                      </button>
                    </div>
                  )}

                  <form onSubmit={handleConfirmBooking} noValidate>
                    <div className="form-group">
                      <label htmlFor={pickupAddressId}>Pickup Street Address</label>
                      <textarea
                        id={pickupAddressId}
                        rows={2}
                        className="form-control"
                        placeholder="e.g. 123 Galle Road, Colombo 03"
                        value={pickupAddress}
                        onChange={(e) => {
                          setPickupAddress(e.target.value)
                          clearBookingFieldError('pickupAddress')
                        }}
                        disabled={isBooking}
                        required
                        aria-invalid={Boolean(bookingFieldErrors.pickupAddress)}
                        aria-describedby={fieldDescribedBy('pickupAddress')}
                      />
                      {bookingFieldErrors.pickupAddress && (
                        <p id={`${pickupAddressId}-error`} className="form-error" role="alert">
                          {bookingFieldErrors.pickupAddress}
                        </p>
                      )}
                    </div>

                    <div className="form-group">
                      <label htmlFor={dropoffAddressId}>Dropoff Street Address</label>
                      <textarea
                        id={dropoffAddressId}
                        rows={2}
                        className="form-control"
                        placeholder="e.g. 45 Kandy Road, Gampaha"
                        value={dropoffAddress}
                        onChange={(e) => {
                          setDropoffAddress(e.target.value)
                          clearBookingFieldError('dropoffAddress')
                        }}
                        disabled={isBooking}
                        required
                        aria-invalid={Boolean(bookingFieldErrors.dropoffAddress)}
                        aria-describedby={fieldDescribedBy('dropoffAddress')}
                      />
                      {bookingFieldErrors.dropoffAddress && (
                        <p id={`${dropoffAddressId}-error`} className="form-error" role="alert">
                          {bookingFieldErrors.dropoffAddress}
                        </p>
                      )}
                    </div>

                    <div className="form-group-grid">
                      <div className="form-group">
                        <label htmlFor={recipientNameId}>Recipient Full Name</label>
                        <input
                          id={recipientNameId}
                          type="text"
                          className="form-control"
                          placeholder="e.g. Nimal Perera"
                          value={recipientName}
                          onChange={(e) => {
                            setRecipientName(e.target.value)
                            clearBookingFieldError('recipientName')
                          }}
                          disabled={isBooking}
                          required
                          aria-invalid={Boolean(bookingFieldErrors.recipientName)}
                          aria-describedby={fieldDescribedBy('recipientName')}
                        />
                        {bookingFieldErrors.recipientName && (
                          <p id={`${recipientNameId}-error`} className="form-error" role="alert">
                            {bookingFieldErrors.recipientName}
                          </p>
                        )}
                      </div>

                      <div className="form-group">
                        <label htmlFor={recipientPhoneId}>Recipient Phone Number</label>
                        <input
                          id={recipientPhoneId}
                          type="tel"
                          className="form-control"
                          placeholder="e.g. +94771234567"
                          value={recipientPhone}
                          onChange={(e) => {
                            setRecipientPhone(e.target.value)
                            clearBookingFieldError('recipientPhone')
                          }}
                          disabled={isBooking}
                          required
                          aria-invalid={Boolean(bookingFieldErrors.recipientPhone)}
                          aria-describedby={fieldDescribedBy('recipientPhone')}
                        />
                        {bookingFieldErrors.recipientPhone && (
                          <p id={`${recipientPhoneId}-error`} className="form-error" role="alert">
                            {bookingFieldErrors.recipientPhone}
                          </p>
                        )}
                      </div>
                    </div>

                    <div className="form-group">
                      <label htmlFor={deliveryNotesId}>
                        Special Delivery Instructions <span className="muted">(Optional)</span>
                      </label>
                      <textarea
                        id={deliveryNotesId}
                        rows={2}
                        className="form-control"
                        placeholder="e.g. Gate code, security desk, or alternative contact instructions"
                        value={deliveryNotes}
                        onChange={(e) => {
                          setDeliveryNotes(e.target.value)
                          clearBookingFieldError('deliveryNotes')
                        }}
                        disabled={isBooking}
                        aria-invalid={Boolean(bookingFieldErrors.deliveryNotes)}
                        aria-describedby={fieldDescribedBy('deliveryNotes')}
                      />
                      {bookingFieldErrors.deliveryNotes && (
                        <p id={`${deliveryNotesId}-error`} className="form-error" role="alert">
                          {bookingFieldErrors.deliveryNotes}
                        </p>
                      )}
                    </div>

                    <div className="booking-submit-wrap">
                      <Button
                        type="submit"
                        variant="primary"
                        size="lg"
                        fullWidth
                        disabled={isBooking || isQuoteExpired}
                        isLoading={isBooking}
                        loadingText="Booking Dispatch In Progress..."
                      >
                        Confirm &amp; Book Delivery — {formatCurrency(quote.totalAmountMinorUnits, quote.currency)}
                      </Button>
                      <p className="muted text-xs secure-notice">
                        🔒 Secured by Keycloak customer authentication and transactional idempotency.
                      </p>
                    </div>
                  </form>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
