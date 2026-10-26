import { describe, expect, it, vi } from 'vitest'
import {
  createCourierProofApi,
  isAssignmentResponse,
  isParcelDetailResponse,
  isProofDetailResponse,
  isProofResponse,
} from './proof'

import type {
  AssignmentResponse,
  ParcelDetailResponse,
  ProofDetailResponse,
  ProofResponse,
} from './proofContracts'

const MOCK_PARCEL_ID = '00000000-0000-0000-0000-000000000001'
const MOCK_PROOF_ID = '00000000-0000-0000-0000-000000000099'
const MOCK_ASSIGNMENT_ID = '00000000-0000-0000-0000-000000000077'

const syntheticAssignment: AssignmentResponse = {
  assignmentId: MOCK_ASSIGNMENT_ID,
  parcelId: MOCK_PARCEL_ID,
  courierSub: 'courier-test-sub',
  active: true,
  assignedAt: '2026-10-26T10:00:00Z',
  assignedBy: 'dispatcher-test-sub',
  newParcelVersion: 3,
}

const syntheticParcel: ParcelDetailResponse = {
  parcelId: MOCK_PARCEL_ID,
  trackingNumber: 'SWD-20261026-0001',
  createdBySub: 'customer-test-sub',
  merchantId: null,
  currentStatus: 'OUT_FOR_DELIVERY',
  version: 3,
  weightKg: 2.5,
  dimensions: '10x10x10',
  amountMinorUnits: 1500,
  currency: 'LKR',
  rateVersion: 'v1.0',
  pickupAddress: 'Colombo 03',
  dropoffAddress: 'Gampaha',
  activeCourierSub: 'courier-test-sub',
  createdAt: '2026-10-26T08:00:00Z',
}

const syntheticProofResponse: ProofResponse = {
  proofId: MOCK_PROOF_ID,
  parcelId: MOCK_PARCEL_ID,
  uploadedBySub: 'courier-test-sub',
  uploadedAt: '2026-10-26T12:00:00Z',
}

const syntheticProofDetail: ProofDetailResponse = {
  proofId: MOCK_PROOF_ID,
  parcelId: MOCK_PARCEL_ID,
  objectReference: 'proof/2026/10/26/secure-uuid.jpg',
  evidenceType: 'PHOTO',
  contentType: 'image/jpeg',
  contentLength: 1048576,
  recipientName: 'Jane Perera',
  deliveryNote: 'Handed directly to recipient',
  uploadedBySub: 'courier-test-sub',
  uploadedAt: '2026-10-26T12:00:00Z',
}

