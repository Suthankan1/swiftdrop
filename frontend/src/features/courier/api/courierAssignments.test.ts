import { describe, expect, it, vi } from 'vitest'
import {
  createCourierAssignmentsApi,
  isPagedAssignmentsResponse,
} from './courierAssignments'
import type { AssignmentResponse, ParcelDetailResponse } from './courierContracts'

describe('courierAssignments API', () => {
  const getAccessToken = vi.fn(async () => 'mock-jwt-token')

  const sampleAssignment: AssignmentResponse = {
    assignmentId: 'assign-101',
    parcelId: 'parcel-101',
    courierSub: 'courier-sub-1',
    active: true,
    assignedAt: '2026-10-26T10:00:00Z',
    assignedBy: 'dispatcher-1',
  }

  const samplePagedResponse = {
    items: [sampleAssignment],
    currentPage: 0,
    pageSize: 20,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
  }

  const sampleParcel: ParcelDetailResponse = {
    parcelId: 'parcel-101',
    trackingNumber: 'SWD-20261026-0001',
    createdBySub: 'customer-1',
    merchantId: 'merchant-1',
    currentStatus: 'OUT_FOR_DELIVERY',
    version: 3,
    pickupAddress: 'Warehouse A, Colombo 01',
    dropoffAddress: '42 Main St, Colombo 03',
    activeCourierSub: 'courier-sub-1',
    createdAt: '2026-10-26T08:00:00Z',
  }

  it('validates schema correctly using isPagedAssignmentsResponse', () => {
    expect(isPagedAssignmentsResponse(samplePagedResponse)).toBe(true)
    expect(isPagedAssignmentsResponse(null)).toBe(false)
    expect(isPagedAssignmentsResponse({})).toBe(false)
    expect(
      isPagedAssignmentsResponse({
        ...samplePagedResponse,
        currentPage: -1,
      }),
    ).toBe(false)
    expect(
      isPagedAssignmentsResponse({
        ...samplePagedResponse,
        pageSize: 0,
      }),
    ).toBe(false)
    expect(
      isPagedAssignmentsResponse({
        ...samplePagedResponse,
        items: [{ invalid: 'item' }],
      }),
    ).toBe(false)
  })

  it('retrieves paginated assignments with correct query params and auth header', async () => {
    const mockFetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      expect(url).toBe('http://localhost:8080/api/v1/assignments/my?activeOnly=true&page=1&size=15')
      expect(init?.method).toBe('GET')
      expect((init?.headers as Record<string, string>)?.Authorization).toBe('Bearer mock-jwt-token')
      return new Response(JSON.stringify(samplePagedResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const api = createCourierAssignmentsApi({
      getAccessToken,
      gatewayUrl: 'http://localhost:8080',
      fetch: mockFetch as unknown as typeof fetch,
    })

    const result = await api.getMyAssignmentsPaged({
      activeOnly: true,
      page: 1,
      size: 15,
    })

    expect(result).toEqual(samplePagedResponse)
  })

  it('validates pagination bounds and rejects invalid inputs with invalid-pagination', async () => {
    const api = createCourierAssignmentsApi({
      getAccessToken,
      fetch: vi.fn(),
    })

    await expect(api.getMyAssignmentsPaged({ page: -1 })).rejects.toMatchObject({
      kind: 'invalid-pagination',
    })

    await expect(api.getMyAssignmentsPaged({ size: 0 })).rejects.toMatchObject({
      kind: 'invalid-pagination',
    })

    await expect(api.getMyAssignmentsPaged({ size: 51 })).rejects.toMatchObject({
      kind: 'invalid-pagination',
    })
  })

  it('maps HTTP 401 to auth-required', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 401 }))
    const api = createCourierAssignmentsApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getMyAssignmentsPaged()).rejects.toMatchObject({
      kind: 'auth-required',
      statusCode: 401,
    })
  })

  it('maps HTTP 403 to forbidden', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 403 }))
    const api = createCourierAssignmentsApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getMyAssignmentsPaged()).rejects.toMatchObject({
      kind: 'forbidden',
      statusCode: 403,
    })
  })

  it('maps HTTP 504 to timeout', async () => {
    const mockFetch = vi.fn(async () => new Response(null, { status: 504 }))
    const api = createCourierAssignmentsApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getMyAssignmentsPaged()).rejects.toMatchObject({
      kind: 'timeout',
      statusCode: 504,
    })
  })

  it('rejects unexpected response structure with invalid-response', async () => {
    const mockFetch = vi.fn(
      async () =>
        new Response(JSON.stringify({ notAPagedResponse: true }), {
          status: 200,
        }),
    )
    const api = createCourierAssignmentsApi({
      getAccessToken,
      fetch: mockFetch as unknown as typeof fetch,
    })

    await expect(api.getMyAssignmentsPaged()).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  })

  describe('getParcel', () => {
    it('retrieves authorized parcel details', async () => {
      const mockFetch = vi.fn(async (url: string | URL | Request) => {
        expect(url).toBe('http://localhost:8080/api/v1/parcels/parcel-101')
        return new Response(JSON.stringify(sampleParcel), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      })

      const api = createCourierAssignmentsApi({
        getAccessToken,
        fetch: mockFetch as unknown as typeof fetch,
      })

      const parcel = await api.getParcel('parcel-101')
      expect(parcel).toEqual(sampleParcel)
    })

    it('rejects empty parcelId with not-found', async () => {
      const api = createCourierAssignmentsApi({
        getAccessToken,
        fetch: vi.fn(),
      })

      await expect(api.getParcel('')).rejects.toMatchObject({
        kind: 'not-found',
      })
    })

    it('maps 403 forbidden when parcel is not assigned to current courier', async () => {
      const mockFetch = vi.fn(async () => new Response(null, { status: 403 }))
      const api = createCourierAssignmentsApi({
        getAccessToken,
        fetch: mockFetch as unknown as typeof fetch,
      })

      await expect(api.getParcel('parcel-other')).rejects.toMatchObject({
        kind: 'forbidden',
        statusCode: 403,
      })
    })

    it('maps 404 not-found when parcel is missing or reassigned', async () => {
      const mockFetch = vi.fn(async () => new Response(null, { status: 404 }))
      const api = createCourierAssignmentsApi({
        getAccessToken,
        fetch: mockFetch as unknown as typeof fetch,
      })

      await expect(api.getParcel('parcel-missing')).rejects.toMatchObject({
        kind: 'not-found',
        statusCode: 404,
      })
    })
  })
})
