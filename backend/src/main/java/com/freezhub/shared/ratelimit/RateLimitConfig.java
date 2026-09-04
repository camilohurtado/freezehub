package com.freezhub.shared.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires the limiter onto the configured paths (FZ-087). */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "freezehub.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitProperties properties;
    private final RateLimiter rateLimiter;

    public RateLimitConfig(RateLimitProperties properties) {
        this.properties = properties;
        this.rateLimiter = new RateLimiter(properties.getRequests(), properties.getWindow(),
                properties.getMaxTrackedClients());
    }

    @Bean
    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.getPaths().isEmpty()) {
            return;
        }
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter))
                .addPathPatterns(properties.getPaths());
    }
}