describe('CourierProofApi client', () => {
  const getAccessToken = vi.fn(async () => 'valid-bearer-token')

  it('fails with auth-required if access token is missing or null', async () => {
    const api = createCourierProofApi({
      getAccessToken: vi.fn(async () => null),
    })

    await expect(api.getMyAssignments()).rejects.toMatchObject({
      name: 'CourierProofError',
      kind: 'auth-required',
    })
  })

  describe('getMyAssignments', () => {
    it('fetches assignments with Authorization Bearer header', async () => {
      const mockFetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        expect(url).toContain('/api/v1/assignments/my?activeOnly=true')
        expect((init?.headers as Record<string, string>)?.Authorization).toBe('Bearer valid-bearer-token')
        return new Response(JSON.stringify({ items: [syntheticAssignment] }), { status: 200 })
      })

      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      const result = await api.getMyAssignments(true)
      expect(result).toHaveLength(1)
      expect(result[0]!.assignmentId).toBe(MOCK_ASSIGNMENT_ID)
    })

    it('handles direct array payload fallback', async () => {
      const mockFetch = vi.fn(async () => {
        return new Response(JSON.stringify([syntheticAssignment]), { status: 200 })
      })

      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      const result = await api.getMyAssignments()
      expect(result).toHaveLength(1)
    })

    it('maps 401 to auth-required and 403 to forbidden', async () => {
      const mock401 = vi.fn(async () => new Response(null, { status: 401 }))
      const api401 = createCourierProofApi({ getAccessToken, fetch: mock401 as unknown as typeof fetch })
      await expect(api401.getMyAssignments()).rejects.toMatchObject({ kind: 'auth-required' })

      const mock403 = vi.fn(async () => new Response(null, { status: 403 }))
      const api403 = createCourierProofApi({ getAccessToken, fetch: mock403 as unknown as typeof fetch })
      await expect(api403.getMyAssignments()).rejects.toMatchObject({ kind: 'forbidden' })
    })
  })

  describe('getParcel', () => {
    it('fetches parcel details and maps schema', async () => {
      const mockFetch = vi.fn(async (url: string | URL | Request) => {
        expect(url).toContain(`/api/v1/parcels/${MOCK_PARCEL_ID}`)
        return new Response(JSON.stringify(syntheticParcel), { status: 200 })
      })

      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      const parcel = await api.getParcel(MOCK_PARCEL_ID)
      expect(parcel.trackingNumber).toBe('SWD-20261026-0001')
      expect(parcel.currentStatus).toBe('OUT_FOR_DELIVERY')
    })

    it('maps 404 to not-found', async () => {
      const mockFetch = vi.fn(async () => new Response(null, { status: 404 }))
      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      await expect(api.getParcel(MOCK_PARCEL_ID)).rejects.toMatchObject({ kind: 'not-found' })
    })
  })

  describe('getProofMetadata', () => {
    it('fetches proof metadata for authorized parcel', async () => {
      const mockFetch = vi.fn(async (url: string | URL | Request) => {
        expect(url).toContain(`/api/v1/parcels/${MOCK_PARCEL_ID}/proof`)
        return new Response(JSON.stringify(syntheticProofDetail), { status: 200 })
      })

      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      const proof = await api.getProofMetadata(MOCK_PARCEL_ID)
      expect(proof.proofId).toBe(MOCK_PROOF_ID)
      expect(proof.recipientName).toBe('Jane Perera')
    })

    it('maps 404 to not-found when no proof exists yet', async () => {
      const mockFetch = vi.fn(async () => new Response(null, { status: 404 }))
      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      await expect(api.getProofMetadata(MOCK_PARCEL_ID)).rejects.toMatchObject({ kind: 'not-found' })
    })
  })

  describe('getProofContent', () => {
    it('streams binary image content without public S3/MinIO URLs', async () => {
      const fakeBinary = new Uint8Array([0xff, 0xd8, 0xff, 0xe0])
      const mockFetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        expect(url).toContain(`/api/v1/parcels/${MOCK_PARCEL_ID}/proof/content`)
        expect(url.toString()).not.toContain('minio')
        expect(url.toString()).not.toContain('s3')
        expect((init?.headers as Record<string, string>)?.Authorization).toBe('Bearer valid-bearer-token')
        return new Response(fakeBinary, {
          status: 200,
          headers: { 'Content-Type': 'image/jpeg' },
        })
      })

      const api = createCourierProofApi({ getAccessToken, fetch: mockFetch as unknown as typeof fetch })
      const blob = await api.getProofContent(MOCK_PARCEL_ID)
      expect(blob.size).toBe(4)
    })
  })

  describe('uploadProof with genuine XHR progress', () => {
    function createMockXhr(statusCode: number, responseBody: string, options?: { triggerProgress?: boolean; error?: boolean; timeout?: boolean }) {
      return function mockXhrFactory(): XMLHttpRequest {
        const headers: Record<string, string> = {}
        const uploadListeners: Record<string, (e: ProgressEvent) => void> = {}
        let onload: (() => void) | null = null
        let onerror: (() => void) | null = null
        let ontimeout: (() => void) | null = null

        const xhr = {
          open: vi.fn(),
          setRequestHeader: vi.fn((k: string, v: string) => {
            headers[k] = v
          }),
          send: vi.fn((data: FormData) => {
            // Verify formData fields
            expect(data).toBeInstanceOf(FormData)

            if (options?.triggerProgress && uploadListeners.progress) {
              uploadListeners.progress({
                lengthComputable: true,
                loaded: 50,
                total: 100,
              } as ProgressEvent)
              uploadListeners.progress({
                lengthComputable: true,
                loaded: 100,
                total: 100,
              } as ProgressEvent)
            }

            setTimeout(() => {
              if (options?.timeout && ontimeout) {
                ontimeout()
                return
              }
              if (options?.error && onerror) {
                onerror()
                return
              }
              (xhr as unknown as { status: number }).status = statusCode
              ;(xhr as unknown as { responseText: string }).responseText = responseBody
              if (onload) onload()
            }, 0)
          }),

          abort: vi.fn(),
          status: 0,
          responseText: '',
          upload: {
            set onprogress(handler: (e: ProgressEvent) => void) {
              uploadListeners.progress = handler
            },
          },
          set onload(h: () => void) {
            onload = h
          },
          set onerror(h: () => void) {
            onerror = h
          },
          set ontimeout(h: () => void) {
            ontimeout = h
          },
        } as unknown as XMLHttpRequest

        return xhr
      }
    }

    it('uploads proof multipart form data and fires real progress events', async () => {
      const file = new File([new Uint8Array(1024)], 'delivery.jpg', { type: 'image/jpeg' })
      const progressSpy = vi.fn()

      const xhrFactory = createMockXhr(201, JSON.stringify(syntheticProofResponse), { triggerProgress: true })
      const api = createCourierProofApi({ getAccessToken, xhrFactory })

      const response = await api.uploadProof({
        parcelId: MOCK_PARCEL_ID,
        file,
        recipientName: 'Jane Perera',
        deliveryNote: 'Gate bell rung',
        onProgress: progressSpy,
      })

      expect(response.proofId).toBe(MOCK_PROOF_ID)
      expect(progressSpy).toHaveBeenCalledWith(50)
      expect(progressSpy).toHaveBeenCalledWith(100)
    })

    it('handles 400 Bad Request (validation failure)', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const errorJson = JSON.stringify({ title: 'Invalid Proof Parameters', status: 400, detail: 'photoObjectKey is required' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(400, errorJson) })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'validation-failed',
        statusCode: 400,
      })
    })

    it('handles 401 Unauthorized without silent retry', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(401, 'Unauthorized') })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'auth-required',
        statusCode: 401,
      })
    })

    it('handles 403 Forbidden', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(403, 'Forbidden') })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'forbidden',
        statusCode: 403,
      })
    })

    it('handles 409 Conflict (proof already recorded)', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(409, 'Conflict') })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'conflict',
        statusCode: 409,
      })
    })

    it('handles 413 Payload Too Large', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(413, 'Too large') })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'payload-too-large',
        statusCode: 413,
      })
    })

    it('handles 422 Illegal Lifecycle Stage', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const errorJson = JSON.stringify({ title: 'Illegal Lifecycle Stage', status: 422, detail: 'Cannot upload proof for terminal parcel' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(422, errorJson) })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'illegal-stage',
        statusCode: 422,
      })
    })

    it('handles 503 Service Unavailable and 504 Gateway Timeout', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api503 = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(503, 'Unavailable') })
      await expect(api503.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'service-unavailable',
        statusCode: 503,
      })

      const api504 = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(504, 'Timeout') })
      await expect(api504.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'storage-timeout',
        statusCode: 504,
      })
    })

    it('handles network failure as ambiguous-failure to prevent blind POST retries', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(0, '', { error: true }) })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'ambiguous-failure',
      })
    })

    it('handles timeout as ambiguous-failure', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const api = createCourierProofApi({ getAccessToken, xhrFactory: createMockXhr(0, '', { timeout: true }) })

      await expect(api.uploadProof({ parcelId: MOCK_PARCEL_ID, file })).rejects.toMatchObject({
        kind: 'ambiguous-failure',
      })
    })

    it('supports AbortSignal to cancel upload cleanly', async () => {
      const file = new File([new Uint8Array(100)], 'test.jpg', { type: 'image/jpeg' })
      const controller = new AbortController()

      const api = createCourierProofApi({
        getAccessToken,
        xhrFactory: () => {
          return {
            open: vi.fn(),
            setRequestHeader: vi.fn(),
            send: vi.fn(),
            abort: vi.fn(),
            upload: {},
          } as unknown as XMLHttpRequest
        },
      })

      const uploadPromise = api.uploadProof({
        parcelId: MOCK_PARCEL_ID,
        file,
        signal: controller.signal,
      })

      controller.abort()

      await expect(uploadPromise).rejects.toMatchObject({
        kind: 'ambiguous-failure',
        message: expect.stringMatching(/abort|cancel/i),
      })

    })
  })

  describe('schema type guards', () => {
    it('validates proof response guard', () => {
      expect(isProofResponse(syntheticProofResponse)).toBe(true)
      expect(isProofResponse({ proofId: '123' })).toBe(false)
      expect(isProofResponse(null)).toBe(false)
    })

    it('validates proof detail guard', () => {
      expect(isProofDetailResponse(syntheticProofDetail)).toBe(true)
      expect(isProofDetailResponse({ proofId: '123' })).toBe(false)
    })

    it('validates assignment guard', () => {
      expect(isAssignmentResponse(syntheticAssignment)).toBe(true)
      expect(isAssignmentResponse({ assignmentId: '123' })).toBe(false)
    })

    it('validates parcel detail guard', () => {
      expect(isParcelDetailResponse(syntheticParcel)).toBe(true)
      expect(isParcelDetailResponse({ parcelId: '123' })).toBe(false)
    })
  })
})
