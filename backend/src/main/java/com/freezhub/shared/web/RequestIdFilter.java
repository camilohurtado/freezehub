package com.freezhub.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, and puts it on every log line it produces (FZ-062).
 *
 * <p>Without this the only way to tie a user's report to a log line is a timestamp, which
 * stops working the moment two things happen in the same second or two tasks are running
 * — and there are two tasks. The id also goes back in the response, and into the error
 * body (`FZ-061`), so "it failed at about three o'clock" becomes an exact lookup.
 *
 * <p>An incoming {@code X-Request-Id} is honoured rather than replaced, so a value set by
 * a load balancer or a caller's own tracing survives into these logs and the two sides can
 * be matched up. It is sanitised first: the value reaches log files, and an unbounded
 * header is somebody else's newline injected into them.
 *
 * <p>Ordered first, so a request rejected by security is still logged with an id.
 *
 * <p>It also writes the one log line per request that gives the id something to correlate
 * with. Without it the id appeared only on the rare line the application chose to write,
 * which is to say almost never — a validation failure logs nothing at all. Health probes
 * are excluded: the load balancer asks every thirty seconds, and that would bury
 * everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    /** Long enough to be useful, short enough to quote over the phone. */
    private static final int MAX_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(HEADER));

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            logCompletion(request, response, startedAt);
            // Threads are pooled: without this the next request on this thread would
            // inherit the previous one's id, which is worse than having none.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * One line per request: what was asked, how it ended, and how long it took.
     *
     * <p>A server error is logged at ERROR even though the handler has already logged the
     * exception, because the two carry different things — the exception has the stack, this
     * has the path and the caller's request id.
     *
     * <p>No headers and no body: one carries credentials, the other carries customer data.
     */
    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        if (isHealthProbe(request)) {
            return;
        }

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        int status = response.getStatus();

        if (status >= 500) {
            log.error("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), status, millis);
        } else {
            log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), status, millis);
        }
    }

    private boolean isHealthProbe(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    /** Keeps only characters that are safe in a log line, and generates one if need be. */
    private String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String cleaned = supplied.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }

        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    /** The current request's id, for anything that needs to report it back. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

}
