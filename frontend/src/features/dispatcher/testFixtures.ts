import type { DispatcherDashboard } from './api/dispatcher'

export const dashboardFixture: DispatcherDashboard = {
  unassignedParcels: [{ parcelId: 'parcel-1', trackingNumber: 'SWD-TEST-1', pickupZone: 'CMB', dropoffZone: 'GAL', weightKg: 2, sizeCategory: 'SMALL', createdAt: '2026-10-26T01:00:00Z', version: 4 }],
  pageInfo: { currentPage: 0, pageSize: 20, totalElements: 21, totalPages: 2, hasNext: true },
  availableCouriers: [
    { courierSub: 'courier-1', fullName: 'Test Courier', activeAssignmentsCount: 2, maxCapacity: 5, eligible: true },
    { courierSub: 'courier-2', fullName: 'Inactive Courier', activeAssignmentsCount: 0, maxCapacity: 5, eligible: false },
  ],
}
export const assignmentFixture = { assignmentId: 'assignment-1', parcelId: 'parcel-1', courierSub: 'courier-1', active: true, assignedAt: '2026-10-26T02:00:00Z', assignedBy: 'dispatcher-1', newParcelVersion: 5 }
