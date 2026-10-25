package com.swiftdrop.deliveryservice.booking;
import java.time.Instant;
import java.util.UUID;
/** Internal foundation result, NOT the public ParcelDetailResponse (dimensions/weight unresolved). */
public record ParcelBooking(UUID parcelId, String trackingNumber, String createdBySub,
 UUID quoteId, String currentStatus, long version, long amountMinorUnits, String currency,
 String rateVersion, String pickupAddress, String dropoffAddress, Instant createdAt) {}
