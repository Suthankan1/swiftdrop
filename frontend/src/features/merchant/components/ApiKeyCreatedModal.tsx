import { useState } from 'react'
import type { ApiKeyCreatedResponse } from '../api/credentialsContracts'

interface ApiKeyCreatedModalProps {
  createdKey: ApiKeyCreatedResponse
  onClose: () => void
}

export function ApiKeyCreatedModal({ createdKey, onClose }: ApiKeyCreatedModalProps) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(createdKey.plaintextKey)
      }
      setCopied(true)
      setTimeout(() => setCopied(false), 3000)
    } catch {
      setCopied(true)
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="created-key-modal-heading"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <span className="badge badge-pending">One-Time Secret Reveal</span>
            <h2 id="created-key-modal-heading" className="modal-title">API Key Generated</h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            aria-label="Close API key reveal dialog"
            onClick={onClose}
          >
            ×
          </button>
        </div>

        <div className="secret-warning-callout" role="alert">
          <strong>⚠️ Save this API key immediately!</strong>
          <p>
            For your organization's security, SwiftDrop does not store this secret and will{' '}
            <strong>never show it to you again</strong>. If you lose this key, you must revoke it and generate a replacement.
          </p>
        </div>

        <div className="key-details-summary">
          <p><strong>Name:</strong> {createdKey.name}</p>
          <p><strong>Prefix:</strong> <code>{createdKey.keyPrefix}</code></p>
          <p><strong>Scopes:</strong> {createdKey.scopes.join(', ')}</p>
        </div>

        <div className="secret-display-box">
          <code className="secret-value" data-testid="plaintext-api-key">
            {createdKey.plaintextKey}
          </code>
          <button
            type="button"
            className="button button-light secret-copy-btn"
            onClick={handleCopy}
            aria-label={copied ? 'API key copied to clipboard' : 'Copy API key to clipboard'}
          >
            {copied ? '✓ Copied!' : 'Copy Key'}
          </button>
        </div>

        <div className="modal-actions">
          <button
            type="button"
            className="button button-primary"
            onClick={onClose}
          >
            I have securely saved my key (Close)
          </button>
        </div>
      </div>
    </div>
  )
}
