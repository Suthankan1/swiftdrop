import type { AssignmentResponse } from '../api/courierContracts'

export interface CourierAssignmentCardProps {
  assignment: AssignmentResponse
  isSelected: boolean
  onSelect: (parcelId: string) => void
}

export function CourierAssignmentCard({
  assignment,
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
            {assignment.parcelId.slice(0, 8)}...
          </h3>
        </div>
        <span className="badge badge-approved">
          {assignment.active ? 'Active' : 'Archived'}
        </span>
      </div>

      <div className="assignment-card-body">
        <div className="assignment-meta-row">
          <span className="meta-label">Assigned At:</span>
          <span className="meta-value">{formattedDate}</span>
        </div>
        <div className="assignment-meta-row">
          <span className="meta-label">Full Parcel ID:</span>
          <span className="meta-value monospace parcel-id-snippet">{assignment.parcelId}</span>
        </div>
      </div>

      <div className="assignment-card-actions">
        <button
          type="button"
          className={`button ${isSelected ? 'button-primary' : 'button-secondary'} button-small`}
          onClick={() => onSelect(assignment.parcelId)}
          aria-label={`View parcel details for ${assignment.parcelId.slice(0, 8)}`}
        >
          {isSelected ? 'Viewing Details' : 'View Details & Handover'}
        </button>
      </div>
    </article>
  )
}
