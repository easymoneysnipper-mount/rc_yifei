package com.example.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Limits limits, Worker worker, Http http) {

    public record Limits(int maxUrlLength, int maxBodyBytes, int maxHeaders, int maxHeaderValueLength) {
    }

    public record Worker(int concurrency, int batchSize, long pollIntervalMs, long leaseSeconds,
                         int maxAttempts, long backoffBaseMs, long backoffMaxMs, long backoffJitterMs) {
    }

    public record Http(long connectTimeoutMs, long requestTimeoutMs) {
    }
}
