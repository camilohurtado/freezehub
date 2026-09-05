package com.freezhub.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freezhub.notification.RetryPolicy;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Tells FreezeHub's own Slack that a demo was requested (FZ-083).
 *
 * <p><strong>Why this is not the notification module.</strong> The backlog assumed it
 * could be. It cannot: a {@code Notification} requires a non-null {@code organizationId}
 * and {@code restrictionId}, and {@code NotificationSender.send} takes a
 * {@code ChangeRestriction}. A demo request has none of the three, and bending the port to
 * fit would ripple through the email and webhook senders and their tests to serve one
 * message that goes to us rather than to a customer.
 *
 * <p>What <em>is</em> reused is the part worth reusing: {@link RetryPolicy}, which is a
 * pure function of attempt count and carries no coupling at all. Same backoff, same
 * give-up point, no duplicated schedule to drift.
 *
 * <p>Only registered when a webhook is configured. Without one there is nowhere to send,
 * and requests are still recorded — the lead is the row, not the message.
 */
@Service
public class DemoRequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH_SIZE = 50;

    private final DemoRequestRepository requests;
    private final RestClient restClient;
    private final String webhookUrl;

    public DemoRequestNotifier(DemoRequestRepository requests,
                               RestClient.Builder restClientBuilder,
                               @Value("${freezehub.demo-requests.slack-webhook:}") String webhookUrl) {
        this.requests = requests;
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl;
    }

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * Sends whatever is due.
     *
     * @return how many were announced on this pass
     */
    @Transactional
    public int notifyPending(Instant now) {
        if (!isConfigured()) {
            return 0;
        }

        List<DemoRequest> pending = requests.findPendingNotification(
                now, RetryPolicy.MAX_ATTEMPTS, Limit.of(BATCH_SIZE));

        int sent = 0;
        for (DemoRequest request : pending) {
            try {
                post(request);
                request.markNotified(now);
                sent += 1;
            } catch (RestClientException failed) {
                // The class name only. Spring puts the request URI in the message, and
                // that URI is the webhook — a bearer credential.
                String error = "Slack rejected or could not be reached: "
                        + failed.getClass().getSimpleName();
                request.markNotificationFailed(error,
                        RetryPolicy.nextAttemptAfter(request.getNotifyAttempts(), now));
                log.warn("Demo request {} could not be announced (attempt {}): {}",
                        request.getId(), request.getNotifyAttempts() + 1, error);
            }
        }
        return sent;
    }

    private void post(DemoRequest request) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", messageFor(request));

        restClient.post()
                .uri(webhookUrl)
                .header("Content-Type", "application/json")
                .body(payload.toString())
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Built with Jackson rather than by concatenation.
     *
     * <p>A company called {@code O"Brien "Ltd"} is not hypothetical, and the same mistake
     * in the audit trail produced an unparseable row once already ({@code FZ-060}).
     */
    private String messageFor(DemoRequest request) {
        StringBuilder text = new StringBuilder("*Demo requested* — ")
                .append(request.getCompany())
                .append("\n")
                .append(request.getName())
                .append(" · ")
                .append(request.getEmail());
        if (request.getTeamSize() != null && !request.getTeamSize().isBlank()) {
            text.append(" · ").append(request.getTeamSize()).append(" engineers");
        }
        if (request.getSource() != null && !request.getSource().isBlank()) {
            text.append("\nFrom: ").append(request.getSource());
        }
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            text.append("\n> ").append(request.getMessage());
        }
        return text.toString();
    }
}
