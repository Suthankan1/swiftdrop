import type { ApiKeySummaryItem } from '../api/credentialsContracts'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'

interface ApiKeyRevokeConfirmModalProps {
  keyItem: ApiKeySummaryItem | null
  isRevoking: boolean
  onClose: () => void
  onConfirm: (keyId: string) => Promise<void>
}

export function ApiKeyRevokeConfirmModal({
  keyItem,
  isRevoking,
  onClose,
  onConfirm,
}: ApiKeyRevokeConfirmModalProps) {
  if (!keyItem) return null

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="revoke-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <Badge variant="danger">Revoke Credential</Badge>
            <h2 id="revoke-modal-title" className="modal-title">Revoke API Key?</h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            aria-label="Close dialog"
            onClick={onClose}
            disabled={isRevoking}
          >
            ×
          </button>
        </div>

        <p>
          Are you sure you want to revoke <strong>"{keyItem.name}"</strong>?
        </p>

        <div className="form-alert form-alert-warning" role="alert">
          <p>
            Any external warehouse script, integration service, or automated client using key{' '}
            <code>{keyItem.keyPrefix}...</code> will immediately and permanently fail to authenticate.
          </p>
          <p className="sub-warning">This action cannot be undone.</p>
        </div>

        <div className="modal-actions">
          <Button
            type="button"
            variant="secondary"
            onClick={onClose}
            disabled={isRevoking}
          >
            Cancel
          </Button>
          <Button
            type="button"
            variant="danger"
            onClick={() => onConfirm(keyItem.keyId)}
            disabled={isRevoking}
            isLoading={isRevoking}
            loadingText="Revoking..."
          >
            Revoke Key Permanently
          </Button>
        </div>
      </div>
    </div>
  )
}
