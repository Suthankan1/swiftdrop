import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { MerchantRegistrationRequest } from '../api/enrollmentContracts'

interface MerchantEnrollmentFormProps {
  isSubmitting: boolean
  apiError?: string | null
  onSubmit: (request: MerchantRegistrationRequest) => Promise<void>
}

export function MerchantEnrollmentForm({
  isSubmitting,
  apiError,
  onSubmit,
}: MerchantEnrollmentFormProps) {
  const [businessName, setBusinessName] = useState('')
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const businessNameRef = useRef<HTMLInputElement>(null)
  const registrationNumberRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (fieldErrors.businessName) {
      businessNameRef.current?.focus()
    } else if (fieldErrors.registrationNumber) {
      registrationNumberRef.current?.focus()
    }
  }, [fieldErrors])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setValidationError(null)
    setFieldErrors({})

    const trimmedBusinessName = businessName.trim()
    const trimmedRegNumber = registrationNumber.trim()

    if (!trimmedBusinessName || trimmedBusinessName.length < 3) {
      const message = 'Business legal name must have at least 3 characters.'
      setValidationError(message)
      setFieldErrors({ businessName: message })
      return
    }
    if (trimmedBusinessName.length > 255) {
      const message = 'Business legal name cannot exceed 255 characters.'
      setValidationError(message)
      setFieldErrors({ businessName: message })
      return
    }
    if (!trimmedRegNumber || trimmedRegNumber.length < 3) {
      const message = 'Business registration number must have at least 3 characters.'
      setValidationError(message)
      setFieldErrors({ registrationNumber: message })
      return
    }
    if (trimmedRegNumber.length > 100) {
      const message = 'Business registration number cannot exceed 100 characters.'
      setValidationError(message)
      setFieldErrors({ registrationNumber: message })
      return
    }

    await onSubmit({
      businessName: trimmedBusinessName,
      registrationNumber: trimmedRegNumber,
    })
  }

  return (
    <section className="card enrollment-card" aria-labelledby="enrollment-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Partner onboarding</p>
          <h2 id="enrollment-heading">Merchant Organization Enrollment</h2>
        </div>
        <span className="badge">Self-Service</span>
      </div>

      <p className="muted">
        Register your commercial enterprise with SwiftDrop. Once submitted, our platform administration team will review your business credentials. During this review phase, your account will enter <strong>PENDING</strong> verification status.
      </p>

      {validationError && (
        <div className="form-alert form-alert-error" role="alert">
          <span id="enrollment-validation-summary">{validationError}</span>
        </div>
      )}

      {apiError && (
        <div className="form-alert form-alert-error" role="alert">
          {apiError}
        </div>
      )}

      <form onSubmit={handleSubmit} className="enrollment-form" noValidate>
        <div className="form-field">
          <label htmlFor="business-name" className="form-label">
            Business Legal Name <span className="required-indicator">*</span>
          </label>
          <input
            id="business-name"
            ref={businessNameRef}
            type="text"
            className="form-input"
            placeholder="e.g. Lanka Retail Holdings Ltd"
            value={businessName}
            onChange={(e) => {
              setBusinessName(e.target.value)
              setFieldErrors((current) => {
                if (!current.businessName) return current
                const next = { ...current }
                delete next.businessName
                return next
              })
              setValidationError(null)
            }}
            disabled={isSubmitting}
            maxLength={255}
            required
            aria-invalid={Boolean(fieldErrors.businessName)}
            aria-describedby={`business-name-hint${fieldErrors.businessName ? ' business-name-error' : ''}`}
          />
          <span id="business-name-hint" className="field-hint">The registered name of your company or commercial trading entity.</span>
          {fieldErrors.businessName && (
            <span id="business-name-error" className="form-error" aria-live="polite">
              {fieldErrors.businessName}
            </span>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="registration-number" className="form-label">
            Business Registration / Tax Number <span className="required-indicator">*</span>
          </label>
          <input
            id="registration-number"
            ref={registrationNumberRef}
            type="text"
            className="form-input"
            placeholder="e.g. PV-109923 or BRN-847291"
            value={registrationNumber}
            onChange={(e) => {
              setRegistrationNumber(e.target.value)
              setFieldErrors((current) => {
                if (!current.registrationNumber) return current
                const next = { ...current }
                delete next.registrationNumber
                return next
              })
              setValidationError(null)
            }}
            disabled={isSubmitting}
            maxLength={100}
            required
            aria-invalid={Boolean(fieldErrors.registrationNumber)}
            aria-describedby={`registration-number-hint${fieldErrors.registrationNumber ? ' registration-number-error' : ''}`}
          />
          <span id="registration-number-hint" className="field-hint">Your official state or commercial register identifier.</span>
          {fieldErrors.registrationNumber && (
            <span id="registration-number-error" className="form-error" aria-live="polite">
              {fieldErrors.registrationNumber}
            </span>
          )}
        </div>

        <div className="enrollment-notice-box">
          <h4>Verification &amp; Security Policy</h4>
          <ul>
            <li>Merchant applications require administrative review before commercial dispatching and machine API keys are enabled.</li>
            <li>Each SwiftDrop account may own at most one merchant tenant.</li>
            <li>Registration numbers must be unique across all merchants.</li>
          </ul>
        </div>

        <div className="form-actions">
          <button
            type="submit"
            className="button button-primary"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Submitting Application...' : 'Submit Merchant Application'}
          </button>
        </div>
      </form>
    </section>
  )
}
