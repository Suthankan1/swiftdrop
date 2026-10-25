package com.swiftdrop.deliveryservice.booking;

/**
 * Bounded feature contract for Delivery Booking.
 *
 * Owned and implemented under Issue #20 (Booking & Canonical Parcel Provider).
 */
public interface BookingContract {

    /**
     * Books a parcel using an accepted quote snapshot, persisting parcel aggregate
     * and initial status history in a single ACID transaction.
     */
    BookingResult bookParcel(BookParcelCommand command);
}
