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
        Instant createdAt,
        Instant updatedAt
) {

    static IntegrationResponse from(Integration integration) {
        return new IntegrationResponse(
                integration.getId(),
                integration.getType(),
                integration.isEnabled(),
                IntegrationConfigs.summarise(integration.getType(), integration.getConfig()),
                integration.getCreatedAt(),
                integration.getUpdatedAt());
    }

}
