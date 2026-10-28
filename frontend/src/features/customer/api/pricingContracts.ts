/**
 * Authoritative contracts for SwiftDrop Pricing & Zones bounded context.
 * Grounded in:
 * - contracts/rest/openapi-pricing.yaml v1.0.0
 * - contracts/rest/problem-detail.yaml
 * - docs/contracts/SERVICE_INTERFACE_MATRIX.md (IF-REST-02)
 */

export type SizeCategory = 'SMALL' | 'MEDIUM' | 'LARGE' | 'OVERSIZED'

export interface QuoteRequest {
  pickupZone: string
  dropoffZone: string
  weightKg: number
  lengthCm: number
  widthCm: number
  heightCm: number
}

export interface QuoteResponse {
  quoteId: string
  rateVersion: string
  pickupZone: string
  dropoffZone: string
  weightKg: number
  volumetricWeightKg: number
  chargeableWeightKg: number
  sizeCategory: SizeCategory
  baseFeeMinorUnits: number
  weightSurchargeMinorUnits: number
  totalAmountMinorUnits: number
  currency: string
  createdAt: string
  expiresAt: string
  isExpired: boolean
}

export interface ZoneItem {
  zoneCode: string
  name: string
  description: string
  standardTransitHours: number
}

export interface ZoneListResponse {
  activeRateVersion: string
  zones: ZoneItem[]
}
