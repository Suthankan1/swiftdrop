import type { AssignmentAuditEntry } from '../api/dispatcherContracts'
import { Badge } from '../../../components/ui/Badge'

interface AssignmentHistoryTimelineProps {
  history: AssignmentAuditEntry[]
  isLoading?: boolean
  error?: string | null
  onRetry?: () => void
}

function formatTimelineDate(isoString: string): string {
  if (!isoString) return 'Time not recorded'
  const d = new Date(isoString)
  if (Number.isNaN(d.getTime())) return isoString
  return d.toLocaleString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function getActionBadgeVariant(action: string): 'primary' | 'success' | 'danger' | 'neutral' {
  switch (action) {
    case 'ASSIGNED':
      return 'primary'
    case 'REASSIGNED':
      return 'neutral'
    case 'CLOSED':
      return 'success'
    case 'TERMINATED':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function AssignmentHistoryTimeline({
  history,
  isLoading,
  error,
  onRetry,
}: AssignmentHistoryTimelineProps) {
  if (isLoading) {
    return (
      <div style={{ padding: '16px 20px', fontSize: '0.9rem' }} className="muted" role="status">
        Loading assignment history...
      </div>
    )
  }

  if (error) {
    return (
      <div style={{ padding: '16px 20px' }}>
        <p style={{ color: 'var(--color-danger, #dc2626)', margin: '0 0 8px 0', fontSize: '0.9rem' }}>
          {error || 'Unable to load history. Please try again.'}
        </p>
        {onRetry && (
          <button
            type="button"
            className="button button-secondary button-sm"
            onClick={onRetry}
          >
            Retry
          </button>
        )}
      </div>
    )
  }

  if (!history || history.length === 0) {
    return (
      <div style={{ padding: '16px 20px', fontSize: '0.9rem' }} className="muted">
        No assignment records are available for this parcel.
      </div>
    )
  }

  return (
    <div style={{ padding: '14px 18px', background: 'rgba(0, 0, 0, 0.015)', borderRadius: '8px' }}>
      <h4 style={{ margin: '0 0 12px 0', fontSize: '0.85rem', color: 'var(--color-heading, #1e3a29)', fontWeight: 600 }}>
        Courier Assignment Audit Timeline (Chronological)
      </h4>
      <div
        style={{
          position: 'relative',
          paddingLeft: '24px',
          borderLeft: '2px solid var(--sd-border, #d2dec8)',
          display: 'flex',
          flexDirection: 'column',
          gap: '16px',
        }}
      >
        {history.map((entry, idx) => (
          <div
            key={entry.auditId || `${entry.assignmentId}-${idx}`}
            style={{ position: 'relative' }}
          >
            {/* Timeline bullet dot */}
            <div
              style={{
                position: 'absolute',
                left: '-31px',
                top: '4px',
                width: '12px',
                height: '12px',
                borderRadius: '50%',
                backgroundColor: '#1e3a29',
                border: '2px solid #fff',
                boxShadow: '0 0 0 1px #1e3a29',
              }}
              aria-hidden="true"
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <Badge variant={getActionBadgeVariant(entry.action)} size="sm">
                {entry.action}
              </Badge>
              <span style={{ fontSize: '0.82rem', fontWeight: 600 }}>
                Courier: {entry.courierSub}
              </span>
              {entry.previousCourierSub && (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  (Previous: {entry.previousCourierSub})
                </span>
              )}
              <span style={{ fontSize: '0.78rem', color: 'var(--color-muted)' }}>
                {formatTimelineDate(entry.occurredAt)}
              </span>
              {entry.parcelVersion !== null && (
                <span
                  style={{
                    fontSize: '0.75rem',
                    padding: '1px 6px',
                    borderRadius: '4px',
                    background: 'rgba(0,0,0,0.05)',
                    fontFamily: 'monospace',
                  }}
                >
                  v{entry.parcelVersion}
                </span>
              )}
            </div>

            <div style={{ marginTop: '4px', fontSize: '0.82rem', color: '#4b5563' }}>
              <span>
                <strong>Actor:</strong> {entry.actorSub} {entry.actorRole ? `(${entry.actorRole})` : ''}
              </span>
              {entry.reason && (
                <span style={{ marginLeft: '12px' }}>
                  <strong>Reason:</strong> {entry.reason}
                </span>
              )}
            </div>

            {entry.assignmentId && (
              <div style={{ marginTop: '2px', fontSize: '0.75rem', color: 'var(--color-muted)', fontFamily: 'monospace' }}>
                Assignment ID: {entry.assignmentId}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
