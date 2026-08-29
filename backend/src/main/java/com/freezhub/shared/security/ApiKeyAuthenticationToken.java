package com.freezhub.shared.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request authenticated by an API key rather than by a person.
 *
 * <p>Carries <strong>no authorities</strong>, deliberately: every role check in the
 * application is about what a user may do, and a pipeline is not a user. An empty
 * authority list means a machine credential can never satisfy {@code hasRole(...)}, so a
 * key cannot reach an administrator-only endpoint even if one were exposed on the machine
 * chain by mistake.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthenticationToken(ApiKeyPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    /** The key itself is never retained — it exists only for the length of the lookup. */
    @Override
    public Object getCredentials() {
        return null;
    }

}
