import type { ReactNode } from 'react'
import { Button } from '../../../components/ui/Button'
import { getStatusPresentation } from '../../customer/components/customerUiUtils'
import { CANONICAL_PARCEL_STATUSES } from './merchantUiUtils'

interface MerchantOrderFilterBarProps {
  statusFilter: string
  onStatusFilterChange: (status: string) => void
  searchQuery: string
  onSearchQueryChange: (query: string) => void
  exportAction: ReactNode
  onRefresh?: () => void
  isRefreshing?: boolean
}

export function MerchantOrderFilterBar({
  statusFilter,
  onStatusFilterChange,
  searchQuery,
  onSearchQueryChange,
  exportAction,
  onRefresh,
  isRefreshing,
}: MerchantOrderFilterBarProps) {
  return (
    <div className="orders-toolbar" role="search" aria-label="Order filters and search">
      <div className="orders-toolbar-row">
        <div className="filter-search-wrap">
          <label htmlFor="order-search-input" className="visually-hidden">
            Search orders by tracking number or parcel ID
          </label>
          <input
            id="order-search-input"
            type="search"
            className="form-control search-input"
            placeholder="Tracking number or parcel ID"
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

        {onRefresh && (
          <Button
            variant="secondary"
            size="sm"
            className="refresh-orders-btn"
            onClick={onRefresh}
            disabled={isRefreshing}
            aria-label="Refresh orders list"
          >
            {isRefreshing ? 'Refreshing...' : 'Refresh'}
          </Button>
        )}
        {exportAction}
      </div>
      <details className="orders-more-filters">
        <summary>More filters{statusFilter !== 'ALL' && statusFilter !== 'ACTIVE' &&
          statusFilter !== 'DELIVERED' && statusFilter !== 'FAILED' ? ' (1 active)' : ''}</summary>
        <label htmlFor="detailed-status-select">Parcel status</label>
        <select
          id="detailed-status-select"
          className="form-control"
          value={statusFilter}
          onChange={event => onStatusFilterChange(event.target.value)}
        >
          <option value="ALL">All statuses</option>
          <option value="ACTIVE">In progress</option>
          {CANONICAL_PARCEL_STATUSES.map(status => (
            <option key={status} value={status}>{getStatusPresentation(status).label}</option>
          ))}
        </select>
      </details>
    </div>
  )
}
