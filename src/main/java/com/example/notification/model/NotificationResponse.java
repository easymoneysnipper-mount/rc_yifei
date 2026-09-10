package com.example.notification.model;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationStatus status,
        int attempts,
        Integer lastHttpStatus,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant nextAttemptAt,
        String statusUrl
) {

    public static NotificationResponse from(NotificationRecord notification) {
        return new NotificationResponse(notification.id(), notification.status(), notification.attempts(),
                notification.lastHttpStatus(), notification.lastError(), notification.createdAt(),
                notification.updatedAt(), notification.completedAt(), notification.nextAttemptAt(),
                "/v1/notifications/" + notification.id());
    }
}
