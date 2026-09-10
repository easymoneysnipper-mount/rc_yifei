package com.example.notification.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryResultTest {

    @Test
    void successIsNotRetryable() {
        com.example.notification.delivery.DeliveryResult result = com.example.notification.delivery.DeliveryResult.success(204);

        assertThat(result.success()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(result.statusCode()).isEqualTo(204);
    }

    @Test
    void retryResultCarriesRetryAfter() {
        com.example.notification.delivery.DeliveryResult result = com.example.notification.delivery.DeliveryResult.failure(true, 429, "rate limited", Duration.ofSeconds(2));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }
}
