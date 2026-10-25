package com.swiftdrop.communicationsservice.client;

/**
 * Permitted recipient contact details resolved from Accounts Service
 * in accordance with the reviewed notification policy (IF-GRPC-03, #110).
 *
 * Strictly omits unrestricted directory data, credentials, and internal accounts metadata.
 */
public record PermittedRecipientContact(
        String recipientSub,
        String email,
        String phoneNumber,
        String fullName,
        boolean isEligible,
        boolean emailPermitted,
        boolean smsPermitted,
        String status,
        String rejectionReason
) {
    public boolean hasPermittedEmail() {
        return emailPermitted && email != null && !email.isBlank();
    }

    public boolean hasPermittedSms() {
        return smsPermitted && phoneNumber != null && !phoneNumber.isBlank();
    }
}
