export type ApiKeyScope = 'parcels:create' | 'parcels:read' | 'webhooks:manage'

export interface CreateApiKeyRequest {
  name: string
  scopes: string[]
  expiresInDays?: number
}

export interface ApiKeyCreatedResponse {
  keyId: string
  name: string
  keyPrefix: string
  plaintextKey: string
  scopes: string[]
  createdAt: string
  expiresAt: string
}

export interface ApiKeySummaryItem {
  keyId: string
  name: string
  keyPrefix: string
  scopes: string[]
  createdAt: string
  expiresAt: string
  isRevoked: boolean
}

export interface ApiKeyListResponse {
  keys: ApiKeySummaryItem[]
}

export type { ProblemDetail } from './webhookContracts'
