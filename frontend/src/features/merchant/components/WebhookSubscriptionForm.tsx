import { useState, type FormEvent } from 'react'
import { SUPPORTED_WEBHOOK_EVENTS, type CreateWebhookSubscriptionRequest } from '../api/webhookContracts'
import { validateWebhookDestination } from '../api/webhooks'
import { Button } from '../../../components/ui/Button'

interface WebhookSubscriptionFormProps {
  onSubmit: (request: CreateWebhookSubscriptionRequest) => Promise<void>
  onCancel: () => void
  isSubmitting: boolean
  apiError?: string | null
}

export function WebhookSubscriptionForm({
  onSubmit,
  onCancel,
  isSubmitting,
  apiError,
}: WebhookSubscriptionFormProps) {
  const [httpsUrl, setHttpsUrl] = useState('')
  const [selectedEvents, setSelectedEvents] = useState<string[]>([...SUPPORTED_WEBHOOK_EVENTS])
  const [validationError, setValidationError] = useState<string | null>(null)
  const [touched, setTouched] = useState(false)

  function handleUrlBlur() {
    setTouched(true)
    const check = validateWebhookDestination(httpsUrl)
    setValidationError(check.valid ? null : (check.error || 'Invalid URL'))
  }

  function handleUrlChange(value: string) {
    setHttpsUrl(value)
    if (touched) {
      const check = validateWebhookDestination(value)
      setValidationError(check.valid ? null : (check.error || 'Invalid URL'))
    }
  }

  function handleEventToggle(eventType: string) {
    setSelectedEvents((current) =>
      current.includes(eventType)
        ? current.filter((e) => e !== eventType)
        : [...current, eventType],
    )
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)

    const check = validateWebhookDestination(httpsUrl)
    if (!check.valid) {
      setValidationError(check.error || 'Invalid URL')
      return
    }

    if (selectedEvents.length === 0) {
      setValidationError('At least one event type must be selected.')
      return
    }

    setValidationError(null)
    await onSubmit({
      httpsUrl: httpsUrl.trim(),
      allowedEventTypes: selectedEvents,
    })
  }

  return (
    <section className="card webhook-form-card" aria-labelledby="register-webhook-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Integration setup</p>
          <h2 id="register-webhook-heading">Register Webhook Destination</h2>
        </div>
      </div>

      <p className="muted">
        Configure an HTTPS endpoint to receive authenticated, real-time parcel status change events.
      </p>

      {(validationError || apiError) && (
        <div className="form-error-banner" role="alert" aria-live="assertive">
          <strong>Validation Error:</strong> {validationError || apiError}
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="webhook-form">
        <div className="form-group">
          <label htmlFor="webhook-url-input" className="form-label">
            HTTPS Callback Destination URL <span className="required-mark" aria-hidden="true">*</span>
          </label>
          <input
            id="webhook-url-input"
            type="url"
            className={`form-input${validationError ? ' is-invalid' : ''}`}
            placeholder="https://warehouse.example.com/api/v1/swiftdrop-callbacks"
            value={httpsUrl}
            onChange={(e) => handleUrlChange(e.target.value)}
            onBlur={handleUrlBlur}
            disabled={isSubmitting}
            required
            aria-invalid={Boolean(validationError)}
            aria-describedby="webhook-url-hint"
          />
          <p id="webhook-url-hint" className="form-hint">
            Must be a secure HTTPS destination (TLS 1.2+). Plain HTTP, localhost, and RFC 1918 private IP ranges are rejected per security baseline DEC-COM-01.
          </p>
        </div>

        <fieldset className="form-group event-fieldset">
          <legend className="form-label">Subscribed Domain Event Types</legend>
          <p className="form-hint">
            Select the lifecycle events you want delivered to this endpoint.
          </p>

          <div className="event-options">
            {SUPPORTED_WEBHOOK_EVENTS.map((eventType) => (
              <label key={eventType} className="form-checkbox-label">
                <input
                  type="checkbox"
                  className="form-checkbox"
                  checked={selectedEvents.includes(eventType)}
                  onChange={() => handleEventToggle(eventType)}
                  disabled={isSubmitting}
                />
                <span className="event-label-text">
                  <code className="event-code">{eventType}</code>
                  <span className="event-description">
                    Emitted upon courier collection, transit scans, last-mile dispatch, final delivery handover, or terminal exception.
                  </span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="form-actions">
          <Button
            type="submit"
            variant="primary"
            isLoading={isSubmitting}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Registering destination...' : 'Register Destination'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={onCancel}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
        </div>

      </form>
    </section>
  )
}
