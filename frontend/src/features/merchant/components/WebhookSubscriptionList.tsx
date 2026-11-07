import type { WebhookSubscription } from '../api/webhookContracts'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { FeedbackState } from '../../../components/ui/FeedbackState'

interface WebhookSubscriptionListProps {
  subscriptions: WebhookSubscription[]
  isLoading: boolean
  isError: boolean
  errorMessage?: string | null
  onRetry?: () => void
  onInspectDeliveries: (subscription: WebhookSubscription) => void
  onDeleteSubscription: (subscription: WebhookSubscription) => void
  onOpenRegisterForm: () => void
}

export function WebhookSubscriptionList({
  subscriptions,
  isLoading,
  isError,
  errorMessage,
  onRetry,
  onInspectDeliveries,
  onDeleteSubscription,
  onOpenRegisterForm,
}: WebhookSubscriptionListProps) {
  if (isLoading) {
    return (
      <FeedbackState title="Loading webhook subscriptions" tone="loading">
        Connecting to Communications Service to retrieve your registered endpoints...
      </FeedbackState>
    )
  }

  if (isError) {
    return (
      <FeedbackState
        title="Webhook service unavailable"
        tone="error"
        action={
          onRetry ? (
            <Button variant="secondary" onClick={onRetry}>
              Retry connection
            </Button>
          ) : null
        }
      >
        {errorMessage || 'Unable to retrieve webhook subscriptions from Communications Service. Please check connectivity and try again.'}
      </FeedbackState>
    )
  }

  if (subscriptions.length === 0) {
    return (
      <section className="card empty-webhooks-card" aria-labelledby="empty-webhooks-heading">
        <div className="section-heading">
          <p className="eyebrow">Zero endpoints registered</p>
          <h2 id="empty-webhooks-heading">No Webhook Subscriptions Active</h2>
        </div>
        <p className="muted">
          Your merchant account does not currently have any webhook callback destinations registered. Register an HTTPS endpoint to receive automated real-time callbacks when parcels are collected, updated in transit, or delivered.
        </p>
        <div className="empty-actions">
          <Button
            type="button"
            variant="primary"
            onClick={onOpenRegisterForm}
          >
            Register Webhook Destination
          </Button>
        </div>
      </section>
    )
  }

  return (
    <section className="webhook-subscriptions-container" aria-labelledby="webhook-list-heading">
      <div className="webhook-list-header">
        <div>
          <p className="eyebrow">Registered endpoints</p>
          <h2 id="webhook-list-heading" className="section-heading">
            Configured Webhooks ({subscriptions.length})
          </h2>
        </div>
        <Button
          type="button"
          variant="primary"
          onClick={onOpenRegisterForm}
        >
          + Register Endpoint
        </Button>
      </div>

      <div className="webhook-list">
        {subscriptions.map((sub) => {
          const createdDate = new Date(sub.createdAt)
          const formattedDate = Number.isNaN(createdDate.getTime())
            ? sub.createdAt
            : createdDate.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })

          return (
            <article key={sub.subscriptionId} className="card webhook-card" aria-label={`Subscription ${sub.subscriptionId}`}>
              <div className="webhook-card-main">
                <div className="webhook-url-row" style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                  <Badge variant="neutral" size="sm">HTTPS</Badge>
                  <code className="webhook-destination-url">{sub.httpsUrl}</code>
                  <Badge variant={sub.active ? 'success' : 'neutral'} size="sm" pulse={sub.active}>
                    {sub.active ? 'Active' : 'Deactivated'}
                  </Badge>
                </div>

                <div className="webhook-events-row">
                  <span className="webhook-meta-label">Subscribed Events:</span>
                  <div className="webhook-event-tags">
                    {sub.allowedEventTypes.map((event) => (
                      <code key={event} className="event-badge">
                        {event}
                      </code>
                    ))}
                  </div>
                </div>

                <div className="webhook-metadata-row">
                  <span className="muted">Subscription ID: <code>{sub.subscriptionId}</code></span>
                  <span className="muted">·</span>
                  <span className="muted">Registered: {formattedDate}</span>
                </div>
              </div>

              <div className="webhook-card-actions">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => onInspectDeliveries(sub)}
                >
                  Delivery Logs
                </Button>
                <Button
                  type="button"
                  variant="danger-ghost"
                  size="sm"
                  onClick={() => onDeleteSubscription(sub)}
                >
                  Deactivate
                </Button>
              </div>
            </article>
          )
        })}
      </div>
    </section>
  )
}
