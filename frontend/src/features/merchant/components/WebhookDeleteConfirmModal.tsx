import { useEffect } from 'react'
import type { WebhookSubscription } from '../api/webhookContracts'

interface WebhookDeleteConfirmModalProps {
  subscription: WebhookSubscription
  onConfirm: () => Promise<void>
  onCancel: () => void
  isDeleting: boolean
}

export function WebhookDeleteConfirmModal({
  subscription,
  onConfirm,
  onCancel,
  isDeleting,
}: WebhookDeleteConfirmModalProps) {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && !isDeleting) {
        onCancel()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isDeleting, onCancel])

  return (
    <div className="modal-backdrop" role="presentation" onClick={(e) => { if (e.target === e.currentTarget && !isDeleting) onCancel() }}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-modal-title"
        aria-describedby="delete-modal-desc"
      >
        <div className="modal-header">
          <h3 id="delete-modal-title" className="section-heading">Deactivate Webhook Endpoint</h3>
          <button
            type="button"
            className="modal-close-btn"
            onClick={onCancel}
            disabled={isDeleting}
            aria-label="Close dialog"
          >
            ×
          </button>
        </div>

        <p id="delete-modal-desc" className="muted">
          Are you sure you want to deactivate and remove this webhook subscription? SwiftDrop will immediately cease dispatching event callbacks to:
        </p>

        <div className="delete-target-preview">
          <code>{subscription.httpsUrl}</code>
          <span className="field-hint">ID: {subscription.subscriptionId}</span>
        </div>

        <p className="delete-warning-note">
          <strong>Note:</strong> Delivery attempts currently in flight may finish, but no further domain events will be queued for this endpoint.
        </p>

        <div className="modal-actions">
          <button
            type="button"
            className="button button-secondary button-danger"
            onClick={() => { void onConfirm() }}
            disabled={isDeleting}
          >
            {isDeleting ? 'Deactivating...' : 'Confirm Deactivation'}
          </button>
          <button
            type="button"
            className="button button-secondary"
            onClick={onCancel}
            disabled={isDeleting}
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}
