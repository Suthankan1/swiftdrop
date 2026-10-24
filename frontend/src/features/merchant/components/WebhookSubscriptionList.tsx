import type { WebhookSubscription } from '../api/webhookContracts'
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
            <button type="button" className="button button-secondary" onClick={onRetry}>
              Retry connection
            </button>
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
          <button
            type="button"
            className="button button-primary"
            onClick={onOpenRegisterForm}
          >
            Register Webhook Destination
          </button>
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
        <button
          type="button"
          className="button button-primary"
          onClick={onOpenRegisterForm}
        >
          + Register Endpoint
        </button>
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
                <div className="webhook-url-row">
                  <span className="badge badge-https">HTTPS</span>
                  <code className="webhook-destination-url">{sub.httpsUrl}</code>
                  <span className={`badge ${sub.active ? 'badge-approved' : 'badge-suspended'}`}>
                    {sub.active ? 'Active' : 'Deactivated'}
                  </span>
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
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => onInspectDeliveries(sub)}
                >
                  Delivery Logs
                </button>
                <button
                  type="button"
                  className="button button-secondary button-danger"
                  onClick={() => onDeleteSubscription(sub)}
                >
                  Deactivate
                </button>
              </div>
            </article>
          )
        })}
      </div>
    </section>
  )
}
