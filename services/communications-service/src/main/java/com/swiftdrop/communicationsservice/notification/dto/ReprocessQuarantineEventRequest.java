package com.swiftdrop.communicationsservice.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record ReprocessQuarantineEventRequest(
        @NotBlank(message = "Replay reason is mandatory")
        String reason,

        @NotBlank(message = "Corrected payload JSON is mandatory")
        String correctedPayloadJson
) {}
