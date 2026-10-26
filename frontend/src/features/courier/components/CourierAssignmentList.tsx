import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../../auth/context'
import {
  type PagedAssignmentsResponse,
  CourierApiError,
} from '../api/courierContracts'
import { createCourierAssignmentsApi } from '../api/courierAssignments'
import { CourierAssignmentCard } from './CourierAssignmentCard'

export interface CourierAssignmentListProps {
  selectedParcelId: string | null
  onSelectParcel: (parcelId: string) => void
  apiOverride?: ReturnType<typeof createCourierAssignmentsApi>
}

const PAGE_SIZE = 20

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

  // Subject change: clear assignments and reset pagination
  const [prevSubject, setPrevSubject] = useState(subject)
  if (subject !== prevSubject) {
    setPrevSubject(subject)
    setCurrentPage(0)
    setPagedData(null)
    setError(null)
  }

  useEffect(() => {
    if (!subject) return

    let active = true
    const controller = new AbortController()

    void (async () => {
      try {
        setIsLoading(true)
        setError(null)
        const response = await api.getMyAssignmentsPaged({
          activeOnly: true,
          page: currentPage,
          size: PAGE_SIZE,
          signal: controller.signal,
        })
        if (active) {
          setPagedData(response)
        }
      } catch (err) {
        if (!active || controller.signal.aborted) return
        if (err instanceof CourierApiError) {
          setError(err.message)
        } else {
          setError('Unable to load assignments from Delivery service.')
        }
      } finally {
        if (active) {
          setIsLoading(false)
        }
      }
    })()

    return () => {
      active = false
      controller.abort()
    }
  }, [api, currentPage, subject])

  const handleRetry = () => {
    if (!subject) return
    setIsLoading(true)
    setError(null)
    void api
      .getMyAssignmentsPaged({
        activeOnly: true,
        page: currentPage,
        size: PAGE_SIZE,
      })
      .then((data) => {
        setPagedData(data)
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : 'Failed to reload assignments.')
      })
      .finally(() => {
        setIsLoading(false)
      })
  }

  const items = pagedData?.items ?? []
  const totalElements = pagedData?.totalElements ?? 0
  const totalPages = Math.max(1, pagedData?.totalPages ?? 1)
  const hasPrevious = Boolean(pagedData?.hasPrevious && currentPage > 0)
  const hasNext = Boolean(pagedData?.hasNext && currentPage < totalPages - 1)

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
          <button
            type="button"
            className="button button-secondary button-small"
            onClick={handleRetry}
          >
            Retry Assignments
          </button>
        </div>
      )}

      {!isLoading && !error && items.length === 0 && (
        <div className="empty-state" role="status">
          <p className="muted">
            No active delivery assignments found for your courier profile.
            When dispatchers assign parcels to your route, they will appear here for handover and proof capture.
          </p>
        </div>
      )}

      {!isLoading && !error && items.length > 0 && (
        <>
          <div className="assignment-cards-grid" role="list" aria-label="Assigned parcels list">
            {items.map((assignment) => (
              <CourierAssignmentCard
                key={assignment.assignmentId}
                assignment={assignment}
                isSelected={assignment.parcelId === selectedParcelId}
                onSelect={onSelectParcel}
              />
            ))}
          </div>

          <nav
            className="pagination-controls-row"
            aria-label="Assignment list pagination"
          >
            <button
              type="button"
              className="button button-secondary button-small"
              disabled={!hasPrevious || isLoading}
              onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              aria-label="Go to previous page of assignments"
            >
              Previous
            </button>
            <span className="pagination-info muted">
              Page {currentPage + 1} of {totalPages}
            </span>
            <button
              type="button"
              className="button button-secondary button-small"
              disabled={!hasNext || isLoading}
              onClick={() => setCurrentPage((p) => p + 1)}
              aria-label="Go to next page of assignments"
            >
              Next
            </button>
          </nav>
        </>
      )}
    </section>
  )
}
