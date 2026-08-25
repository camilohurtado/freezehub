package com.freezhub.restriction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Set;

/**
 * Creation payload. {@code type} and {@code status} are deliberately absent: both are
 * server-controlled (DEPLOYMENT_FREEZE / SCHEDULED) per 01-domain.md and FZ-020.
 */
public record CreateRestrictionRequest(
        @NotBlank String name,
        String description,
        @NotBlank String reason,
        @NotNull RestrictionLevel level,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        ScopeRequest scope
) {

    /**
     * Each list is a scope dimension. Any of them may be empty or absent - an empty
     * dimension is a wildcard - but at least one target must be present overall
     * (validated in the service, per domain invariant 3).
     */
    public record ScopeRequest(Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {

        public Set<Long> teamIds() {
            return teamIds == null ? Set.of() : teamIds;
        }

        public Set<Long> applicationIds() {
            return applicationIds == null ? Set.of() : applicationIds;
        }

        public Set<Long> environmentIds() {
            return environmentIds == null ? Set.of() : environmentIds;
        }
    }

    public ScopeRequest scope() {
        return scope == null ? new ScopeRequest(Set.of(), Set.of(), Set.of()) : scope;
    }

}
