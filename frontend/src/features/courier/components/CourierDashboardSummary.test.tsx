import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CourierApiError } from '../api/courierContracts'
import { CourierDashboardSummary } from './CourierDashboardSummary'
import type { createCourierDashboardApi } from '../api/courierDashboard'

function courierAuth(subject = 'courier-test-sub'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles: ['COURIER'] },
    getAccessToken: async () => 'test-token',
  }
}

describe('CourierDashboardSummary component', () => {
  const mockSummary = {
    courierSub: 'courier-test-sub',
    activeAssignedCount: 7,
    todayDeliveredCount: 14,
    todayFailedCount: 2,
  }

  function createMockApi(overrides: Partial<ReturnType<typeof createCourierDashboardApi>> = {}) {
    return {
      getSummary: vi.fn(async () => mockSummary),
      ...overrides,
    } as unknown as ReturnType<typeof createCourierDashboardApi>
  }

  it('renders loading state initially then displays authoritative shift counts', async () => {
    const api = createMockApi()
    render(
      <TestProviders auth={courierAuth()}>
        <CourierDashboardSummary apiOverride={api} />
      </TestProviders>,
    )

    expect(screen.getByRole('heading', { name: /today's operational summary/i })).toBeInTheDocument()
    expect(screen.getByText(/shift overview · asia\/colombo day/i)).toBeInTheDocument()

    expect(await screen.findByText('7')).toBeInTheDocument()
    expect(screen.getByText('14')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()

    expect(screen.getByText(/active assigned/i)).toBeInTheDocument()
    expect(screen.getByText(/delivered \(today\)/i)).toBeInTheDocument()
    expect(screen.getByText(/exceptions \(today\)/i)).toBeInTheDocument()
  })

  it('displays provider failure truthfully with working retry', async () => {
    const user = userEvent.setup()
    let calls = 0
    const api = createMockApi({
      getSummary: vi.fn(async () => {
        calls++
        if (calls === 1) {
          throw new CourierApiError('unavailable', 'GraphQL summary provider unavailable.')
        }
        return mockSummary
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierDashboardSummary apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/graphql summary provider unavailable/i)

    const retryBtn = screen.getByRole('button', { name: /retry summary/i })
    await user.click(retryBtn)

    expect(await screen.findByText('7')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('clears metrics when the authenticated subject changes (session isolation)', async () => {
    const api = createMockApi()
    const { rerender } = render(
      <TestProviders auth={courierAuth('courier-one')}>
        <CourierDashboardSummary apiOverride={api} />
      </TestProviders>,
    )

    expect(await screen.findByText('7')).toBeInTheDocument()

    // Switch subject to courier-two with zero initial cache
    rerender(
      <TestProviders auth={courierAuth('courier-two')}>
        <CourierDashboardSummary apiOverride={api} />
      </TestProviders>,
    )

    // Old counts cleared immediately
    expect(api.getSummary).toHaveBeenCalledTimes(2)
  })
})
