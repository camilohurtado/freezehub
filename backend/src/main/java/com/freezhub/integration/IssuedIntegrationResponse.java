package com.freezhub.integration;

import java.time.Instant;

/**
 * The creation and rotation response, and the only place a webhook's
 * {@code signingSecret} ever appears (FZ-048).
 *
 * <p>A separate type from {@link IntegrationResponse} on purpose, matching how
 * {@code IssuedApiKeyResponse} handles the same problem: a field that exists only on the
 * endpoints meant to reveal it cannot be leaked into a listing by accident.
 *
 * <p>{@code signingSecret} is null for Slack and email, which have no such secret.
 */
public record IssuedIntegrationResponse(
        Long id,
        IntegrationType type,
        boolean enabled,
        String summary,
        String signingSecret,
        Instant createdAt,
        Instant updatedAt
) {

    static IssuedIntegrationResponse from(Integration integration) {
        return new IssuedIntegrationResponse(
                integration.getId(),
                integration.getType(),
                integration.isEnabled(),
                IntegrationConfigs.summarise(integration.getType(), integration.getConfig()),
                integration.getSigningSecret(),
                integration.getCreatedAt(),
                integration.getUpdatedAt());
    }

}
