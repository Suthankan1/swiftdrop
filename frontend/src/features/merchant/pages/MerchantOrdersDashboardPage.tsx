import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { Button } from '../../../components/ui/Button'
import {
  createMerchantDashboardApi,
  MerchantDashboardError,
} from '../api/merchantDashboard'
import { createDeliveryApi, triggerBlobDownload } from '../../customer/api/delivery'
import type { MerchantOrderSummaryItem } from '../api/merchantDashboardContracts'
import { MerchantMachineApiBanner } from '../components/MerchantMachineApiBanner'
import { MerchantOrderSummaryCards } from '../components/MerchantOrderSummaryCards'
import { MerchantOrderFilterBar } from '../components/MerchantOrderFilterBar'
import { MerchantOrderTable } from '../components/MerchantOrderTable'
import { BulkOrderUploadModal } from '../components/BulkOrderUploadModal'
import { CustomerTrackingModal } from '../../customer/components/CustomerTrackingModal'
import { ACTIVE_STATUSES, formatDateTime, merchantOrdersCsv } from '../components/merchantUiUtils'
import { merchantReadRetry } from '../api/merchantReadRetry'

const MAX_ORDER_PAGES = 50

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
  const [inspect, setInspect] = useState<{ parcelId: string; trackingNumber: string } | null>(null)
  const [receiptState, setReceiptState] = useState<{ parcelId: string | null; error: string | null }>({ parcelId: null, error: null })
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [showBulkUploadModal, setShowBulkUploadModal] = useState(false)

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
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
  })

  // Query owns cancellation and deduplication, including subject changes and unmount.
  const allOrdersQuery = useQuery({
    queryKey: ['private', 'merchant-all-orders', subject],
    enabled: isMerchant && !!subject && !!query.data,
    queryFn: async ({ signal }) => {
      const collected: MerchantOrderSummaryItem[] = []
      let page = 0
      let hasNext = true
      while (hasNext && page < MAX_ORDER_PAGES) {
        const result = await deliveryApi.getCustomerDeliveries({ page, size: 100 }, signal)
        signal.throwIfAborted()
        for (const item of result.items) {
          collected.push({
              parcelId: item.parcelId, trackingNumber: item.trackingNumber,
              currentStatus: item.currentStatus, weightKg: item.weightKg ?? Number.NaN,
              feeMinorUnits: item.amountMinorUnits, currency: item.currency,
              createdAt: item.createdAt, lastStatusUpdate: item.updatedAt ?? '',
          })
        }
        hasNext = result.hasNext
        page++
      }
      return { orders: collected, truncated: hasNext }
    },
    retry: merchantReadRetry,
    retryDelay: attempt => 250 * 2 ** attempt,
    gcTime: 0,
    staleTime: 15_000,
  })
  const allOrders = allOrdersQuery.data?.orders ?? null
  const allOrdersState = {
    loading: allOrdersQuery.isFetching,
    error: allOrdersQuery.isError ? 'All orders could not be loaded. Previously loaded orders remain visible. Retry loading all orders.' : null,
    truncated: allOrdersQuery.data?.truncated ?? false,
  }

  const recentOrders = allOrders ?? query.data?.recentOrders

  const summaryCounts = useMemo(() => {
    if (allOrders && !allOrdersState.truncated) {
      const active = allOrders.filter(o => ACTIVE_STATUSES.includes(o.currentStatus)).length
      const delivered = allOrders.filter(o => o.currentStatus === 'DELIVERED').length
      const failed = allOrders.filter(o => o.currentStatus === 'FAILED').length
      return {
        total: allOrders.length,
        active: active,
        delivered: delivered,
        failed: failed,
      }
    }
    return {
      total: query.data?.totalOrdersCount ?? 0,
      active: query.data?.activeOrdersCount ?? 0,
      delivered: query.data?.deliveredOrdersCount ?? 0,
      failed: query.data?.failedOrdersCount ?? 0,
    }
  }, [allOrders, allOrdersState.truncated, query.data])

  function loadAllOrders() {
    if (!allOrdersQuery.isFetching) void allOrdersQuery.refetch({ cancelRefetch: false })
  }

  function refreshOrders() {
    if (query.isFetching || allOrdersQuery.isFetching) return
    setCurrentPage(0)
    void query.refetch({ cancelRefetch: false })
    void allOrdersQuery.refetch({ cancelRefetch: false })
  }

  async function handleDownloadReceipt(parcelId: string) {
    setReceiptState({ parcelId, error: null })
    try {
      const { blob, filename } = await deliveryApi.downloadReceipt(parcelId)
      triggerBlobDownload(blob, filename)
      setReceiptState({ parcelId: null, error: null })
    } catch {
      setReceiptState({ parcelId: null, error: 'The receipt could not be downloaded. Please try again.' })
    }
  }

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

  const handleOpenTrackingModal = (trackingNumber: string, parcelId: string) => {
    setInspect({ parcelId, trackingNumber })
    setIsModalOpen(true)
  }

  return (
    <div className="page-container merchant-page merchant-orders-dashboard-page">
      <header className="page-heading">
        <p className="eyebrow">Merchant space</p>
        <h1>Your orders.</h1>
        <p className="muted">
          Review commercial shipments, inspect live milestone states, and track fulfillment across delivery routes.
        </p>
      </header>

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
          <Button
            type="button"
            variant="secondary"
            disabled={query.isFetching}
            onClick={() => { if (!query.isFetching) void query.refetch({ cancelRefetch: false }) }}
          >
            Retry Dashboard
          </Button>
        </div>
      )}

      {/* Successful Dashboard Data View */}
      {query.data && (
        <div className="merchant-dashboard-content-section">
          {/* State Count Metric Cards */}
          <MerchantOrderSummaryCards
            totalOrdersCount={summaryCounts.total}
            activeOrdersCount={summaryCounts.active}
            deliveredOrdersCount={summaryCounts.delivered}
            failedOrdersCount={summaryCounts.failed}
            activeFilter={statusFilter}
            onSelectFilter={handleStatusFilterChange}
          />

          {/* Quick Actions Toolbar */}
          <p className="muted" role="status">
            {allOrders
              ? `Showing ${allOrders.length} loaded orders${allOrdersState.truncated ? ' (first 5,000 only)' : ' (complete history)'}. Search applies to these orders; CSV exports the filtered results.`
              : `Showing the ${query.data.recentOrders.length} most recent orders. Search applies to these orders; CSV exports the filtered results.`}
          </p>
          {allOrdersState.error && <p role="alert">{allOrdersState.error}</p>}
          {receiptState.error && <p role="alert">{receiptState.error}</p>}
          <div className="merchant-order-actions">
            {!allOrders && !allOrdersState.loading && (
              <Button type="button" variant="secondary" size="sm" isLoading={allOrdersState.loading}
                disabled={allOrdersState.loading} onClick={() => void loadAllOrders()}>
                Load all orders
              </Button>
            )}
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={filteredOrders.length === 0}
              onClick={() => {
                const blob = new Blob([merchantOrdersCsv(filteredOrders)], { type: 'text/csv;charset=utf-8' })
                const url = URL.createObjectURL(blob)
                const a = document.createElement('a')
                a.href = url
                a.download = `swiftdrop-orders-${new Date().toISOString().slice(0, 10)}.csv`
                a.click()
                URL.revokeObjectURL(url)
              }}
            >
              📊 Export Orders to CSV
            </Button>
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={() => setShowBulkUploadModal(true)}
            >
              📁 Bulk CSV Upload
            </Button>
          </div>

          {/* Filter Bar & Search */}
          <MerchantOrderFilterBar
            statusFilter={statusFilter}
            onStatusFilterChange={handleStatusFilterChange}
            searchQuery={searchQuery}
            onSearchQueryChange={handleSearchQueryChange}
            totalCount={recentOrders?.length ?? 0}
            filteredCount={filteredOrders.length}
            onRefresh={refreshOrders}
            isRefreshing={query.isFetching || allOrdersState.loading}
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

      {/* Bulk CSV Upload Modal */}
      <BulkOrderUploadModal
        isOpen={showBulkUploadModal}
        onClose={() => setShowBulkUploadModal(false)}
        onImportComplete={() => {
          refreshOrders()
        }}
      />

      {/* Safe Tracking Modal Dialog */}
      <CustomerTrackingModal
        formatTimestamp={formatDateTime}
        parcelId={inspect?.parcelId ?? null}
        trackingNumber={inspect?.trackingNumber}
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        deliveryApi={deliveryApi}
        onDownloadReceipt={handleDownloadReceipt}
        isDownloadingReceipt={receiptState.parcelId !== null}
      />
    </div>
  )
}
