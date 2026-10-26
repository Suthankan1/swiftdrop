import { describe, expect, it, vi } from 'vitest'
import {
  createPricingApi,
  isQuoteResponse,
  isSizeCategory,
  isZoneItem,
  isZoneListResponse,
  PricingError,
} from './pricing'
import type { QuoteRequest, QuoteResponse, ZoneListResponse } from './pricingContracts'

describe('Pricing Type Guards', () => {
  it('validates SizeCategory enum values', () => {
    expect(isSizeCategory('SMALL')).toBe(true)
    expect(isSizeCategory('MEDIUM')).toBe(true)
    expect(isSizeCategory('LARGE')).toBe(true)
    expect(isSizeCategory('OVERSIZED')).toBe(true)
    expect(isSizeCategory('EXTRA_LARGE')).toBe(false)
    expect(isSizeCategory(123)).toBe(false)
    expect(isSizeCategory(null)).toBe(false)
  })

  it('validates ZoneItem structure', () => {
    const valid = {
      zoneCode: 'ZONE_1',
      name: 'Colombo',
      description: 'Intra-city Colombo',
      standardTransitHours: 24,
    }
    expect(isZoneItem(valid)).toBe(true)
    expect(isZoneItem({ ...valid, zoneCode: 123 })).toBe(false)
    expect(isZoneItem({ ...valid, standardTransitHours: '24' })).toBe(false)
    expect(isZoneItem(null)).toBe(false)
  })

  it('validates ZoneListResponse structure', () => {
    const validList: ZoneListResponse = {
      activeRateVersion: 'RATE-2026-V1',
      zones: [
        {
          zoneCode: 'ZONE_1',
          name: 'Colombo Municipal',
          description: 'Intra-city delivery',
          standardTransitHours: 24,
        },
      ],
    }
    expect(isZoneListResponse(validList)).toBe(true)
    expect(isZoneListResponse({ ...validList, zones: [{ invalid: true }] })).toBe(false)
    expect(isZoneListResponse(null)).toBe(false)
  })

  it('validates QuoteResponse structure', () => {
    const validQuote: QuoteResponse = {
      quoteId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
      rateVersion: 'RATE-2026-V1',
      pickupZone: 'ZONE_1',
      dropoffZone: 'ZONE_2',
      weightKg: 2.5,
      volumetricWeightKg: 1.8,
      chargeableWeightKg: 2.5,
      sizeCategory: 'MEDIUM',
      baseFeeMinorUnits: 35000,
      weightSurchargeMinorUnits: 15000,
      totalAmountMinorUnits: 50000,
      currency: 'LKR',
      createdAt: '2026-10-26T10:00:00Z',
      expiresAt: '2026-10-26T10:15:00Z',
      isExpired: false,
    }
    expect(isQuoteResponse(validQuote)).toBe(true)
    expect(isQuoteResponse({ ...validQuote, sizeCategory: 'UNKNOWN' })).toBe(false)
    expect(isQuoteResponse({ ...validQuote, totalAmountMinorUnits: '50000' })).toBe(false)
    expect(isQuoteResponse(null)).toBe(false)
  })
})

