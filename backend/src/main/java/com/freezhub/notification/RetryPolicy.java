package com.freezhub.notification;

import java.time.Duration;
import java.time.Instant;

/**
 * How often a failed delivery is retried, and when it is given up on (FZ-044, fixes
 * `OI-1`).
 *
 * <p>Separate and static so the schedule can be asserted directly, without waiting for
 * real time to pass or driving it through a database.
 *
 * <p>Exponential backoff, because the two failure modes want opposite things: a brief
 * network blip should be retried almost immediately, while a destination that has been
 * deleted at the other end should be backed off from rather than hit on every pass for
 * days. Doubling gets both from one rule.
 */
public final class RetryPolicy {

    /**
     * After this many failed attempts a notification is abandoned as FAILED.
     *
     * <p>Six attempts spans roughly half an hour of backoff — long enough to ride out a
     * restart or a short outage at the destination, short enough that a genuinely
     * undeliverable announcement is visibly given up on rather than retried for ever.
     */
    public static final int MAX_ATTEMPTS = 6;

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private RetryPolicy() {
    }

    /** True once a notification has used up its attempts. */
    public static boolean isExhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }

    /**
     * How long to wait before attempt number {@code attempts + 1}.
     *
     * <p>Capped so the interval cannot grow without limit: a destination that recovers
     * after a long outage should still be retried within a useful period.
     */
    public static Duration backoffAfter(int attempts) {
        if (attempts <= 0) {
            return FIRST_BACKOFF;
        }
        // 30s, 60s, 2m, 4m, 8m, then capped at 15m.
        long seconds = FIRST_BACKOFF.getSeconds() << Math.min(attempts, 20);
        Duration backoff = Duration.ofSeconds(seconds);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    public static Instant nextAttemptAfter(int attempts, Instant now) {
        return now.plus(backoffAfter(attempts));
    }

}
