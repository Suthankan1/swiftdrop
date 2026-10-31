import { useEffect, useState } from 'react'
import type {
  WebhookDeliveryItem,
  WebhookDeliveryLogListResponse,
  WebhookDeliveryStatus,
  WebhookSubscription,
} from '../api/webhookContracts'
import { Button } from '../../../components/ui/Button'

interface WebhookDeliveryLogsModalProps {
  subscription: WebhookSubscription
  onClose: () => void
  fetchDeliveries: (subscriptionId: string) => Promise<WebhookDeliveryLogListResponse>
}

export function WebhookDeliveryLogsModal({
  subscription,
  onClose,
  fetchDeliveries,
}: WebhookDeliveryLogsModalProps) {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [deliveries, setDeliveries] = useState<WebhookDeliveryItem[]>([])

  useEffect(() => {
    let active = true
    const controller = new AbortController()

    async function loadLogs() {
      setLoading(true)
      setError(null)
      try {
        const response = await fetchDeliveries(subscription.subscriptionId)
        if (active) {
          setDeliveries(response.deliveries)
        }
      } catch (err) {
        if (active) {
          setError(err instanceof Error ? err.message : 'Failed to retrieve delivery execution logs.')
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    void loadLogs()

    return () => {
      active = false
      controller.abort()
    }
  }, [subscription.subscriptionId, fetchDeliveries])

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  function getStatusPillClass(status: WebhookDeliveryStatus) {
    switch (status) {
      case 'SUCCESS':
        return 'status-pill status-pill-success'
      case 'FAILED':
        return 'status-pill status-pill-failed'
      case 'EXHAUSTED':
        return 'status-pill status-pill-exhausted'
      case 'PENDING':
        return 'status-pill status-pill-pending'
      case 'PROCESSING':
        return 'status-pill status-pill-processing'
      default:
        return 'status-pill'
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div
        className="modal-panel modal-panel-wide"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delivery-logs-title"
      >
        <div className="modal-header">
          <div>
            <span className="badge">Audit Trail</span>
            <h3 id="delivery-logs-title" className="section-heading">Webhook Delivery Logs</h3>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            onClick={onClose}
            aria-label="Close logs dialog"
          >
            ×
          </button>
        </div>

        <p className="muted logs-subtitle">
          Execution outcome and retry history for destination: <code>{subscription.httpsUrl}</code>
        </p>

        {loading && (
          <div className="logs-loading-state" role="status" aria-live="polite">
            <span className="loading-indicator" aria-hidden="true" />
            <p className="muted">Retrieving delivery logs from Communications Service...</p>
          </div>
        )}

        {error && (
          <div className="form-error-banner" role="alert">
            <p><strong>Failed to load delivery logs:</strong> {error}</p>
            <Button
              variant="secondary"
              onClick={() => {
                setLoading(true)
                setError(null)
                fetchDeliveries(subscription.subscriptionId)
                  .then((res) => setDeliveries(res.deliveries))
                  .catch((err) => setError(err instanceof Error ? err.message : 'Retry failed.'))
                  .finally(() => setLoading(false))
              }}
            >
              Retry Loading
            </Button>
          </div>
        )}

        {!loading && !error && deliveries.length === 0 && (
          <div className="logs-empty-state">
            <h4>No delivery records found</h4>
            <p className="muted">
              Delivery records are generated when parcel status transition events (e.g., PICKED_UP, DELIVERED) trigger an asynchronous dispatch attempt to this endpoint.
            </p>
          </div>
        )}

        {!loading && !error && deliveries.length > 0 && (
          <div className="delivery-table-wrapper">
            <table className="delivery-log-table">
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">HTTP Code</th>
                  <th scope="col">Latency</th>
                  <th scope="col">Last Attempt (UTC)</th>
                  <th scope="col">Error / Summary</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.map((item) => {
                  const attemptDate = new Date(item.lastAttemptAt)
                  return (
                    <tr key={item.deliveryId}>
                      <td>
                        <span className={getStatusPillClass(item.status)}>{item.status}</span>
                      </td>
                      <td>{item.attemptCount} of 5</td>
                      <td>
                        {item.lastHttpStatusCode ? (
                          <code>{item.lastHttpStatusCode}</code>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td>
                        {item.lastResponseTimeMs != null ? (
                          `${item.lastResponseTimeMs} ms`
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td>
                        {Number.isNaN(attemptDate.getTime())
                          ? item.lastAttemptAt
                          : attemptDate.toLocaleString('en-GB', { timeZone: 'UTC' })}
                      </td>
                      <td>
                        {item.errorSummary ? (
                          <span className="log-error-text">{item.errorSummary}</span>
                        ) : (
                          <span className="muted">None</span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        <div className="logs-security-footer">
          <p className="muted">
            <em>Privacy Note:</em> Third-party response bodies are intentionally omitted per architectural security decision DEC-COM-04 to prevent sensitive customer data leakage and payload injection.
          </p>
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </div>
  )
}
