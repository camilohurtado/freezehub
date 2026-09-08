package com.freezhub.integration;

import java.time.Instant;

/**
 * What a client is allowed to see.
 *
 * <p>Note what is absent: {@code config}. A Slack webhook URL is a bearer credential —
 * anyone holding it can post into that channel — so it is stored and never read back out.
 * {@code summary} identifies the destination without being enough to reuse it.
 */
public record IntegrationResponse(
        Long id,
        IntegrationType type,
        boolean enabled,
        String summary,
        /**
         * Announcements to this channel that were given up on and never arrived (FZ-117).
         *
         * <p>A channel that is enabled and failing looks identical to one that is working
         * until you go and read the notifications, which is the wrong way round: settings
         * is where somebody goes to fix it.
         *
         * <p>Counted from the outbox rather than stored on the channel, so it cannot claim
         * a channel is healthy after the record says otherwise. It clears when a retry
         * succeeds ({@code FZ-119}).
         */
        long failedDeliveries,
        Instant createdAt,
        Instant updatedAt
) {

    static IntegrationResponse from(Integration integration, long failedDeliveries) {
        return new IntegrationResponse(
                integration.getId(),
                integration.getType(),
                integration.isEnabled(),
                IntegrationConfigs.summarise(integration.getType(), integration.getConfig()),
                failedDeliveries,
                integration.getCreatedAt(),
                integration.getUpdatedAt());
    }

}
