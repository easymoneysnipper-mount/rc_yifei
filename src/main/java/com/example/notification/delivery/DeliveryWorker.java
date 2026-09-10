package com.example.notification.delivery;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.NotificationRecord;
import com.example.notification.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final NotificationRepository repository;
    private final OutboundHttpClient httpClient;
    private final NotificationProperties properties;
    private final Executor deliveryExecutor;

    public DeliveryWorker(NotificationRepository repository, OutboundHttpClient httpClient,
                          NotificationProperties properties,
                          @Qualifier("deliveryExecutor") Executor deliveryExecutor) {
        this.repository = repository;
        this.httpClient = httpClient;
        this.properties = properties;
        this.deliveryExecutor = deliveryExecutor;
    }

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms:500}")
    public void poll() {
        Instant now = Instant.now();
        List<NotificationRecord> notifications = repository.claimDue(properties.worker().batchSize(), now,
                now.plusSeconds(properties.worker().leaseSeconds()));
        notifications.forEach(notification -> deliveryExecutor.execute(() -> deliver(notification)));
    }

    private void deliver(NotificationRecord notification) {
        DeliveryResult result = httpClient.send(notification);
        Instant now = Instant.now();
        if (result.success()) {
            repository.markSucceeded(notification.id(), notification.leaseToken(), now, result.statusCode());
            log.info("notification delivered id={} attempt={} status={}", notification.id(), notification.attempts(),
                    result.statusCode());
            return;
        }

        if (!result.retryable() || notification.attempts() >= properties.worker().maxAttempts()) {
            repository.markFailed(notification.id(), notification.leaseToken(), now,
                    result.statusCode() == 0 ? null : result.statusCode(), result.error());
            log.warn("notification permanently failed id={} attempt={} status={} error={}", notification.id(),
                    notification.attempts(), result.statusCode(), result.error());
            return;
        }

        Duration delay = result.retryAfter() == null ? backoff(notification.attempts()) : result.retryAfter();
        Duration max = Duration.ofMillis(properties.worker().backoffMaxMs());
        if (delay.compareTo(max) > 0) delay = max;
        repository.markRetry(notification.id(), notification.leaseToken(), now, now.plus(delay),
                result.statusCode() == 0 ? null : result.statusCode(), result.error());
        log.info("notification retry scheduled id={} attempt={} delayMs={} status={}", notification.id(),
                notification.attempts(), delay.toMillis(), result.statusCode());
    }

    private Duration backoff(int attempt) {
        long base = properties.worker().backoffBaseMs();
        long cap = properties.worker().backoffMaxMs();
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long exponential = base > cap / (1L << shift) ? cap : base * (1L << shift);
        long jitter = properties.worker().backoffJitterMs() == 0 ? 0
                : ThreadLocalRandom.current().nextLong(properties.worker().backoffJitterMs() + 1);
        return Duration.ofMillis(Math.min(cap, exponential + jitter));
    }
}
