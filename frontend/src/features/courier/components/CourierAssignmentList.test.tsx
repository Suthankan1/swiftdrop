import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { type AuthContextValue } from '../../../auth/context'
import { anonymousAuth, TestProviders } from '../../../test/authFixture'
import { CourierApiError } from '../api/courierContracts'
import { CourierAssignmentList } from './CourierAssignmentList'
import type { createCourierAssignmentsApi } from '../api/courierAssignments'

function courierAuth(subject = 'courier-test-sub'): AuthContextValue {
  return {
    ...anonymousAuth,
    snapshot: { status: 'authenticated', subject, roles: ['COURIER'] },
    getAccessToken: async () => 'test-token',
  }
}

describe('CourierAssignmentList component', () => {
  const mockAssignment1 = {
    assignmentId: 'assign-1',
    parcelId: '00000000-0000-0000-0000-000000000001',
    courierSub: 'courier-test-sub',
    active: true,
    assignedAt: '2026-10-26T10:00:00Z',
    assignedBy: 'dispatcher-1',
  }

  const mockAssignment2 = {
    assignmentId: 'assign-2',
    parcelId: '00000000-0000-0000-0000-000000000002',
    courierSub: 'courier-test-sub',
    active: true,
    assignedAt: '2026-10-26T10:30:00Z',
    assignedBy: 'dispatcher-1',
  }

  const mockPagedResponse = {
    items: [mockAssignment1, mockAssignment2],
    currentPage: 0,
    pageSize: 20,
    totalElements: 2,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
  }

  function createMockApi(overrides: Partial<ReturnType<typeof createCourierAssignmentsApi>> = {}) {
    return {
      getMyAssignmentsPaged: vi.fn(async () => mockPagedResponse),
      getParcel: vi.fn(),
      ...overrides,
    } as unknown as ReturnType<typeof createCourierAssignmentsApi>
  }

  it('renders assignment list with count badge and assignment cards', async () => {
    const api = createMockApi()
    const handleSelect = vi.fn()

    render(
      <TestProviders auth={courierAuth()}>
        <CourierAssignmentList
          selectedParcelId={null}
          onSelectParcel={handleSelect}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(screen.getByRole('heading', { name: /assigned deliveries/i })).toBeInTheDocument()
    expect(await screen.findByText('2 Assignments')).toBeInTheDocument()
    expect(screen.getByText('00000000-0000-0000-0000-000000000001')).toBeInTheDocument()
    expect(screen.getByText('00000000-0000-0000-0000-000000000002')).toBeInTheDocument()

    // Pagination controls disabled when single page
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
    expect(screen.getByText(/page 1 of 1/i)).toBeInTheDocument()
  })

  it('handles empty state when courier has no active assignments', async () => {
    const api = createMockApi({
      getMyAssignmentsPaged: vi.fn(async () => ({
        items: [],
        currentPage: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
        hasPrevious: false,
      })),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierAssignmentList
          selectedParcelId={null}
          onSelectParcel={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByText(/no active delivery assignments found for your courier profile/i)).toBeInTheDocument()
  })

  it('navigates through multiple pages with working pagination', async () => {
    const user = userEvent.setup()
    const api = createMockApi({
      getMyAssignmentsPaged: vi.fn(async ({ page = 0 }) => {
        if (page === 0) {
          return {
            items: [mockAssignment1],
            currentPage: 0,
            pageSize: 20,
            totalElements: 25,
            totalPages: 2,
            hasNext: true,
            hasPrevious: false,
          }
        }
        return {
          items: [mockAssignment2],
          currentPage: 1,
          pageSize: 20,
          totalElements: 25,
          totalPages: 2,
          hasNext: false,
          hasPrevious: true,
        }
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierAssignmentList
          selectedParcelId={null}
          onSelectParcel={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByText('25 Assignments')).toBeInTheDocument()
    expect(screen.getByText(/page 1 of 2/i)).toBeInTheDocument()

    const nextBtn = screen.getByRole('button', { name: /next/i })
    expect(nextBtn).toBeEnabled()
    await user.click(nextBtn)

    expect(await screen.findByText(/page 2 of 2/i)).toBeInTheDocument()
    expect(nextBtn).toBeDisabled()
    expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled()
  })

  it('shows error banner with retry button on provider failure', async () => {
    const user = userEvent.setup()
    let calls = 0
    const api = createMockApi({
      getMyAssignmentsPaged: vi.fn(async () => {
        calls++
        if (calls === 1) {
          throw new CourierApiError('unavailable', 'Delivery assignment service down.')
        }
        return mockPagedResponse
      }),
    })

    render(
      <TestProviders auth={courierAuth()}>
        <CourierAssignmentList
          selectedParcelId={null}
          onSelectParcel={vi.fn()}
          apiOverride={api}
        />
      </TestProviders>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/delivery assignment service down/i)

    const retryBtn = screen.getByRole('button', { name: /retry assignments/i })
    await user.click(retryBtn)

    expect(await screen.findByText('2 Assignments')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
