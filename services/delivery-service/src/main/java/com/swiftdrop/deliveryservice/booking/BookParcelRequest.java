package com.swiftdrop.deliveryservice.booking;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
public record BookParcelRequest(@NotNull UUID quoteId,
 @NotBlank @Size(max=2000) String pickupAddress,
 @NotBlank @Size(max=2000) String dropoffAddress,
 @NotBlank @Size(max=255) String recipientName,
 @NotBlank @Pattern(regexp="^\\+94[0-9]{9}$", message="Phone must be in +94XXXXXXXXX format") String recipientPhone,
 @Size(max=2000) String deliveryNotes) {}
