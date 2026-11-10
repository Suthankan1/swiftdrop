import type { AssignmentResponse, ParcelDetailResponse } from '../api/courierContracts'
import { Button } from '../../../components/ui/Button'

export interface CourierAssignmentCardProps {
  assignment: AssignmentResponse
  /** Parcel details when available: tracking number, status and route. */
  parcel?: ParcelDetailResponse
  isSelected: boolean
  onSelect: (parcelId: string) => void
}

export function CourierAssignmentCard({
  assignment,
  parcel,
  isSelected,
  onSelect,
}: CourierAssignmentCardProps) {
  const formattedDate = (() => {
    try {
      const d = new Date(assignment.assignedAt)
      if (isNaN(d.getTime())) return assignment.assignedAt
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + ', ' + d.toLocaleDateString()
    } catch {
      return assignment.assignedAt
    }
  })()

  return (
    <article
      className={`card assignment-item-card ${isSelected ? 'is-selected' : ''}`}
      aria-labelledby={`assignment-title-${assignment.assignmentId}`}
    >
      <div className="assignment-card-header">
        <div>
          <span className="eyebrow">Assigned Parcel</span>
          <h3
            id={`assignment-title-${assignment.assignmentId}`}
            className="assignment-card-title monospace"
          >
            {parcel?.trackingNumber ?? `${assignment.parcelId.slice(0, 8)}...`}
          </h3>
        </div>
        <span className="badge badge-approved">
          {parcel ? parcel.currentStatus.replaceAll('_', ' ') : assignment.active ? 'Active' : 'Archived'}
        </span>
      </div>

      <div className="assignment-card-body">
        {parcel?.pickupAddress && (
          <div className="assignment-meta-row">
            <span className="meta-label">Pickup:</span>
            <span className="meta-value">{parcel.pickupAddress}</span>
          </div>
        )}
        {parcel?.dropoffAddress && (
          <div className="assignment-meta-row">
            <span className="meta-label">Drop-off:</span>
            <span className="meta-value">{parcel.dropoffAddress}</span>
          </div>
        )}
        <div className="assignment-meta-row">
          <span className="meta-label">Assigned At:</span>
          <span className="meta-value">{formattedDate}</span>
        </div>
      </div>

      <div className="assignment-card-actions">
        <Button
          variant={isSelected ? 'primary' : 'secondary'}
          size="sm"
          onClick={() => onSelect(assignment.parcelId)}
          aria-label={`View parcel details for ${assignment.parcelId.slice(0, 8)}`}
        >
          {isSelected ? 'Viewing Details' : 'View Details & Handover'}
        </Button>
      </div>
    </article>
  )
}
