import type { ParcelStatusHistoryEntry } from '../api/dispatcherContracts'
import { Badge } from '../../../components/ui/Badge'

interface ParcelHistoryTimelineProps {
  history: ParcelStatusHistoryEntry[]
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

function getStatusBadgeVariant(status: string): 'primary' | 'success' | 'danger' | 'neutral' {
  switch (status) {
    case 'DELIVERED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PICKED_UP':
    case 'IN_TRANSIT':
    case 'OUT_FOR_DELIVERY':
      return 'primary'
    default:
      return 'neutral'
  }
}

export function ParcelHistoryTimeline({
  history,
  isLoading,
  error,
  onRetry,
}: ParcelHistoryTimelineProps) {
  if (isLoading) {
    return (
      <div style={{ padding: '16px 20px', fontSize: '0.9rem' }} className="muted" role="status">
        Loading parcel history...
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
        No status updates are available for this parcel.
      </div>
    )
  }

  return (
    <div style={{ padding: '14px 18px', background: 'rgba(0, 0, 0, 0.015)', borderRadius: '8px' }}>
      <h4 style={{ margin: '0 0 12px 0', fontSize: '0.85rem', color: 'var(--color-heading, #1e3a29)', fontWeight: 600 }}>
        Parcel Status Timeline (Oldest to Newest)
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
            key={entry.historyId || `${entry.resultingVersion}-${idx}`}
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
                backgroundColor: 'var(--color-teal, #0d9488)',
                border: '2px solid #fff',
                boxShadow: '0 0 0 1px var(--color-teal, #0d9488)',
              }}
              aria-hidden="true"
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              {entry.previousStatus ? (
                <span style={{ fontSize: '0.82rem', color: 'var(--color-muted)' }}>
                  <Badge variant={getStatusBadgeVariant(entry.previousStatus)} size="sm">
                    {entry.previousStatus}
                  </Badge>
                  {' → '}
                  <Badge variant={getStatusBadgeVariant(entry.newStatus)} size="sm">
                    {entry.newStatus}
                  </Badge>
                </span>
              ) : (
                <Badge variant={getStatusBadgeVariant(entry.newStatus)} size="sm">
                  {entry.newStatus}
                </Badge>
              )}
              <span style={{ fontSize: '0.78rem', color: 'var(--color-muted)' }}>
                {formatTimelineDate(entry.occurredAt)}
              </span>
              <span
                style={{
                  fontSize: '0.75rem',
                  padding: '1px 6px',
                  borderRadius: '4px',
                  background: 'rgba(0,0,0,0.05)',
                  fontFamily: 'monospace',
                }}
              >
                v{entry.resultingVersion}
              </span>
            </div>
            {(entry.reason || entry.note) && (
              <div style={{ marginTop: '4px', fontSize: '0.82rem', color: '#374151' }}>
                {entry.reason && <span><strong>Reason:</strong> {entry.reason} </span>}
                {entry.note && <span className="muted">({entry.note})</span>}
              </div>
            )}
            {entry.proofId && (
              <div style={{ marginTop: '2px', fontSize: '0.75rem', color: 'var(--color-muted)', fontFamily: 'monospace' }}>
                Proof ID: {entry.proofId}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
