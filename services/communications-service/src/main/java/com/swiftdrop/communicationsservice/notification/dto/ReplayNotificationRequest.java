package com.swiftdrop.communicationsservice.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record ReplayNotificationRequest(
        @NotBlank(message = "Replay reason is mandatory")
        String reason
) {}
