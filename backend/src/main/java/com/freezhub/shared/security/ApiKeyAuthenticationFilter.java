package com.freezhub.shared.security;

import com.freezhub.apikey.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a machine client from its {@code X-API-Key} header (FZ-052).
 *
 * <p>A dedicated header rather than {@code Authorization}, per `06-security.md`: human and
 * machine authentication stay mechanically distinct, so a JWT can never be presented where
 * a key is expected or the other way round.
 *
 * <p>An absent, unknown or revoked key simply leaves the context unauthenticated; the
 * chain's entry point turns that into a {@code 401}. Nothing here distinguishes the three
 * cases in its response — a caller must not be able to probe which keys exist.
 *
 * <p>The presented key is never logged, and never appears in an exception message.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presentedKey = request.getHeader(HEADER);

        if (presentedKey != null && !presentedKey.isBlank()) {
            apiKeyService.authenticate(presentedKey)
                    .map(ApiKeyAuthenticationToken::new)
                    .ifPresent(token -> SecurityContextHolder.getContext().setAuthentication(token));
        }

        filterChain.doFilter(request, response);
    }

}
