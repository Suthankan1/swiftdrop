import { useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '../../../auth/context'
import {
  type PagedAssignmentsResponse,
  type ParcelDetailResponse,
  CourierApiError,
} from '../api/courierContracts'
import { createCourierAssignmentsApi } from '../api/courierAssignments'
import { CourierAssignmentCard } from './CourierAssignmentCard'
import { Button } from '../../../components/ui/Button'

export interface CourierAssignmentListProps {
  selectedParcelId: string | null
  onSelectParcel: (parcelId: string) => void
  apiOverride?: ReturnType<typeof createCourierAssignmentsApi>
}

const PAGE_SIZE = 20

/** The courier's local midnight; the server filters on it so every page respects "today". */
function startOfToday(): Date {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate())
}

export function CourierAssignmentList({
  selectedParcelId,
  onSelectParcel,
  apiOverride,
}: CourierAssignmentListProps) {
  const auth = useAuth()
  const subject = auth.snapshot.status === 'authenticated' ? auth.snapshot.subject : ''

  const api = useMemo(
    () =>
      apiOverride ||
      createCourierAssignmentsApi({
        getAccessToken: auth.getAccessToken,
      }),
    [apiOverride, auth.getAccessToken],
  )

  const [currentPage, setCurrentPage] = useState(0)
  const [pagedData, setPagedData] = useState<PagedAssignmentsResponse | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [todayOnly, setTodayOnly] = useState(false)
  const [parcels, setParcels] = useState<Record<string, ParcelDetailResponse>>({})

  const abortControllerRef = useRef<AbortController | null>(null)
  const [retryCount, setRetryCount] = useState(0)

  // Subject change: clear assignments, reset parcels and reset pagination
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setCurrentPage(0)
    setPagedData(null)
    setParcels({})
    setError(null)
  }

  useEffect(() => {
    if (!subject) return

    let active = true
    const controller = new AbortController()
    abortControllerRef.current = controller

    void (async () => {
      try {
        setIsLoading(true)
        setError(null)

        const response = await api.getMyAssignmentsPaged({
          activeOnly: true,
          page: currentPage,
          size: PAGE_SIZE,
          signal: controller.signal,
          ...(todayOnly ? { assignedSince: startOfToday() } : {}),
        })

        if (!active || controller.signal.aborted) return
        setPagedData(response)

        // Enrich cards with tracking number, status and route (best effort; cards still work without it).
        if (typeof api.getParcel === 'function' && response.items.length > 0) {
          const details = await Promise.allSettled(
            response.items.map((item) =>
              Promise.resolve().then(() => api.getParcel(item.parcelId, controller.signal)),
            ),
          )

          if (!active || controller.signal.aborted) return

          const next: Record<string, ParcelDetailResponse> = {}
          details.forEach((result) => {
            if (result.status === 'fulfilled' && result.value?.parcelId && result.value.trackingNumber) {
              next[result.value.parcelId] = result.value
            }
          })
          setParcels(next)
        } else if (response.items.length === 0) {
          setParcels({})
        }
      } catch (err) {
        if (!active || controller.signal.aborted) return
        if (err instanceof CourierApiError) {
          setError(err.message)
        } else if (err instanceof Error) {
          setError(err.message)
        } else {
          setError('Unable to load assignments from Delivery service.')
        }
      } finally {
        if (active && !controller.signal.aborted) {
          setIsLoading(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, currentPage, subject, todayOnly, retryCount])

  const handleRetry = () => {
    if (!subject) return
    abortControllerRef.current?.abort()
    setRetryCount((c) => c + 1)
  }

  const items = pagedData?.items ?? []
  const totalElements = pagedData?.totalElements ?? 0
  const totalPages = Math.max(1, pagedData?.totalPages ?? 1)
  const hasPrevious = Boolean(pagedData?.hasPrevious && currentPage > 0)
  const hasNext = Boolean(pagedData?.hasNext && currentPage < totalPages - 1)

  // The server already applied the "today" filter.
  const visibleItems = items

  return (
    <section className="card assignment-list-section" aria-labelledby="assignment-list-heading">
      <div className="section-heading-row">
        <div>
          <p className="eyebrow">Active Route Queue</p>
          <h2 id="assignment-list-heading">Assigned Deliveries</h2>
        </div>
        <span className="badge badge-approved">
          {totalElements} {totalElements === 1 ? 'Assignment' : 'Assignments'}
        </span>
      </div>

      {isLoading && (
        <div className="loading-state" role="status" aria-label="Loading assignments">
          <span className="loading-indicator" aria-hidden="true" />
          <p className="muted">Retrieving your active route assignments...</p>
        </div>
      )}

      {error && (
        <div className="feedback-banner is-error" role="alert">
          <span>{error}</span>
          <Button
            variant="secondary"
            size="sm"
            onClick={handleRetry}
          >
            Retry Assignments
          </Button>
        </div>
      )}

      {!isLoading && !error && items.length === 0 && todayOnly && (
        <p className="muted" role="status">No parcels have been assigned to you today.</p>
      )}

      {!isLoading && !error && items.length === 0 && !todayOnly && (
        <div className="empty-state" role="status">
          <p className="muted">
            No active delivery assignments found for your courier profile.
            When dispatchers assign parcels to your route, they will appear here for handover and proof capture.
          </p>
        </div>
      )}

      {!isLoading && !error && (items.length > 0 || todayOnly) && (
        <label className="assignment-today-filter">
          <input type="checkbox" checked={todayOnly} onChange={(event) => { setTodayOnly(event.target.checked); setCurrentPage(0) }} />
          {' '}Assigned today only
        </label>
      )}

      {!isLoading && !error && items.length > 0 && (
        <>
          <div className="assignment-cards-grid" role="list" aria-label="Assigned parcels list">
            {visibleItems.map((assignment) => (
              <CourierAssignmentCard
                key={assignment.assignmentId}
                assignment={assignment}
                parcel={parcels[assignment.parcelId]}
                isSelected={assignment.parcelId === selectedParcelId}
                onSelect={onSelectParcel}
              />
            ))}
          </div>

          <nav
            className="pagination-controls-row"
            aria-label="Assignment list pagination"
          >
            <Button
              variant="secondary"
              size="sm"
              disabled={!hasPrevious || isLoading}
              onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              aria-label="Go to previous page of assignments"
            >
              Previous
            </Button>
            <span className="pagination-info muted">
              Page {currentPage + 1} of {totalPages}
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={!hasNext || isLoading}
              onClick={() => setCurrentPage((p) => p + 1)}
              aria-label="Go to next page of assignments"
            >
              Next
            </Button>
          </nav>
        </>
      )}
    </section>
  )
}
