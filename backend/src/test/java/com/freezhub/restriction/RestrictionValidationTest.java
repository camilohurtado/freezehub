package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Focused unit coverage of the ChangeRestriction domain invariants (01-domain.md),
 * without a Spring context. The integration test covers the same rules through HTTP;
 * these keep the rules themselves fast to exercise and easy to read.
 */
class RestrictionValidationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void rejectsStartsAtEqualToEndsAt() {
        Instant sameInstant = NOW.plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> ChangeRestrictionService.validatePeriod(sameInstant, sameInstant, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("startsAt must be earlier than endsAt");
    }

    @Test
    void rejectsStartsAtAfterEndsAt() {
        Instant startsAt = NOW.plus(2, ChronoUnit.DAYS);
        Instant endsAt = NOW.plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> ChangeRestrictionService.validatePeriod(startsAt, endsAt, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("startsAt must be earlier than endsAt");
    }

    @Test
    void rejectsAWindowEntirelyInThePast() {
        Instant startsAt = NOW.minus(5, ChronoUnit.DAYS);
        Instant endsAt = NOW.minus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> ChangeRestrictionService.validatePeriod(startsAt, endsAt, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("entirely in the past");
    }

    @Test
    void acceptsAWindowAlreadyStartedButNotYetEnded() {
        // Invariant 2 forbids only a wholly past window; FZ-025 activates this one later.
        Instant startsAt = NOW.minus(1, ChronoUnit.DAYS);
        Instant endsAt = NOW.plus(1, ChronoUnit.DAYS);

        assertThatCode(() -> ChangeRestrictionService.validatePeriod(startsAt, endsAt, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAFullyFutureWindow() {
        Instant startsAt = NOW.plus(1, ChronoUnit.DAYS);
        Instant endsAt = NOW.plus(2, ChronoUnit.DAYS);

        assertThatCode(() -> ChangeRestrictionService.validatePeriod(startsAt, endsAt, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAScopeWithNoTargetsInAnyDimension() {
        assertThatThrownBy(() -> ChangeRestrictionService.validateScopeNotEmpty(Set.of(), Set.of(), Set.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("At least one scope target is required");
    }

    @Test
    void acceptsAScopeWithASingleDimensionPopulated() {
        // An empty dimension is a wildcard, so one populated dimension is a valid scope.
        assertThatCode(() -> ChangeRestrictionService.validateScopeNotEmpty(Set.of(), Set.of(), Set.of(1L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeRestrictionService.validateScopeNotEmpty(Set.of(1L), Set.of(), Set.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeRestrictionService.validateScopeNotEmpty(Set.of(), Set.of(1L), Set.of()))
                .doesNotThrowAnyException();
    }

}
