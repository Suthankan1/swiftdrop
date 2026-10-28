import { describe, expect, it, vi, beforeEach } from 'vitest'
import {
  createDeliveryApi,
  DeliveryError,
  isPublicTrackingResponse,
  isTrackingTimelineItem,
  isValidTrackingNumber,
  sanitizeReceiptFilename,
  triggerBlobDownload,
} from './delivery'
import type { BookParcelRequest, ParcelDetailResponse, PublicTrackingResponse } from './deliveryContracts'

describe('sanitizeReceiptFilename', () => {
  it('extracts filename from standard Content-Disposition', () => {
    const header = 'attachment; filename="receipt-SWD-20261025-A1B2.pdf"'
    expect(sanitizeReceiptFilename(header, 'fallback.pdf')).toBe('receipt-SWD-20261025-A1B2.pdf')
  })

  it('extracts filename from unquoted Content-Disposition', () => {
    const header = 'attachment; filename=receipt-SWD-001.pdf'
    expect(sanitizeReceiptFilename(header, 'fallback.pdf')).toBe('receipt-SWD-001.pdf')
  })

  it('strips directory traversal paths', () => {
    const header = 'attachment; filename="../../../etc/passwd.pdf"'
    expect(sanitizeReceiptFilename(header, 'fallback.pdf')).toBe('passwd.pdf')
  })

  it('replaces dangerous characters with underscores', () => {
    const header = 'attachment; filename="receipt<test>alert(1).pdf"'
    expect(sanitizeReceiptFilename(header, 'fallback.pdf')).toBe('receipt_test_alert_1_.pdf')
  })

  it('ensures .pdf extension is added if missing', () => {
    const header = 'attachment; filename="document_without_ext"'
    expect(sanitizeReceiptFilename(header, 'fallback.pdf')).toBe('document_without_ext.pdf')
  })

  it('returns fallback when header is null or empty', () => {
    expect(sanitizeReceiptFilename(null, 'fallback.pdf')).toBe('fallback.pdf')
    expect(sanitizeReceiptFilename('', 'fallback.pdf')).toBe('fallback.pdf')
  })
})

describe('triggerBlobDownload', () => {
  it('creates an object URL, clicks a temporary anchor, and revokes the URL', () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:http://localhost:5173/mock-uuid')
    const revokeObjectURL = vi.fn()
    window.URL.createObjectURL = createObjectURL
    window.URL.revokeObjectURL = revokeObjectURL

    const clickSpy = vi.fn()
    const appendSpy = vi.spyOn(document.body, 'appendChild')
    const removeSpy = vi.spyOn(document.body, 'removeChild')

    // Mock HTMLAnchorElement click
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      const el = document.createElementNS('http://www.w3.org/1999/xhtml', tagName)
      if (tagName === 'a') {
        el.click = clickSpy
      }
      return el
    })

    const blob = new Blob(['%PDF-1.4 test'], { type: 'application/pdf' })
    triggerBlobDownload(blob, 'receipt-test.pdf')

    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(clickSpy).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:http://localhost:5173/mock-uuid')
    expect(appendSpy).toHaveBeenCalled()
    expect(removeSpy).toHaveBeenCalled()

    vi.restoreAllMocks()
  })
})

