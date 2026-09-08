package com.freezhub.notification;

import com.freezhub.integration.IntegrationType;
import java.time.Instant;
import java.util.List;

/**
 * One lifecycle event and what each channel did with it (`1g`, FZ-115).
 *
 * <p><strong>Grouped by event, not listed per delivery.</strong> The question this screen
 * answers is "was everybody told?", and a flat list of deliveries makes that a counting
 * exercise: three rows saying the same thing happened, one of which quietly says it did
 * not arrive. Grouping puts the event on the left and the channels beside it, so a gap is
 * visible rather than derivable.
 */
public record NotificationEventResponse(
        Long restrictionId,
        String restrictionName,
        NotificationEvent event,
        /** When the event happened — the earliest of its deliveries was written with it. */
        Instant occurredAt,
        List<Delivery> deliveries
) {

    /** What one channel did with one event. */
    public record Delivery(
            Long integrationId,
            IntegrationType channel,
            NotificationStatus status,
            int attempts,
            /**
             * Why the last attempt failed, as the destination said it. Null unless it did.
             * Never a credential: the sender records the response, not the request.
             */
            String lastError,
            Instant sentAt
    ) {
    }

    /** True when any channel has not accepted this event — the reason to look at all. */
    public boolean hasFailure() {
        return deliveries.stream()
                .anyMatch(delivery -> delivery.status() == NotificationStatus.FAILED);
    }
}
