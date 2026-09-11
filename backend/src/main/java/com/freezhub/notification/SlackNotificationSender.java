package com.freezhub.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Posts an announcement to a Slack incoming webhook (FZ-041).
 *
 * <p>The webhook URL comes from the integration's config and is used, never logged: it is
 * a bearer credential, and an exception message ends up in {@code notification.last_error}
 * where a support engineer would read it.
 */
@Component
public class SlackNotificationSender implements NotificationSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    /*
     * The guarded client (FZ-126): no redirects, and every destination resolved and checked
     * on the way out. This one calls an address a customer chose, which is what separates
     * it from the demo notifier's own builder.
     */
    public SlackNotificationSender(@Qualifier("outboundDeliveryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.SLACK;
    }

    @Override
    public void send(Notification notification, ChangeRestriction restriction, Integration destination) {
        String webhookUrl = webhookUrlOf(destination);
        String text = NotificationMessage.body(notification.getEvent(), restriction);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", text);

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(payload.toString())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            // Deliberately not including the exception message verbatim: Spring puts the
            // request URI in it, and that URI is the credential.
            throw new NotificationDeliveryException(
                    "Slack rejected or could not be reached: " + failed.getClass().getSimpleName());
        }
    }

    private String webhookUrlOf(Integration destination) {
        try {
            JsonNode config = MAPPER.readTree(destination.getConfig());
            String url = config.path("webhookUrl").asText("");
            if (url.isBlank()) {
                throw new NotificationDeliveryException("Slack integration has no webhookUrl configured");
            }
            return url;
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new NotificationDeliveryException("Slack integration config is not valid JSON");
        }
    }

}
