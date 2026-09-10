package com.example.notification.delivery;

import java.time.Duration;

public record DeliveryResult(boolean success, boolean retryable, int statusCode, String error, Duration retryAfter) {

    public static DeliveryResult success(int statusCode) {
        return new DeliveryResult(true, false, statusCode, null, null);
    }

    public static DeliveryResult failure(boolean retryable, int statusCode, String error, Duration retryAfter) {
        return new DeliveryResult(false, retryable, statusCode, error, retryAfter);
    }
}
