package com.freezhub.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers a machine-readable lifecycle event to a customer endpoint (FZ-043).
 *
 * <p>The endpoint belongs to someone else, so two things matter more than they do for
 * Slack or email: the body is a versioned contract ({@link WebhookPayload}), and the URL
 * is treated as sensitive — a webhook URL commonly carries a token in its path, so it is
 * never put into an error message that lands in {@code notification.last_error}.
 */
@Component
public class WebhookNotificationSender implements NotificationSender {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RestClient restClient;

    public WebhookNotificationSender(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.WEBHOOK;
    }

    @Override
    public void send(Notification notification, ChangeRestriction restriction, Integration destination) {
        String url = urlOf(destination);
        String body = serialise(WebhookPayload.of(notification.getEvent(), restriction, Instant.now()));

        try {
            restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    // Lets a receiver route or filter without parsing the body first.
                    .header("X-FreezeHub-Event", notification.getEvent().name())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            // Not the exception's own message: Spring includes the request URI in it.
            throw new NotificationDeliveryException(
                    "Webhook endpoint rejected or could not be reached: "
                            + failed.getClass().getSimpleName());
        }
    }

    private String serialise(WebhookPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            throw new NotificationDeliveryException("Could not build the webhook payload");
        }
    }

    private String urlOf(Integration destination) {
        try {
            JsonNode config = MAPPER.readTree(destination.getConfig());
            String url = config.path("url").asText("");
            if (url.isBlank()) {
                throw new NotificationDeliveryException("Webhook integration has no url configured");
            }
            return url;
        } catch (JsonProcessingException unreadable) {
            throw new NotificationDeliveryException("Webhook integration config is not valid JSON");
        }
    }

}
