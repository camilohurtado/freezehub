package com.freezhub.shared.ratelimit;

import java.time.Duration;

/** Too many requests from one client (FZ-087). Answered as {@code 429}. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests. Try again in " + Math.max(1, retryAfter.toSeconds()) + " seconds.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
