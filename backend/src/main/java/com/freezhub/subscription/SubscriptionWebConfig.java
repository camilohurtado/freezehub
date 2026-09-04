package com.freezhub.subscription;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where the write guard applies (FZ-081).
 *
 * <p>The two exclusions are the whole decision, and both are load-bearing:
 *
 * <ul>
 *   <li><b>{@code /api/policy/**}</b> — a suspended organization's freezes are enforced
 *       exactly as before ({@code D-21}). Failing this endpoint would take every one of
 *       that customer's pipelines down over an invoice; allowing through it would silently
 *       lift every freeze at the moment of a commercial dispute.
 *
 *       <p>Honest about what this line is worth: interceptors do not care which security
 *       chain served a request, so it looks load-bearing, and it is not. A policy caller
 *       arrives as an {@code ApiKeyPrincipal}, so {@link SubscriptionWriteGuard} already
 *       ignores it — removing this exclusion alone changes nothing, and removing it
 *       together with that check is what makes {@code SuspensionTest} fail. It stays as a
 *       second line and as a statement of intent for whoever edits the guard next.</li>
 *   <li><b>{@code /api/billing/**}</b> — it is how an organization stops being suspended.
 *       A read-only mode that locks out the only route to fixing it is a trap. Populated
 *       by {@code FZ-084}; excluded now because adding the exclusion later means shipping
 *       the trap in between.</li>
 * </ul>
 */
@Configuration
public class SubscriptionWebConfig implements WebMvcConfigurer {

    private final SubscriptionWriteGuard writeGuard;

    public SubscriptionWebConfig(SubscriptionWriteGuard writeGuard) {
        this.writeGuard = writeGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(writeGuard)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/policy/**", "/api/billing/**");
    }
}
