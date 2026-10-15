import type { CurrentUserProfile } from '../features/customer/api/contracts'

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
