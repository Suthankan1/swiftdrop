import { Link } from 'react-router'

interface MerchantMachineApiBannerProps {
  merchantId?: string
  businessName?: string
}

export function MerchantMachineApiBanner({ merchantId, businessName }: MerchantMachineApiBannerProps) {
  return (
    <aside className="merchant-auth-scope-card card" aria-labelledby="scope-banner-heading">
      <div className="scope-banner-header">
        <span className="scope-badge scope-badge-browser">Browser Session JWT</span>
        <span className="scope-badge scope-badge-machine">Scoped Machine API (API Keys)</span>
      </div>
      <div className="scope-banner-content">
        <h2 id="scope-banner-heading" className="scope-banner-title">
          Authenticated Portal View · {businessName || 'Merchant Operations'}
        </h2>
        <p className="scope-banner-text muted">
          You are viewing orders authorized through your interactive browser Keycloak session
          {merchantId ? ` (Tenant ID: ${merchantId})` : ''}.
          Automated server-to-server order dispatches and webhook listeners require scoped machine API credentials.
        </p>
      </div>
      <div className="scope-banner-actions">
        <Link to="/merchant/credentials" className="button button-secondary button-small">
          Manage API Keys ↗
        </Link>
        <Link to="/merchant/webhooks" className="button button-secondary button-small">
          Webhook Endpoints ↗
        </Link>
      </div>
    </aside>
  )
}
