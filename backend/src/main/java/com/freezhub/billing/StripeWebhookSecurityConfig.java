package com.freezhub.billing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The webhook boundary (FZ-084).
 *
 * <p>A third chain beside the human one and the machine one ({@code FZ-052}), for the same
 * reason those two are separate: a credential that works on one boundary must not work on
 * another. Here there is no credential at all — the {@code Stripe-Signature} header is the
 * authentication, checked in the controller against the exact bytes received.
 *
 * <p><strong>No CORS.</strong> Stripe is a server, not a browser. Allowing a cross-origin
 * request here would mean a page somewhere could try to post events.
 *
 * <p>Ordered before the human chain so this path never falls through to a filter that
 * would answer a missing JWT with 401 — which would be a lie about what was wrong, and the
 * exact failure {@code FZ-052} found on the machine chain.
 */
@Configuration
public class StripeWebhookSecurityConfig {

    @Bean
    // 0 rather than 1: DevSignInSecurityConfig already holds 1, and two chains sharing
    // an order are resolved in whatever sequence the context happens to produce. Their
    // matchers do not overlap today, which is a reason it works rather than a reason it
    // is safe.
    @Order(0)
    SecurityFilterChain stripeWebhookSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/webhooks/stripe/**")
                // Stripe cannot present a CSRF token, and the signature is what makes the
                // request trustworthy.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());

        return http.build();
    }
}
