/** Documented response from LOGIN_CONTRACT.md §4.2; IDs are opaque strings. */
export interface MerchantProfile {
  merchantId: string
  businessName: string
  registrationNumber: string
  // The complete business-status vocabulary is owned by Accounts (#5).
  status: string
}

export interface CurrentUserProfile {
  id: string
  keycloakSub: string
  email: string
  fullName: string
  phoneNumber: string
  roles: string[]
  merchantProfile: MerchantProfile | null
  createdAt: string
}
