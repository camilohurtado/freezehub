package com.freezhub.shared.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserResolvingJwtAuthenticationConverter converter)
            throws Exception {
        http
                // Enabled so preflight is answered before authorization runs; an OPTIONS
                // request carries no Authorization header and would otherwise be a 401,
                // making every browser call fail before it was ever sent.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // An error is already the outcome of a request that was authorized
                        // (or refused) on its way in; re-authorizing the forward to /error
                        // only replaces that outcome with a worse one. It broke the machine
                        // chain in particular: /error does not match /api/policy/**, so a
                        // 400 from a policy call fell through to this chain, which found no
                        // JWT and answered 401 — telling CI its credential was bad when the
                        // request was. Verified live; MockMvc does not forward to /error, so
                        // no controller test could have shown it (FZ-052).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));

        return http.build();
    }

}
