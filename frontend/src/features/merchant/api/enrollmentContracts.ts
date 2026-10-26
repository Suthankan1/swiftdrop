import type { MerchantApprovalStatus } from './merchant'

export interface MerchantRegistrationRequest {
  businessName: string
  registrationNumber: string
}

export interface MerchantProfileResponse {
  id: string
  keycloakSub: string
  businessName: string
  registrationNumber: string
  status: MerchantApprovalStatus
  createdAt: string
  updatedAt: string
}
