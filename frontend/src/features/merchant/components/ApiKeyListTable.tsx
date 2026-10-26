import type { ApiKeySummaryItem } from '../api/credentialsContracts'
import { FeedbackState } from '../../../components/ui/FeedbackState'

interface ApiKeyListTableProps {
  keys: ApiKeySummaryItem[]
  isLoading: boolean
  isError: boolean
  errorMessage?: string | null
  onRetry?: () => void
  onOpenGenerateModal: () => void
  onInitiateRevoke: (keyItem: ApiKeySummaryItem) => void
  canGenerate: boolean
}

export function ApiKeyListTable({
  keys,
  isLoading,
  isError,
  errorMessage,
  onRetry,
  onOpenGenerateModal,
  onInitiateRevoke,
  canGenerate,
}: ApiKeyListTableProps) {
  if (isLoading) {
    return (
      <FeedbackState title="Loading API keys" tone="loading">
        Retrieving machine credential metadata from Accounts Service...
      </FeedbackState>
    )
  }

  if (isError) {
    return (
      <FeedbackState
        title="Credential service unavailable"
        tone="error"
        action={
          onRetry ? (
            <button type="button" className="button button-secondary" onClick={onRetry}>
              Retry connection
            </button>
          ) : null
        }
      >
        {errorMessage || 'Unable to retrieve API keys. Please check connectivity and try again.'}
      </FeedbackState>
    )
  }

  const now = new Date()
  const activeKeysCount = keys.filter(
    (k) => !k.isRevoked && new Date(k.expiresAt).getTime() > now.getTime()
  ).length
  const limitReached = activeKeysCount >= 3

  return (
    <section className="card credentials-card" aria-labelledby="credentials-heading">
      <div className="section-heading credentials-header">
        <div>
          <p className="eyebrow">Machine credentials</p>
          <h2 id="credentials-heading">API Keys ({activeKeysCount}/3 active)</h2>
        </div>
        <button
          type="button"
          className="button button-primary"
          onClick={onOpenGenerateModal}
          disabled={!canGenerate || limitReached}
          title={
            !canGenerate
              ? 'Merchant approval required'
              : limitReached
              ? 'Maximum active API keys limit reached (3/3)'
              : 'Generate new machine API key'
          }
        >
          + Generate API Key
        </button>
      </div>

      <p className="muted">
        Manage machine API keys for external automated order dispatching. Keys authenticate via the <code>X-API-Key</code> request header.
      </p>

      {limitReached && (
        <div className="form-alert form-alert-warning" role="status">
          <strong>Maximum active API key limit reached (3/3).</strong>
          <p>Revoke an existing key to issue a replacement without exceeding tenant capacity.</p>
        </div>
      )}

      {keys.length === 0 ? (
        <div className="empty-state-box">
          <p className="empty-title">No API keys generated yet</p>
          <p className="muted">
            You do not have any machine API keys configured. Generate an API key to connect external ERP systems, store management software, or warehouse fulfillment tools.
          </p>
          {canGenerate && (
            <button
              type="button"
              className="button button-secondary"
              onClick={onOpenGenerateModal}
            >
              Generate First API Key
            </button>
          )}
        </div>
      ) : (
        <div className="table-responsive-wrapper">
          <table className="credentials-table" aria-label="Merchant API keys list">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Key Prefix</th>
                <th scope="col">Scopes</th>
                <th scope="col">Created</th>
                <th scope="col">Expires</th>
                <th scope="col">Status</th>
                <th scope="col">Action</th>
              </tr>
            </thead>
            <tbody>
              {keys.map((item) => {
                const createdDate = new Date(item.createdAt)
                const expiresDate = new Date(item.expiresAt)
                const isExpired = expiresDate.getTime() <= now.getTime()
                const isInactive = item.isRevoked || isExpired

                let statusBadge = <span className="badge badge-approved">Active</span>
                if (item.isRevoked) {
                  statusBadge = <span className="badge badge-rejected">Revoked</span>
                } else if (isExpired) {
                  statusBadge = <span className="badge badge-suspended">Expired</span>
                }

                return (
                  <tr key={item.keyId} className={isInactive ? 'row-inactive' : undefined}>
                    <td className="key-name-cell">
                      <strong>{item.name}</strong>
                    </td>
                    <td>
                      <code className="key-prefix-badge">{item.keyPrefix}...</code>
                    </td>
                    <td>
                      <div className="scopes-badge-list">
                        {item.scopes.map((s) => (
                          <span key={s} className="scope-tag">
                            {s}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="date-cell">
                      {Number.isNaN(createdDate.getTime())
                        ? item.createdAt
                        : createdDate.toLocaleDateString('en-GB', { dateStyle: 'medium' })}
                    </td>
                    <td className="date-cell">
                      {Number.isNaN(expiresDate.getTime())
                        ? item.expiresAt
                        : expiresDate.toLocaleDateString('en-GB', { dateStyle: 'medium' })}
                    </td>
                    <td>{statusBadge}</td>
                    <td>
                      {!item.isRevoked && !isExpired ? (
                        <button
                          type="button"
                          className="button button-danger-ghost button-small"
                          onClick={() => onInitiateRevoke(item)}
                          aria-label={`Revoke API key ${item.name}`}
                        >
                          Revoke
                        </button>
                      ) : (
                        <span className="muted-text">—</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
