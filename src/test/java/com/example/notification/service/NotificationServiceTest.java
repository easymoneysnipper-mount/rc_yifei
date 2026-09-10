package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.CreateNotificationRequest;
import com.example.notification.model.NotificationRecord;
import com.example.notification.model.NotificationStatus;
import com.example.notification.persistence.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationProperties properties = new NotificationProperties(
            new NotificationProperties.Limits(2048, 1024, 32, 4096),
            new NotificationProperties.Worker(1, 4, 500, 60, 3, 100, 10_000, 0),
            new NotificationProperties.Http(1000, 1000));
    private final NotificationService service = new NotificationService(repository, properties, new ObjectMapper());

    @Test
    void sameIdempotencyKeyReturnsExistingNotification() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var request = new CreateNotificationRequest("https://vendor.example/hook", Map.of("X-B", "2", "X-A", "1"),
                new ObjectMapper().createObjectNode().put("orderId", "1"));
        var existing = new NotificationRecord(id, "key", hashForTest(request), request.targetUrl(),
                "{\"X-A\":\"1\",\"X-B\":\"2\"}", "{\"orderId\":\"1\"}",
                NotificationStatus.PENDING, 0, now, null, null, null, null, now, now, null);
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(existing));

        var result = service.enqueue("key", request);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.response().id()).isEqualTo(id);
    }

    @Test
    void differentRequestWithSameKeyConflicts() {
        var existing = new NotificationRecord(UUID.randomUUID(), "key", "different", "https://vendor.example/hook",
                "{}", null, NotificationStatus.PENDING, 0, Instant.now(),
                null, null, null, null, Instant.now(), Instant.now(), null);
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.enqueue("key",
                new CreateNotificationRequest("https://vendor.example/hook", Map.of(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void rejectsNonHttpTarget() {
        assertThatThrownBy(() -> service.enqueue("key",
                new CreateNotificationRequest("file:///etc/passwd", Map.of(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTTP(S)");
    }

    private String hashForTest(CreateNotificationRequest request) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(request.targetUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("{\"X-A\":\"1\",\"X-B\":\"2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("{\"orderId\":\"1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
