import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import {
  createMerchantDashboardApi,
  MerchantDashboardError,
} from '../api/merchantDashboard'
import { createDeliveryApi } from '../../customer/api/delivery'
import { MerchantNav } from '../components/MerchantNav'
import { MerchantMachineApiBanner } from '../components/MerchantMachineApiBanner'
import { MerchantOrderSummaryCards } from '../components/MerchantOrderSummaryCards'
import { MerchantOrderFilterBar } from '../components/MerchantOrderFilterBar'
import { MerchantOrderTable } from '../components/MerchantOrderTable'
import { CustomerTrackingModal } from '../../customer/components/CustomerTrackingModal'
import { ACTIVE_STATUSES } from '../components/merchantUiUtils'

export interface MerchantOrdersDashboardPageProps {
  apiOverride?: ReturnType<typeof createMerchantDashboardApi>
}

export function MerchantOrdersDashboardPage({ apiOverride }: MerchantOrdersDashboardPageProps) {
  const auth = useAuth()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''
  const isMerchant = auth.snapshot.status === 'authenticated' && auth.snapshot.roles.includes('MERCHANT')

  // Filter and pagination state
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [searchQuery, setSearchQuery] = useState('')
  const [currentPage, setCurrentPage] = useState(0)
  const pageSize = 10

  // Tracking modal inspection state
  const [inspectTrackingNumber, setInspectTrackingNumber] = useState<string | null>(null)
  const [isModalOpen, setIsModalOpen] = useState(false)

  const dashboardApi = useMemo(
    () =>
      apiOverride ||
      createMerchantDashboardApi({
        getAccessToken: auth.getAccessToken,
      }),
    [apiOverride, auth.getAccessToken],
  )

  const deliveryApi = useMemo(
    () =>
      createDeliveryApi({
        getAccessToken: auth.getAccessToken,
      }),
    [auth.getAccessToken],
  )

  // Clear search and filter when subject changes (prevent cross-merchant leakage)
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setStatusFilter('ALL')
    setSearchQuery('')
    setCurrentPage(0)
  }

  const query = useQuery({
    queryKey: ['private', 'merchant-dashboard', subject],
    enabled: isMerchant && !!subject,
    queryFn: async ({ signal }) => {
      return dashboardApi.getDashboard(signal)
    },
    gcTime: 0,
    staleTime: 15_000,
  })

  const recentOrders = query.data?.recentOrders

  // Filter orders according to active criteria
  const filteredOrders = useMemo(() => {
    if (!recentOrders) return []

    return recentOrders.filter((order) => {
      // 1. Status Filter
      if (statusFilter === 'ACTIVE') {
        if (!ACTIVE_STATUSES.includes(order.currentStatus)) return false
      } else if (statusFilter !== 'ALL') {
        if (order.currentStatus !== statusFilter) return false
      }

      // 2. Search Query
      if (searchQuery.trim()) {
        const queryNorm = searchQuery.trim().toLowerCase()
        const trackMatch = order.trackingNumber.toLowerCase().includes(queryNorm)
        const idMatch = order.parcelId.toLowerCase().includes(queryNorm)
        if (!trackMatch && !idMatch) return false
      }

      return true
    })
  }, [recentOrders, statusFilter, searchQuery])

  const handleStatusFilterChange = (newStatus: string) => {
    setStatusFilter(newStatus)
    setCurrentPage(0)
  }

  const handleSearchQueryChange = (queryStr: string) => {
    setSearchQuery(queryStr)
    setCurrentPage(0)
  }

  const handleResetFilters = () => {
    setStatusFilter('ALL')
    setSearchQuery('')
    setCurrentPage(0)
  }

  const handleOpenTrackingModal = (trackingNumber: string) => {
    setInspectTrackingNumber(trackingNumber)
    setIsModalOpen(true)
  }

  return (
    <div className="page-container merchant-page merchant-orders-dashboard-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>Commercial Order Dashboard.<br />Authoritative Dispatches.</h1>
        <p className="muted">
          Review commercial shipments, inspect live milestone states, and track fulfillment across delivery routes.
        </p>
      </header>

      <MerchantNav />

      {/* Distinction between Browser JWT and Scoped Machine API */}
      <MerchantMachineApiBanner
        merchantId={query.data?.merchantId}
        businessName={query.data?.businessName}
      />

      {/* Loading State */}
      {query.isPending && (
        <div className="card loading-history-card" role="status" aria-busy="true">
          <span className="btn-spinner large" aria-hidden="true" />
          <p>Loading merchant orders dashboard...</p>
        </div>
      )}

      {/* Error / Access Denied States */}
      {query.isError && (
        <div className="card state-panel state-panel-error" role="alert">
          <h2>Unable to load merchant dashboard</h2>
          <p>
            {query.error instanceof MerchantDashboardError && query.error.kind === 'forbidden'
              ? 'Access restricted. Your merchant account is pending review, suspended, or unapproved.'
              : query.error instanceof MerchantDashboardError && query.error.kind === 'auth-required'
                ? 'Your session has expired. Please sign in again.'
                : 'A temporary service or network issue occurred while loading orders.'}
          </p>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => void query.refetch()}
          >
            Retry Dashboard
          </button>
        </div>
      )}

      {/* Successful Dashboard Data View */}
      {query.data && (
        <div className="merchant-dashboard-content-section">
          {/* State Count Metric Cards */}
          <MerchantOrderSummaryCards
            totalOrdersCount={query.data.totalOrdersCount}
            activeOrdersCount={query.data.activeOrdersCount}
            deliveredOrdersCount={query.data.deliveredOrdersCount}
            failedOrdersCount={query.data.failedOrdersCount}
            activeFilter={statusFilter}
            onSelectFilter={handleStatusFilterChange}
          />

          {/* Filter Bar & Search */}
          <MerchantOrderFilterBar
            statusFilter={statusFilter}
            onStatusFilterChange={handleStatusFilterChange}
            searchQuery={searchQuery}
            onSearchQueryChange={handleSearchQueryChange}
            totalCount={query.data.recentOrders.length}
            filteredCount={filteredOrders.length}
            onRefresh={() => void query.refetch()}
            isRefreshing={query.isFetching}
          />

          {/* Orders Table */}
          <MerchantOrderTable
            orders={filteredOrders}
            currentPage={currentPage}
            pageSize={pageSize}
            totalItems={filteredOrders.length}
            onPageChange={(newPage) => setCurrentPage(newPage)}
            onSelectTracking={handleOpenTrackingModal}
            isLoading={query.isFetching}
            hasFiltersApplied={statusFilter !== 'ALL' || searchQuery.trim().length > 0}
            onResetFilters={handleResetFilters}
          />
        </div>
      )}

      {/* Safe Tracking Modal Dialog */}
      <CustomerTrackingModal
        parcelId={inspectTrackingNumber}
        trackingNumber={inspectTrackingNumber ?? undefined}
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        deliveryApi={deliveryApi}
        onDownloadReceipt={async () => {}}
        isDownloadingReceipt={false}
      />
    </div>
  )
}
