package com.freezhub.shared.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin access for the browser client.
 *
 * <p>The frontend is served from a different origin than the API - a Vite dev server
 * locally, S3/CloudFront in a deployed environment (02-architecture.md) - so without this
 * the browser refuses every request before it is sent.
 *
 * <p>Origins are **not** defaulted to anything permissive: {@code freezehub.cors
 * .allowed-origins} is empty unless configured, so a deployed environment has to name its
 * frontend origin explicitly. The {@code local} profile fills in the Vite dev server.
 * Credentials are not enabled because authentication is a bearer token, not a cookie.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${freezehub.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

}
