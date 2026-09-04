package com.freezhub.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the limit, and decides who "one client" is (FZ-087).
 *
 * <p>An interceptor rather than a filter, for the same reason as {@code
 * SubscriptionWriteGuard}: a filter throws outside the DispatcherServlet, so the refusal
 * would arrive as a generic 500 instead of the Problem Details shape the API promises
 * ({@code FZ-061}).
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Optional<Duration> retryAfter = rateLimiter.check(clientOf(request), Instant.now());
        if (retryAfter.isPresent()) {
            // Set here rather than in the exception handler: Retry-After is a header on
            // the response, and the handler builds a body.
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter.get().toSeconds())));
            throw new RateLimitExceededException(retryAfter.get());
        }
        return true;
    }

    /**
     * The caller's address.
     *
     * <p>Deliberately {@code getRemoteAddr()} and <strong>not</strong> a hand-read
     * {@code X-Forwarded-For}. Reading that header directly would let anyone send one and
     * choose their own bucket — evading the limit, or filling somebody else's. Behind the
     * load balancer, {@code server.forward-headers-strategy} is what makes this return the
     * real client, and it is set only where a trusted proxy actually terminates the
     * connection.
     *
     * <p>The failure mode if that is misconfigured is loud rather than silent: every
     * request appears to come from the load balancer, so the whole service shares one
     * counter and starts refusing everyone at once.
     */
    private String clientOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address != null ? address : "unknown";
    }
}
