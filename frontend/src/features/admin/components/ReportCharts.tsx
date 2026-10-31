import type { DeliveryStatsSummary } from '../api/reports'
import { outcomeRates } from '../api/reports'

export interface ReportChartsProps {
  data: DeliveryStatsSummary
}

export function ReportCharts({ data }: ReportChartsProps) {
  const { terminal, successRate, failureRate } = outcomeRates(data)
  const sRate = successRate ?? 0
  const fRate = failureRate ?? 0
  const maxVal = Math.max(1, data.createdCount, data.inTransitCount, data.deliveredCount, data.failedCount)

  const bars = [
    { label: 'Awaiting Pickup', count: data.createdCount, color: '#f59e0b' },
    { label: 'In Transit', count: data.inTransitCount, color: '#3b82f6' },
    { label: 'Delivered', count: data.deliveredCount, color: '#059669' },
    { label: 'Failed', count: data.failedCount, color: '#dc2626' },
  ]

  // Donut chart calculation
  const radius = 38
  const circumference = 2 * Math.PI * radius
  const successDash = terminal > 0 ? sRate * circumference : 0
  const failureDash = terminal > 0 ? fRate * circumference : 0

  return (
    <div
      className="report-charts-grid"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
        gap: '16px',
        margin: '20px 0',
      }}
      aria-label="Delivery outcomes visual charts"
    >
      {/* 1. Volume Bar Chart */}
      <div className="card chart-card" style={{ padding: '16px', background: 'var(--sd-surface-card, #fff)' }}>
        <h4 style={{ margin: '0 0 12px 0', fontSize: '0.95rem' }}>Lifecycle Volume Distribution</h4>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          {bars.map((bar) => {
            const widthPct = Math.round((bar.count / maxVal) * 100)
            return (
              <div key={bar.label}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.82rem', marginBottom: '3px' }}>
                  <span className="muted">{bar.label}</span>
                  <strong>{bar.count}</strong>
                </div>
                <div style={{ height: '8px', background: '#f1f5f9', borderRadius: '4px', overflow: 'hidden' }}>
                  <div
                    style={{
                      width: `${widthPct}%`,
                      height: '100%',
                      background: bar.color,
                      borderRadius: '4px',
                      transition: 'width 0.4s ease',
                    }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* 2. Donut Chart */}
      <div
        className="card chart-card"
        style={{
          padding: '16px',
          background: 'var(--sd-surface-card, #fff)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <h4 style={{ margin: '0 0 8px 0', fontSize: '0.95rem', alignSelf: 'flex-start' }}>Terminal SLA Outcomes</h4>
        {terminal === 0 ? (
          <p className="muted" style={{ fontSize: '0.85rem', margin: 'auto' }}>
            No terminal outcomes in period
          </p>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: '20px', flexWrap: 'wrap', justifyContent: 'center' }}>
            <svg width="105" height="105" viewBox="0 0 100 100" style={{ transform: 'rotate(-90deg)' }}>
              <circle
                cx="50"
                cy="50"
                r={radius}
                fill="transparent"
                stroke="#e2e8f0"
                strokeWidth="12"
              />
              <circle
                cx="50"
                cy="50"
                r={radius}
                fill="transparent"
                stroke="#059669"
                strokeWidth="12"
                strokeDasharray={`${successDash} ${circumference}`}
                strokeDashoffset="0"
              />
              <circle
                cx="50"
                cy="50"
                r={radius}
                fill="transparent"
                stroke="#dc2626"
                strokeWidth="12"
                strokeDasharray={`${failureDash} ${circumference}`}
                strokeDashoffset={-successDash}
              />
              <text
                x="50"
                y="52"
                textAnchor="middle"
                dominantBaseline="middle"
                fontSize="14"
                fontWeight="bold"
                fill="#1e293b"
                style={{ transform: 'rotate(90deg)', transformOrigin: 'center' }}
              >
                {(sRate * 100).toFixed(0)}%
              </text>
            </svg>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '0.8rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span style={{ width: '10px', height: '10px', background: '#059669', borderRadius: '50%' }} />
                <span>Delivered: {(sRate * 100).toFixed(1)}% ({data.deliveredCount})</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span style={{ width: '10px', height: '10px', background: '#dc2626', borderRadius: '50%' }} />
                <span>Failed: {(fRate * 100).toFixed(1)}% ({data.failedCount})</span>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

