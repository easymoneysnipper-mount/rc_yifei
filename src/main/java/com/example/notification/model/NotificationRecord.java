package com.example.notification.model;

import java.time.Instant;
import java.util.UUID;

public record NotificationRecord(
        UUID id,
        String idempotencyKey,
        String requestHash,
        String targetUrl,
        String headersJson,
        String bodyJson,
        NotificationStatus status,
        int attempts,
        Instant nextAttemptAt,
        UUID leaseToken,
        Instant leaseUntil,
        Integer lastHttpStatus,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
