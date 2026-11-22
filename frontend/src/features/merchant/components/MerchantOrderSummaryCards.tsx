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
  const filters = [
    { value: 'ALL', label: 'All', count: totalOrdersCount },
    { value: 'ACTIVE', label: 'In progress', count: activeOrdersCount },
    { value: 'DELIVERED', label: 'Delivered', count: deliveredOrdersCount },
    { value: 'FAILED', label: 'Failed', count: failedOrdersCount },
  ]

  return (
    <div className="orders-status-chips" role="group" aria-label="Filter orders by status">
      {filters.map(({ value, label, count }) => (
        <button
          key={value}
          type="button"
          aria-pressed={activeFilter === value}
          onClick={() => onSelectFilter?.(value)}
        >
          {label} <span>{count}</span>
        </button>
      ))}
    </div>
  )
}
