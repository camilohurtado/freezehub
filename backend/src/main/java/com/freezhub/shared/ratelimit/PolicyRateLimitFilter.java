package com.freezhub.shared.ratelimit;

import com.freezhub.shared.security.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The limit on {@code /api/policy/**} (FZ-130, `OI-27`).
 *
 * <p>A filter and not an interceptor, unlike {@link RateLimitInterceptor}, because half of
 * what has to be counted never reaches the DispatcherServlet: a request without a usable
 * API key is refused by the chain's entry point, so an interceptor sees only the successful
 * calls and the endpoint's failed attempts stay unmetered — which is the half that has no
 * credential behind it.
 *
 * <p><strong>Two counters, keyed differently, and the split is the point.</strong> An
 * unauthenticated attempt is counted against its source address; an authenticated
 * evaluation is counted against the API key it presented. A pipeline with a valid key
 * therefore cannot be refused by somebody else's failures — including somebody else behind
 * the same corporate NAT, which is the shape a shared source address actually takes.
 * {@code freeze-check.sh} fails closed by default (`FREEZEHUB_ON_ERROR=block`, `FZ-053`), so "this deployment is blocked because a
 * stranger mistyped a key" would be an outage caused by the defence.
 *
 * <p>Placed after {@code ApiKeyAuthenticationFilter} and before authorization: after,
 * because which counter applies depends on whether the key resolved; before, because a
 * refusal has to happen instead of the work, not after it.
 *
 * <p>The refusal is rendered through the application's {@code @RestControllerAdvice} rather
 * than written here, so a caller sees the same Problem Details body as every other refusal
 * in the API (`FZ-061`) — a filter that writes its own JSON is how an API ends up with two
 * error shapes and clients that parse both.
 */
public class PolicyRateLimitFilter extends OncePerRequestFilter {

    private final PolicyRateLimiters limiters;
    private final HandlerExceptionResolver exceptionResolver;

    public PolicyRateLimitFilter(PolicyRateLimiters limiters, HandlerExceptionResolver exceptionResolver) {
        this.limiters = limiters;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<ApiKeyPrincipal> caller = authenticatedCaller();

        // The key never appears in a counter key, logged or otherwise: the identifier is
        // the credential's database id, which is useless to anyone who obtains it.
        Optional<Duration> retryAfter = caller.isPresent()
                ? limiters.perKey().check("key:" + caller.get().apiKeyId(), Instant.now())
                : limiters.failures().check("from:" + sourceOf(request), Instant.now());

        if (retryAfter.isPresent()) {
            refuse(request, response, retryAfter.get());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Optional<ApiKeyPrincipal> authenticatedCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof ApiKeyPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /**
     * The caller's address — {@code getRemoteAddr()}, never a hand-read
     * {@code X-Forwarded-For}, for the reason {@link RateLimitInterceptor} spells out: a
     * header anyone can send is a bucket anyone can choose, or fill on somebody else's
     * behalf.
     */
    private String sourceOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address != null ? address : "unknown";
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, Duration retryAfter) {
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter.toSeconds())));
        exceptionResolver.resolveException(request, response, null, new RateLimitExceededException(retryAfter));
    }
}
