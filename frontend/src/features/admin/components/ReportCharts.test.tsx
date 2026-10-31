import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReportCharts } from './ReportCharts'
import type { DeliveryStatsSummary } from '../api/reports'

describe('ReportCharts', () => {
  const sampleStats: DeliveryStatsSummary = {
    totalCount: 100,
    createdCount: 20,
    inTransitCount: 30,
    deliveredCount: 45,
    failedCount: 5,
    averageDeliveryHours: 4.2,
  }

  it('renders volume distribution bars and SLA donut chart', () => {
    render(<ReportCharts data={sampleStats} />)
    expect(screen.getByText('Lifecycle Volume Distribution')).toBeInTheDocument()
    expect(screen.getByText('Awaiting Pickup')).toBeInTheDocument()
    expect(screen.getByText('Terminal SLA Outcomes')).toBeInTheDocument()
    expect(screen.getByText(/Delivered: 90.0%/)).toBeInTheDocument()
    expect(screen.getByText(/Failed: 10.0%/)).toBeInTheDocument()
  })

  it('handles zero terminal outcomes gracefully', () => {
    const zeroTerminal: DeliveryStatsSummary = {
      totalCount: 10,
      createdCount: 5,
      inTransitCount: 5,
      deliveredCount: 0,
      failedCount: 0,
      averageDeliveryHours: null,
    }
    render(<ReportCharts data={zeroTerminal} />)
    expect(screen.getByText('No terminal outcomes in period')).toBeInTheDocument()
  })
})

