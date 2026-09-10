package com.example.notification.api;

import com.example.notification.model.CreateNotificationRequest;
import com.example.notification.model.EnqueueResult;
import com.example.notification.model.NotificationResponse;
import com.example.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateNotificationRequest request) {
        EnqueueResult result = service.enqueue(idempotencyKey, request);
        return ResponseEntity.accepted()
                .location(URI.create(result.response().statusUrl()))
                .body(result.response());
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