describe('createDeliveryApi', () => {
  const getAccessToken = vi.fn().mockResolvedValue('valid-test-token')
  let mockFetch: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn<typeof fetch>()
  })

  describe('authentication boundary', () => {
    it('throws auth-required when getAccessToken returns null or empty', async () => {
      const api = createDeliveryApi({
        getAccessToken: async () => null,
        fetch: mockFetch,
      })

      await expect(api.getParcel('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toThrowError(
        DeliveryError,
      )
      await expect(api.getParcel('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'auth-required',
      })
      expect(mockFetch).not.toHaveBeenCalled()
    })
  })

  describe('downloadReceipt', () => {
    it('successfully downloads official PDF receipt with attachment filename', async () => {
      const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34]) // %PDF-1.4
      const mockResponse = new Response(pdfBytes, {
        status: 200,
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="receipt-SWD-20261025-0001.pdf"',
        },
      })
      mockFetch.mockResolvedValueOnce(mockResponse)

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const result = await api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')

      expect(result.filename).toBe('receipt-SWD-20261025-0001.pdf')
      expect(result.blob.size).toBe(pdfBytes.length)
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/parcels/f47ac10b-58cc-4372-a567-0e02b2c3d479/receipt',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer valid-test-token',
            Accept: 'application/pdf, application/problem+json',
          }),
        }),
      )
    })

    it('rejects response if Content-Type is not application/pdf', async () => {
      const mockResponse = new Response(JSON.stringify({ error: 'not a pdf' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
      mockFetch.mockResolvedValueOnce(mockResponse)

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'invalid-response',
      })
    })

    it('rejects empty PDF body', async () => {
      const mockResponse = new Response(new Uint8Array(0), {
        status: 200,
        headers: { 'Content-Type': 'application/pdf' },
      })
      mockFetch.mockResolvedValueOnce(mockResponse)

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'invalid-response',
      })
    })

    it('handles 403 Forbidden with IDOR problem detail', async () => {
      const mockResponse = new Response(
        JSON.stringify({
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          detail: 'Access is denied. You do not own this parcel.',
        }),
        {
          status: 403,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      )
      mockFetch.mockResolvedValueOnce(mockResponse)

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'forbidden',
        statusCode: 403,
      })
    })

    it('handles 404 Not Found for uncompleted parcel receipt', async () => {
      const mockResponse = new Response(
        JSON.stringify({
          title: 'Not Found',
          status: 404,
          detail: 'Receipt not available or parcel not completed.',
        }),
        {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      )
      mockFetch.mockResolvedValueOnce(mockResponse)

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'not-found',
        statusCode: 404,
      })
    })

    it('handles network failure', async () => {
      mockFetch.mockRejectedValueOnce(new Error('Network offline'))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.downloadReceipt('f47ac10b-58cc-4372-a567-0e02b2c3d479')).rejects.toMatchObject({
        kind: 'unavailable',
      })
    })
  })

  describe('getParcel', () => {
    it('retrieves and validates authoritative parcel details', async () => {
      const mockParcel: ParcelDetailResponse = {
        parcelId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
        trackingNumber: 'SWD-20261025-ABCD',
        createdBySub: 'test-subject-customer',
        merchantId: null,
        currentStatus: 'IN_TRANSIT',
        version: 2,
        weightKg: 2.5,
        dimensions: '30x20x15',
        amountMinorUnits: 45000,
        currency: 'LKR',
        rateVersion: 'RATE-2026-V1',
        pickupAddress: '123 Galle Rd, Colombo 03',
        dropoffAddress: '45 Kandy Rd, Gampaha',
        activeCourierSub: 'courier-sub-1',
        createdAt: '2026-10-25T10:00:00Z',
      }
      mockFetch.mockResolvedValueOnce(new Response(JSON.stringify(mockParcel), { status: 200 }))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const parcel = await api.getParcel(mockParcel.parcelId)

      expect(parcel.trackingNumber).toBe('SWD-20261025-ABCD')
      expect(parcel.currentStatus).toBe('IN_TRANSIT')
      expect(parcel.amountMinorUnits).toBe(45000)
    })

    it('rejects invalid schema payload', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify({ parcelId: 'invalid-payload' }), { status: 200 }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getParcel('some-id')).rejects.toMatchObject({
        kind: 'invalid-response',
      })
    })
  })

  describe('getTracking', () => {
    it('validates tracking references with isValidTrackingNumber', () => {
      expect(isValidTrackingNumber('SWD-20261025-ABCD')).toBe(true)
      expect(isValidTrackingNumber('SWD-20261023-A1B2C3')).toBe(true)
      expect(isValidTrackingNumber('SWD-20261023-ABCDEFGH')).toBe(true)
      expect(isValidTrackingNumber('  SWD-20261023-A1B2C3  ')).toBe(true)

      expect(isValidTrackingNumber('')).toBe(false)
      expect(isValidTrackingNumber('   ')).toBe(false)
      expect(isValidTrackingNumber(null)).toBe(false)
      expect(isValidTrackingNumber(undefined)).toBe(false)
      expect(isValidTrackingNumber('INVALID-REF-001')).toBe(false)
      expect(isValidTrackingNumber('SWD-1234-ABCD')).toBe(false)
      expect(isValidTrackingNumber('SWD-20261023-ABC')).toBe(false)
      expect(isValidTrackingNumber('SWD-20261023-ABCDEFGHI')).toBe(false)
      expect(isValidTrackingNumber('SWD-20261023-A1B2C3;DROP TABLE')).toBe(false)
      expect(isValidTrackingNumber('SWD-20261023-A1B2C3'.repeat(3))).toBe(false)
    })

    it('validates timeline item schema with isTrackingTimelineItem', () => {
      expect(
        isTrackingTimelineItem({
          status: 'CREATED',
          occurredAt: '2026-10-25T08:00:00Z',
          cityOrZone: 'Colombo',
        }),
      ).toBe(true)
      expect(
        isTrackingTimelineItem({
          status: 'PICKED_UP',
          occurredAt: '2026-10-25T10:00:00Z',
        }),
      ).toBe(true)

      // Invalid status
      expect(
        isTrackingTimelineItem({
          status: 'INVALID_STATUS',
          occurredAt: '2026-10-25T08:00:00Z',
        }),
      ).toBe(false)

      // Invalid date
      expect(
        isTrackingTimelineItem({
          status: 'CREATED',
          occurredAt: 'invalid-date-string',
        }),
      ).toBe(false)

      // Oversized cityOrZone
      expect(
        isTrackingTimelineItem({
          status: 'CREATED',
          occurredAt: '2026-10-25T08:00:00Z',
          cityOrZone: 'x'.repeat(101),
        }),
      ).toBe(false)
    })

    it('validates public tracking response schema with isPublicTrackingResponse', () => {
      const valid: PublicTrackingResponse = {
        trackingNumber: 'SWD-20261025-ABCD',
        currentStatus: 'PICKED_UP',
        originCity: 'Colombo',
        destinationCity: 'Gampaha',
        timeline: [
          { status: 'CREATED', occurredAt: '2026-10-25T08:00:00Z', cityOrZone: 'Colombo Central' },
        ],
      }
      expect(isPublicTrackingResponse(valid)).toBe(true)

      // Missing trackingNumber
      expect(isPublicTrackingResponse({ ...valid, trackingNumber: '' })).toBe(false)
      // Invalid status
      expect(isPublicTrackingResponse({ ...valid, currentStatus: 'UNKNOWN' })).toBe(false)
      // Invalid timeline item
      expect(
        isPublicTrackingResponse({
          ...valid,
          timeline: [{ status: 'BAD', occurredAt: '2026-10-25T08:00:00Z' }],
        }),
      ).toBe(false)
      // Oversized timeline
      expect(
        isPublicTrackingResponse({
          ...valid,
          timeline: Array.from({ length: 101 }, () => ({
            status: 'CREATED',
            occurredAt: '2026-10-25T08:00:00Z',
          })),
        }),
      ).toBe(false)
    })

    it('retrieves public tracking with timeline checkpoints and normalizes reference', async () => {
      const mockTracking: PublicTrackingResponse = {
        trackingNumber: 'SWD-20261025-ABCD',
        currentStatus: 'PICKED_UP',
        originCity: 'Colombo',
        destinationCity: 'Gampaha',
        timeline: [
          { status: 'CREATED', occurredAt: '2026-10-25T08:00:00Z', cityOrZone: 'Colombo Central' },
          { status: 'PICKED_UP', occurredAt: '2026-10-25T10:00:00Z', cityOrZone: 'Colombo Sorting Hub' },
        ],
      }
      mockFetch.mockResolvedValueOnce(new Response(JSON.stringify(mockTracking), { status: 200 }))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const tracking = await api.getTracking('  swd-20261025-abcd  ')

      expect(tracking.trackingNumber).toBe('SWD-20261025-ABCD')
      expect(tracking.timeline).toHaveLength(2)
      expect(tracking.timeline[0]?.status).toBe('CREATED')

      // Verify request parameters: unauthenticated, omit credentials, no-store
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/tracking/SWD-20261025-ABCD',
        expect.objectContaining({
          method: 'GET',
          credentials: 'omit',
          cache: 'no-store',
          headers: {
            Accept: 'application/json, application/problem+json',
          },
        }),
      )
      // Ensure NO Authorization header is sent
      const headers = mockFetch.mock.calls[0]?.[1]?.headers as Record<string, string>
      expect(headers.Authorization).toBeUndefined()
    })

    it('throws validation-error for invalid or empty tracking references without calling fetch', async () => {
      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })

      await expect(api.getTracking('')).rejects.toMatchObject({
        kind: 'validation-error',
        statusCode: 400,
      })

      await expect(api.getTracking('INVALID-FORMAT')).rejects.toMatchObject({
        kind: 'validation-error',
        statusCode: 400,
      })

      await expect(api.getTracking('SWD-20261023-A1B2C3'.repeat(3))).rejects.toMatchObject({
        kind: 'validation-error',
        statusCode: 400,
      })

      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('handles HTTP 404 tracking not found', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            title: 'Not Found',
            status: 404,
            detail: 'Tracking number not found.',
          }),
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'not-found',
        statusCode: 404,
      })
    })

    it('handles HTTP 429 rate limit exceeded safely', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            title: 'Rate Limit Exceeded',
            status: 429,
            detail: 'Too many tracking requests from this network.',
          }),
          { status: 429, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'unavailable',
        statusCode: 429,
      })
    })

    it('handles HTTP 503 service unavailable', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            title: 'Service Unavailable',
            status: 503,
            detail: 'Tracking service temporarily down.',
          }),
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'unavailable',
        statusCode: 503,
      })
    })

    it('handles network unreachable error', async () => {
      mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'unavailable',
      })
    })

    it('handles aborted signal without swallowing abort error', async () => {
      const controller = new AbortController()
      controller.abort()

      mockFetch.mockRejectedValueOnce(new DOMException('The operation was aborted', 'AbortError'))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD', controller.signal)).rejects.toThrow()
    })

    it('handles invalid non-JSON response payload', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response('<html><body>502 Bad Gateway</body></html>', {
          status: 200,
          headers: { 'Content-Type': 'text/html' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'invalid-response',
      })
    })

    it('handles payload failing contract validation', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            trackingNumber: 'SWD-20261025-ABCD',
            currentStatus: 'UNAPPROVED_STATUS',
            timeline: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.getTracking('SWD-20261025-ABCD')).rejects.toMatchObject({
        kind: 'invalid-response',
      })
    })

    it('strips unauthorized sensitive fields from provider response (defense in depth)', async () => {
      const maliciousPayload = {
        trackingNumber: 'SWD-20261025-ABCD',
        currentStatus: 'IN_TRANSIT',
        originCity: 'Colombo',
        destinationCity: 'Kandy',
        timeline: [
          { status: 'CREATED', occurredAt: '2026-10-25T08:00:00Z', cityOrZone: 'Colombo' },
        ],
        // Extraneous sensitive fields that should never pass through
        recipientName: 'PRIVATE_RECIPIENT',
        recipientPhone: 'PRIVATE_PHONE',
        pickupAddress: 'PRIVATE_STREET',
        proofUrl: 'PRIVATE_PROOF_URL',
        receiptUrl: 'PRIVATE_RECEIPT',
        courierSub: 'PRIVATE_COURIER',
        privateNotes: 'CONFIDENTIAL_NOTE',
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(maliciousPayload), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const result = await api.getTracking('SWD-20261025-ABCD')

      expect(result.trackingNumber).toBe('SWD-20261025-ABCD')
      expect(result.currentStatus).toBe('IN_TRANSIT')
      const stripped = result as unknown as Record<string, unknown>
      expect(stripped.recipientName).toBeUndefined()
      expect(stripped.recipientPhone).toBeUndefined()
      expect(stripped.pickupAddress).toBeUndefined()
      expect(stripped.proofUrl).toBeUndefined()
      expect(stripped.receiptUrl).toBeUndefined()
      expect(stripped.courierSub).toBeUndefined()
      expect(stripped.privateNotes).toBeUndefined()
    })
  })

  describe('getCustomerDeliveries', () => {
    it('requests deliveries with pagination parameters', async () => {
      const mockPage = {
        items: [
          {
            parcelId: 'p-1',
            trackingNumber: 'SWD-20261025-0001',
            currentStatus: 'DELIVERED',
            amountMinorUnits: 50000,
            currency: 'LKR',
            pickupAddress: 'Colombo',
            dropoffAddress: 'Gampaha',
            createdAt: '2026-10-25T08:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        currentPage: 0,
        pageSize: 10,
        hasNext: false,
        hasPrevious: false,
      }
      mockFetch.mockResolvedValueOnce(new Response(JSON.stringify(mockPage), { status: 200 }))

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const page = await api.getCustomerDeliveries({ page: 0, size: 10 })

      expect(page.items).toHaveLength(1)
      expect(page.totalElements).toBe(1)
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/parcels?page=0&size=10',
        expect.anything(),
      )
    })
  })

  describe('bookParcel', () => {
    const validBookingPayload: BookParcelRequest = {
      quoteId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
      pickupAddress: '123 Galle Road, Colombo 03',
      dropoffAddress: '45 Kandy Road, Gampaha',
      recipientName: 'Nimal Perera',
      recipientPhone: '+94771234567',
      deliveryNotes: 'Leave at security gate.',
    }

    const mockParcelResponse: ParcelDetailResponse = {
      parcelId: 'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      trackingNumber: 'SWD-20261026-A1B2C3',
      createdBySub: 'user-sub-123',
      merchantId: null,
      currentStatus: 'CREATED',
      version: 1,
      weightKg: 2.5,
      dimensions: '30x20x15',
      amountMinorUnits: 50000,
      currency: 'LKR',
      rateVersion: 'RATE-2026-V1',
      pickupAddress: '123 Galle Road, Colombo 03',
      dropoffAddress: '45 Kandy Road, Gampaha',
      activeCourierSub: null,
      createdAt: '2026-10-26T10:05:00Z',
    }

    it('books parcel successfully with 201 Created and Idempotency-Key header', async () => {
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(mockParcelResponse), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      const idempotencyKey = '11111111-2222-3333-4444-555555555555'
      const parcel = await api.bookParcel(validBookingPayload, idempotencyKey)

      expect(parcel.parcelId).toBe('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11')
      expect(parcel.trackingNumber).toBe('SWD-20261026-A1B2C3')
      expect(parcel.currentStatus).toBe('CREATED')
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/parcels',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            Authorization: 'Bearer valid-test-token',
            'Idempotency-Key': idempotencyKey,
            'Content-Type': 'application/json',
          }),
          body: JSON.stringify(validBookingPayload),
        }),
      )
    })

    it('handles 409 Conflict when quote already consumed or idempotency mismatch', async () => {
      const problem = {
        title: 'Conflict',
        status: 409,
        detail: 'Quote already booked.',
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(problem), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.bookParcel(validBookingPayload)).rejects.toMatchObject({
        kind: 'conflict',
        message: 'Quote already booked.',
        statusCode: 409,
      })
    })

    it('handles 422 Unprocessable Entity when quote expired', async () => {
      const problem = {
        title: 'Unprocessable Entity',
        status: 422,
        detail: 'Quote expired. Request a new quote.',
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(problem), {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.bookParcel(validBookingPayload)).rejects.toMatchObject({
        kind: 'expired',
        message: 'Quote expired. Request a new quote.',
        statusCode: 422,
      })
    })

    it('handles 400 Bad Request with validation details', async () => {
      const problem = {
        title: 'Bad Request',
        status: 400,
        detail: 'recipientPhone must not be blank',
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(problem), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.bookParcel(validBookingPayload)).rejects.toMatchObject({
        kind: 'validation-error',
        message: 'recipientPhone must not be blank',
        statusCode: 400,
      })
    })

    it('handles missing auth token with auth-required error', async () => {
      const api = createDeliveryApi({
        getAccessToken: async () => null,
        fetch: mockFetch,
      })

      await expect(api.bookParcel(validBookingPayload)).rejects.toMatchObject({
        kind: 'auth-required',
      })
    })

    it('handles 403 Forbidden with ownership/role error', async () => {
      const problem = {
        title: 'Forbidden',
        status: 403,
        detail: 'Caller does not possess CUSTOMER role.',
      }
      mockFetch.mockResolvedValueOnce(
        new Response(JSON.stringify(problem), {
          status: 403,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createDeliveryApi({ getAccessToken, fetch: mockFetch })
      await expect(api.bookParcel(validBookingPayload)).rejects.toMatchObject({
        kind: 'forbidden',
        statusCode: 403,
      })
    })
  })
})
