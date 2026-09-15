package com.freezhub.shared.security;

import com.freezhub.apikey.ApiKeyService;
import com.freezhub.shared.ratelimit.PolicyRateLimitFilter;
import com.freezhub.shared.ratelimit.PolicyRateLimiters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The machine-facing chain: {@code /api/policy/**}, authenticated by API key only
 * (FZ-052, `06-security.md`).
 *
 * <p>Scoped to that prefix so a key opens exactly one door. It buys no access to the human
 * API — a credential that could also manage teams, invite users or issue further keys
 * would make a leaked CI variable a full account takeover.
 *
 * <p>This chain exists before the endpoint it guards. `FZ-051` adds
 * {@code POST /api/policy/evaluate}; shipping the endpoint first would have meant either
 * leaving the deployment gate unauthenticated or handing CI a human credential
 * (`OI-9`). The chain is what makes that ordering unnecessary.
 *
 * <p><strong>No CORS.</strong> Every other chain enables it; this one must not. An API key
 * has no business being sent from a browser, and refusing cross-origin preflight means a
 * page cannot be written that asks a visitor's browser to spend one.
 *
 * <p><strong>Rate limited here rather than by the interceptor the other endpoints use</strong>
 * (`FZ-130`): a request whose key does not resolve is refused by the entry point below and
 * never reaches an interceptor, so counting it has to happen in the chain.
 */
@Configuration
public class ApiKeySecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain apiKeyFilterChain(
            HttpSecurity http,
            ApiKeyService apiKeyService,
            ObjectProvider<PolicyRateLimiters> policyRateLimiters,
            @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> exceptionResolver)
            throws Exception {
        http
                .securityMatcher("/api/policy/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // Without this the default is 403; a missing or bad credential is a 401
                // (04-api.md), and CI needs to tell "not authenticated" from "not allowed".
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // Constructed here rather than being a @Component: a Filter bean is
                // auto-registered with the servlet container for *every* request, which
                // would run API key resolution on the human API too.
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService),
                        UsernamePasswordAuthenticationFilter.class);

        // Absent when freezehub.rate-limit.enabled is false — the whole suite runs that
        // way, because MockMvc reports every request as coming from 127.0.0.1 and tests
        // sharing a context would drain each other's budget.
        PolicyRateLimiters limiters = policyRateLimiters.getIfAvailable();
        if (limiters != null) {
            // After the key filter, so the limit knows whether a key resolved and can
            // count an authenticated caller against its own key rather than its address.
            http.addFilterAfter(new PolicyRateLimitFilter(limiters, exceptionResolver.getObject()),
                    ApiKeyAuthenticationFilter.class);
        }

        return http.build();
    }

}
