package com.freezhub.notification;

import jakarta.validation.constraints.NotNull;

/**
 * Which announcement to send again (`FZ-119`).
 *
 * <p>An event rather than a delivery: the screen offers one button beside "1 delivery
 * failed", and the two names here are what identifies an event, since deliveries are
 * grouped by restriction and lifecycle event rather than carrying an id of their own.
 */
public record RetryRequest(
        @NotNull Long restrictionId,
        @NotNull NotificationEvent event
) {
}
