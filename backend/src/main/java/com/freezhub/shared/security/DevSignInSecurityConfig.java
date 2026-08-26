package com.freezhub.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes the local development sign-in endpoint reachable without a token (FZ-035) -
 * unavoidable for a sign-in endpoint, which is why it is fenced off this tightly.
 *
 * <p>Scoped to {@code /api/dev/**} and to the {@code local} profile. Outside that profile
 * this bean does not exist, so the path falls through to the main chain in
 * {@link SecurityConfig} and is rejected as unauthenticated - it is never publicly
 * reachable in a deployed environment, and there is a test asserting exactly that.
 *
 * <p>Ordered ahead of the main chain, which carries no security matcher and therefore
 * stays last as the catch-all.
 */
@Configuration
@Profile("local")
public class DevSignInSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain devSignInFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/dev/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());

        return http.build();
    }

}
