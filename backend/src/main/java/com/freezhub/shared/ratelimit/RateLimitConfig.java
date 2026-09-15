package com.freezhub.shared.ratelimit;

import com.freezhub.shared.ratelimit.RateLimitProperties.Limit;
import com.freezhub.shared.ratelimit.RateLimitProperties.PathLimit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires each limit onto what it protects (FZ-087, FZ-130).
 *
 * <p>Two mechanisms, because the endpoints are reached differently. The path-based limits
 * are interceptors: they guard endpoints that are permitted to everyone, so every request
 * reaches the DispatcherServlet and an interceptor sees it. The Policy API's limits cannot
 * be — a request with no usable key is refused in the security chain and never arrives — so
 * those two counters are handed to the machine chain, which builds a filter from them.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "freezehub.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitProperties properties;
    private final PathLimit unauthenticated;
    private final PathLimit stripeWebhook;
    private final RateLimiter unauthenticatedLimiter;
    private final RateLimiter stripeWebhookLimiter;
    private final PolicyRateLimiters policyRateLimiters;

    public RateLimitConfig(RateLimitProperties properties) {
        this.properties = properties;
        this.unauthenticated = properties.getUnauthenticated();
        this.stripeWebhook = properties.getStripeWebhook();
        this.unauthenticatedLimiter = limiterFor(unauthenticated);
        this.stripeWebhookLimiter = limiterFor(stripeWebhook);
        this.policyRateLimiters = new PolicyRateLimiters(
                limiterFor(properties.getPolicy().getFailures()),
                limiterFor(properties.getPolicy().getPerKey()));
    }

    private RateLimiter limiterFor(Limit limit) {
        return new RateLimiter(limit.getRequests(), limit.getWindow(), properties.getMaxTrackedClients());
    }

    /**
     * The Policy API's counters, for {@code ApiKeySecurityConfig} to build its filter from.
     *
     * <p>Separate counters rather than one shared with the endpoints above: a limit tuned
     * for a signup form would refuse a pipeline on its fourth deployment of the minute.
     */
    @Bean
    public PolicyRateLimiters policyRateLimiters() {
        return policyRateLimiters;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        register(registry, unauthenticatedLimiter, unauthenticated);
        register(registry, stripeWebhookLimiter, stripeWebhook);
    }

    private void register(InterceptorRegistry registry, RateLimiter limiter, PathLimit limit) {
        if (limit.getPaths().isEmpty()) {
            return;
        }
        registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns(limit.getPaths());
    }
}
