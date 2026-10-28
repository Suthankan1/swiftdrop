interface MerchantOrderSummaryCardsProps {
  totalOrdersCount: number
  activeOrdersCount: number
  deliveredOrdersCount: number
  failedOrdersCount: number
  activeFilter?: string
  onSelectFilter?: (filter: string) => void
}

export function MerchantOrderSummaryCards({
  totalOrdersCount,
  activeOrdersCount,
  deliveredOrdersCount,
  failedOrdersCount,
  activeFilter,
  onSelectFilter,
}: MerchantOrderSummaryCardsProps) {
  return (
    <div className="courier-summary-grid merchant-metrics-grid" role="region" aria-label="Order state metrics">
      <button
        type="button"
        className={`card metric-card filter-metric-card${activeFilter === 'ALL' ? ' is-active' : ''}`}
        onClick={() => onSelectFilter?.('ALL')}
        aria-pressed={activeFilter === 'ALL'}
      >
        <span className="eyebrow">Total Bookings</span>
        <span className="metric-value monospace">{totalOrdersCount}</span>
        <span className="muted metric-caption">All recorded merchant shipments</span>
      </button>

      <button
        type="button"
        className={`card metric-card filter-metric-card${activeFilter === 'ACTIVE' ? ' is-active' : ''}`}
        onClick={() => onSelectFilter?.('ACTIVE')}
        aria-pressed={activeFilter === 'ACTIVE'}
      >
        <span className="eyebrow">Active Dispatches</span>
        <span className="metric-value monospace">{activeOrdersCount}</span>
        <span className="muted metric-caption">Parcels pending, collected, or out for delivery</span>
      </button>

      <button
        type="button"
        className={`card metric-card filter-metric-card${activeFilter === 'DELIVERED' ? ' is-active' : ''}`}
        onClick={() => onSelectFilter?.('DELIVERED')}
        aria-pressed={activeFilter === 'DELIVERED'}
      >
        <span className="eyebrow">Delivered</span>
        <span className="metric-value monospace">{deliveredOrdersCount}</span>
        <span className="muted metric-caption">Authoritatively fulfilled parcels</span>
      </button>

      <button
        type="button"
        className={`card metric-card filter-metric-card${activeFilter === 'FAILED' ? ' is-active' : ''}`}
        onClick={() => onSelectFilter?.('FAILED')}
        aria-pressed={activeFilter === 'FAILED'}
      >
        <span className="eyebrow">Exceptions &amp; Failed</span>
        <span className="metric-value monospace">{failedOrdersCount}</span>
        <span className="muted metric-caption">Parcels requiring merchant attention</span>
      </button>
    </div>
  )
}
