package com.freezhub.subscription;

import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Makes a suspended organization's human API read-only (FZ-081).
 *
 * <p>One interceptor rather than a check in every service. A new write endpoint is covered
 * the day it is written instead of the day somebody remembers to add a line to it, and the
 * failure mode of the other approach — an endpoint that quietly keeps working for a
 * customer who stopped paying — is one nobody finds.
 *
 * <p><strong>What keeps this away from {@code /api/policy/**} is the principal type.</strong>
 * A policy caller authenticates with an API key and arrives as an {@code ApiKeyPrincipal},
 * never an {@link AuthenticatedUser}, so the check below does not fire for it — verified
 * by removing both that check and the path exclusion in {@code SubscriptionWebConfig},
 * which makes {@code SuspensionTest} fail. Removing the path exclusion alone does not:
 * it is a second, independent line, not the one doing the work.
 *
 * <p><strong>An interceptor and not a filter, deliberately.</strong> A filter throws
 * outside the DispatcherServlet, so {@code @RestControllerAdvice} never sees it and the
 * refusal would come back as a generic 500 instead of the one error shape the API promises
 * ({@code FZ-061}). {@code FZ-052} already paid for that lesson once, with the {@code
 * /error} forward.
 */
@Component
public class SubscriptionWriteGuard implements HandlerInterceptor {

    private final SubscriptionService subscriptions;

    public SubscriptionWriteGuard(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Reads stay open, deliberately. A suspended customer must still be able to see
        // what is frozen and read their own audit trail — locking them out of their record
        // is not leverage, it is hostage-taking.
        if (isRead(request.getMethod())) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            subscriptions.requireWritable(user.organizationId());
        }
        return true;
    }

    private boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
