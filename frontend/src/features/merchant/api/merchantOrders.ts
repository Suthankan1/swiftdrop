import {
  createDeliveryApi,
  DeliveryError,
} from '../../customer/api/delivery'
import type {
  ParcelDetailResponse,
  ParcelStatusHistoryItem,
  ParcelStatusHistoryPage,
} from '../../customer/api/deliveryContracts'

export {
  DeliveryError,
  type ParcelDetailResponse,
  type ParcelStatusHistoryItem,
  type ParcelStatusHistoryPage,
}

export interface MerchantOrdersApiOptions {
  getAccessToken: () => Promise<string | null>
  gatewayUrl?: string
  fetch?: typeof globalThis.fetch
}

export function createMerchantOrdersApi(options: MerchantOrdersApiOptions) {
  const deliveryApi = createDeliveryApi(options)

  return {
    /**
     * Retrieves authoritative parcel details with object-level merchant tenant verification (IF-REST-05).
     * Accessible by approved merchant owning the parcel.
     */
    async getOrder(parcelId: string, signal?: AbortSignal): Promise<ParcelDetailResponse> {
      return deliveryApi.getParcel(parcelId, signal)
    },

    /**
     * Retrieves append-only status transition history for an order with object-level verification.
     */
    async getOrderHistory(
      parcelId: string,
      page = 0,
      size = 20,
      signal?: AbortSignal,
    ): Promise<ParcelStatusHistoryPage> {
      return deliveryApi.getParcelHistory(parcelId, { page, size }, signal)
    },
  }
}
