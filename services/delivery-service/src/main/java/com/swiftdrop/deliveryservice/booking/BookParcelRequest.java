package com.swiftdrop.deliveryservice.booking;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
public record BookParcelRequest(@NotNull UUID quoteId,
 @NotBlank @Size(max=2000) String pickupAddress,
 @NotBlank @Size(max=2000) String dropoffAddress,
 @NotBlank @Size(max=255) String recipientName,
 @NotBlank @Size(max=50) String recipientPhone,
 @Size(max=2000) String deliveryNotes) {}
