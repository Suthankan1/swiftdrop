import type { CurrentUserProfile } from '../features/customer/api/contracts'
import type { StaffProfile } from '../features/staff/api/contracts'

/** Synthetic test data only; never imported by the running application. */
export const customerFixture: CurrentUserProfile = {
  id: 'test-profile-customer',
  keycloakSub: 'test-subject-customer',
  email: 'synthetic-customer@demo.invalid',
  fullName: 'Synthetic Customer',
  phoneNumber: '+94000000000',
  roles: ['CUSTOMER'],
  merchantProfile: null,
  createdAt: '2026-10-15T10:00:00Z',
}

export const merchantFixture: CurrentUserProfile = {
  ...customerFixture,
  id: 'test-profile-merchant',
  keycloakSub: 'test-subject-merchant',
  email: 'synthetic-merchant@demo.invalid',
  fullName: 'Synthetic Merchant',
  roles: ['MERCHANT'],
  merchantProfile: {
    merchantId: 'test-merchant',
    businessName: 'Synthetic Test Business',
    registrationNumber: 'TEST-ONLY-001',
    status: 'PENDING',
  },
}

export const courierStaffFixture: StaffProfile = {
  id: '11111111-1111-1111-1111-111111111111',
  profileId: '11111111-1111-1111-1111-111111111111',
  staffId: 'STF-2026-0042',
  keycloakSub: 'courier-test-subject',
  displayName: 'Kamal Perera',
  email: 'courier.kamal@swiftdrop.test',
  phoneNumber: '+94770000001',
  role: 'COURIER',
  department: 'FIELD_LOGISTICS',
  assignedZone: 'ZONE-WEST-COLOMBO',
  status: 'ACTIVE',
  businessStatus: 'ACTIVE',
  createdAt: '2026-10-18T08:30:00Z',
  updatedAt: '2026-10-18T08:30:00Z',
}

export const dispatcherStaffFixture: StaffProfile = {
  id: '22222222-2222-2222-2222-222222222222',
  profileId: '22222222-2222-2222-2222-222222222222',
  staffId: 'STF-2026-0010',
  keycloakSub: 'synthetic-dispatcher-subject',
  displayName: 'Nimal Silva',
  email: 'dispatcher.nimal@swiftdrop.test',
  phoneNumber: '+94770000002',
  role: 'DISPATCHER',
  department: 'DISPATCH_OPERATIONS',
  assignedZone: null,
  status: 'ACTIVE',
  businessStatus: 'ACTIVE',
  createdAt: '2026-10-18T08:30:00Z',
  updatedAt: '2026-10-18T08:30:00Z',
}

export const adminStaffFixture: StaffProfile = {
  id: '33333333-3333-3333-3333-333333333333',
  profileId: '33333333-3333-3333-3333-333333333333',
  staffId: 'STF-2026-0001',
  keycloakSub: 'synthetic-admin-subject',
  displayName: 'John Admin',
  email: 'admin.john@swiftdrop.test',
  phoneNumber: '+94770000003',
  role: 'ADMIN',
  department: 'SYSTEM_OPERATIONS',
  assignedZone: null,
  status: 'ACTIVE',
  businessStatus: 'ACTIVE',
  createdAt: '2026-10-18T08:30:00Z',
  updatedAt: '2026-10-18T08:30:00Z',
}
