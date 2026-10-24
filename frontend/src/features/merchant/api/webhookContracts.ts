/**
 * Authoritative contracts mirroring contracts/rest/openapi-communications.yaml (v1.0.0)
 * and problem-detail.yaml for the Communications bounded context.
 */

export const SUPPORTED_WEBHOOK_EVENTS = ['parcel.status.changed.v1'] as const
export type SupportedWebhookEventType = typeof SUPPORTED_WEBHOOK_EVENTS[number]

export interface CreateWebhookSubscriptionRequest {
  httpsUrl: string
  allowedEventTypes: string[]
}

export interface WebhookSubscription {
  subscriptionId: string
  merchantId: string
  httpsUrl: string
  allowedEventTypes: string[]
  active: boolean
  createdAt: string
}

export interface WebhookSubscriptionCreated extends WebhookSubscription {
  /**
   * Shared secret for verifying X-SwiftDrop-Signature header.
   * Returned and displayed ONCE upon creation.
   */
  hmacSigningSecret: string
}

export interface WebhookSubscriptionListResponse {
  subscriptions: WebhookSubscription[]
}

export type WebhookDeliveryStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'EXHAUSTED'

export interface WebhookDeliveryItem {
  deliveryId: string
  eventId: string
  parcelId: string
  status: WebhookDeliveryStatus
  attemptCount: number
  lastAttemptAt: string
  lastHttpStatusCode?: number
  lastResponseTimeMs?: number
  errorSummary?: string | null
}

export interface WebhookDeliveryLogListResponse {
  totalElements: number
  deliveries: WebhookDeliveryItem[]
}

export interface ProblemDetail {
  type?: string
  title: string
  status: number
  detail?: string
  instance?: string
  code?: string
  timestamp?: string
  invalidParameters?: Array<{ field: string; message: string }>
}
