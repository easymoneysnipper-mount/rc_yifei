package com.example.notification.delivery;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.NotificationRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class OutboundHttpClient {

    private final HttpClient client;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;

    public OutboundHttpClient(NotificationProperties properties, ObjectMapper objectMapper) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.http().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DeliveryResult send(NotificationRecord notification) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(notification.targetUrl()))
                    .timeout(Duration.ofMillis(properties.http().requestTimeoutMs()))
                    .header("X-Notification-Id", notification.id().toString())
                    .header("Content-Type", "application/json");
            Map<String, String> headers = objectMapper.readValue(notification.headersJson(), new TypeReference<>() {});
            headers.forEach(builder::header);
            String body = notification.bodyJson();
            HttpRequest request = builder.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code >= 200 && code < 300) return DeliveryResult.success(code);
            boolean retryable = code == 408 || code == 425 || code == 429 || code >= 500;
            return DeliveryResult.failure(retryable, code, "vendor returned HTTP " + code,
                    retryAfter(response));
        } catch (Exception e) {
            return DeliveryResult.failure(true, 0, e.getClass().getSimpleName() + ": " + safeMessage(e), null);
        }
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) return null;
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "request failed";
        return message.length() > 300 ? message.substring(0, 297) + "..." : message;
    }
}