describe('createPricingApi', () => {
  const sampleQuoteRequest: QuoteRequest = {
    pickupZone: 'ZONE_1',
    dropoffZone: 'ZONE_2',
    weightKg: 2.5,
    lengthCm: 30,
    widthCm: 20,
    heightCm: 15,
  }

  const sampleQuoteResponse: QuoteResponse = {
    quoteId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
    rateVersion: 'RATE-2026-V1',
    pickupZone: 'ZONE_1',
    dropoffZone: 'ZONE_2',
    weightKg: 2.5,
    volumetricWeightKg: 1.8,
    chargeableWeightKg: 2.5,
    sizeCategory: 'MEDIUM',
    baseFeeMinorUnits: 35000,
    weightSurchargeMinorUnits: 15000,
    totalAmountMinorUnits: 50000,
    currency: 'LKR',
    createdAt: '2026-10-26T10:00:00Z',
    expiresAt: '2026-10-26T10:15:00Z',
    isExpired: false,
  }

  const sampleZonesResponse: ZoneListResponse = {
    activeRateVersion: 'RATE-2026-V1',
    zones: [
      {
        zoneCode: 'ZONE_1',
        name: 'Colombo Municipal',
        description: 'Intra-city delivery',
        standardTransitHours: 24,
      },
      {
        zoneCode: 'ZONE_2',
        name: 'Western Province Suburbs',
        description: 'Suburban regional delivery',
        standardTransitHours: 48,
      },
    ],
  }

  describe('listZones', () => {
    it('successfully retrieves active delivery zones without authentication', async () => {
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(sampleZonesResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createPricingApi({
        gatewayUrl: 'http://localhost:8080',
        fetch: mockFetch,
      })

      const result = await api.listZones()
      expect(result.activeRateVersion).toBe('RATE-2026-V1')
      expect(result.zones).toHaveLength(2)
      expect(result.zones[0]?.zoneCode).toBe('ZONE_1')
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/zones',
        expect.objectContaining({ method: 'GET' }),
      )
    })

    it('attaches optional bearer token when available', async () => {
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(sampleZonesResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createPricingApi({
        getAccessToken: async () => 'mock-jwt-token',
        gatewayUrl: 'http://localhost:8080',
        fetch: mockFetch,
      })

      await api.listZones()
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/zones',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-jwt-token',
          }),
        }),
      )
    })

    it('throws unavailable error on network failure', async () => {
      const mockFetch = vi.fn().mockRejectedValue(new Error('Network disconnected'))
      const api = createPricingApi({ fetch: mockFetch })

      await expect(api.listZones()).rejects.toThrow(PricingError)
      await expect(api.listZones()).rejects.toMatchObject({ kind: 'unavailable' })
    })

    it('throws invalid-response when payload does not conform to schema', async () => {
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ notZones: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      const api = createPricingApi({ fetch: mockFetch })

      await expect(api.listZones()).rejects.toThrow(PricingError)
      await expect(api.listZones()).rejects.toMatchObject({ kind: 'invalid-response' })
    })
  })

  describe('calculateQuote', () => {
    it('successfully posts quote parameters and returns calculated QuoteResponse', async () => {
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(sampleQuoteResponse), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createPricingApi({
        gatewayUrl: 'http://localhost:8080',
        fetch: mockFetch,
      })

      const quote = await api.calculateQuote(sampleQuoteRequest)
      expect(quote.quoteId).toBe('f47ac10b-58cc-4372-a567-0e02b2c3d479')
      expect(quote.totalAmountMinorUnits).toBe(50000)
      expect(quote.currency).toBe('LKR')
      expect(quote.sizeCategory).toBe('MEDIUM')
      expect(mockFetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1/quotes',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify(sampleQuoteRequest),
        }),
      )
    })

    it('handles 400 Bad Request with ProblemDetail as invalid-input', async () => {
      const problem = {
        title: 'Bad Request',
        status: 400,
        detail: 'weightKg must not exceed 30.00 kg',
      }
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(problem), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createPricingApi({ fetch: mockFetch })

      await expect(api.calculateQuote(sampleQuoteRequest)).rejects.toMatchObject({
        kind: 'invalid-input',
        message: 'weightKg must not exceed 30.00 kg',
        statusCode: 400,
      })
    })

    it('handles 422 Unprocessable Entity with ProblemDetail as unprocessable-zone', async () => {
      const problem = {
        title: 'Unprocessable Entity',
        status: 422,
        detail: 'Zone combination ZONE_1 to ZONE_9 is unserviceable.',
      }
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(problem), {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createPricingApi({ fetch: mockFetch })

      await expect(api.calculateQuote(sampleQuoteRequest)).rejects.toMatchObject({
        kind: 'unprocessable-zone',
        message: 'Zone combination ZONE_1 to ZONE_9 is unserviceable.',
        statusCode: 422,
      })
    })
  })

  describe('getQuoteById', () => {
    it('successfully retrieves quote by UUID', async () => {
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(sampleQuoteResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const api = createPricingApi({ fetch: mockFetch })
      const quote = await api.getQuoteById('f47ac10b-58cc-4372-a567-0e02b2c3d479')
      expect(quote.quoteId).toBe('f47ac10b-58cc-4372-a567-0e02b2c3d479')
    })

    it('handles 404 Not Found as not-found error', async () => {
      const problem = {
        title: 'Not Found',
        status: 404,
        detail: 'Quote not found.',
      }
      const mockFetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(problem), {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      )

      const api = createPricingApi({ fetch: mockFetch })
      await expect(
        api.getQuoteById('f47ac10b-58cc-4372-a567-0e02b2c3d479'),
      ).rejects.toMatchObject({
        kind: 'not-found',
        statusCode: 404,
      })
    })
  })
})
