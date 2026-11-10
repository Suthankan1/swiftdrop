import { Link } from 'react-router'

interface MerchantMachineApiBannerProps {
  merchantId?: string
  businessName?: string
}

/** Points merchants at the tools for connecting their own systems (API keys and webhooks). */
export function MerchantMachineApiBanner({ businessName }: MerchantMachineApiBannerProps) {
  return (
    <aside className="merchant-auth-scope-card card" aria-labelledby="scope-banner-heading">
      <div className="scope-banner-content">
        <h2 id="scope-banner-heading" className="scope-banner-title">
          {businessName || 'Your store'}
        </h2>
        <p className="scope-banner-text muted">
          These are the orders for your store. To send orders from your own system or get status updates
          automatically, create an API key or add a webhook.
        </p>
      </div>
      <div className="scope-banner-actions">
        <Link to="/merchant/credentials" className="button button-secondary button-small">
          Manage API keys
        </Link>
        <Link to="/merchant/webhooks" className="button button-secondary button-small">
          Manage webhooks
        </Link>
      </div>
    </aside>
  )
}
