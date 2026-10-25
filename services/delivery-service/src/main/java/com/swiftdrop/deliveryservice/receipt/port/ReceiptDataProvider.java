package com.swiftdrop.deliveryservice.receipt.port;

import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;

import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for retrieving authoritative, canonical receipt data from trusted persistence/payment sources.
 */
public interface ReceiptDataProvider {

    /**
     * Resolves authoritative receipt data for the specified parcel ID.
     *
     * @param parcelId authoritative parcel identifier
     * @return Optional containing the validated receipt data, or empty if unknown/ineligible
     */
    Optional<ReceiptData> getAuthoritativeReceiptData(UUID parcelId);
}
