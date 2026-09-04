package com.freezhub.restriction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * The full mutable state of a restriction, shared by create (FZ-020) and update (FZ-023).
 * Update is a full replacement (PUT), so both carry exactly the same fields and the same
 * validation rules - one record rather than two that would have to be kept in step.
 *
 * <p>{@code type} and {@code status} are deliberately absent: both are server-controlled
 * (DEPLOYMENT_FREEZE / SCHEDULED) per 01-domain.md, and status transitions belong to the
 * cancel and lifecycle stories (FZ-024, FZ-025), not to editing.
 */
public record RestrictionRequest(
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

    /**
     * The window, rounded down to what the database can hold (FZ-098).
     *
     * <p>PostgreSQL {@code TIMESTAMPTZ} stores microseconds; {@link Instant} carries
     * nanoseconds, and on Linux {@code Instant.now()} populates them. Normalised here,
     * at the edge where external precision enters, so that everything downstream agrees:
     * validation, the before/after comparison that writes the audit trail, the aggregate,
     * and the response.
     *
     * <p>Without it, {@code update} compared the stored value against the raw request and
     * found a difference on every no-op save - recording a freeze window that the database
     * had already truncated away, in the trail meant to say what actually changed.
     *
     * <p>Truncating both ends can collapse a window shorter than a microsecond into a
     * zero-length one, which then fails the {@code startsAt < endsAt} rule. That is the
     * right answer: a window the database cannot represent as non-empty is not a window.
     *
     * <p>An accessor rather than a compact constructor, matching {@link #scope()} above,
     * and so that {@code @NotNull} still sees a genuinely absent value as absent.
     */
    public Instant startsAt() {
        return storable(startsAt);
    }

    public Instant endsAt() {
        return storable(endsAt);
    }

    private static Instant storable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    public ScopeRequest scope() {
        return scope == null ? new ScopeRequest(Set.of(), Set.of(), Set.of()) : scope;
    }

}
