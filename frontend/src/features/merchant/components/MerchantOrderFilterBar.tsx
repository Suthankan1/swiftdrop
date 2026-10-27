import { CANONICAL_PARCEL_STATUSES } from './merchantUiUtils'

interface MerchantOrderFilterBarProps {
  statusFilter: string
  onStatusFilterChange: (status: string) => void
  searchQuery: string
  onSearchQueryChange: (query: string) => void
  totalCount: number
  filteredCount: number
  onRefresh?: () => void
  isRefreshing?: boolean
}

export function MerchantOrderFilterBar({
  statusFilter,
  onStatusFilterChange,
  searchQuery,
  onSearchQueryChange,
  totalCount,
  filteredCount,
  onRefresh,
  isRefreshing,
}: MerchantOrderFilterBarProps) {
  return (
    <div className="merchant-order-filter-bar card" role="search" aria-label="Order filters and search">
      <div className="filter-controls-row">
        {/* Quick Filter Segmented Buttons */}
        <div className="filter-pills-group" role="radiogroup" aria-label="Status filter">
          <button
            type="button"
            className={`filter-pill-btn${statusFilter === 'ALL' ? ' is-active' : ''}`}
            onClick={() => onStatusFilterChange('ALL')}
            aria-checked={statusFilter === 'ALL'}
            role="radio"
          >
            All Orders
          </button>
          <button
            type="button"
            className={`filter-pill-btn${statusFilter === 'ACTIVE' ? ' is-active' : ''}`}
            onClick={() => onStatusFilterChange('ACTIVE')}
            aria-checked={statusFilter === 'ACTIVE'}
            role="radio"
          >
            Active
          </button>
          <button
            type="button"
            className={`filter-pill-btn${statusFilter === 'DELIVERED' ? ' is-active' : ''}`}
            onClick={() => onStatusFilterChange('DELIVERED')}
            aria-checked={statusFilter === 'DELIVERED'}
            role="radio"
          >
            Delivered
          </button>
          <button
            type="button"
            className={`filter-pill-btn${statusFilter === 'FAILED' ? ' is-active' : ''}`}
            onClick={() => onStatusFilterChange('FAILED')}
            aria-checked={statusFilter === 'FAILED'}
            role="radio"
          >
            Failed
          </button>
        </div>

        {/* Detailed Canonical Status Dropdown */}
        <div className="filter-dropdown-wrap">
          <label htmlFor="detailed-status-select" className="visually-hidden">
            Specific status filter
          </label>
          <select
            id="detailed-status-select"
            className="form-control status-select"
            value={statusFilter}
            onChange={(e) => onStatusFilterChange(e.target.value)}
            aria-label="Filter by specific lifecycle status"
          >
            <option value="ALL">All Statuses</option>
            <option value="ACTIVE">All Active Dispatches</option>
            {CANONICAL_PARCEL_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </div>

        {/* Search Input */}
        <div className="filter-search-wrap">
          <label htmlFor="order-search-input" className="visually-hidden">
            Search orders by tracking number or parcel ID
          </label>
          <input
            id="order-search-input"
            type="search"
            className="form-control search-input"
            placeholder="Search tracking # or parcel ID..."
            value={searchQuery}
            onChange={(e) => onSearchQueryChange(e.target.value)}
            aria-label="Search orders by tracking number or parcel ID"
          />
          {searchQuery && (
            <button
              type="button"
              className="search-clear-btn"
              onClick={() => onSearchQueryChange('')}
              aria-label="Clear search query"
            >
              ✕
            </button>
          )}
        </div>

        {/* Refresh Action */}
        {onRefresh && (
          <button
            type="button"
            className="button button-secondary button-small refresh-orders-btn"
            onClick={onRefresh}
            disabled={isRefreshing}
            aria-label="Refresh orders list"
          >
            {isRefreshing ? 'Refreshing...' : '↻ Refresh'}
          </button>
        )}
      </div>

      <div className="filter-meta-row">
        <span className="muted filter-count-text">
          Showing <strong>{filteredCount}</strong> of <strong>{totalCount}</strong> orders
          {statusFilter !== 'ALL' && ` (Filtered: ${statusFilter})`}
          {searchQuery && ` (Search: "${searchQuery}")`}
        </span>
      </div>
    </div>
  )
}
