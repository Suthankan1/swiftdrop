package com.swiftdrop.deliveryservice.quote;
import java.util.UUID;
/**
 * Delivery-owned adapter seam, not a new Pricing RPC contract. No production implementation.
 * The eventual adapter must obtain expected rate/amount/currency authoritatively before invoking
 * the existing ValidateQuote RPC. How it obtains them is BLOCKED, not assumed here.
 * Use authenticated internal gRPC, verified actor delegation and a bounded deadline.
 * This operation must be retry-safe; no remote reservation or consumption is assumed.
 */
public interface BookingQuoteProvider {
 QuoteValidation validate(UUID quoteId, String authenticatedSubject);
}
