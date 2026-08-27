package com.freezhub.notification;

import com.freezhub.restriction.ChangeRestriction;
import java.time.Instant;

/**
 * The JSON body delivered to a customer webhook (FZ-043).
 *
 * <p>Unlike Slack and email, this is a **contract**: someone else's software parses it, so
 * the field names are part of the public surface and a careless rename breaks a consumer
 * silently. It carries a {@code version} for that reason — cheap now, and the only thing
 * that makes a future change safe to roll out.
 *
 * <p><strong>Scope is deliberately absent.</strong> A consumer that received the affected
 * teams, applications and environments would have to re-implement the matching rules
 * (OR within a dimension, AND across them, empty meaning any — 01-domain.md) to decide
 * whether a given deployment is affected. Two implementations of that would drift, and
 * the one outside FreezeHub would be wrong. The Policy API (`FZ-051`) exists to answer
 * "may I deploy"; this event says only that something changed and is worth re-checking.
 */
public record WebhookPayload(
        int version,
        NotificationEvent event,
        Instant occurredAt,
        Restriction restriction
) {

    /** Current contract version. Increment only for a breaking change to these fields. */
    public static final int VERSION = 1;

    public record Restriction(
            Long id,
            String name,
            String description,
            String reason,
            String type,
            String level,
            String status,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    public static WebhookPayload of(NotificationEvent event, ChangeRestriction restriction, Instant occurredAt) {
        return new WebhookPayload(
                VERSION,
                event,
                occurredAt,
                new Restriction(
                        restriction.getId(),
                        restriction.getName(),
                        restriction.getDescription(),
                        restriction.getReason(),
                        restriction.getType().name(),
                        restriction.getLevel().name(),
                        restriction.getStatus().name(),
                        restriction.getStartsAt(),
                        restriction.getEndsAt()));
    }

}
