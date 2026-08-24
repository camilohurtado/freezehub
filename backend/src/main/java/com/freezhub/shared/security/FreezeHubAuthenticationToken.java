package com.freezhub.shared.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

public class FreezeHubAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;
    private final Jwt jwt;

    public FreezeHubAuthenticationToken(AuthenticatedUser principal, Jwt jwt) {
        super(List.of());
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

}
