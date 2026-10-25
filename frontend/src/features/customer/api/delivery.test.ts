import { describe, expect, it, vi, beforeEach } from 'vitest'
import {
  createDeliveryApi,
  DeliveryError,
  sanitizeReceiptFilename,
  triggerBlobDownload,
} from './delivery'
import type { ParcelDetailResponse, PublicTrackingResponse } from './deliveryContracts'

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
    it('retrieves public tracking with timeline checkpoints', async () => {
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
      const tracking = await api.getTracking('SWD-20261025-ABCD')

      expect(tracking.trackingNumber).toBe('SWD-20261025-ABCD')
      expect(tracking.timeline).toHaveLength(2)
      expect(tracking.timeline[0]?.status).toBe('CREATED')
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
})
