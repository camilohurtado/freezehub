package com.freezhub.shared.ratelimit;

/**
 * The two counters the Policy API is limited by (FZ-130).
 *
 * <p>Exposed as a bean of its own rather than as the finished {@link PolicyRateLimitFilter}
 * because <strong>a {@code Filter} bean is auto-registered with the servlet container for
 * every request</strong>. That would apply a limit designed for one machine endpoint to the
 * whole human API, where an unauthenticated request is an ordinary sign-in rather than a
 * failed key — so the filter is built inside the machine security chain, which is the only
 * place it belongs. {@code ApiKeyAuthenticationFilter} is not a bean for the same reason.
 */
public record PolicyRateLimiters(RateLimiter failures, RateLimiter perKey) {
}
