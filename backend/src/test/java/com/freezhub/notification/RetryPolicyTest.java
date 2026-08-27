package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The retry schedule, asserted directly rather than by waiting for real time (FZ-044).
 */
class RetryPolicyTest {

    @Test
    void backsOffExponentially() {
        // A brief blip is retried almost immediately; a lasting outage is backed off from.
        assertThat(RetryPolicy.backoffAfter(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(RetryPolicy.backoffAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(RetryPolicy.backoffAfter(4)).isEqualTo(Duration.ofMinutes(8));
    }

    @Test
    void capsTheBackoffSoARecoveredDestinationIsStillRetried() {
        // Unbounded doubling would eventually mean "never" in practice.
        assertThat(RetryPolicy.backoffAfter(5)).isEqualTo(Duration.ofMinutes(15));
        assertThat(RetryPolicy.backoffAfter(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void neverReturnsAZeroBackoff() {
        // A zero backoff would reproduce the hot loop this policy exists to prevent (OI-1).
        for (int attempts = 0; attempts <= 12; attempts++) {
            assertThat(RetryPolicy.backoffAfter(attempts))
                    .as("attempts %s", attempts)
                    .isPositive();
        }
    }

    @Test
    void givesUpAfterTheAttemptLimit() {
        assertThat(RetryPolicy.isExhausted(RetryPolicy.MAX_ATTEMPTS - 1)).isFalse();
        assertThat(RetryPolicy.isExhausted(RetryPolicy.MAX_ATTEMPTS)).isTrue();
        assertThat(RetryPolicy.isExhausted(RetryPolicy.MAX_ATTEMPTS + 1)).isTrue();
    }

    @Test
    void schedulesTheNextAttemptFromTheGivenInstant() {
        Instant now = Instant.parse("2026-11-27T14:00:00Z");

        assertThat(RetryPolicy.nextAttemptAfter(0, now)).isEqualTo(Instant.parse("2026-11-27T14:00:30Z"));
        assertThat(RetryPolicy.nextAttemptAfter(2, now)).isEqualTo(Instant.parse("2026-11-27T14:02:00Z"));
    }

    @Test
    void spansALongEnoughWindowToRideOutAShortOutage() {
        // The point of the limit is to give up eventually, not immediately: the whole
        // schedule should still cover a restart or a brief provider outage.
        Duration total = Duration.ZERO;
        for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS - 1; attempt++) {
            total = total.plus(RetryPolicy.backoffAfter(attempt));
        }
        assertThat(total).isGreaterThan(Duration.ofMinutes(10));
    }

}
