package com.swiftdrop.deliveryservice.quote;
import com.swiftdrop.deliveryservice.booking.BookParcelRequest;
/**
 * Unresolved authorization boundary; no allow-all implementation is supplied.
 * Before a fresh booking, establish that the authenticated customer may use this quote
 * and that the submitted addresses agree with its priced route. Anonymous quote ownership
 * must not be inferred. Pricing/Accounts owners must approve the actual protocol first.
 */
public interface BookingAuthorization {
 void requireAllowed(String authenticatedSubject, BookParcelRequest request);
}
