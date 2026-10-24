import { useState } from 'react'

interface WebhookSecretAlertProps {
  secret: string
  endpointUrl: string
  onDismiss: () => void
}

export function WebhookSecretAlert({ secret, endpointUrl, onDismiss }: WebhookSecretAlertProps) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(secret)
      }
      setCopied(true)
      setTimeout(() => setCopied(false), 3000)
    } catch {
      // Fallback if clipboard API is restricted
      setCopied(true)
    }
  }

  return (
    <aside className="secret-callout" role="alert" aria-label="One-time webhook signing secret">
      <div className="secret-callout-header">
        <div>
          <span className="badge badge-pending">Security Sensitive</span>
          <h3 className="secret-title">Webhook Signing Secret Generated</h3>
        </div>
      </div>

      <p className="secret-explanation">
        SwiftDrop signs all outgoing webhook callbacks to <strong>{endpointUrl}</strong> using HMAC-SHA256 in the{' '}
        <code>X-SwiftDrop-Signature</code> header.
      </p>

      <p className="secret-warning">
        <strong>Important:</strong> This signing secret is displayed <em>once</em> upon registration and cannot be recovered later.
        Store it immediately in your enterprise key vault or secure configuration manager.
      </p>

      <div className="secret-display-box">
        <code className="secret-value" data-testid="webhook-signing-secret">{secret}</code>
        <button
          type="button"
          className="button button-light secret-copy-btn"
          onClick={handleCopy}
          aria-label={copied ? 'Signing secret copied to clipboard' : 'Copy signing secret to clipboard'}
        >
          {copied ? '✓ Copied!' : 'Copy Secret'}
        </button>
      </div>

      <div className="secret-actions">
        <button
          type="button"
          className="button button-primary"
          onClick={onDismiss}
        >
          I have saved my secret (Dismiss)
        </button>
      </div>
    </aside>
  )
}
