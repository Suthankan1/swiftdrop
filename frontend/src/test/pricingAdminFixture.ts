import type { Card, Page, Rule, Zone } from '../features/admin/api/pricingAdmin'
// Synthetic provider DTOs. This file is never imported by production features.
export const zone: Zone = { id: '10000000-0000-0000-0000-000000000001', zoneCode: 'ZONE_1', name: 'Synthetic zone', description: 'Synthetic district', standardTransitHours: 24, active: true, revision: 3, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z' }
export const draft: Card = { id: '20000000-0000-0000-0000-000000000002', versionCode: 'SYNTHETIC-V2', currency: 'LKR', status: 'DRAFT', effectiveFrom: '2020-01-01T00:00:00Z', effectiveTo: null, description: '', revision: 7, createdAt: '2026-10-02T00:00:00Z', activatedAt: null, archivedAt: null }
export const active: Card = { ...draft, id: '30000000-0000-0000-0000-000000000003', versionCode: 'SYNTHETIC-V1', status: 'ACTIVE', revision: 2 }
export const rule: Rule = { id: '40000000-0000-0000-0000-000000000004', rateVersionId: draft.id, pickupZoneCode: zone.zoneCode, dropoffZoneCode: zone.zoneCode, sizeCategory: 'SMALL', minWeightKg: .01, maxWeightKg: 1, baseFeeMinorUnits: '35000', perKgSurchargeMinorUnits: '15000', weightThresholdKg: 0, revision: draft.revision }
export function pricingPage<T>(items: T[], page = 0, totalElements = items.length, size = 20): Page<T> { return { items, page, size, totalElements } }
