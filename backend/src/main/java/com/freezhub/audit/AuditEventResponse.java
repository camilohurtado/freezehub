package com.freezhub.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * One entry in the trail.
 *
 * <p>{@code details} is emitted as raw JSON rather than as a string containing JSON, so a
 * reader gets an object to look at instead of an escaped blob to unescape.
 */
public record AuditEventResponse(
        Long id,
        AuditActor.AuditActorType actorType,
        Long actorId,
        String actorLabel,
        AuditAction action,
        AuditResourceType resourceType,
        Long resourceId,
        @JsonRawValue String details,
        Instant occurredAt
) {

    static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getActorType(),
                event.getActorId(),
                event.getActorLabel(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getDetails(),
                event.getOccurredAt());
    }

}
