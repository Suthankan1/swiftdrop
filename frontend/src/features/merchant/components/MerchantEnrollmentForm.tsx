import { useState, type FormEvent } from 'react'
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

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setValidationError(null)

    const trimmedBusinessName = businessName.trim()
    const trimmedRegNumber = registrationNumber.trim()

    if (!trimmedBusinessName || trimmedBusinessName.length < 3) {
      setValidationError('Business legal name must have at least 3 characters.')
      return
    }
    if (trimmedBusinessName.length > 255) {
      setValidationError('Business legal name cannot exceed 255 characters.')
      return
    }
    if (!trimmedRegNumber || trimmedRegNumber.length < 3) {
      setValidationError('Business registration number must have at least 3 characters.')
      return
    }
    if (trimmedRegNumber.length > 100) {
      setValidationError('Business registration number cannot exceed 100 characters.')
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
          {validationError}
        </div>
      )}

      {apiError && (
        <div className="form-alert form-alert-error" role="alert">
          {apiError}
        </div>
      )}

      <form onSubmit={handleSubmit} className="enrollment-form">
        <div className="form-field">
          <label htmlFor="business-name" className="form-label">
            Business Legal Name <span className="required-indicator">*</span>
          </label>
          <input
            id="business-name"
            type="text"
            className="form-input"
            placeholder="e.g. Lanka Retail Holdings Ltd"
            value={businessName}
            onChange={(e) => setBusinessName(e.target.value)}
            disabled={isSubmitting}
            maxLength={255}
            required
          />
          <span className="field-hint">The registered name of your company or commercial trading entity.</span>
        </div>

        <div className="form-field">
          <label htmlFor="registration-number" className="form-label">
            Business Registration / Tax Number <span className="required-indicator">*</span>
          </label>
          <input
            id="registration-number"
            type="text"
            className="form-input"
            placeholder="e.g. PV-109923 or BRN-847291"
            value={registrationNumber}
            onChange={(e) => setRegistrationNumber(e.target.value)}
            disabled={isSubmitting}
            maxLength={100}
            required
          />
          <span className="field-hint">Your official state or commercial register identifier.</span>
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
