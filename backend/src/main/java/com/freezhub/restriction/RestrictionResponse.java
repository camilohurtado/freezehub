package com.freezhub.restriction;

import java.time.Instant;
import java.util.Set;

public record RestrictionResponse(
        Long id,
        String name,
        String description,
        String reason,
        RestrictionType type,
        RestrictionLevel level,
        RestrictionStatus status,
        Instant startsAt,
        Instant endsAt,
        ScopeResponse scope,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    public record ScopeResponse(Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {
    }

    static RestrictionResponse from(ChangeRestriction restriction) {
        return new RestrictionResponse(
                restriction.getId(),
                restriction.getName(),
                restriction.getDescription(),
                restriction.getReason(),
                restriction.getType(),
                restriction.getLevel(),
                restriction.getStatus(),
                restriction.getStartsAt(),
                restriction.getEndsAt(),
                new ScopeResponse(
                        restriction.getTeamIds(),
                        restriction.getApplicationIds(),
                        restriction.getEnvironmentIds()),
                restriction.getCreatedBy(),
                restriction.getCreatedAt(),
                restriction.getUpdatedAt());
    }

}
