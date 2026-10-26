import { useState, type FormEvent } from 'react'
import type { ApiKeyScope, CreateApiKeyRequest } from '../api/credentialsContracts'
import { PERMITTED_API_KEY_SCOPES } from '../api/credentials'

interface ApiKeyCreateFormModalProps {
  isOpen: boolean
  isSubmitting: boolean
  apiError?: string | null
  onClose: () => void
  onSubmit: (request: CreateApiKeyRequest) => Promise<void>
}

const SCOPE_DESCRIPTIONS: Record<ApiKeyScope, string> = {
  'parcels:create': 'Create and book delivery orders via automated REST API',
  'parcels:read': 'Query and track delivery statuses and parcel details',
  'webhooks:manage': 'Configure and inspect webhook delivery event destinations',
}

export function ApiKeyCreateFormModal({
  isOpen,
  isSubmitting,
  apiError,
  onClose,
  onSubmit,
}: ApiKeyCreateFormModalProps) {
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<ApiKeyScope[]>(['parcels:create', 'parcels:read'])
  const [expiresInDays, setExpiresInDays] = useState<number>(90)
  const [validationError, setValidationError] = useState<string | null>(null)

  if (!isOpen) return null

  function handleScopeToggle(scope: ApiKeyScope) {
    setScopes((prev) =>
      prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope]
    )
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setValidationError(null)

    const trimmedName = name.trim()
    if (!trimmedName) {
      setValidationError('Key name is required.')
      return
    }
    if (trimmedName.length > 100) {
      setValidationError('Key name must be 100 characters or fewer.')
      return
    }
    if (scopes.length === 0) {
      setValidationError('Select at least one permitted scope for this key.')
      return
    }
    if (isNaN(expiresInDays) || expiresInDays < 1 || expiresInDays > 365) {
      setValidationError('Expiration must be between 1 and 365 days.')
      return
    }

    await onSubmit({
      name: trimmedName,
      scopes,
      expiresInDays,
    })
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-key-modal-heading"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <p className="eyebrow">Machine credentials</p>
            <h2 id="create-key-modal-heading" className="modal-title">Generate Merchant API Key</h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            aria-label="Close dialog"
            onClick={onClose}
            disabled={isSubmitting}
          >
            ×
          </button>
        </div>

        <p className="muted">
          Scoped API keys authenticate automated enterprise software, warehouse management systems, or dispatch scripts to book and monitor parcels.
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

        <form onSubmit={handleSubmit} className="credential-form">
          <div className="form-field">
            <label htmlFor="api-key-name" className="form-label">
              Key Name <span className="required-indicator">*</span>
            </label>
            <input
              id="api-key-name"
              type="text"
              className="form-input"
              placeholder="e.g. Warehouse ERP Integration"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isSubmitting}
              maxLength={100}
              required
            />
            <span className="field-hint">A recognizable label for this integration or service.</span>
          </div>

          <fieldset className="form-field">
            <legend className="form-label">
              Permission Scopes <span className="required-indicator">*</span>
            </legend>
            <div className="scopes-checklist">
              {PERMITTED_API_KEY_SCOPES.map((scope) => (
                <label key={scope} className="scope-checkbox-label">
                  <input
                    type="checkbox"
                    checked={scopes.includes(scope)}
                    onChange={() => handleScopeToggle(scope)}
                    disabled={isSubmitting}
                  />
                  <div>
                    <code>{scope}</code>
                    <span className="scope-description">{SCOPE_DESCRIPTIONS[scope]}</span>
                  </div>
                </label>
              ))}
            </div>
          </fieldset>

          <div className="form-field">
            <label htmlFor="api-key-expiration" className="form-label">
              Expiration in Days
            </label>
            <input
              id="api-key-expiration"
              type="number"
              className="form-input"
              min={1}
              max={365}
              value={expiresInDays}
              onChange={(e) => setExpiresInDays(parseInt(e.target.value, 10) || 90)}
              disabled={isSubmitting}
            />
            <span className="field-hint">Standard lifespan is 90 days. Range: 1 to 365 days.</span>
          </div>

          <div className="modal-actions">
            <button
              type="button"
              className="button button-secondary"
              onClick={onClose}
              disabled={isSubmitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="button button-primary"
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Generating...' : 'Generate Key'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
