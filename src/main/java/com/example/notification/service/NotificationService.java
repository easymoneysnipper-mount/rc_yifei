package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.CreateNotificationRequest;
import com.example.notification.model.EnqueueResult;
import com.example.notification.model.NotificationRecord;
import com.example.notification.model.NotificationResponse;
import com.example.notification.model.NotificationStatus;
import com.example.notification.persistence.NotificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repository, NotificationProperties properties,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public EnqueueResult enqueue(String idempotencyKey, CreateNotificationRequest request) {
        validateKey(idempotencyKey);
        validateRequest(request);
        String headersJson = writeJson(request.headers() == null ? Map.of() : new TreeMap<>(request.headers()));
        String bodyJson = request.body() == null ? null : writeJson(request.body());
        String requestHash = hash(request.targetUrl(), headersJson, bodyJson);

        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), requestHash);
        }

        Instant now = Instant.now();
        NotificationRecord notification = new NotificationRecord(UUID.randomUUID(), idempotencyKey, requestHash,
                request.targetUrl(), headersJson, bodyJson, NotificationStatus.PENDING, 0, now,
                null, null, null, null, now, now, null);
        try {
            return new EnqueueResult(NotificationResponse.from(repository.insert(notification)), true);
        } catch (DuplicateKeyException race) {
            NotificationRecord existingAfterRace = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return sameOrConflict(existingAfterRace, requestHash);
        }
    }

    public NotificationResponse get(UUID id) {
        return repository.findById(id)
                .map(NotificationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found"));
    }

    private EnqueueResult sameOrConflict(NotificationRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used for a different request");
        }
        return new EnqueueResult(NotificationResponse.from(existing), false);
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required and must be at most 128 characters");
        }
    }

    private void validateRequest(CreateNotificationRequest request) {
        URI uri;
        try {
            uri = URI.create(request.targetUrl());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetUrl is not a valid URI");
        }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || request.targetUrl().length() > properties.limits().maxUrlLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetUrl must be a bounded absolute HTTP(S) URL");
        }

        if (request.headers() != null) {
            if (request.headers().size() > properties.limits().maxHeaders()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many headers");
            }
            request.headers().forEach((name, value) -> {
                if (name == null || name.isBlank() || value == null || value.length() > properties.limits().maxHeaderValueLength()
                        || name.contains("\r") || name.contains("\n") || value.contains("\r") || value.contains("\n")
                        || isReservedHeader(name)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid header");
                }
            });
        }

        if (request.body() != null) {
            int size = writeJson(request.body()).getBytes(StandardCharsets.UTF_8).length;
            if (size > properties.limits().maxBodyBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "body is too large");
            }
        }
    }

    private boolean isReservedHeader(String name) {
        return name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")
                || name.equalsIgnoreCase("x-notification-id");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request contains unsupported JSON", e);
        }
    }

    private String hash(String targetUrl, String headersJson, String bodyJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(targetUrl.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(headersJson.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (bodyJson != null) digest.update(bodyJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
