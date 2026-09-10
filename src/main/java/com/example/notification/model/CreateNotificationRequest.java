package com.example.notification.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreateNotificationRequest(
        @NotBlank String targetUrl,
        Map<String, String> headers,
        JsonNode body
) {
}
