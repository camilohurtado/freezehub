package com.freezhub.restriction;

import java.time.Instant;

/**
 * List projection. Deliberately omits scope: `FZ-022` owns "retrieve a restriction and
 * its scope", and keeping scope out means listing stays a single query no matter how many
 * restrictions an organization has. Adding fields later is additive and non-breaking.
 */
public record RestrictionSummaryResponse(
        Long id,
        String name,
        String reason,
        RestrictionType type,
        RestrictionLevel level,
        RestrictionStatus status,
        Instant startsAt,
        Instant endsAt,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    static RestrictionSummaryResponse from(ChangeRestriction restriction) {
        return new RestrictionSummaryResponse(
                restriction.getId(),
                restriction.getName(),
                restriction.getReason(),
                restriction.getType(),
                restriction.getLevel(),
                restriction.getStatus(),
                restriction.getStartsAt(),
                restriction.getEndsAt(),
                restriction.getCreatedBy(),
                restriction.getCreatedAt(),
                restriction.getUpdatedAt());
    }

}
